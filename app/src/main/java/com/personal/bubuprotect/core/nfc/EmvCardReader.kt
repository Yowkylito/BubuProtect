package com.personal.bubuprotect.core.nfc

import com.personal.bubuprotect.domain.model.ScannedCard
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** One APDU exchange. Implemented over `IsoDep` in production, and over a script in tests. */
fun interface ApduTransceiver {
    /**
     * @return the card's full response, status word included.
     * @throws java.io.IOException when the card left the field or the exchange failed.
     */
    suspend fun transceive(command: ByteArray): ByteArray
}

sealed interface EmvReadResult {
    data class Success(val card: ScannedCard) : EmvReadResult

    /** Responded, but not as a payment card - a transit pass, a hotel key, an NDEF tag. */
    data object NotAPaymentCard : EmvReadResult

    /**
     * A payment application answered, and would not part with a number.
     *
     * A real and increasingly common outcome, not a bug: some issuers decline `READ RECORD` outside
     * a full transaction, and cards personalised for tokenised use may carry no static number at
     * all. Distinct from [NotAPaymentCard] because the honest message differs - "that is not a card"
     * versus "this card does not share its number".
     */
    data object NumberWithheld : EmvReadResult
}

/**
 * Reads the card number and expiry from a contactless EMV card.
 *
 * ### The conversation
 *
 * `SELECT` the payment system directory to list the card's applications, `SELECT` one of them,
 * `GET PROCESSING OPTIONS` to learn which records exist, then `READ RECORD` until a card number
 * turns up. Cards that refuse to enumerate get a brute sweep of the low short file identifiers
 * instead, which is the difference between working on most cards and working on some.
 *
 * ### What this deliberately never sends
 *
 * `GENERATE AC`. That is the command that asks a card to authorise a transaction, and it is the line
 * between reading a card and using one. `GET PROCESSING OPTIONS` does begin a transaction from the
 * card's point of view - it advances the application transaction counter and the card prepares a
 * cryptogram - but nothing here consumes that cryptogram and no amount is ever presented. An
 * abandoned transaction at the GPO stage is exactly what happens when someone taps a terminal and
 * walks away, and issuers expect gaps in that counter.
 *
 * ### Hostile input
 *
 * Every byte came from a card, and the person holding the phone is not necessarily the person who
 * owns it. Lengths, counts and loop bounds are capped, [Tlv] refuses to read past its buffer, and a
 * response that does not parse ends the attempt rather than throwing.
 */
