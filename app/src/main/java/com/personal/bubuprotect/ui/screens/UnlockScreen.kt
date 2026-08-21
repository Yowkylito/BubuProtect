package com.personal.bubuprotect.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.crypto.PassphraseKdf
import com.personal.bubuprotect.data.local.UserPreferences
import com.personal.bubuprotect.core.security.IntegrityChecker
import com.personal.bubuprotect.ui.components.BackupPassphraseDialog
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOrDivider
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.SecretStrengthMeter
import com.personal.bubuprotect.ui.components.SecretTextField
import com.personal.bubuprotect.ui.components.SecurityWarningBanner
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.wobble
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.UnlockStage
import com.personal.bubuprotect.ui.vm.UnlockUiState
import com.personal.bubuprotect.ui.vm.UnlockViewModel
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

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
    viewModel: UnlockViewModel = koinViewModel(),
    userPreferences: UserPreferences = koinInject()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberBiometricGate()

    // This ViewModel is activity-scoped, so it outlives an unlock/auto-lock cycle. Without this the
    // stage computed at process start would still read SETUP after the user had created the vault
    // and been auto-locked back out to this screen.
    LaunchedEffect(Unit) { viewModel.refresh() }

    /*
     * Onboarding is gated on enrollment, not on a preference.
     *
     * [UnlockStage.SETUP] means `VaultKeyStore.isEnrolled` is false - there is no passphrase yet.
     * That is the only signal in this app that means "freshly installed", it is persisted in
     * keystore metadata rather than written at the end of a screen, and it stops being true the
     * instant the vault is created. So the guide can appear at most once per install no matter what
     * happens to the process in between.
     *
     * The preference is a second, softer gate on top: it stops the guide reappearing if the user
     * skips it and then backgrounds the app while still on setup. If it were the *only* gate - as it
     * was when this lived in the unlocked shell - then any session that ended before the guide was
     * dismissed (screen off locks immediately, and a 60s background does too) would leave it unset
     * and show the guide again on the next unlock.
     */
    var guideDismissed by rememberSaveable { mutableStateOf(userPreferences.hasSeenSecurityGuide) }
    val showGuide = state.stage == UnlockStage.SETUP && !guideDismissed

    /*
     * Restore, in two steps: pick the file, then ask for its passphrase.
     *
     * `OpenDocument` with a wildcard filter rather than a specific MIME type - a backup written to
     * Drive or copied between phones frequently comes back typed as `application/octet-stream`,
     * `text/plain`, or nothing at all, and a strict filter would grey out the user's own file in the
     * picker. The magic bytes are the real check, and they run before the passphrase is used.
     */
    /*
     * The recovery flow replaces this screen rather than stacking on it.
     *
     * Not a dialog and not a navigation destination. A dialog would put a passphrase field on top of
     * a passphrase field; a destination would need a locked graph this app deliberately does not have
     * - see [com.personal.bubuprotect.ui.Routes] on why locked and unlocked are not two places in one
     * back stack.
     *
     * Whether it is showing is read from the ViewModel rather than kept locally, because getting here
     * now requires passing a biometric check - see [UnlockViewModel.requestRecoveryAccess]. A local
     * flag would be a second source of truth for an authorisation, and the kind that survives into a
     * saved instance state it has no business being in.
     */
    var restoreSource by remember { mutableStateOf<Uri?>(null) }
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> restoreSource = uri }

    // The vault opening is what dismisses this dialog - VaultSession.isUnlocked flips and the whole
    // screen is composed away - so there is no success path to handle here. A failure leaves the
    // dialog up with the message rendered by UnlockScreen behind it.
    LaunchedEffect(state.message, state.failureToken) {
        if (state.message != null && state.isMessageAnError) restoreSource = null
    }

    if (state.recoveryAccessGranted) {
        RecoveryUnlockRoute(onCancel = viewModel::clearRecoveryAccess, modifier = modifier)
        return
    }

    // Null hides the link entirely - see the parameter doc on UnlockScreen.
    val useRecoveryCode: (() -> Unit)? = if (state.hasRecoveryKit) {
        { viewModel.requestRecoveryAccess(gate) }
    } else {
        null
    }

    AnimatedContent(
        targetState = showGuide,
        // Forward, not a cross-fade: "Build my vault" moves the user onward through a setup flow
        // rather than swapping one view of the same thing for another.
        transitionSpec = { BubuMotion.forwardEnter() togetherWith BubuMotion.forwardExit() },
        label = "setupGuide",
        modifier = modifier
    ) { guide ->
        if (guide) {
            SecurityGuideScreen(
                onDone = {
                    userPreferences.markSecurityGuideSeen()
                    guideDismissed = true
                },
                showSkip = true,
                doneLabel = "Build my vault"
            )
        } else {
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
                onRestoreBackup = { restorePicker.launch(arrayOf("*/*")) },
                onUseRecoveryCode = useRecoveryCode
            )
        }
    }

    restoreSource?.let { source ->
        BackupPassphraseDialog(
            title = "Restore this backup",
            body = "Enter the master passphrase that was protecting this file when you saved it. " +
                "Bubu will rebuild the vault from it.",
            confirmLabel = "Restore",
            mood = BubuMood.THINKING,
            isBusy = state.isBusy,
            footnote = "That passphrase becomes this vault's passphrase. You can change it later.",
            onConfirm = { passphrase -> viewModel.restoreFromBackup(source, passphrase, gate) },
            onDismiss = { restoreSource = null }
        )
    }
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
    modifier: Modifier = Modifier,
    /** Null in previews and wherever restore is not offered. */
    onRestoreBackup: (() -> Unit)? = null,
    /**
     * Null when no recovery kit exists for this vault.
     *
     * Hidden rather than shown-and-disabled. A "Forgot your passphrase?" link that leads nowhere is
     * worse than no link at all: someone who has genuinely forgotten would tap it, discover there is
     * no way back, and have learned that at the one moment it is too late to act on.
     */
    onUseRecoveryCode: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val bubu = MaterialTheme.bubu
    var passphraseVisible by rememberSaveable { mutableStateOf(false) }
    var didAutoPromptBiometric by rememberSaveable { mutableStateOf(false) }
    var focusedField by rememberSaveable { mutableStateOf<String?>(null) }

    // Returning users with fingerprint unlock get the system prompt as soon as the lock screen
    // paints - the same path Apple Passwords and 1Password take. Cancelled once, it stays cancelled
    // for this composition so a declined prompt does not loop. Locking the vault recomposes this
    // screen and the prompt is offered again, which is what "I locked it, I want back in" means.
    LaunchedEffect(
        state.stage,
        state.biometricUnlockOffered,
        state.isLockedOut,
        state.isBusy
    ) {
        if (
            !didAutoPromptBiometric &&
            state.stage == UnlockStage.LOCKED &&
            state.biometricUnlockOffered &&
            !state.isLockedOut &&
            !state.isBusy
        ) {
            didAutoPromptBiometric = true
            delay(380)
            onBiometricUnlock()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        bubu.champagneContainer.copy(alpha = 0.62f),
                        scheme.primaryContainer.copy(alpha = 0.24f),
                        scheme.background,
                        scheme.background
                    )
                )
            )
    ) {
        if (state.stage == UnlockStage.CHECKING) {
            LoadingPane(label = "Looking for your vault")
            return@Box
        }

        val biometricPrimary = state.stage == UnlockStage.LOCKED &&
            state.biometricUnlockOffered &&
            !state.isLockedOut
        val typingPassphrase = state.passphrase.isNotEmpty()

        ResponsiveContainer(
            modifier = Modifier
                .fillMaxSize()
                // System bars constrain the stable viewport. IME padding is deliberately owned by
                // the form Surface below, so keyboard animation does not remeasure the mascot and
                // heading on every frame.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    )
                )
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BubuSpacing.lg),
            maxContentWidth = 480.dp,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(BubuSpacing.lg))

            BubuMascot(
                mood = state.mood(),
                size = 168.dp,
                // Pause the extra Compose breathing motion while typing or doing key derivation.
                // The GIF itself still goes through Coil; painterResource cannot decode GIF files.
                breathing = !state.isBusy && focusedField == null,
                contentDescription = null
            )

            Spacer(Modifier.height(BubuSpacing.md))

            Text(
                text = when (state.stage) {
                    UnlockStage.SETUP -> "Hello, you two"
                    else -> "Welcome back"
                },
                style = MaterialTheme.typography.displaySmall,
                color = scheme.onBackground
            )
            Spacer(Modifier.height(BubuSpacing.xxs))
            Text(
                text = when (state.stage) {
                    UnlockStage.SETUP -> "Pick a master passphrase. It is the only thing that can " +
                        "open this vault, and Bubu and Dudu cannot reset it for you."
                    else -> if (biometricPrimary) {
                        "Unlock with your fingerprint, or type your passphrase."
                    } else {
                        "Prove it to Bubu and Dudu"
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp)
            )

            Spacer(Modifier.height(BubuSpacing.screen))

            SecurityWarningBanner(
                findings = state.warnings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = BubuSpacing.md)
            )

            // The whole form shakes on a rejection, not just the field - the message sits under the
            // fields, and moving them together keeps the two visually attached.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .wobble(trigger = state.failureToken.takeIf { it > 0 }),
                shape = MaterialTheme.shapes.extraLarge,
                color = scheme.surfaceContainerLowest.copy(alpha = 0.94f),
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, bubu.cardBorder.copy(alpha = 0.76f))
            ) {
                Column(Modifier.padding(BubuSpacing.md)) formColumn@{
                AnimatedVisibility(
                    visible = biometricPrimary,
                    enter = fadeIn(tween(BubuMotion.MEDIUM)) + expandVertically(tween(BubuMotion.MEDIUM)),
                    exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
                ) {
                    Column {
                        if (typingPassphrase) {
                            BubuOutlinedButton(
                                text = "Use my fingerprint",
                                onClick = onBiometricUnlock,
                                enabled = !state.isBusy,
                                leadingIcon = ImageVector.vectorResource(R.drawable.ic_fingerprint),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            BubuButton(
                                text = "Unlock with fingerprint",
                                onClick = onBiometricUnlock,
                                enabled = !state.isBusy,
                                isBusy = state.isBusy,
                                busyText = "Unlocking",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(BubuSpacing.md))
                        BubuOrDivider()
                        Spacer(Modifier.height(BubuSpacing.md))
                    }
                }

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
                            isVisible = passphraseVisible,
                            onVisibilityToggle = { passphraseVisible = !passphraseVisible },
                            onFocusStateChange = { focused ->
                                focusedField = when {
                                    focused -> "passphrase"
                                    focusedField == "passphrase" -> null
                                    else -> focusedField
                                }
                            },
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
                                modifier = Modifier.padding(
                                    horizontal = BubuSpacing.xxs,
                                    vertical = BubuSpacing.xs
                                )
                            )
                            Spacer(Modifier.height(BubuSpacing.xxs))
                            SecretTextField(
                                value = state.confirmation,
                                onValueChange = onConfirmationChange,
                                label = "Type it once more",
                                isVisible = passphraseVisible,
                                onVisibilityToggle = { passphraseVisible = !passphraseVisible },
                                onFocusStateChange = { focused ->
                                    focusedField = when {
                                        focused -> "confirmation"
                                        focusedField == "confirmation" -> null
                                        else -> focusedField
                                    }
                                },
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
                            scheme.error
                        } else {
                            scheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = BubuSpacing.sm)
                            // Assertive: a failed unlock is exactly the kind of thing a screen
                            // reader user must hear without hunting for it.
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }

                val showPassphraseSubmit = !biometricPrimary || typingPassphrase ||
                    state.stage == UnlockStage.SETUP ||
                    state.isLockedOut

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    this@formColumn.AnimatedVisibility(
                        visible = showPassphraseSubmit,
                        enter = fadeIn(tween(BubuMotion.FAST)),
                        exit = fadeOut(tween(BubuMotion.FAST))
                    ) {
                        Column {
                            Spacer(Modifier.height(BubuSpacing.screen))
                            BubuButton(
                                text = when {
                                    state.isLockedOut -> "Wait ${state.lockoutSecondsRemaining}s"
                                    state.stage == UnlockStage.SETUP -> "Create my vault"
                                    else -> "Open with passphrase"
                                },
                                onClick = onSubmit,
                                enabled = state.canSubmit,
                                isBusy = state.isBusy &&
                                    (state.stage == UnlockStage.SETUP ||
                                        typingPassphrase ||
                                        !biometricPrimary),
                                busyText = if (state.stage == UnlockStage.SETUP) {
                                    "Building it"
                                } else {
                                    "Unlocking"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Locked only, and only when a kit exists. During setup there is no
                            // passphrase to have forgotten yet.
                            if (state.stage == UnlockStage.LOCKED && onUseRecoveryCode != null) {
                                Spacer(Modifier.height(BubuSpacing.sm))
                                BubuOutlinedButton(
                                    text = "Forgot your passphrase?",
                                    onClick = onUseRecoveryCode,
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Setup only. Restoring into a vault that already exists would need
                            // merge semantics - which entry wins, what happens to one that exists
                            // in both - and inventing an answer to that silently is how people
                            // lose passwords. Reinstalling is the case this feature is for.
                            if (state.stage == UnlockStage.SETUP && onRestoreBackup != null) {
                                Spacer(Modifier.height(BubuSpacing.sm))
                                BubuOutlinedButton(
                                    text = "Restore from a backup",
                                    onClick = onRestoreBackup,
                                    enabled = !state.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(BubuSpacing.xs))
                                Text(
                                    text = "Reinstalled, or moving to a new phone? Pick the backup " +
                                        "file you saved and Bubu will rebuild the vault from it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            }

            Spacer(Modifier.height(BubuSpacing.xl))
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
@Preview(name = "Locked · dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Locked · large text", showBackground = true, fontScale = 1.3f)
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
