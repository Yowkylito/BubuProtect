package com.personal.bubuprotect.core.otp

/**
 * A 2FA seed and the parameters it was issued with.
 *
 * ### Why the canonical form is an `otpauth://` URI
 *
 * That is what gets stored in the entry's encrypted extras, and it is the right choice for three
 * reasons: it is lossless, so digits, period and algorithm survive a round trip instead of being
 * re-guessed as defaults; it is what every other authenticator reads, so the user is never locked
 * into this app; and it is one string, so it drops into the existing `FieldSlot.Extra` blob with no
 * schema change at all.
 *
 * The alternative - store the bare Base32 and assume SHA1/6/30 - works for most issuers and silently
 * breaks the ones using 8 digits or a 60-second step, in the form of codes that are always rejected.
 *
 * ### Why the URI is assembled by hand
 *
 * `android.net.Uri` would do it in three lines and would not run in a JVM unit test, where it is a
 * stub that throws. The same argument that made [com.personal.bubuprotect.core.crypto.PassphraseKdf]
 * hand-roll PBKDF2 applies: this parses attacker-influenced input on a path whose failure mode is a
 * user locked out of an account, so being able to test it matters more than three lines.
 */
data class OtpSecret(
    /** Canonical Base32: uppercase, unpadded, no separators. */
    val base32: String,
    val digits: Int = TotpGenerator.DEFAULT_DIGITS,
    val periodSeconds: Int = TotpGenerator.DEFAULT_PERIOD_SECONDS,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val issuer: String? = null,
    val account: String? = null
) {

    /**
     * Decodes the seed for one HMAC.
     *
     * Not cached. It is key material, and keeping a decoded copy so the once-a-second refresh can
     * reuse it would hold it on the heap for as long as the screen is open. An HMAC over twenty bytes
     * is not worth a longer-lived secret.
     */
    internal fun secretBytes(): ByteArray? = Base32.decode(base32)

    /** The label an authenticator would show. */
    val displayName: String
        get() = when {
            !issuer.isNullOrBlank() && !account.isNullOrBlank() -> "$issuer ($account)"
            !issuer.isNullOrBlank() -> issuer
            !account.isNullOrBlank() -> account
            else -> "One-time code"
        }

    /** The portable form, for storage and for handing the seed back out to another app. */
    fun toUri(): String {
        val label = listOfNotNull(
            issuer?.takeIf(String::isNotBlank),
            account?.takeIf(String::isNotBlank)
        ).joinToString(":").ifEmpty { DEFAULT_LABEL }

        return buildString {
            append("otpauth://totp/")
            append(percentEncode(label))
            append("?secret=").append(base32)
            issuer?.takeIf(String::isNotBlank)?.let {
                append("&issuer=").append(percentEncode(it))
            }
            append("&algorithm=").append(algorithm.uriName)
            append("&digits=").append(digits)
            append("&period=").append(periodSeconds)
        }
    }

    internal companion object {
        const val DEFAULT_LABEL = "Bubu Protect"

        /** RFC 3986 unreserved set. Everything else becomes `%XX` over its UTF-8 bytes. */
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

        fun percentEncode(value: String): String = buildString(value.length) {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val character = byte.toInt().toChar()
                if (byte >= 0 && character in UNRESERVED) {
                    append(character)
                } else {
                    append('%').append("%02X".format(byte.toInt() and 0xff))
                }
            }
        }

        /** Tolerant of a stray `%` or a truncated escape - those are left as literal text. */
        fun percentDecode(value: String): String {
            if (!value.contains('%') && !value.contains('+')) return value
            val bytes = java.io.ByteArrayOutputStream(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                when {
                    character == '%' && index + 2 < value.length -> {
                        val hex = value.substring(index + 1, index + 3)
                        val decoded = hex.toIntOrNull(16)
                        if (decoded != null) {
                            bytes.write(decoded)
                            index += 3
                        } else {
                            bytes.write(character.code)
                            index++
                        }
                    }
                    // Some issuers emit form-encoded labels, where a space became a plus.
                    character == '+' -> {
                        bytes.write(' '.code)
                        index++
                    }
                    else -> {
                        bytes.write(value.substring(index, index + 1).toByteArray(Charsets.UTF_8))
                        index++
                    }
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}
