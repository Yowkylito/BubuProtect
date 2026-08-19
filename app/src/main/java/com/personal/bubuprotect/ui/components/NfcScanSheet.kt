package com.personal.bubuprotect.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.personal.bubuprotect.core.nfc.CardScanFailure
import com.personal.bubuprotect.core.nfc.CardScanState
import com.personal.bubuprotect.core.nfc.NfcCardScanner
import com.personal.bubuprotect.domain.model.ScannedCard
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * The scan sheet, wired to the NFC controller.
 *
 * ### Reader mode lives and dies with this composable
 *
 * `LaunchedEffect` starts the flow when the sheet enters the composition and cancels it when the
 * sheet leaves, which closes the `callbackFlow` and disables reader mode. There is no path by which
 * the app is listening for cards while this sheet is not on screen - see [NfcCardScanner] for why
 * that is the design rather than a convenience.
 *
 * ### The card's lifetime
 *
 * A successful read hands [ScannedCard] to [onScanned] and wipes it on the next line. The editor has
 * copied the digits into its form state by then, which is where they now live and where this file's
 * responsibility ends. The `onDispose` wipe covers the other exit: a user who swipes the sheet away
 * during the success beat, before the handoff ever runs.
 *
 * @param onScanned called once, with a card whose digits are valid only for the duration of the
 *   call. Copy, do not retain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcCardScanSheet(
    onScanned: (ScannedCard) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scanner: NfcCardScanner = koinInject()
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState()

    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<CardScanState>(CardScanState.Waiting) }

    LaunchedEffect(activity, attempt) {
        val host = activity
        if (host == null) {
            state = CardScanState.Unsupported
        } else {
            scanner.scans(host).collect { state = it }
        }
    }

    // Returning from system settings is the one moment the adapter can have changed underneath the
    // sheet, and the only case that warrants tearing the flow down and rebuilding it. It is safe
    // here precisely because the disabled branch never turned reader mode on, so nothing is being
    // disabled behind the replacement - see the note on onRetry for why that distinction matters.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state is CardScanState.Disabled) attempt++
    }

    LaunchedEffect(state) {
        val success = state as? CardScanState.Success ?: return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        // A held beat so the masked number is readable. Handing the form back instantly leaves the
        // user unsure whether the tap or their own typing produced what is now on screen.
        delay(SUCCESS_HOLD_MILLIS)
        onScanned(success.card)
        success.card.wipe()
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose { (state as? CardScanState.Success)?.card?.wipe() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        NfcScanSheetContent(
            state = state,
            onRetry = {
                // Deliberately not a restart. Reader mode is still enabled - a failed read leaves
                // the flow running - so the card can simply be tapped again, and this only puts the
                // sheet back to its inviting state. Restarting would also race its own teardown:
                // LaunchedEffect does not wait for a cancelled block's awaitClose before running
                // the replacement, so the old disableReaderMode can land after the new enable.
                state = CardScanState.Waiting
            },
            onOpenNfcSettings = {
                // Deep link only - the app never changes the setting itself, it asks the system to
                // show the user where it is.
                runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            },
            onEnterManually = onDismiss
        )
    }
}

/**
 * Everything the sheet shows, driven purely by [state].
 *
 * Stateless so every branch below has a preview, including the two that need hardware the emulator
 * does not have.
 */
