package com.personal.bubuprotect.core.shield.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The checksum is the reason this file has tests.
 *
 * A wrong IP header checksum does not throw and does not log - the kernel silently discards the packet,
 * and the symptom is "DNS sometimes hangs", which is close to undiagnosable on a device. Verifying it
 * arithmetically here is far cheaper than finding it later.
 */
class Ipv4UdpPacketTest {

    /** An IPv4/UDP packet from 198.18.0.2:40000 to 198.18.0.1:53 carrying [payload]. */
    private fun packet(
        payload: ByteArray,
        protocol: Int = Ipv4UdpPacket.PROTOCOL_UDP,
        version: Int = 4
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val bytes = ByteArray(total)

        bytes[0] = ((version shl 4) or 5).toByte()
        bytes[2] = ((total shr 8) and 0xFF).toByte()
        bytes[3] = (total and 0xFF).toByte()
        bytes[8] = 64
        bytes[9] = protocol.toByte()
        listOf(198, 18, 0, 2).forEachIndexed { i, b -> bytes[12 + i] = b.toByte() }
        listOf(198, 18, 0, 1).forEachIndexed { i, b -> bytes[16 + i] = b.toByte() }

        // Source port 40000, destination 53.
        bytes[20] = 0x9C.toByte(); bytes[21] = 0x40
        bytes[22] = 0x00; bytes[23] = 0x35
        val udpLength = 8 + payload.size
        bytes[24] = ((udpLength shr 8) and 0xFF).toByte()
        bytes[25] = (udpLength and 0xFF).toByte()

        payload.copyInto(bytes, 28)
        return bytes
    }

    @Test
    fun `parses addresses ports and payload`() {
        val parsed = Ipv4UdpPacket.parse(packet(byteArrayOf(1, 2, 3)), 31)

        assertNotNull(parsed)
        assertEquals("198.18.0.2", parsed!!.sourceAddress)
        assertEquals("198.18.0.1", parsed.destinationAddress)
        assertEquals(40000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertEquals(3, parsed.payloadLength)
        assertEquals(listOf<Byte>(1, 2, 3), parsed.payload.toList())
    }

    @Test
    fun `rejects what is not IPv4 UDP`() {
        val udp = packet(byteArrayOf(1))

        assertNull("IPv6", Ipv4UdpPacket.parse(packet(byteArrayOf(1), version = 6), 29))
        assertNull("TCP", Ipv4UdpPacket.parse(packet(byteArrayOf(1), protocol = 6), 29))
        assertNull("truncated", Ipv4UdpPacket.parse(udp, 10))
        assertNull("empty", Ipv4UdpPacket.parse(ByteArray(0), 0))
    }

    @Test
    fun `rejects fragments rather than parsing a piece as a whole`() {
        // A non-zero fragment offset in the low 13 bits of bytes 6-7.
        val fragment = packet(byteArrayOf(1, 2)).also { it[7] = 0x10 }

        assertNull(Ipv4UdpPacket.parse(fragment, 30))
    }

    @Test
    fun `truncated reads never throw`() {
        val full = packet(byteArrayOf(1, 2, 3, 4, 5))

        for (cut in 0..full.size) {
            Ipv4UdpPacket.parse(full, cut)
        }
    }

    @Test
    fun `a reply reverses the addresses and ports`() {
        val request = Ipv4UdpPacket.parse(packet(byteArrayOf(9)), 29)!!
        val reply = Ipv4UdpPacket.buildReply(request, byteArrayOf(7, 7))
        val parsedReply = Ipv4UdpPacket.parse(reply, reply.size)!!

        assertEquals(request.destinationAddress, parsedReply.sourceAddress)
        assertEquals(request.sourceAddress, parsedReply.destinationAddress)
        assertEquals(request.destinationPort, parsedReply.sourcePort)
        assertEquals(request.sourcePort, parsedReply.destinationPort)
        assertEquals(listOf<Byte>(7, 7), parsedReply.payload.toList())
    }

    @Test
    fun `the IP header checksum verifies`() {
        val request = Ipv4UdpPacket.parse(packet(byteArrayOf(1, 2, 3)), 31)!!
        val reply = Ipv4UdpPacket.buildReply(request, ByteArray(12) { it.toByte() })

        // A correct one's-complement sum over the header, checksum field included, is 0xFFFF. This is
        // exactly the check the kernel does before deciding whether to drop the packet.
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((reply[i].toInt() and 0xFF) shl 8) or (reply[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)

        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `the reply declares the right total and UDP lengths`() {
        val request = Ipv4UdpPacket.parse(packet(byteArrayOf(1)), 29)!!
        val payload = ByteArray(40)
        val reply = Ipv4UdpPacket.buildReply(request, payload)

        assertEquals(20 + 8 + 40, reply.size)
        assertEquals(reply.size, ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF))
        assertEquals(8 + 40, ((reply[24].toInt() and 0xFF) shl 8) or (reply[25].toInt() and 0xFF))
    }
}
