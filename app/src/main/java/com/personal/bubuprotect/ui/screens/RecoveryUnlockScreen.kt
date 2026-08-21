package com.personal.bubuprotect.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.core.crypto.PassphraseKdf
import com.personal.bubuprotect.core.crypto.RecoveryCode
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.SecretStrengthMeter
import com.personal.bubuprotect.ui.components.SecretTextField
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.vm.RecoveryStage
import com.personal.bubuprotect.ui.vm.RecoveryUnlockUiState
import com.personal.bubuprotect.ui.vm.RecoveryUnlockViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun RecoveryUnlockRoute(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryUnlockViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val dismiss = {
        viewModel.cancel()
        onCancel()
    }

    // Leaving mid-recovery has to run through the ViewModel, which wipes the recovered root key.
    // Navigating straight out would leave it live in a retained ViewModel behind a lock screen.
    BackHandler(enabled = !state.isBusy) { dismiss() }

    RecoveryUnlockScreen(
        state = state,
        onCodeChange = viewModel::onCodeChange,
        onSubmitCode = viewModel::submitCode,
        onPassphraseChange = viewModel::onPassphraseChange,
        onConfirmationChange = viewModel::onConfirmationChange,
        onComplete = viewModel::completeRecovery,
        onCancel = dismiss,
        modifier = modifier
    )
}

/**
 * Getting back in with a printed kit, in two steps that cannot be separated.
 *
 * Step two is not a courtesy. Someone here has forgotten their passphrase, so finishing at step one
 * would leave them with a vault whose only remaining key is a piece of paper - and a paper credential
 * used routinely is one that gets photographed and left on a desk. See
 * [RecoveryUnlockViewModel] for why the session is opened only at the end.
 */
@Composable
fun RecoveryUnlockScreen(
    state: RecoveryUnlockUiState,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onPassphraseChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var passphraseVisible by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ResponsiveContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = BubuSpacing.lg),
            maxContentWidth = 480.dp,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(BubuSpacing.lg))

            BubuMascot(
                mood = when {
                    state.isBusy -> BubuMood.THINKING
                    state.message != null && state.isMessageAnError -> BubuMood.WORRIED
                    state.stage == RecoveryStage.NEW_PASSPHRASE -> BubuMood.CELEBRATING
                    else -> BubuMood.GUARDING
                },
                size = 148.dp,
                breathing = !state.isBusy
            )

            Spacer(Modifier.height(BubuSpacing.md))

            Text(
                text = when (state.stage) {
                    RecoveryStage.CODE -> "Use your recovery kit"
                    RecoveryStage.NEW_PASSPHRASE -> "Choose a new passphrase"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(BubuSpacing.xs))

            Text(
                text = when (state.stage) {
                    RecoveryStage.CODE ->
                        "Type the code from your recovery kit. Upper or lower case, dashes or " +
                            "not - Bubu will work it out."
                    RecoveryStage.NEW_PASSPHRASE ->
                        "Your vault is open. Set the passphrase you will use from now on - this " +
                            "is the last step."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(BubuSpacing.lg))

            when (state.stage) {
                RecoveryStage.CODE -> {
                    /*
                     * A secret field that is never masked.
                     *
                     * `isSecret` is doing three jobs here, none of them about hiding the value. It
                     * selects the monospace, letter-spaced style - this is transcribed character by
                     * character off paper, and proportional digits make that needlessly hard. It
                     * turns off autocorrect, which would otherwise happily "fix" a group of four
                     * random characters. And it maps the field to `KeyboardType.Password`, which
                     * carries `IME_FLAG_NO_PERSONALIZED_LEARNING` - so a recovery code cannot end up
                     * as an autocomplete suggestion in some other app.
                     *
                     * `isVisible` stays true with no toggle offered: masking a code the user is
                     * copying off a page would make it impossible to check, and there is nothing to
                     * hide from someone who is holding the page.
                     */
                    SecretTextField(
                        value = state.code,
                        onValueChange = onCodeChange,
                        label = "Recovery code",
                        isVisible = true,
                        supportingText = "${RecoveryCode.PREFIX}-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX",
                        errorText = state.message?.takeIf { state.isMessageAnError },
                        imeAction = ImeAction.Done,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(BubuSpacing.md))

                    BubuButton(
                        text = "Open my vault",
                        onClick = onSubmitCode,
                        enabled = state.canSubmitCode,
                        isBusy = state.isBusy,
                        busyText = "Checking",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                RecoveryStage.NEW_PASSPHRASE -> {
                    SecretTextField(
                        value = state.passphrase,
                        onValueChange = onPassphraseChange,
                        label = "New master passphrase",
                        isVisible = passphraseVisible,
                        onVisibilityToggle = { passphraseVisible = !passphraseVisible },
                        supportingText = "At least ${PassphraseKdf.MIN_PASSPHRASE_LENGTH} characters.",
                        imeAction = ImeAction.Next,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(BubuSpacing.xs))
                    SecretStrengthMeter(
                        secret = state.passphrase,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(BubuSpacing.sm))

                    SecretTextField(
                        value = state.confirmation,
                        onValueChange = onConfirmationChange,
                        label = "Type it again",
                        isVisible = passphraseVisible,
                        errorText = state.message?.takeIf { state.isMessageAnError },
                        imeAction = ImeAction.Done,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(BubuSpacing.md))

                    BubuButton(
                        text = "Save my new passphrase",
                        onClick = onComplete,
                        enabled = state.canSubmitPassphrase,
                        isBusy = state.isBusy,
                        busyText = "Saving",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.xs))

            // Offered on step one only. There is no going back from step two: the vault is open and
            // the old passphrase is still forgotten, so leaving would strand the user exactly where
            // they started.
            if (state.stage == RecoveryStage.CODE) {
                BubuOutlinedButton(
                    text = "Back to the lock screen",
                    onClick = onCancel,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(BubuSpacing.xl))
        }
    }
}

// --- Previews ----------------------------------------------------------------------------------

@Preview(name = "Enter code", showBackground = true)
@Composable
private fun RecoveryUnlockCodePreview() {
    BubuProtectTheme {
        RecoveryUnlockScreen(
            state = RecoveryUnlockUiState(code = "BP1-4K7M-9QRT"),
            onCodeChange = {}, onSubmitCode = {}, onPassphraseChange = {},
            onConfirmationChange = {}, onComplete = {}, onCancel = {}
        )
    }
}

@Preview(name = "Wrong code", showBackground = true)
@Composable
private fun RecoveryUnlockErrorPreview() {
    BubuProtectTheme {
        RecoveryUnlockScreen(
            state = RecoveryUnlockUiState(
                code = "BP1-0000-0000-0000-0000-0000-0000",
                message = "That code does not open this vault. Check it is the kit for this phone.",
                failureToken = 1
            ),
            onCodeChange = {}, onSubmitCode = {}, onPassphraseChange = {},
            onConfirmationChange = {}, onComplete = {}, onCancel = {}
        )
    }
}

@Preview(name = "New passphrase", showBackground = true)
@Composable
private fun RecoveryUnlockPassphrasePreview() {
    BubuProtectTheme {
        RecoveryUnlockScreen(
            state = RecoveryUnlockUiState(
                stage = RecoveryStage.NEW_PASSPHRASE,
                passphrase = "correct horse battery",
                confirmation = "correct horse battery"
            ),
            onCodeChange = {}, onSubmitCode = {}, onPassphraseChange = {},
            onConfirmationChange = {}, onComplete = {}, onCancel = {}
        )
    }
}
