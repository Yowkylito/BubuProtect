package com.personal.bubuprotect.core.otp

/**
 * RFC 4648 Base32, the encoding every 2FA secret arrives in.
 *
 * ### Not the same codec as the recovery kit, deliberately
 *
 * [com.personal.bubuprotect.core.crypto.RecoveryCode] also does Base32, and sharing this would be a
 * bug rather than a saving. That one is *Crockford* Base32: it omits `I`, `L`, `O` and `U`, and it
 * folds `I`/`L` to `1` and `O` to `0` because a human copies it off paper. This one is RFC 4648,
 * whose alphabet is `A-Z` plus `2-7` - so `0`, `1`, `8` and `9` are not digits at all, and folding
 * them would produce a *different secret* that generates wrong codes forever.
 *
 * Which is why this rejects unknown characters instead of forgiving them. A 2FA secret is
 * copy-pasted or scanned, not transcribed, so there is no transcription error to be generous about -
 * and a silently mangled seed is a code that never works with no explanation.
 *
 * ### What it does tolerate
 *
 * Lowercase, `=` padding, spaces and dashes. Issuers print secrets in groups of four, users paste
 * them with the trailing padding still attached, and some QR payloads arrive lowercased. None of
 * those change the value.
 */
internal object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * A ceiling on input length.
     *
     * The seed can come from a scanned QR code, which is attacker-controlled input. RFC 4226 asks for
     * at least 128 bits and recommends 160; 512 characters is 320 bytes, far past anything legitimate,
     * and stops a crafted payload asking for a large allocation.
     */
    private const val MAX_INPUT_LENGTH = 512

    /** @return the decoded bytes, or null if [value] is not usable Base32. */
    fun decode(value: String): ByteArray? {
        val cleaned = clean(value) ?: return null
        if (cleaned.isEmpty()) return null

        val output = ArrayList<Byte>(cleaned.length * 5 / 8 + 1)
        var buffer = 0
        var bitsHeld = 0

        for (character in cleaned) {
            val index = ALPHABET.indexOf(character)
            if (index < 0) return null
            buffer = (buffer shl 5) or index
            bitsHeld += 5
            if (bitsHeld >= 8) {
                bitsHeld -= 8
                output.add(((buffer ushr bitsHeld) and 0xff).toByte())
            }
        }

        // Leftover bits are dropped, which is what RFC 4648 requires: a group that does not complete
        // a byte is padding, not data. Secrets of 16 or 26 characters both land here.
        return if (output.isEmpty()) null else output.toByteArray()
    }

    /** Canonical form for storage and display: uppercase, unpadded, no separators. */
    fun normalize(value: String): String? = clean(value)?.takeIf(String::isNotEmpty)

    /** True when [value] decodes to at least one byte. Lets a field validate without allocating. */
    fun isValid(value: String): Boolean = decode(value) != null

    private fun clean(value: String): String? {
        if (value.length > MAX_INPUT_LENGTH) return null
        return buildString(value.length) {
            for (character in value.uppercase()) {
                when {
                    character.isWhitespace() || character == '-' || character == '=' -> Unit
                    else -> append(character)
                }
            }
        }
    }
}
