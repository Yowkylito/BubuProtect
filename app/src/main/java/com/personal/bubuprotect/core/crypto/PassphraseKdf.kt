package com.personal.bubuprotect.core.crypto

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA512, hand-rolled against RFC 2898 rather than routed through
 * `SecretKeyFactory("PBKDF2WithHmacSHA512")`.
 *
 * The reason is not distrust of the primitive but of the *encoding*: several Bouncy Castle PBKDF2
 * registrations feed [javax.crypto.spec.PBEKeySpec] chars through PKCS#5's 8-bit conversion, which
 * silently discards the high byte of every non-ASCII character. A passphrase with an accent in it
 * would then derive a different key depending on provider version, and the vault would become
 * unopenable after an OS update. Encoding the passphrase as UTF-8 ourselves makes the derivation
 * reproducible forever, which for a store of last-resort secrets is the property that matters.
 *
 * [DEFAULT_ITERATIONS] follows the OWASP recommendation for HMAC-SHA512. The count is persisted per
 * vault ([com.personal.bubuprotect.data.local.VaultKeyStore]) rather than hardcoded at the call
 * site, so it can be raised on future hardware without locking anyone out of an existing vault.
 */
object PassphraseKdf {

    const val DEFAULT_ITERATIONS = 210_000
    const val SALT_LENGTH = 32
    private const val HMAC = "HmacSHA512"
    private const val HASH_LENGTH = 64

    /** Minimum length we accept. Length beats character-class rules for passphrase entropy. */
    const val MIN_PASSPHRASE_LENGTH = 12

    /**
     * Derives a key-wrapping key. Runs for a deliberate fraction of a second - call it off the
     * main thread.
     *
     * Does not wipe [passphrase]; the caller owns it and usually needs it for confirmation checks.
     */
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        length: Int = 32
    ): SecureBytes {
        require(iterations > 0) { "Iteration count must be positive" }
        val passwordBytes = toUtf8(passphrase)
        return try {
            SecureBytes.adopt(pbkdf2(passwordBytes, salt, iterations, length))
        } finally {
            passwordBytes.wipe()
        }
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(password, HMAC)) }
        val output = ByteArray(length)
        val blocks = (length + HASH_LENGTH - 1) / HASH_LENGTH
        var written = 0

        for (block in 1..blocks) {
            // U1 = PRF(P, S || INT_BE(block))
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf(
                (block ushr 24).toByte(),
                (block ushr 16).toByte(),
                (block ushr 8).toByte(),
                block.toByte()
            ))
            var u = mac.doFinal()
            val accumulator = u.copyOf()

            // T = U1 xor U2 xor ... xor Uc
            for (round in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (i in accumulator.indices) accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
            }
            u.wipe()

            val take = minOf(HASH_LENGTH, length - written)
            accumulator.copyInto(output, written, 0, take)
            accumulator.wipe()
            written += take
        }
        return output
    }

    /** Encodes without ever materialising the passphrase as an immutable, unwipeable String. */
    private fun toUtf8(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        // The intermediate buffer keeps a copy of the plaintext passphrase; scrub it.
        if (encoded.hasArray()) encoded.array().wipe()
        return bytes
    }

    fun newSalt(): ByteArray = SecureBytes.randomBytes(SALT_LENGTH)
}
