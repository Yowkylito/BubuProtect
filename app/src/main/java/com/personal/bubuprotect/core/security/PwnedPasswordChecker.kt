package com.personal.bubuprotect.core.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

sealed interface PwnedPasswordResult {
    data object NotFound : PwnedPasswordResult
    data class Found(val exposureCount: Long) : PwnedPasswordResult
}

/**
 * The service asked us to slow down, or was briefly unavailable.
 *
 * Distinct from a plain [IOException] so a vault-wide scan can tell "the network is gone, stop" from
 * "we are going too fast, wait and carry on". Carries no message beyond the status class - a message
 * built from the response could echo the requested URL, and the URL contains the hash prefix.
 */
class PwnedPasswordsThrottledException(
    /** `Retry-After` in seconds, when the service supplied one. */
    val retryAfterSeconds: Long?
) : IOException("Pwned Passwords service asked for a pause")

/**
 * Looks up a password in HIBP's Pwned Passwords corpus without sending the password or its full hash.
 *
 * SHA-1 is used only because it is the lookup key of that public corpus, never as password storage.
 * The first five hexadecimal characters are sent; the remaining 35 stay on-device and are matched
 * against the padded range response.
 */
class PwnedPasswordChecker(
    private val client: PwnedPasswordsClient
) {
    suspend fun check(password: String): PwnedPasswordResult = withContext(Dispatchers.Default) {
        require(password.isNotEmpty()) { "A blank password cannot be checked" }

        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-1").digest(passwordBytes)
        } finally {
            passwordBytes.fill(0)
        }
        val hex = CharArray(SHA1_HEX_LENGTH)

        try {
            digest.writeUpperHexTo(hex)
            val prefix = String(hex, 0, PREFIX_LENGTH)
            val suffix = hex.copyOfRange(PREFIX_LENGTH, hex.size)
            try {
                val exposureCount = client.findExposureCount(prefix, suffix)
                if (exposureCount > 0L) {
                    PwnedPasswordResult.Found(exposureCount)
                } else {
                    PwnedPasswordResult.NotFound
                }
            } finally {
                suffix.fill('\u0000')
            }
        } finally {
            digest.fill(0)
            hex.fill('\u0000')
        }
    }

    private fun ByteArray.writeUpperHexTo(destination: CharArray) {
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            destination[index * 2] = HEX[value ushr 4]
            destination[index * 2 + 1] = HEX[value and 0x0f]
        }
    }

    private companion object {
        const val PREFIX_LENGTH = 5
        const val SHA1_HEX_LENGTH = 40
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}

fun interface PwnedPasswordsClient {
    /**
     * [prefix] is the only password-derived value allowed into the request.
     * [suffix] must be used solely for local comparison and must never be logged or transmitted.
     */
    suspend fun findExposureCount(prefix: String, suffix: CharArray): Long
}

/**
 * Minimal HTTPS client for the single endpoint Bubu Protect needs.
 *
 * It deliberately has no general-purpose request API, cookies, redirects, request body, or logging.
 * That keeps the INTERNET permission from turning this component into an accidental exfiltration
 * path for vault fields.
 */
class HibpPwnedPasswordsClient(
    private val connectionFactory: (URL) -> HttpsURLConnection = { url ->
        url.openConnection() as HttpsURLConnection
    }
) : PwnedPasswordsClient {

    override suspend fun findExposureCount(prefix: String, suffix: CharArray): Long =
        withContext(Dispatchers.IO) {
            require(prefix.length == PREFIX_LENGTH && prefix.all(::isUpperHex)) {
                "Invalid hash prefix"
            }
            require(suffix.size == SUFFIX_LENGTH && suffix.all(::isUpperHex)) {
                "Invalid hash suffix"
            }

            val url = URL("$RANGE_ENDPOINT$prefix")
            check(url.protocol == "https" && url.host == HIBP_HOST) {
                "Pwned Passwords endpoint must remain HTTPS on the expected host"
            }

            val connection = connectionFactory(url)
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = false
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "text/plain")
                connection.setRequestProperty("Add-Padding", "true")
                connection.setRequestProperty("User-Agent", USER_AGENT)

                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> Unit

                    // 429 is the documented rate-limit response; 503 is what a service under load
                    // returns. Both mean "try later", which is a materially different instruction to
                    // a caller than "this failed" - a scan should pause, not abandon the vault.
                    HTTP_TOO_MANY_REQUESTS, HttpURLConnection.HTTP_UNAVAILABLE ->
                        throw PwnedPasswordsThrottledException(
                            retryAfterSeconds = connection
                                .getHeaderField("Retry-After")
                                ?.trim()
                                ?.toLongOrNull()
                                ?.takeIf { it in 0..MAX_RETRY_AFTER_SECONDS }
                        )

                    else -> throw IOException(
                        // The status code is safe to include; it is not derived from the password.
                        "Pwned Passwords service returned a non-success response ($status)"
                    )
                }

                connection.inputStream
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { reader ->
                        var consumedCharacters = 0
                        var sawValidLine = false

                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = reader.readLine() ?: break
                            consumedCharacters += line.length + 1
                            if (consumedCharacters > MAX_RESPONSE_CHARACTERS) {
                                throw IOException("Pwned Passwords response exceeded the safety limit")
                            }

                            val separator = line.indexOf(':')
                            if (separator != SUFFIX_LENGTH ||
                                separator != line.lastIndexOf(':') ||
                                !line.hasValidHexSuffix() ||
                                line.length == separator + 1
                            ) {
                                throw IOException("Pwned Passwords response was malformed")
                            }

                            val count = line.substring(SUFFIX_LENGTH + 1).toLongOrNull()
                                ?: throw IOException("Pwned Passwords response was malformed")
                            if (count < 0L) {
                                throw IOException("Pwned Passwords response was malformed")
                            }
                            sawValidLine = true
                            if (line.regionMatchesSuffix(suffix)) {
                                return@withContext count
                            }
                        }

                        if (!sawValidLine) {
                            throw IOException("Pwned Passwords response contained no valid ranges")
                        }
                        0L
                    }
            } finally {
                connection.disconnect()
            }
        }

    private fun String.regionMatchesSuffix(suffix: CharArray): Boolean {
        if (length < suffix.size) return false
        for (index in suffix.indices) {
            if (this[index].uppercaseChar() != suffix[index]) return false
        }
        return true
    }

    private fun String.hasValidHexSuffix(): Boolean {
        if (length < SUFFIX_LENGTH) return false
        for (index in 0 until SUFFIX_LENGTH) {
            val character = this[index].uppercaseChar()
            if (!isUpperHex(character)) return false
        }
        return true
    }

    private companion object {
        const val HIBP_HOST = "api.pwnedpasswords.com"
        const val RANGE_ENDPOINT = "https://$HIBP_HOST/range/"
        const val USER_AGENT = "BubuProtect-PwnedPasswordCheck"
        const val PREFIX_LENGTH = 5
        const val SUFFIX_LENGTH = 35
        const val TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_CHARACTERS = 1_000_000

        /** `HttpURLConnection` predates RFC 6585 and has no constant for it. */
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** A server-controlled value; clamped so a hostile header cannot park a scan for a week. */
        const val MAX_RETRY_AFTER_SECONDS = 300L

        fun isUpperHex(character: Char): Boolean =
            character in '0'..'9' || character in 'A'..'F'
    }
}
