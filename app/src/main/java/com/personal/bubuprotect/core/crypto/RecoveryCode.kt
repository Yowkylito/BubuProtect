package com.personal.bubuprotect.core.crypto

/**
 * The code printed on a recovery kit: 120 bits of randomness a human can copy off paper.
 *
 * ### Why this exists
 *
 * Before it, the master passphrase was an unrecoverable single point of failure - and worse than it
 * looked, because [com.personal.bubuprotect.core.backup.VaultBackupService] seals backups with that
 * same passphrase. "I forgot it" did not mean "restore from a backup", it meant every secret was
 * gone permanently. A recovery code is a second, independent way to reach the same root key.
 *
 * ### Why 120 bits, and why that number exactly
 *
 * 15 random bytes is 120 bits, which is 24 Crockford Base32 characters with **no padding** - 120 is
 * divisible by both 8 and 5. That is not numerology: a padded encoding has trailing bits that decode
 * to more than one valid string, so two different typings of "the same" code could both parse and
 * only one would work. An exact fit makes the encoding a bijection, and a recovery path is the last
 * place to accept an ambiguity.
 *
 * 120 bits is far past brute force (2^120 is about 1.3e36), which is what licenses the KDF choice
 * documented on [VaultKeyManager.unlockWithRecoveryCode].
 *
 * ### Why Crockford Base32
 *
 * This string gets written on paper and typed back months later, quite possibly by someone stressed
 * because they have just lost access to everything. Crockford's alphabet omits `I`, `L`, `O` and `U`,
 * and its decoder folds `I` and `L` into `1` and `O` into `0` - so the classic transcription errors
 * are *forgiven* rather than punished. `U` is omitted so a random code cannot spell something the
 * user would rather not have printed on a page they hand to a family member.
 *
 * ### On the missing checksum
 *
 * There is deliberately none. A checksum would let the app say "that has a typo in it" instead of
 * "that does not open this vault", which sounds kinder but is barely different in practice - both
 * mean check your typing - and it would add a second parser to the one code path that absolutely
 * must not have bugs in it. Length and alphabet already reject most mistyping, and the GCM tag is
 * the real answer. Fewer moving parts wins here.
 *
 * ### The unavoidable `String`
 *
 * [formatted] returns a `String`, which cannot be wiped. Displaying the code requires one, exactly
 * as the master passphrase field does, so this is the same accepted platform limit rather than a new
 * one - and it is bounded to the screen that shows the kit. The *bytes* are held in [SecureBytes]
 * and wiped on [close].
 */
class RecoveryCode private constructor(private val material: SecureBytes) : AutoCloseable {

    /** The human-facing form: `BP1-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`. */
    fun formatted(): String =
        PREFIX + SEPARATOR + encode(material.use()).chunked(GROUP_SIZE).joinToString(SEPARATOR)

    /** Key material for the wrapper. Stays owned by this object. */
    internal fun secret(): SecureBytes = material

    override fun close() = material.destroy()

    companion object {

        const val PREFIX = "BP1"
        private const val SEPARATOR = "-"
        private const val GROUP_SIZE = 4

        /** 15 bytes -> 120 bits -> exactly 24 characters. See the class doc. */
        const val MATERIAL_BYTES = 15
        const val CODE_LENGTH = MATERIAL_BYTES * 8 / 5

        /**
         * Crockford Base32. Note the gaps: no `I`, `L`, `O`, `U`.
         *
         * The prefix is versioned rather than decorative. If a later build moves to a different
         * length or a different derivation, the reader dispatches on `BP1` and old kits keep
         * working - the same reasoning that gave the backup envelope its own `kdfId` field.
         */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun generate(): RecoveryCode = RecoveryCode(SecureBytes.random(MATERIAL_BYTES))

        /**
         * Parses a code as typed, however it was typed.
         *
         * @return null when the input is not a well-formed code of this version. That is a different
         *   answer from "this code does not open the vault", and the two are reported differently -
         *   telling someone their kit is for another vault when they simply dropped a character is a
         *   cruel way to fail.
         */
        fun parse(input: String): RecoveryCode? {
            val payload = payloadOf(input) ?: return null
            val bytes = decode(payload) ?: return null
            return RecoveryCode(SecureBytes.adopt(bytes))
        }

        /** True when [input] is the right shape. Lets a field validate without deriving anything. */
        fun looksWellFormed(input: String): Boolean = payloadOf(input) != null

        /**
         * Recovers the 24-character payload from anything a human might have typed, or null.
         *
         * ### Why the prefix is stripped conditionally rather than always
         *
         * `B`, `P` and `1` are all payload characters, so `BP1` is a sequence a random payload can
         * begin with - about one code in 32,768 does. Unconditionally removing a leading `BP1` would
         * eat three real characters from those codes whenever the user typed the payload *without*
         * the prefix, and the kit would simply refuse to work. One in 32,768 is rare; on the path
         * that exists specifically to rescue people, rare is not the same as acceptable.
         *
         * So both readings are considered, and length decides between them. There is no ambiguity to
         * resolve: a payload is always exactly [CODE_LENGTH], so at most one reading can be right.
         */
        private fun payloadOf(input: String): String? {
            val cleaned = clean(input)
            if (cleaned.length == CODE_LENGTH) return cleaned
            val withoutPrefix = cleaned.removePrefix(PREFIX)
            return withoutPrefix.takeIf { it.length == CODE_LENGTH }
        }

        /**
         * Folds the confusable characters and drops everything that is not in the alphabet.
         *
         * That covers separators, spaces and line breaks in one pass, so a code pasted out of a
         * printed kit - dashes, newlines and all - reduces to the same string as one typed carefully
         * into six boxes. Whitespace is handled here rather than with a `trim`, because it can appear
         * anywhere in a hand-copied code, not only at the ends.
         */
        private fun clean(input: String): String = buildString(input.length) {
            for (character in input.uppercase()) {
                when (character) {
                    // Crockford's forgiveness, and the entire reason for this alphabet.
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    else -> if (character in ALPHABET) append(character)
                }
            }
        }

        private fun encode(bytes: ByteArray): String {
            val output = StringBuilder(CODE_LENGTH)
            var buffer = 0
            var bitsHeld = 0
            for (byte in bytes) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bitsHeld += 8
                while (bitsHeld >= 5) {
                    bitsHeld -= 5
                    output.append(ALPHABET[(buffer ushr bitsHeld) and 0x1f])
                }
            }
            // No tail handling: the class doc explains why the length is chosen so that none exists.
            return output.toString()
        }

        /** @return null if any character is outside the alphabet - [clean] should have dropped it. */
        private fun decode(code: String): ByteArray? {
            val output = ByteArray(MATERIAL_BYTES)
            var buffer = 0
            var bitsHeld = 0
            var written = 0
            for (character in code) {
                val value = ALPHABET.indexOf(character)
                if (value < 0) return null
                buffer = (buffer shl 5) or value
                bitsHeld += 5
                if (bitsHeld >= 8) {
                    bitsHeld -= 8
                    if (written >= output.size) return null
                    output[written++] = ((buffer ushr bitsHeld) and 0xff).toByte()
                }
            }
            return if (written == output.size) output else null
        }
    }
}
