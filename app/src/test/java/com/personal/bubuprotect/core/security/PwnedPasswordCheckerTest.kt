package com.personal.bubuprotect.core.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class PwnedPasswordCheckerTest {

    @Test
    fun `sends only five character prefix and matches suffix locally`() = runBlocking {
        var receivedPrefix = ""
        var receivedSuffix = ""
        val checker = PwnedPasswordChecker(
            PwnedPasswordsClient { prefix, suffix ->
                receivedPrefix = prefix
                receivedSuffix = String(suffix)
                42L
            }
        )

        val result = checker.check("password")

        assertEquals("5BAA6", receivedPrefix)
        assertEquals("1E4C9B93F3F0682250B6CF8331B7EE68FD8", receivedSuffix)
        assertFalse(receivedPrefix.contains("password", ignoreCase = true))
        assertEquals(PwnedPasswordResult.Found(42L), result)
    }

    @Test
    fun `zero matches is reported as not found`() = runBlocking {
        val checker = PwnedPasswordChecker(PwnedPasswordsClient { _, _ -> 0L })

        assertSame(PwnedPasswordResult.NotFound, checker.check("a unique passphrase"))
    }

    @Test
    fun `range client requests prefix with padding and keeps suffix out of URL`() = runBlocking {
        val suffix = "1E4C9B93F3F0682250B6CF8331B7EE68FD8".toCharArray()
        lateinit var requestedUrl: URL
        lateinit var connection: FakeHttpsConnection
        val client = HibpPwnedPasswordsClient { url ->
            requestedUrl = url
            FakeHttpsConnection(
                url = url,
                body = "00000000000000000000000000000000000:0\r\n" +
                    "${String(suffix)}:42\r\n"
            ).also { connection = it }
        }

        val count = client.findExposureCount("5BAA6", suffix)

        assertEquals(42L, count)
        assertEquals("https://api.pwnedpasswords.com/range/5BAA6", requestedUrl.toString())
        assertFalse(requestedUrl.toString().contains(String(suffix)))
        assertEquals("true", connection.getRequestProperty("Add-Padding"))
        assertFalse(connection.instanceFollowRedirects)
    }
}

private class FakeHttpsConnection(
    url: URL,
    body: String
) : HttpsURLConnection(url) {
    private val response = body.toByteArray(StandardCharsets.UTF_8)

    override fun getResponseCode(): Int = HTTP_OK
    override fun getInputStream(): InputStream = ByteArrayInputStream(response)
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getCipherSuite(): String = "TLS_FAKE"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}