class EmvCardReader(
    private val random: SecureRandom = SecureRandom()
) {

    suspend fun read(transceiver: ApduTransceiver): EmvReadResult {
        val applications = discoverApplications(transceiver)
        if (applications.isEmpty()) return EmvReadResult.NotAPaymentCard

        var anyApplicationAnswered = false

        for (application in applications) {
            currentCoroutineContext().ensureActive()

            val fci = select(transceiver, application.aid) ?: continue
            anyApplicationAnswered = true

            val harvest = Harvest()
            harvest.label = application.label ?: readText(fci, Tlv.find(fci, TAG_APPLICATION_LABEL))
            try {
                collectRecords(transceiver, fci, harvest)
                harvest.toCardOrNull()?.let { return EmvReadResult.Success(it) }
            } finally {
                harvest.wipe()
                fci.fill(0)
            }
        }

        return if (anyApplicationAnswered) {
            EmvReadResult.NumberWithheld
        } else {
            EmvReadResult.NotAPaymentCard
        }
    }

    // ------------------------------------------------------------------ application discovery

    private class CardApplication(val aid: ByteArray, val label: String?)

    /**
     * Asks the payment system directory what is on the card.
     *
     * Falls back to selecting well-known application identifiers directly. Some cards - and some
     * phones' NFC stacks under poor coupling - fail the directory select while answering a direct
     * one, and five extra APDUs is a cheap price for a card that would otherwise have just failed.
     */
    private suspend fun discoverApplications(transceiver: ApduTransceiver): List<CardApplication> {
        val directory = select(transceiver, PPSE_NAME)
            ?: return KNOWN_AIDS.map { CardApplication(it, label = null) }

        return try {
            val applications = Tlv.findAll(directory, TAG_DIRECTORY_ENTRY)
                .take(MAX_APPLICATIONS)
                .mapNotNull { entry ->
                    val slice = directory.copyOfRange(entry.start, entry.endExclusive)
                    try {
                        val aid = Tlv.find(slice, TAG_ADF_NAME)
                            ?.let { slice.copyOfRange(it.start, it.endExclusive) }
                            ?.takeIf { it.size in MIN_AID_LENGTH..MAX_AID_LENGTH }
                            ?: return@mapNotNull null
                        CardApplication(aid, readText(slice, Tlv.find(slice, TAG_APPLICATION_LABEL)))
                    } finally {
                        slice.fill(0)
                    }
                }
            applications.ifEmpty { KNOWN_AIDS.map { CardApplication(it, label = null) } }
        } finally {
            directory.fill(0)
        }
    }

    // ------------------------------------------------------------------ record collection

    private suspend fun collectRecords(
        transceiver: ApduTransceiver,
        fci: ByteArray,
        harvest: Harvest
    ) {
        var budget = MAX_RECORD_READS

        val locator = getProcessingOptions(transceiver, fci)?.let { options ->
            try {
                applicationFileLocator(options)
            } finally {
                options.fill(0)
            }
        }

        if (locator != null) {
            budget = readLocatedRecords(transceiver, locator, harvest, budget)
            locator.fill(0)
            if (harvest.isComplete) return
        }

        // The sweep. Cards that refuse GPO, or answer it without a file locator, still tend to
        // answer READ RECORD on the first couple of short file identifiers - which is where the
        // track-2 record almost always lives.
        outer@ for (sfi in 1..SWEEP_MAX_SFI) {
            for (record in 1..SWEEP_MAX_RECORD) {
                if (budget <= 0) break@outer
                currentCoroutineContext().ensureActive()
                budget--
                readRecord(transceiver, sfi, record)?.let { bytes ->
                    try {
                        harvestFrom(bytes, harvest)
                    } finally {
                        bytes.fill(0)
                    }
                }
                if (harvest.isComplete) break@outer
            }
        }
    }

    private suspend fun readLocatedRecords(
        transceiver: ApduTransceiver,
        locator: ByteArray,
        harvest: Harvest,
        startingBudget: Int
    ): Int {
        var budget = startingBudget
        var index = 0

        while (index + AFL_ENTRY_LENGTH <= locator.size) {
            val sfi = (locator[index].toInt() and 0xF8) ushr 3
            val first = locator[index + 1].toInt() and 0xFF
            val last = locator[index + 2].toInt() and 0xFF
            index += AFL_ENTRY_LENGTH

            if (sfi !in 1..MAX_SFI || first < 1 || last < first || last > MAX_RECORD_NUMBER) continue

            for (record in first..last) {
                if (budget <= 0) return budget
                currentCoroutineContext().ensureActive()
                budget--
                readRecord(transceiver, sfi, record)?.let { bytes ->
                    try {
                        harvestFrom(bytes, harvest)
                    } finally {
                        bytes.fill(0)
                    }
                }
                if (harvest.isComplete) return budget
            }
        }
        return budget
    }

    /**
     * Pulls whatever this record is willing to give up.
     *
     * Track 2 equivalent data is tried first because it carries the number and the expiry in one
     * object, so the common case resolves in a single record read.
     */
    private fun harvestFrom(record: ByteArray, harvest: Harvest) {
        if (!harvest.hasPan) {
            val track2 = Tlv.find(record, TAG_TRACK2_EQUIVALENT)
                ?: Tlv.find(record, TAG_TRACK2_DATA)
            if (track2 != null) readTrack2(record, track2, harvest)
        }
        if (!harvest.hasPan) {
            Tlv.find(record, TAG_PAN)?.let { readPaddedPan(record, it, harvest) }
        }
        if (!harvest.hasExpiry) {
            Tlv.find(record, TAG_EXPIRY)?.let { readExpiry(record, it, harvest) }
        }
        if (harvest.holderName == null) {
            harvest.holderName = readText(record, Tlv.find(record, TAG_CARDHOLDER_NAME))
                ?.replace('/', ' ')
                ?.replace(WHITESPACE_RUN, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(PLACEHOLDER_NAME, ignoreCase = true) }
        }
    }

    /** Track 2: PAN nibbles, a `D` separator, `YYMM`, service code, discretionary data. */
    private fun readTrack2(buffer: ByteArray, range: TlvRange, harvest: Harvest) {
        val digits = CharArray(MAX_PAN_DIGITS)
        try {
            val totalNibbles = range.length * 2
            var nibble = 0
            var count = 0

            while (nibble < totalNibbles) {
                val value = nibbleAt(buffer, range.start, nibble)
                if (value == SEPARATOR_NIBBLE) break
                if (value > 9 || count == MAX_PAN_DIGITS) return
                digits[count] = '0' + value
                count++
                nibble++
            }
            // A run of digits with no separator is not track 2, whatever the tag claimed.
            if (nibble >= totalNibbles) return

            val expiryStart = nibble + 1
            if (expiryStart + EXPIRY_NIBBLES <= totalNibbles) {
                val year = twoDigits(buffer, range.start, expiryStart)
                val month = twoDigits(buffer, range.start, expiryStart + 2)
                if (year >= 0 && month in 1..12) {
                    harvest.expiryYear = CENTURY + year
                    harvest.expiryMonth = month
                }
            }
            harvest.acceptPan(digits, count)
        } finally {
            digits.fill(Char.MIN_VALUE)
        }
    }

    /** Tag `5A`: the number as BCD, right-padded with `F` nibbles to a whole number of bytes. */
    private fun readPaddedPan(buffer: ByteArray, range: TlvRange, harvest: Harvest) {
        val digits = CharArray(MAX_PAN_DIGITS)
        try {
            val totalNibbles = range.length * 2
            var count = 0
            for (nibble in 0 until totalNibbles) {
                val value = nibbleAt(buffer, range.start, nibble)
                if (value == PADDING_NIBBLE) break
                if (value > 9 || count == MAX_PAN_DIGITS) return
                digits[count] = '0' + value
                count++
            }
            harvest.acceptPan(digits, count)
        } finally {
            digits.fill(Char.MIN_VALUE)
        }
    }

    /** Tag `5F24`: `YYMMDD` as BCD. Only the first four nibbles matter here. */
    private fun readExpiry(buffer: ByteArray, range: TlvRange, harvest: Harvest) {
        if (range.length < EXPIRY_BYTES) return
        val year = twoDigits(buffer, range.start, 0)
        val month = twoDigits(buffer, range.start, 2)
        if (year < 0 || month !in 1..12) return
        harvest.expiryYear = CENTURY + year
        harvest.expiryMonth = month
    }

    // ------------------------------------------------------------------ APDU plumbing

    private suspend fun select(transceiver: ApduTransceiver, name: ByteArray): ByteArray? {
        if (name.isEmpty() || name.size > MAX_SELECT_NAME_LENGTH) return null
        val command = ByteArray(HEADER_LENGTH + 1 + name.size + 1)
        command[0] = CLA_ISO
        command[1] = INS_SELECT
        command[2] = 0x04
        command[3] = 0x00
        command[4] = name.size.toByte()
        name.copyInto(command, HEADER_LENGTH + 1)
        command[command.size - 1] = LE_ANY
        return transmit(transceiver, command)
    }

    /**
     * `GET PROCESSING OPTIONS`, answering the card's PDOL when it asks for one.
     *
     * The naive form - an empty `83` template - is what most sample code sends, and a large share of
     * contactless cards refuse it. That is the single biggest reason a card-scanning feature "works
     * on my card" and nowhere else. When the application declares a PDOL, every field it lists has
     * to come back at exactly the length requested or the card rejects the command outright.
     */
    private suspend fun getProcessingOptions(
        transceiver: ApduTransceiver,
        fci: ByteArray
    ): ByteArray? {
        val data = Tlv.find(fci, TAG_PDOL)?.let { buildPdolData(fci, it) } ?: ByteArray(0)
        if (data.size > MAX_PDOL_DATA_LENGTH) return null

        val command = ByteArray(HEADER_LENGTH + 1 + 2 + data.size + 1)
        command[0] = CLA_PROPRIETARY
        command[1] = INS_GET_PROCESSING_OPTIONS
        command[2] = 0x00
        command[3] = 0x00
        command[4] = (data.size + 2).toByte()
        command[5] = TAG_COMMAND_TEMPLATE
        command[6] = data.size.toByte()
        data.copyInto(command, 7)
        command[command.size - 1] = LE_ANY
        return transmit(transceiver, command)
    }

    private suspend fun readRecord(
        transceiver: ApduTransceiver,
        sfi: Int,
        record: Int
    ): ByteArray? {
        val command = byteArrayOf(
            CLA_ISO,
            INS_READ_RECORD,
            record.toByte(),
            (((sfi shl 3) or 0x04) and 0xFF).toByte(),
            LE_ANY
        )
        return transmit(transceiver, command)
    }

    /**
     * Sends one command and returns its body, or null for any status that is not `9000`.
     *
     * A non-success status is routine here - the sweep asks for records that do not exist and every
     * miss answers `6A83`. That is a normal outcome, not an error worth propagating.
     */
    private suspend fun transmit(transceiver: ApduTransceiver, command: ByteArray): ByteArray? {
        var response = transceiver.transceive(command)
        if (response.size < STATUS_LENGTH) return null

        // 6CXX: the card wants the exact length it just named. Re-ask with it.
        if (statusByte1(response) == SW1_WRONG_LENGTH) {
            val retry = command.copyOf()
            retry[retry.size - 1] = statusByte2(response).toByte()
            response = transceiver.transceive(retry)
            if (response.size < STATUS_LENGTH) return null
        }

        // 61XX: more data waiting. Rare over contactless, cheap to honour.
        if (statusByte1(response) == SW1_MORE_DATA) {
            response = transceiver.transceive(
                byteArrayOf(CLA_ISO, INS_GET_RESPONSE, 0x00, 0x00, statusByte2(response).toByte())
            )
            if (response.size < STATUS_LENGTH) return null
        }

        if (statusByte1(response) != SW1_OK || statusByte2(response) != SW2_OK) return null
        return response.copyOfRange(0, response.size - STATUS_LENGTH)
    }

    /** Format 2 (`77`) tags the file locator; format 1 (`80`) is the AIP followed by the locator. */
    private fun applicationFileLocator(options: ByteArray): ByteArray? {
        Tlv.find(options, TAG_AFL)?.let {
            return options.copyOfRange(it.start, it.endExclusive)
        }
        val format1 = Tlv.find(options, TAG_RESPONSE_FORMAT_1) ?: return null
        if (format1.length <= AIP_LENGTH) return null
        return options.copyOfRange(format1.start + AIP_LENGTH, format1.endExclusive)
    }

    private fun buildPdolData(buffer: ByteArray, pdol: TlvRange): ByteArray {
        val out = ByteArrayOutputStream()
        var index = pdol.start
        val end = pdol.endExclusive
        var fields = 0

        while (index < end && fields < MAX_PDOL_FIELDS) {
            val lead = buffer[index].toInt() and 0xFF
            index++
            var tag = lead
            if (lead and 0x1F == 0x1F) {
                var extra = 0
                var next: Int
                do {
                    if (index >= end || extra >= MAX_TAG_EXTRA_BYTES) return out.toByteArray()
                    next = buffer[index].toInt() and 0xFF
                    tag = (tag shl 8) or next
                    index++
                    extra++
                } while (next and 0x80 != 0)
            }
            if (index >= end) break
            val length = buffer[index].toInt() and 0xFF
            index++
            if (length > MAX_PDOL_FIELD_LENGTH) return out.toByteArray()
            out.write(terminalValueFor(tag, length))
            fields++
        }
        return out.toByteArray()
    }

    /**
     * What this "terminal" claims about itself.
     *
     * Zeros are the right answer for most fields - a zero amount, a zero date, cleared verification
     * results. The ones that matter are the transaction qualifiers, which tell a contactless card it
     * is talking to an online-capable terminal so it proceeds rather than declining, a country and
     * currency, and an unpredictable number. None of it is read back; it exists so the card agrees
     * to describe its own file layout.
     */
    private fun terminalValueFor(tag: Int, length: Int): ByteArray = when (tag) {
        TAG_TTQ -> fit(TTQ_ONLINE_CAPABLE, length)
        TAG_TERMINAL_COUNTRY, TAG_TRANSACTION_CURRENCY -> fit(COUNTRY_US, length)
        TAG_TERMINAL_TYPE -> fit(TERMINAL_TYPE_ATTENDED_ONLINE, length)
        TAG_UNPREDICTABLE_NUMBER -> ByteArray(length).also(random::nextBytes)
        else -> ByteArray(length)
    }

    private fun fit(value: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        value.copyInto(out, endIndex = minOf(value.size, length))
        return out
    }

    // ------------------------------------------------------------------ accumulation

    /**
     * Fields gathered so far, across however many records it took.
     *
     * Mutable and wipeable rather than a `data class` rebuilt by `copy`, because every `copy` would
     * strand the previous card number on the heap.
     */
    private class Harvest {
        var pan: CharArray? = null
        var expiryMonth: Int = 0
        var expiryYear: Int = 0
        var holderName: String? = null
        var label: String? = null

        val hasPan: Boolean get() = pan != null
        val hasExpiry: Boolean get() = expiryMonth in 1..12

        /** Enough to stop reading: nothing later in the file can improve on this. */
        val isComplete: Boolean get() = hasPan && hasExpiry

        fun acceptPan(digits: CharArray, count: Int) {
            if (pan != null || !isLuhnValid(digits, count)) return
            pan = CharArray(count).also { digits.copyInto(it, endIndex = count) }
        }

        fun toCardOrNull(): ScannedCard? {
            val digits = pan ?: return null
            // Ownership passes to the card, so wipe() must not clear what the caller is about to
            // read. ScannedCard.wipe() is what zeroes it, once the editor has copied the digits.
            pan = null
            return ScannedCard(
                pan = digits,
                expiryMonth = expiryMonth,
                expiryYear = expiryYear,
                holderName = holderName,
                applicationLabel = label
            )
        }

        fun wipe() {
            pan?.fill(Char.MIN_VALUE)
            pan = null
        }
    }

    private companion object {
        val PPSE_NAME = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

        /** Visa, Mastercard, Amex, Discover, JCB - the fallback when the directory select fails. */
        val KNOWN_AIDS = listOf(
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x03, 0x10, 0x10),
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x04, 0x10, 0x10),
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x25, 0x01, 0x00),
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x01, 0x52, 0x30, 0x10),
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x00, 0x65, 0x10, 0x10)
        )

        val TTQ_ONLINE_CAPABLE = byteArrayOf(0xF0.toByte(), 0x20, 0x40, 0x00)
        val COUNTRY_US = byteArrayOf(0x08, 0x40)
        val TERMINAL_TYPE_ATTENDED_ONLINE = byteArrayOf(0x22)

        val WHITESPACE_RUN = Regex("\\s+")

        /** Cards personalised without a real name print this. Prefilling it is worse than blank. */
        const val PLACEHOLDER_NAME = "CARDHOLDER"

        const val CLA_ISO: Byte = 0x00
        val CLA_PROPRIETARY: Byte = 0x80.toByte()
        val INS_SELECT: Byte = 0xA4.toByte()
        val INS_READ_RECORD: Byte = 0xB2.toByte()
        val INS_GET_RESPONSE: Byte = 0xC0.toByte()
        val INS_GET_PROCESSING_OPTIONS: Byte = 0xA8.toByte()
        val TAG_COMMAND_TEMPLATE: Byte = 0x83.toByte()
        const val LE_ANY: Byte = 0x00
        const val HEADER_LENGTH = 4
        const val STATUS_LENGTH = 2

        const val SW1_OK = 0x90
        const val SW2_OK = 0x00
        const val SW1_WRONG_LENGTH = 0x6C
        const val SW1_MORE_DATA = 0x61

        const val TAG_DIRECTORY_ENTRY = 0x61
        const val TAG_ADF_NAME = 0x4F
        const val TAG_APPLICATION_LABEL = 0x50
        const val TAG_RESPONSE_FORMAT_1 = 0x80
        const val TAG_AFL = 0x94
        const val TAG_PAN = 0x5A
        const val TAG_TRACK2_EQUIVALENT = 0x57
        const val TAG_TRACK2_DATA = 0x9F6B
        const val TAG_EXPIRY = 0x5F24
        const val TAG_CARDHOLDER_NAME = 0x5F20
        const val TAG_PDOL = 0x9F38

        const val TAG_TTQ = 0x9F66
        const val TAG_TERMINAL_COUNTRY = 0x9F1A
        const val TAG_TRANSACTION_CURRENCY = 0x5F2A
        const val TAG_TERMINAL_TYPE = 0x9F35
        const val TAG_UNPREDICTABLE_NUMBER = 0x9F37

        const val AIP_LENGTH = 2
        const val AFL_ENTRY_LENGTH = 4
        const val EXPIRY_BYTES = 3
        const val EXPIRY_NIBBLES = 4
        const val CENTURY = 2000
        const val SEPARATOR_NIBBLE = 0xD
        const val PADDING_NIBBLE = 0xF

        const val MIN_PAN_DIGITS = 12
        const val MAX_PAN_DIGITS = 19
        const val MIN_AID_LENGTH = 5
        const val MAX_AID_LENGTH = 16
        const val MAX_SELECT_NAME_LENGTH = 32
        const val MAX_APPLICATIONS = 8
        const val MAX_SFI = 30
        const val MAX_RECORD_NUMBER = 16
        const val MAX_TAG_EXTRA_BYTES = 3
        const val MAX_PDOL_FIELDS = 32
        const val MAX_PDOL_FIELD_LENGTH = 64
        const val MAX_PDOL_DATA_LENGTH = 252

        /**
         * Ceiling on APDU exchanges per application.
         *
         * A cooperative card is done in three or four. The sweep is what can run long, and at
         * roughly ten milliseconds per exchange this bounds a stubborn card well under a second -
         * short enough that the user has not yet moved the card away.
         */
        const val MAX_RECORD_READS = 48
        const val SWEEP_MAX_SFI = 8
        const val SWEEP_MAX_RECORD = 6

        fun statusByte1(response: ByteArray): Int = response[response.size - 2].toInt() and 0xFF

        fun statusByte2(response: ByteArray): Int = response[response.size - 1].toInt() and 0xFF

        fun nibbleAt(buffer: ByteArray, start: Int, nibble: Int): Int {
            val byte = buffer[start + (nibble shr 1)].toInt() and 0xFF
            return if (nibble and 1 == 0) byte ushr 4 else byte and 0x0F
        }

        /** Two BCD nibbles as a number, or -1 when either is not a decimal digit. */
        fun twoDigits(buffer: ByteArray, start: Int, nibble: Int): Int {
            val high = nibbleAt(buffer, start, nibble)
            val low = nibbleAt(buffer, start, nibble + 1)
            return if (high > 9 || low > 9) -1 else high * 10 + low
        }

        /**
         * The check digit test every card number satisfies.
         *
         * Cheap, and it is what stops the sweep from mistaking a counter, a length, or a chunk of
         * discretionary data for a card number. Without it a scan can plausibly return sixteen
         * digits of nonsense, which is a good deal worse than returning nothing.
         */
        fun isLuhnValid(digits: CharArray, count: Int): Boolean {
            if (count < MIN_PAN_DIGITS || count > MAX_PAN_DIGITS) return false
            var sum = 0
            var doubling = false
            for (index in count - 1 downTo 0) {
                var value = digits[index] - '0'
                if (value !in 0..9) return false
                if (doubling) {
                    value *= 2
                    if (value > 9) value -= 9
                }
                sum += value
                doubling = !doubling
            }
            return sum % 10 == 0
        }

        /** Printable ASCII out of a TLV value, with control characters dropped. */
        fun readText(buffer: ByteArray, range: TlvRange?): String? {
            if (range == null || range.length == 0) return null
            val text = buildString(range.length) {
                for (index in range.start until range.endExclusive) {
                    val character = buffer[index].toInt() and 0xFF
                    if (character in 0x20..0x7E) append(character.toChar())
                }
            }.trim()
            return text.takeIf(String::isNotEmpty)
        }
    }
}