@Composable
fun NfcScanSheetContent(
    state: CardScanState,
    onRetry: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onEnterManually: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keyed on the state so the masked number is captured before ScannedCard.wipe() blanks it.
    val copy = remember(state) { copyFor(state) }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = BubuSpacing.lg)
                .padding(bottom = BubuSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScanBeacon(
                mood = copy.mood,
                isSearching = state is CardScanState.Waiting || state is CardScanState.Reading
            )

            Spacer(Modifier.height(BubuSpacing.md))

            Text(
                text = copy.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(BubuSpacing.xs))

            Text(
                text = copy.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // TalkBack announces each transition without the user having to hunt for what
                // changed - which matters most here, where the feedback is otherwise a change of
                // colour and an animation.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

            if (copy.showsChipLimitation) {
                Spacer(Modifier.height(BubuSpacing.sm))
                Text(
                    text = "Cards never share their CVV or PIN over NFC - you will still type " +
                        "those two in yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(BubuSpacing.lg))

            copy.primaryAction?.let { action ->
                BubuButton(
                    text = action.label,
                    onClick = {
                        when (action.kind) {
                            ActionKind.RETRY -> onRetry()
                            ActionKind.OPEN_SETTINGS -> onOpenNfcSettings()
                            ActionKind.MANUAL -> onEnterManually()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(BubuSpacing.xs))
            }

            if (copy.showsManualFallback) {
                BubuOutlinedButton(
                    text = "Type it in instead",
                    onClick = onEnterManually,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Bubu, with rings when a card is expected.
 *
 * The rings animate through `Canvas`, reading the phase inside the draw lambda. A
 * `Modifier.size(animatedDp)` would relayout the sheet on every frame of a loop that runs for as
 * long as the user is fumbling with their wallet.
 */
@Composable
private fun ScanBeacon(
    mood: BubuMood,
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "beacon")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val ringColor = MaterialTheme.bubu.champagne

    Box(modifier.size(BEACON_SIZE), contentAlignment = Alignment.Center) {
        if (isSearching) {
            Canvas(Modifier.fillMaxSize()) {
                val maxRadius = size.minDimension / 2f
                val minRadius = maxRadius * 0.42f
                repeat(RING_COUNT) { index ->
                    val offset = (phase.value + index.toFloat() / RING_COUNT) % 1f
                    drawCircle(
                        color = ringColor.copy(alpha = (1f - offset) * RING_PEAK_ALPHA),
                        radius = lerp(minRadius, maxRadius, offset),
                        style = Stroke(width = RING_STROKE.toPx())
                    )
                }
            }
        }
        BubuMascot(mood = mood, size = MASCOT_SIZE, contentDescription = null)
    }
}

private enum class ActionKind { RETRY, OPEN_SETTINGS, MANUAL }

private class ScanAction(val label: String, val kind: ActionKind)

private class ScanCopy(
    val mood: BubuMood,
    val title: String,
    val body: String,
    val primaryAction: ScanAction? = null,
    val showsManualFallback: Boolean = true,
    /** The CVV note. Shown while a scan is live, suppressed once there is a real problem to read. */
    val showsChipLimitation: Boolean = false
)

/**
 * One place where every outcome gets its sentence.
 *
 * Two calls here are deliberate. A card that answered but withholds its number gets no "Try again" -
 * tapping again cannot change the answer, and offering it would send the user through the same
 * failure until they gave up. And no branch says "error": every one of these is either a normal
 * property of the card, or a card that moved.
 */
private fun copyFor(state: CardScanState): ScanCopy = when (state) {
    CardScanState.Waiting -> ScanCopy(
        mood = BubuMood.GUARDING,
        title = "Hold your card to the phone",
        body = "Against the back, near the top. Keep it there until Bubu says it is done.",
        showsChipLimitation = true
    )

    CardScanState.Reading -> ScanCopy(
        mood = BubuMood.THINKING,
        title = "Reading your card",
        body = "Keep holding it still.",
        showsManualFallback = false,
        showsChipLimitation = true
    )

    is CardScanState.Success -> ScanCopy(
        mood = BubuMood.CELEBRATING,
        title = "Got it",
        body = "${state.card.maskedNumber} is in the form. Add the CVV and PIN yourself.",
        showsManualFallback = false
    )

    CardScanState.Unsupported -> ScanCopy(
        mood = BubuMood.WORRIED,
        title = "This phone has no NFC",
        body = "Nothing to tap with, so the card number will have to be typed.",
        primaryAction = ScanAction("Type it in instead", ActionKind.MANUAL),
        showsManualFallback = false
    )

    CardScanState.Disabled -> ScanCopy(
        mood = BubuMood.HIDING,
        title = "NFC is switched off",
        body = "Turn it on in system settings, then come back and tap your card.",
        primaryAction = ScanAction("Open NFC settings", ActionKind.OPEN_SETTINGS)
    )

    is CardScanState.Failed -> when (state.reason) {
        CardScanFailure.MOVED_TOO_SOON -> ScanCopy(
            mood = BubuMood.WORRIED,
            title = "The card moved away",
            body = "It needs to stay against the phone for a second or two.",
            primaryAction = ScanAction("Try again", ActionKind.RETRY)
        )

        CardScanFailure.NOT_A_PAYMENT_CARD -> ScanCopy(
            mood = BubuMood.WORRIED,
            title = "That is not a payment card",
            body = "Transit passes, door badges and hotel keys answer too, but they carry no card " +
                "number.",
            primaryAction = ScanAction("Try again", ActionKind.RETRY)
        )

        CardScanFailure.NUMBER_WITHHELD -> ScanCopy(
            mood = BubuMood.HIDING,
            title = "This card keeps its number",
            body = "Some banks build cards that never hand out their number over NFC. Tapping " +
                "again will not change that.",
            primaryAction = ScanAction("Type it in instead", ActionKind.MANUAL),
            showsManualFallback = false
        )

        CardScanFailure.UNREADABLE -> ScanCopy(
            mood = BubuMood.WORRIED,
            title = "Bubu could not read that",
            body = "Try again with the card flat against the back of the phone, and the case off " +
                "if it has one.",
            primaryAction = ScanAction("Try again", ActionKind.RETRY)
        )
    }
}

private const val SUCCESS_HOLD_MILLIS = 900L
private const val PULSE_MILLIS = 1_600
private const val RING_COUNT = 3
private const val RING_PEAK_ALPHA = 0.5f
private val RING_STROKE = 2.dp
private val BEACON_SIZE = 168.dp
private val MASCOT_SIZE = 108.dp

@Preview(showBackground = true, name = "Waiting")
@Preview(showBackground = true, name = "Waiting · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Waiting · large text", fontScale = 1.3f)
@Composable
private fun NfcScanWaitingPreview() {
    BubuProtectTheme {
        NfcScanSheetContent(
            state = CardScanState.Waiting,
            onRetry = {},
            onOpenNfcSettings = {},
            onEnterManually = {}
        )
    }
}

@Preview(showBackground = true, name = "Reading")
@Composable
private fun NfcScanReadingPreview() {
    BubuProtectTheme {
        NfcScanSheetContent(
            state = CardScanState.Reading,
            onRetry = {},
            onOpenNfcSettings = {},
            onEnterManually = {}
        )
    }
}

@Preview(showBackground = true, name = "Success")
@Composable
private fun NfcScanSuccessPreview() {
    BubuProtectTheme {
        NfcScanSheetContent(
            state = CardScanState.Success(
                ScannedCard(
                    pan = "4242424242424242".toCharArray(),
                    expiryMonth = 11,
                    expiryYear = 2029,
                    holderName = "B Bear",
                    applicationLabel = "VISA CREDIT"
                )
            ),
            onRetry = {},
            onOpenNfcSettings = {},
            onEnterManually = {}
        )
    }
}

@Preview(showBackground = true, name = "NFC off")
@Composable
private fun NfcScanDisabledPreview() {
    BubuProtectTheme {
        NfcScanSheetContent(
            state = CardScanState.Disabled,
            onRetry = {},
            onOpenNfcSettings = {},
            onEnterManually = {}
        )
    }
}

@Preview(showBackground = true, name = "Number withheld")
@Composable
private fun NfcScanWithheldPreview() {
    BubuProtectTheme {
        NfcScanSheetContent(
            state = CardScanState.Failed(CardScanFailure.NUMBER_WITHHELD),
            onRetry = {},
            onOpenNfcSettings = {},
            onEnterManually = {}
        )
    }
}

@Preview(showBackground = true, name = "Moved too soon")
@Composable
private fun NfcScanMovedPreview() {
    BubuProtectTheme {
        NfcScanSheetContent(
            state = CardScanState.Failed(CardScanFailure.MOVED_TOO_SOON),
            onRetry = {},
            onOpenNfcSettings = {},
            onEnterManually = {}
        )
    }
}
