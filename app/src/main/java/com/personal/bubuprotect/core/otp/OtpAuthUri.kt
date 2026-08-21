package com.personal.bubuprotect.core.otp

/**
 * Reads an `otpauth://` URI, or a bare secret typed by hand.
 *
 * ```
 * otpauth://totp/GitHub:me@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=6&period=30
 * ```
 *
 * ### Everything numeric is clamped, not trusted
 *
 * This URI can come from a QR code, which is input the user did not write and an attacker may have.
 * A `digits=2` would produce a hundred possible codes; a `digits=100` would overflow the modulus; a
 * `period=1` would spin the countdown into a blur. So the parser refuses values outside
 * [TotpGenerator.DIGIT_RANGE] and [TotpGenerator.PERIOD_RANGE] rather than clamping them silently -
 * a seed whose parameters we had to alter would generate codes the server rejects, and failing at
 * enrollment is far kinder than failing at every login afterwards.
 *
 * An unknown `algorithm` is the one exception, and it *is* refused rather than defaulted: quietly
 * treating `SHA3-512` as SHA1 would store a seed that never works.
 *
 * ### Why HOTP is not supported
 *
 * `otpauth://hotp/` is counter-based: every code consumed advances a counter that has to be written
 * back and kept in step with the server. Getting that wrong desynchronises the account, and it is
 * rare enough that the honest answer is to reject it clearly rather than half-support it.
 */
internal object OtpAuthUri {

    private const val SCHEME = "otpauth://"
    private const val TOTP_HOST = "totp"

    /**
     * @param input an `otpauth://` URI, or a bare Base32 secret for manual entry.
     * @return null when the input is neither.
     */
    fun parse(input: String): OtpSecret? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        return if (trimmed.startsWith(SCHEME, ignoreCase = true)) {
            parseUri(trimmed)
        } else {
            // Manual entry: the user pasted just the secret, so the defaults apply. That is the right
            // assumption because an issuer that needs non-default parameters always supplies a QR.
            Base32.normalize(trimmed)
                ?.takeIf(Base32::isValid)
                ?.let { OtpSecret(base32 = it) }
        }
    }

    private fun parseUri(uri: String): OtpSecret? {
        val withoutScheme = uri.substring(SCHEME.length)

        val host = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
        if (host != TOTP_HOST) return null

        val afterHost = withoutScheme.substringAfter('/', missingDelimiterValue = "")
        val label = OtpSecret.percentDecode(afterHost.substringBefore('?'))
        val query = afterHost.substringAfter('?', missingDelimiterValue = "")
        val parameters = parseQuery(query)

        val base32 = parameters["secret"]?.let(Base32::normalize) ?: return null
        if (!Base32.isValid(base32)) return null

        val digits = parameters["digits"]?.let { value ->
            value.toIntOrNull()?.takeIf { it in TotpGenerator.DIGIT_RANGE } ?: return null
        } ?: TotpGenerator.DEFAULT_DIGITS

        val period = parameters["period"]?.let { value ->
            value.toIntOrNull()?.takeIf { it in TotpGenerator.PERIOD_RANGE } ?: return null
        } ?: TotpGenerator.DEFAULT_PERIOD_SECONDS

        val algorithm = parameters["algorithm"]?.let { value ->
            OtpAlgorithm.fromUri(value) ?: return null
        } ?: OtpAlgorithm.SHA1

        // The label is `Issuer:Account`, and the `issuer` parameter repeats it when both are present.
        // The parameter wins: the spec calls the label form legacy, and issuers that set both
        // sometimes abbreviate in the label.
        val labelIssuer = label.substringBefore(':', missingDelimiterValue = "").trim()
        val labelAccount = label.substringAfter(':', missingDelimiterValue = label).trim()

        return OtpSecret(
            base32 = base32,
            digits = digits,
            periodSeconds = period,
            algorithm = algorithm,
            issuer = (parameters["issuer"]?.trim()?.takeIf(String::isNotEmpty)
                ?: labelIssuer.takeIf(String::isNotEmpty))
                ?.takeIf { it != OtpSecret.DEFAULT_LABEL },
            account = labelAccount.takeIf(String::isNotEmpty)
                ?.takeIf { it != OtpSecret.DEFAULT_LABEL }
        )
    }

    /** Last value wins for a repeated key, matching how browsers read a query string. */
    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return buildMap {
            query.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val name = pair.substringBefore('=').lowercase()
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                if (name.isNotEmpty()) put(name, OtpSecret.percentDecode(value))
            }
        }
    }
}
