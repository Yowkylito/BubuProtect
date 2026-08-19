package com.personal.bubuprotect.core.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.Bundle
import com.personal.bubuprotect.domain.model.ScannedCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/** Why one tap produced nothing. Each maps to its own sentence on screen - none is "error". */
enum class CardScanFailure {
    /** Something answered, but not a payment application. A transit pass, a hotel key, a badge. */
    NOT_A_PAYMENT_CARD,

    /** A payment card that will not disclose a static number. Not the user's fault, not fixable. */
    NUMBER_WITHHELD,

    /** The card left the field mid-conversation. The one failure that retrying reliably fixes. */
    MOVED_TOO_SOON,

    /** Malformed responses, or a transceive that failed outright. */
    UNREADABLE
}

sealed interface CardScanState {
    /** No NFC hardware. A permanent answer, so the UI offers manual entry instead of a retry. */
    data object Unsupported : CardScanState

    /** Hardware present, switched off in system settings. Recoverable, and worth saying how. */
    data object Disabled : CardScanState

    data object Waiting : CardScanState

    data object Reading : CardScanState

    /** The caller owns [card] and must call [ScannedCard.wipe] once it has copied the digits. */
    data class Success(val card: ScannedCard) : CardScanState

    data class Failed(val reason: CardScanFailure) : CardScanState
}

/**
 * Turns taps on the back of the phone into [CardScanState].
 *
 * ### Reader mode, and only while a scan is on screen
 *
 * There is deliberately no `TECH_DISCOVERED` intent filter in the manifest. Registering one would
 * make Bubu Protect a permanently-listening NFC handler - the OS would wake it for any card that
 * passed near the phone, whether or not anyone asked it to. Reader mode, enabled by
 * [scans] and torn down by `awaitClose` when the scan sheet leaves the composition, means the app
 * can only ever read a card during the few seconds a user is looking at a screen that says so.
 *
 * That is the whole anti-skimming argument, and it is a structural one rather than a policy one: a
 * scan cannot happen without the sheet, and the sheet cannot appear without a tap on the card field.
 */
class NfcCardScanner(
    private val reader: EmvCardReader = EmvCardReader()
) {

    /**
     * Whether this device has an NFC controller at all.
     *
     * Lets the editor omit the scan affordance entirely rather than offering a button whose only
     * outcome is a sheet apologising. `uses-feature` is declared `required="false"`, so the app does
     * install on phones where this returns false.
     */
    fun isSupported(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context) != null

    fun scans(activity: Activity): Flow<CardScanState> = callbackFlow {
        val adapter = NfcAdapter.getDefaultAdapter(activity)

        when {
            adapter == null -> {
                send(CardScanState.Unsupported)
                awaitClose { }
            }

            !adapter.isEnabled -> {
                send(CardScanState.Disabled)
                awaitClose { }
            }

            else -> {
                send(CardScanState.Waiting)

                // A card resting on the phone is discovered repeatedly. Without this, a successful
                // read is immediately followed by another that resets the sheet out of its success
                // state and back to "hold your card here".
                val busy = AtomicBoolean(false)

                val callback = NfcAdapter.ReaderCallback { tag ->
                    if (busy.compareAndSet(false, true)) {
                        launch(Dispatchers.IO) {
                            try {
                                trySend(CardScanState.Reading)
                                val outcome = readCard(tag)
                                // A closed channel here means the sheet was dismissed mid-read. The
                                // digits would then have no owner to wipe them, so wipe them now.
                                if (trySend(outcome).isFailure && outcome is CardScanState.Success) {
                                    outcome.card.wipe()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (lost: TagLostException) {
                                trySend(CardScanState.Failed(CardScanFailure.MOVED_TOO_SOON))
                            } catch (failure: Throwable) {
                                // The class only. An NFC stack's exception messages can quote the
                                // bytes of the exchange that failed, and those bytes are the card.
                                Timber.tag(TAG)
                                    .w("Card read failed (%s)", failure::class.java.simpleName)
                                trySend(CardScanState.Failed(CardScanFailure.UNREADABLE))
                            } finally {
                                busy.set(false)
                            }
                        }
                    }
                }

                val extras = Bundle().apply {
                    // The platform polls to check the card is still there, and each poll steals time
                    // from the exchange. Slowing it down is what stops a long read on a slow card
                    // from being interrupted by the presence check itself.
                    putInt(
                        NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,
                        PRESENCE_CHECK_DELAY_MILLIS
                    )
                }

                adapter.enableReaderMode(activity, callback, READER_FLAGS, extras)
                awaitClose { runCatching { adapter.disableReaderMode(activity) } }
            }
        }
    }

    private suspend fun readCard(tag: Tag): CardScanState {
        // Null for tags that do not speak ISO-DEP at all - MIFARE Classic transit cards, most
        // access badges. They are not broken payment cards, they are not payment cards.
        val isoDep = IsoDep.get(tag)
            ?: return CardScanState.Failed(CardScanFailure.NOT_A_PAYMENT_CARD)

        isoDep.connect()
        return try {
            isoDep.timeout = TRANSCEIVE_TIMEOUT_MILLIS
            when (val result = reader.read { command -> isoDep.transceive(command) }) {
                is EmvReadResult.Success -> CardScanState.Success(result.card)
                EmvReadResult.NotAPaymentCard ->
                    CardScanState.Failed(CardScanFailure.NOT_A_PAYMENT_CARD)
                EmvReadResult.NumberWithheld ->
                    CardScanState.Failed(CardScanFailure.NUMBER_WITHHELD)
            }
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private companion object {
        const val TAG = "NfcCardScanner"

        /**
         * NFC-A and NFC-B cover every contactless payment card in circulation.
         *
         * `SKIP_NDEF_CHECK` matters for more than speed: without it the platform reads the tag for
         * NDEF content before handing it over, which costs a beat of the short window the card is
         * actually against the phone.
         */
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        const val PRESENCE_CHECK_DELAY_MILLIS = 500

        /** Per exchange, not per scan. Generous: some cards take their time over `GPO`. */
        const val TRANSCEIVE_TIMEOUT_MILLIS = 3_000
    }
}
