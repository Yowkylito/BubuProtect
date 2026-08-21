package com.personal.bubuprotect.core.shield.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Malformed and hostile packets are the whole point of these tests.
 *
 * The parser runs inside the tunnel's packet loop reading bytes written by any app on the phone,
 * including the adware under investigation. An exception there kills the tunnel and takes the user's DNS
 * with it, so "returns null" is a correctness requirement rather than politeness - and byte arrays are
 * far easier to make hostile here than over a real network.
 */
class DnsMessageTest {

    /** A standard query: id 0x1234, one question, for the given name, type A, class IN. */
    private fun query(vararg labels: String): ByteArray {
        val header = byteArrayOf(
            0x12, 0x34,
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00
        )
        val name = labels.flatMap { label ->
            listOf(label.length.toByte()) + label.toByteArray(Charsets.US_ASCII).toList()
        } + listOf<Byte>(0)
        val tail = byteArrayOf(0x00, 0x01, 0x00, 0x01)

        return header + name.toByteArray() + tail
    }

    @Test
    fun `reads a simple hostname`() {
        assertEquals("doubleclick.net", DnsMessage.questionName(query("doubleclick", "net")))
    }

    @Test
    fun `lowercases the name so blocklist matching does not have to`() {
        assertEquals("ads.example.com", DnsMessage.questionName(query("ADS", "Example", "COM")))
    }

    @Test
    fun `reads a deep subdomain`() {
        assertEquals(
            "stats.g.doubleclick.net",
            DnsMessage.questionName(query("stats", "g", "doubleclick", "net"))
        )
    }

    @Test
    fun `a response is not treated as a query`() {
        // The QR bit set means this came back from a resolver. Parsing it as a question would attribute
        // the answer to whoever it was addressed to.
        val response = query("example", "com").also { it[2] = 0x81.toByte() }

        assertNull(DnsMessage.questionName(response))
    }

    @Test
    fun `a compression pointer in the question section is refused`() {
        // Illegal in a question, and a standard way to make a naive parser chase an offset. 0xC0 sets
        // the two high bits that mark a pointer.
        val hostile = query("example", "com").also { it[12] = 0xC0.toByte() }

        assertNull(DnsMessage.questionName(hostile))
    }

    @Test
    fun `truncated packets return null rather than throwing`() {
        val full = query("doubleclick", "net")

        // Every prefix of a valid query. Any index that throws instead of returning null is a crash in
        // the packet loop waiting to happen.
        for (cut in 0 until full.size) {
            DnsMessage.questionName(full.copyOf(cut))
        }
    }

    @Test
    fun `a label length running past the end of the packet returns null`() {
        val hostile = query("example", "com").also { it[12] = 60 }

        assertNull(DnsMessage.questionName(hostile))
    }

    @Test
    fun `multi-question messages are not parsed`() {
        // QDCOUNT of 2. Answering one question of two would produce a reply the client discards.
        val two = query("example", "com").also { it[5] = 0x02 }

        assertNull(DnsMessage.questionName(two))
    }

    @Test
    fun `the refusal keeps the transaction id and sets NXDOMAIN`() {
        val request = query("doubleclick", "net")
        val response = DnsMessage.nameErrorResponse(request)!!

        // Id preserved: it is how the client matches the reply to its own outstanding query.
        assertEquals(request[0], response[0])
        assertEquals(request[1], response[1])

        // QR set, RCODE 3.
        assertEquals(0x80, response[2].toInt() and 0x80)
        assertEquals(3, response[3].toInt() and 0x0F)

        // One question echoed, no answers.
        assertEquals(1, response[5].toInt())
        assertEquals(0, response[7].toInt())
        assertEquals(0, response[9].toInt())
        assertEquals(0, response[11].toInt())
    }

    @Test
    fun `the refusal echoes the question back verbatim`() {
        val request = query("ads", "example", "com")
        val response = DnsMessage.nameErrorResponse(request)!!

        assertEquals("ads.example.com", DnsMessage.questionName(request))
        // Bytes 12 onward are the question, copied unchanged - a resolver that rewrote them would
        // produce an answer the client cannot match to its query.
        assertEquals(
            request.copyOfRange(12, response.size).toList(),
            response.copyOfRange(12, response.size).toList()
        )
    }

    @Test
    fun `an unparseable query gets no invented answer`() {
        assertNull(DnsMessage.nameErrorResponse(ByteArray(4)))
        assertNull(DnsMessage.nameErrorResponse(ByteArray(0)))
    }
}
