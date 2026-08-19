package com.personal.bubuprotect.core.nfc

/**
 * Where one BER-TLV object's value sits inside the buffer it was parsed from.
 *
 * A range rather than a copy, and that is the whole point of this API. The buffers being walked are
 * EMV record responses, and those contain the primary account number. Handing back
 * `ByteArray.copyOfRange` at every level would scatter copies of the PAN across the heap for the
 * garbage collector to release whenever it feels like it - the caller can zero the one response
 * buffer it owns, but it cannot zero copies it never sees. Ranges mean the digits exist in exactly
 * one place, and [EmvCardReader] wipes that place before it returns.
 */
internal class TlvRange(val start: Int, val length: Int) {
    val endExclusive: Int get() = start + length
}

/**
 * A reader for the BER-TLV encoding EMV uses for every response.
 *
 * Only the read side, and only the parts EMV actually emits: definite lengths, tags of at most a few
 * bytes, constructed objects nesting a handful deep. Everything it parses arrives from a card held
 * against the phone by someone who may not own it, so every field is treated as hostile - a length
 * that runs past the buffer, a tag that never terminates, or a template nested a thousand deep ends
 * the walk instead of throwing, looping, or reading out of bounds.
 */
internal object Tlv {

    /** The value of the first object with this tag, depth-first, or null. */
    fun find(data: ByteArray, tag: Int): TlvRange? {
        var found: TlvRange? = null
        walk(data, 0) { candidate, _, range ->
            if (candidate == tag) {
                found = range
                false
            } else {
                true
            }
        }
        return found
    }

    /** Every object with this tag, in document order. Used for the PPSE's repeated `61` entries. */
    fun findAll(data: ByteArray, tag: Int): List<TlvRange> {
        val found = mutableListOf<TlvRange>()
        walk(data, 0) { candidate, _, range ->
            if (candidate == tag) found += range
            true
        }
        return found
    }

    /**
     * Depth-first walk. [visit] returns false to stop.
     *
     * @return false when a visitor asked to stop, so recursive calls can unwind.
     */
    private fun walk(
        data: ByteArray,
        depth: Int,
        visit: (tag: Int, isConstructed: Boolean, range: TlvRange) -> Boolean
    ): Boolean {
        if (depth > MAX_DEPTH) return true

        var index = 0
        while (index < data.size) {
            // 0x00 and 0xFF between objects are padding, not tags. Cards do emit them.
            val lead = data[index].toInt() and 0xFF
            if (lead == 0x00 || lead == 0xFF) {
                index++
                continue
            }
            index++

            var tag = lead
            if (lead and 0x1F == 0x1F) {
                var continues = true
                var extraBytes = 0
                while (continues) {
                    // EMV tags are two bytes; the cap is what stops a run of 0x80s walking the
                    // buffer looking for a terminator that is never coming.
                    if (index >= data.size || extraBytes >= MAX_TAG_EXTRA_BYTES) return true
                    val next = data[index].toInt() and 0xFF
                    tag = (tag shl 8) or next
                    index++
                    extraBytes++
                    continues = next and 0x80 != 0
                }
            }

            if (index >= data.size) return true
            var length = data[index].toInt() and 0xFF
            index++
            if (length and 0x80 != 0) {
                val lengthBytes = length and 0x7F
                // 0 is the indefinite form, which BER allows and EMV does not; anything past three
                // bytes describes a response larger than a card will ever send.
                if (lengthBytes == 0 || lengthBytes > MAX_LENGTH_BYTES) return true
                if (index + lengthBytes > data.size) return true
                length = 0
                repeat(lengthBytes) {
                    length = (length shl 8) or (data[index].toInt() and 0xFF)
                    index++
                }
            }
            if (length < 0 || index + length > data.size) return true

            val isConstructed = lead and 0x20 != 0
            if (!visit(tag, isConstructed, TlvRange(index, length))) return false

            if (isConstructed) {
                // Recursing over a copy is the one place a copy is unavoidable, so the offsets are
                // rebased afterwards rather than the bytes being handed to the visitor.
                val childStart = index
                val child = data.copyOfRange(childStart, childStart + length)
                try {
                    val continued = walk(child, depth + 1) { childTag, childConstructed, childRange ->
                        visit(
                            childTag,
                            childConstructed,
                            TlvRange(childStart + childRange.start, childRange.length)
                        )
                    }
                    if (!continued) return false
                } finally {
                    child.fill(0)
                }
            }

            index += length
        }
        return true
    }

    /** Templates nest three or four deep in practice. Eight is slack, not a design constraint. */
    private const val MAX_DEPTH = 8
    private const val MAX_TAG_EXTRA_BYTES = 3
    private const val MAX_LENGTH_BYTES = 3
}
