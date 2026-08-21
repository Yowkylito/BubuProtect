package com.personal.bubuprotect.core.shield.network

/**
 * Reads and writes the IPv4 + UDP framing around a DNS datagram.
 *
 * ### Why this exists at all
 *
 * A `VpnService` hands over raw IP packets, not sockets. To answer a DNS query that arrived through the
 * tunnel, the reply has to be framed as a complete IPv4 packet with the addresses and ports swapped and
 * a valid header checksum, then written back to the same file descriptor.
 *
 * That is the entire scope. There is no TCP handling, no fragment reassembly, no IPv6, and no
 * connection tracking - because the tunnel is built with a single `/32` route to its own fake DNS
 * server, so DNS is the only thing that ever enters it. Everything else on the phone bypasses this code
 * at the kernel level. That constraint is what keeps the filter a few hundred lines instead of the
 * userspace TCP/IP stack that a route-everything VPN would need.
 *
 * ### Pure and total
 *
 * Every parse returns null rather than throwing. These bytes come from arbitrary apps and the caller is
 * a packet loop; an exception there takes the tunnel down and the user's DNS with it. Keeping this file
 * free of Android imports also means the checksum arithmetic - the part most likely to be subtly wrong -
 * is covered by ordinary JVM unit tests.
 */
object Ipv4UdpPacket {

    const val PROTOCOL_UDP = 17

    private const val IP_HEADER_BYTES = 20
    private const val UDP_HEADER_BYTES = 8
    private const val VERSION_4 = 4

    /**
     * A parsed UDP datagram carried over IPv4.
     *
     * Addresses are kept as dotted strings rather than `InetAddress` because that is the shape both
     * consumers need: the attributor builds `InetSocketAddress` from them, and the reply builder writes
     * them straight back out. Resolving to `InetAddress` inside a packet loop would mean an allocation
     * and a potential name-service call per packet.
     */
    data class Datagram(
        val sourceAddress: String,
        val destinationAddress: String,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray,
        val payloadLength: Int
    ) {
        // ByteArray in a data class gives identity-based equals, which would silently break any
        // comparison. Nothing here compares datagrams, so rather than leave a trap the generated
        // methods are replaced with ones that mean what they say.
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * @return null for anything that is not a well-formed IPv4 UDP packet - a v6 packet, a fragment, a
     *   truncated read, or a protocol other than UDP. All of those are simply not this filter's
     *   business, and the caller drops them.
     */
    fun parse(packet: ByteArray, length: Int): Datagram? {
        if (length < IP_HEADER_BYTES) return null

        val versionAndLength = packet[0].toInt() and 0xFF
        if (versionAndLength shr 4 != VERSION_4) return null

        val headerBytes = (versionAndLength and 0x0F) * 4
        if (headerBytes < IP_HEADER_BYTES || headerBytes + UDP_HEADER_BYTES > length) return null

        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        // A fragmented DNS query is pathological and reassembling it is out of scope. The offset field
        // occupies the low 13 bits of [6,7]; anything non-zero, or the more-fragments bit, means this
        // is a piece of something and cannot be parsed on its own.
        val fragmentField = readShort(packet, 6)
        if (fragmentField and 0x1FFF != 0 || fragmentField and 0x2000 != 0) return null

        val udpLength = readShort(packet, headerBytes + 4)
        if (udpLength < UDP_HEADER_BYTES) return null

        val payloadStart = headerBytes + UDP_HEADER_BYTES
        val payloadLength = minOf(udpLength - UDP_HEADER_BYTES, length - payloadStart)
        if (payloadLength < 0) return null

        return Datagram(
            sourceAddress = readAddress(packet, 12),
            destinationAddress = readAddress(packet, 16),
            sourcePort = readShort(packet, headerBytes),
            destinationPort = readShort(packet, headerBytes + 2),
            payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength),
            payloadLength = payloadLength
        )
    }

    /**
     * Frames [payload] as a reply to [request], with addresses and ports reversed.
     *
     * The UDP checksum is written as zero, which IPv4 explicitly permits and which every stack accepts.
     * The IP header checksum is not optional and is computed. Skipping the UDP one saves a second pass
     * over the payload per packet, on a path that runs for every DNS query on the device.
     *
     * TTL is 64 - the conventional default - and the identification field is zero, which is only
     * meaningful for fragmentation and this never fragments.
     */
    fun buildReply(request: Datagram, payload: ByteArray): ByteArray {
        val total = IP_HEADER_BYTES + UDP_HEADER_BYTES + payload.size
        val packet = ByteArray(total)

        packet[0] = ((VERSION_4 shl 4) or (IP_HEADER_BYTES / 4)).toByte()
        packet[1] = 0
        writeShort(packet, 2, total)
        writeShort(packet, 4, 0)
        writeShort(packet, 6, 0)
        packet[8] = 64
        packet[9] = PROTOCOL_UDP.toByte()
        writeShort(packet, 10, 0)
        writeAddress(packet, 12, request.destinationAddress)
        writeAddress(packet, 16, request.sourceAddress)
        writeShort(packet, 10, checksum(packet, 0, IP_HEADER_BYTES))

        writeShort(packet, IP_HEADER_BYTES, request.destinationPort)
        writeShort(packet, IP_HEADER_BYTES + 2, request.sourcePort)
        writeShort(packet, IP_HEADER_BYTES + 4, UDP_HEADER_BYTES + payload.size)
        writeShort(packet, IP_HEADER_BYTES + 6, 0)

        payload.copyInto(packet, IP_HEADER_BYTES + UDP_HEADER_BYTES)
        return packet
    }

    /** Standard one's-complement sum of 16-bit words, per RFC 1071. */
    private fun checksum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset

        while (i < offset + length - 1) {
            sum += readShort(packet, i)
            i += 2
        }
        if (i < offset + length) sum += (packet[i].toInt() and 0xFF) shl 8

        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)

        return sum.inv() and 0xFFFF
    }

    private fun readShort(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun writeShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 1] = (value and 0xFF).toByte()
    }

    private fun readAddress(packet: ByteArray, offset: Int): String = (0 until 4)
        .joinToString(".") { (packet[offset + it].toInt() and 0xFF).toString() }

    private fun writeAddress(packet: ByteArray, offset: Int, address: String) {
        address.split('.').forEachIndexed { index, part ->
            if (index < 4) packet[offset + index] = (part.toIntOrNull() ?: 0).toByte()
        }
    }
}
