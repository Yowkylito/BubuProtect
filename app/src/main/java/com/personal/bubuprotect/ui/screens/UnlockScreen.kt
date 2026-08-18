package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.crypto.PassphraseKdf
import com.personal.bubuprotect.core.security.IntegrityChecker
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.SecretStrengthMeter
import com.personal.bubuprotect.ui.components.SecretTextField
import com.personal.bubuprotect.ui.components.SecurityWarningBanner
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.wobble
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.vm.UnlockStage
import com.personal.bubuprotect.ui.vm.UnlockUiState
import com.personal.bubuprotect.ui.vm.UnlockViewModel
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
import org.koin.androidx.compose.koinViewModel

/**
 * The stateful wrapper.
 *
 * Nothing here navigates on success. Unlocking flips
 * [com.personal.bubuprotect.session.VaultSession.isUnlocked], and the root composable swaps this
 * screen out - so there is one source of truth for "is the vault open" and no way for the UI to
 * believe it is unlocked while the keys say otherwise.
 */
@Composable
fun UnlockRoute(
    modifier: Modifier = Modifier,
    viewModel: UnlockViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberBiometricGate()

    // This ViewModel is activity-scoped, so it outlives an unlock/auto-lock cycle. Without this the
    // stage computed at process start would still read SETUP after the user had created the vault
    // and been auto-locked back out to this screen.
    LaunchedEffect(Unit) { viewModel.refresh() }

    UnlockScreen(
        state = state,
        onPassphraseChange = viewModel::onPassphraseChange,
        onConfirmationChange = viewModel::onConfirmationChange,
        onSubmit = {
            when (state.stage) {
                UnlockStage.SETUP -> viewModel.completeSetup(gate)
                UnlockStage.LOCKED -> viewModel.unlockWithPassphrase()
                UnlockStage.CHECKING -> Unit
            }
        },
        onBiometricUnlock = { viewModel.unlockWithBiometrics(gate) },
        modifier = modifier
    )
}

/**
 * First run and unlock, in one screen.
 *
 * The two stages share a frame and swap only their middle, so setting up a vault and coming back to
 * it a week later feel like the same place. The mascot carries the state - guarding when locked,
 * thinking during key derivation, worried after a rejection - which is what lets the error text stay
 * one quiet line instead of a red slab.
 */
