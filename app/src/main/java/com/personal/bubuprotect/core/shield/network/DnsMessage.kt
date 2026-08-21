package com.personal.bubuprotect.core.shield.network

/**
 * Just enough DNS to read the name being asked for and to refuse it.
 *
 * ### Why a hand-rolled parser rather than a library
 *
 * This needs two operations - pull the QNAME out of a query, and turn a query into an NXDOMAIN reply -
 * and both are a few dozen lines against a format that has not changed since 1987. A DNS library would
 * add a dependency, a resolver, and a threading model to a class that must run inside a packet loop
 * with no allocation budget to speak of.
 *
 * Everything here is pure and byte-oriented, which is also what makes the filter testable off-device:
 * the interesting failure modes are malformed packets, and those are far easier to construct as byte
 * arrays than to provoke through a real network.
 *
 * ### Hostile input assumed throughout
 *
 * These bytes arrive from any app on the phone, including the adware being investigated. A malformed
 * or deliberately hostile packet must produce null rather than an exception, because the caller is a
 * `while (true)` loop reading a file descriptor - a throw there kills the tunnel and the user's DNS
 * with it. Every read is bounds-checked and every length is treated as untrusted.
 */
object DnsMessage {

    private const val HEADER_BYTES = 12
    private const val MAX_LABEL_BYTES = 63
    private const val MAX_NAME_BYTES = 255

    /** Flag bits: response, recursion-desired echoed back, and the RCODE for "no such name". */
    private const val FLAG_RESPONSE = 0x8000
    private const val FLAG_RECURSION_AVAILABLE = 0x0080
    private const val RCODE_NAME_ERROR = 0x0003

    /**
     * The hostname a query is asking about, lowercased and without a trailing dot.
     *
     * @return null for anything that is not a single-question query this code understands: a response
     *   rather than a query, a multi-question message, a compressed name in the question section
     *   (which is illegal there and is a common evasion attempt), or a truncated packet.
     *
     *   Null means *do not filter this* rather than *block it*. An unparseable query is forwarded
     *   untouched, because the alternative - dropping what we cannot read - would break any app using
     *   a DNS feature this parser does not cover.
     */
    fun questionName(packet: ByteArray, length: Int = packet.size): String? {
        if (length < HEADER_BYTES) return null

        val flags = readShort(packet, 2, length) ?: return null
        if (flags and FLAG_RESPONSE != 0) return null

        val questions = readShort(packet, 4, length) ?: return null
        if (questions != 1) return null

        val name = StringBuilder()
        var cursor = HEADER_BYTES
        var consumed = 0

        while (true) {
            if (cursor >= length) return null
            val labelLength = packet[cursor].toInt() and 0xFF

            // 0xC0 is a compression pointer. Legal in answers, never in a question, and a pointer here
            // is either a broken client or an attempt to make a parser follow an offset. Refuse.
            if (labelLength and 0xC0 != 0) return null
            if (labelLength == 0) break
            if (labelLength > MAX_LABEL_BYTES) return null

            cursor++
            if (cursor + labelLength > length) return null

            consumed += labelLength + 1
            if (consumed > MAX_NAME_BYTES) return null

            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until labelLength) {
                name.append((packet[cursor + i].toInt() and 0xFF).toChar())
            }
            cursor += labelLength
        }

        return name.toString().lowercase().takeIf(String::isNotEmpty)
    }

    /**
     * Turns a query into an authoritative "this name does not exist".
     *
     * NXDOMAIN rather than an A record pointing at 0.0.0.0, which is the other common approach. The
     * difference shows up in the app being blocked: a bogus address makes it open a socket, wait for a
     * connection that cannot happen, and retry - burning battery and often stalling its own UI for the
     * timeout. NXDOMAIN fails it immediately, which is both kinder to the battery and less likely to
     * make a blocked app look broken to the user.
     *
     * @return null if the query could not be understood well enough to answer it, in which case the
     *   caller forwards the original rather than inventing a reply.
     */
    fun nameErrorResponse(query: ByteArray, length: Int = query.size): ByteArray? {
        if (length < HEADER_BYTES) return null

        val questionEnd = questionSectionEnd(query, length) ?: return null

        val response = ByteArray(questionEnd)
        query.copyInto(response, 0, 0, questionEnd)

        // Transaction id at [0,1] is left exactly as it arrived: it is how the client matches the
        // reply to its own outstanding query, and changing it would make the answer be discarded.
        val flags = FLAG_RESPONSE or FLAG_RECURSION_AVAILABLE or RCODE_NAME_ERROR
        writeShort(response, 2, flags)

        // One question echoed back, and no answer, authority or additional records.
        writeShort(response, 4, 1)
        writeShort(response, 6, 0)
        writeShort(response, 8, 0)
        writeShort(response, 10, 0)

        return response
    }

    /** Offset just past the single question, or null when it does not parse. */
    private fun questionSectionEnd(packet: ByteArray, length: Int): Int? {
        var cursor = HEADER_BYTES

        while (true) {
            if (cursor >= length) return null
            val labelLength = packet[cursor].toInt() and 0xFF
            if (labelLength and 0xC0 != 0) return null
            if (labelLength == 0) {
                cursor++
                break
            }
            cursor += labelLength + 1
        }

        // QTYPE and QCLASS.
        val end = cursor + 4
        return if (end <= length) end else null
    }

    private fun readShort(packet: ByteArray, offset: Int, length: Int): Int? {
        if (offset + 2 > length) return null
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    private fun writeShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 1] = (value and 0xFF).toByte()
    }
}