@Composable
fun UnlockScreen(
    state: UnlockUiState,
    onPassphraseChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBiometricUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (state.stage == UnlockStage.CHECKING) {
            LoadingPane(label = "Looking for your vault")
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Order matters, and getting it wrong here makes the form unreachable.
                //
                // `safeDrawing` already covers the status bar, the navigation bar, the cutout *and*
                // the IME, so adding imePadding()/navigationBarsPadding() on top would subtract the
                // keyboard height twice and push the submit button off the bottom.
                //
                // The inset padding also has to come *before* verticalScroll: applied after, it pads
                // the scrolling content instead of shrinking the viewport, so the content grows by
                // exactly as much as the space it lost and never comes back into reach.
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(24.dp))

            BubuMascot(
                mood = state.mood(),
                size = 180.dp,
                // Only the idle mascot breathes. During key derivation the CPU has better things to
                // do than run a decorative animation, and after a failure a calm bear is the wrong
                // note.
                breathing = !state.isBusy,
                contentDescription = null
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = when (state.stage) {
                    UnlockStage.SETUP -> "Hello, Bubu"
                    else -> "Welcome back"
                },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (state.stage) {
                    UnlockStage.SETUP -> "Pick a master passphrase. It is the only thing that can " +
                        "open this vault, and Bubu cannot reset it for you."
                    else -> "Prove that you are my Bubu"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp)
            )

            Spacer(Modifier.height(20.dp))

            SecurityWarningBanner(
                findings = state.warnings,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .padding(bottom = 16.dp)
            )

            // The whole form shakes on a rejection, not just the field - the message sits under the
            // fields, and moving them together keeps the two visually attached.
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .wobble(trigger = state.failureToken.takeIf { it > 0 })
            ) {
                AnimatedContent(
                    targetState = state.stage,
                    transitionSpec = {
                        (slideInVertically(tween(BubuMotion.MEDIUM)) { it / 6 } +
                            fadeIn(tween(BubuMotion.MEDIUM))) togetherWith
                            (slideOutVertically(tween(BubuMotion.FAST)) { -it / 6 } +
                                fadeOut(tween(BubuMotion.FAST)))
                    },
                    label = "unlockStage"
                ) { stage ->
                    Column {
                        SecretTextField(
                            value = state.passphrase,
                            onValueChange = onPassphraseChange,
                            label = "Master passphrase",
                            enabled = !state.isBusy && !state.isLockedOut,
                            imeAction = if (stage == UnlockStage.SETUP) {
                                ImeAction.Next
                            } else {
                                ImeAction.Done
                            },
                            supportingText = if (stage == UnlockStage.SETUP) {
                                "At least ${PassphraseKdf.MIN_PASSPHRASE_LENGTH} characters"
                            } else {
                                null
                            }
                        )

                        if (stage == UnlockStage.SETUP) {
                            SecretStrengthMeter(
                                secret = state.passphrase,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            SecretTextField(
                                value = state.confirmation,
                                onValueChange = onConfirmationChange,
                                label = "Type it once more",
                                enabled = !state.isBusy,
                                imeAction = ImeAction.Done
                            )
                        }
                    }
                }

                // Errors and notices share one slot so they cannot stack and push the button around.
                AnimatedVisibility(
                    visible = state.message != null,
                    enter = fadeIn(tween(BubuMotion.FAST)) + expandVertically(tween(BubuMotion.FAST)),
                    exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
                ) {
                    Text(
                        text = state.message.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isMessageAnError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            // Assertive: a failed unlock is exactly the kind of thing a screen
                            // reader user must hear without hunting for it.
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }

                Spacer(Modifier.height(20.dp))

                BubuButton(
                    text = when {
                        state.isLockedOut -> "Wait ${state.lockoutSecondsRemaining}s"
                        state.stage == UnlockStage.SETUP -> "Create my vault"
                        else -> "Open the vault"
                    },
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    isBusy = state.isBusy,
                    busyText = if (state.stage == UnlockStage.SETUP) "Building it" else "Unlocking",
                    modifier = Modifier.fillMaxWidth()
                )

                AnimatedVisibility(
                    visible = state.biometricUnlockOffered && !state.isLockedOut,
                    enter = fadeIn(tween(BubuMotion.MEDIUM)) + expandVertically(tween(BubuMotion.MEDIUM)),
                    exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        BubuOutlinedButton(
                            text = "Use my fingerprint",
                            onClick = onBiometricUnlock,
                            enabled = !state.isBusy,
                            leadingIcon = ImageVector.vectorResource(R.drawable.ic_fingerprint),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The mascot is the status indicator; this is the whole mapping. */
private fun UnlockUiState.mood(): BubuMood = when {
    isBusy -> BubuMood.THINKING
    message != null && isMessageAnError -> BubuMood.WORRIED
    stage == UnlockStage.SETUP -> BubuMood.GREETING
    else -> BubuMood.GUARDING
}

@Preview(name = "First run", showBackground = true)
@Composable
private fun UnlockSetupPreview() {
    BubuProtectTheme {
        UnlockScreen(
            state = UnlockUiState(stage = UnlockStage.SETUP, passphrase = "little-bear-2026!"),
            onPassphraseChange = {},
            onConfirmationChange = {},
            onSubmit = {},
            onBiometricUnlock = {}
        )
    }
}

@Preview(name = "Locked", showBackground = true)
@Composable
private fun UnlockLockedPreview() {
    BubuProtectTheme {
        UnlockScreen(
            state = UnlockUiState(stage = UnlockStage.LOCKED, biometricUnlockOffered = true),
            onPassphraseChange = {},
            onConfirmationChange = {},
            onSubmit = {},
            onBiometricUnlock = {}
        )
    }
}

@Preview(name = "Rejected", showBackground = true)
@Composable
private fun UnlockRejectedPreview() {
    BubuProtectTheme {
        UnlockScreen(
            state = UnlockUiState(
                stage = UnlockStage.LOCKED,
                passphrase = "nope",
                message = "That passphrase is not right.",
                failureToken = 1,
                warnings = setOf(IntegrityChecker.Finding.UNTRUSTED_BUILD)
            ),
            onPassphraseChange = {},
            onConfirmationChange = {},
            onSubmit = {},
            onBiometricUnlock = {}
        )
    }
}

@Preview(name = "Busy", showBackground = true)
@Composable
private fun UnlockBusyPreview() {
    BubuProtectTheme {
        UnlockScreen(
            state = UnlockUiState(stage = UnlockStage.LOCKED, passphrase = "x", isBusy = true),
            onPassphraseChange = {},
            onConfirmationChange = {},
            onSubmit = {},
            onBiometricUnlock = {}
        )
    }
}
