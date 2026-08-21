package com.personal.bubuprotect.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.RecoveryKitUiState
import com.personal.bubuprotect.ui.vm.RecoveryKitViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun RecoveryKitRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryKitViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let(viewModel::saveKit) }

    /*
     * The on-screen code dies with the screen.
     *
     * The ViewModel is retained across a configuration change, so without this a recovery code would
     * survive in memory after the user navigated away - and it is the single most valuable secret the
     * vault holds. Tied to the composition rather than to a back handler so it also covers the
     * process being backgrounded out from under the screen.
     */
    DisposableEffect(Unit) { onDispose { viewModel.forget() } }

    RecoveryKitScreen(
        state = state,
        onCreate = viewModel::createKit,
        onSaveToFile = { savePicker.launch(viewModel.suggestedFileName()) },
        onAcknowledge = viewModel::acknowledge,
        onDiscard = viewModel::discardKit,
        onDone = onDone,
        modifier = modifier
    )
}

/**
 * Create, replace or delete the recovery kit.
 *
 * ### The one screen in the app that shows a secret it will never show again
 *
 * A recovery code exists in the clear exactly once, on this screen, at the moment it is created. The
 * wrapper stored on the device is one-way, so there is no "show it to me again" - which is a property
 * worth having and a UX hazard to be handled carefully. Hence: the code is large and unmissable, the
 * two ways to keep it sit directly under it, and leaving is a deliberate act with a confirmation
 * behind it rather than a back swipe.
 */
@Composable
fun RecoveryKitScreen(
    state: RecoveryKitUiState,
    onCreate: () -> Unit,
    onSaveToFile: () -> Unit,
    onAcknowledge: () -> Unit,
    onDiscard: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var confirmingSave by remember { mutableStateOf(false) }

    /*
     * A back gesture while an unsaved code is on screen has to be caught.
     *
     * This is the only screen in the app where leaving destroys something unrecoverable: the code
     * exists in the clear here and nowhere else, and the stored wrapper cannot reproduce it. A
     * reflexive back swipe would silently cost the user their kit, and they would not find out until
     * the day they needed it. Enabled only while there is something to lose, so back behaves normally
     * everywhere else on this screen.
     */
    BackHandler(enabled = state.revealedCode != null && !state.isAcknowledged) {
        confirmingLeave = true
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ResponsiveContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = BubuSpacing.lg),
            maxContentWidth = 520.dp,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(BubuSpacing.md))

            BubuMascot(
                mood = if (state.revealedCode != null) BubuMood.GUARDING else BubuMood.GREETING,
                size = 132.dp,
                breathing = !state.isBusy
            )

            Spacer(Modifier.height(BubuSpacing.md))

            Text(
                text = "Recovery kit",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(BubuSpacing.xs))

            Text(
                // The honest version. A vault with no way back is a feature until the day it is a
                // disaster, and the user deserves to know which one they are currently living in.
                text = if (state.revealedCode != null) {
                    "Write this down before you leave this screen. Bubu cannot show it again."
                } else {
                    "Your master passphrase is the only way into this vault, and nobody can " +
                        "reset it. A recovery kit is a second key, kept on paper - and locked to " +
                        "this phone's fingerprint so finding it is not enough."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(BubuSpacing.lg))

            when {
                state.revealedCode != null -> RevealedCodeBlock(code = state.revealedCode)

                /*
                 * Checked *before* the "a kit exists" branch, and the order is the whole point.
                 *
                 * A kit is sealed to this phone's biometric enrollment. Remove the last fingerprint
                 * and the guard cannot be re-armed, so the kit stops working while still existing.
                 * Reporting "created three months ago" over that state would be precisely the false
                 * confidence this feature exists to prevent - the user would find out on the one day
                 * it mattered.
                 */
                !state.canGuardKit -> Text(
                    text = if (state.hasKit) {
                        "Your kit is sealed. Bubu locks it to this phone's fingerprint, and there " +
                            "is none set up right now - add one and the kit works again."
                    } else {
                        "Set up a fingerprint or face unlock on this phone first. Bubu locks the " +
                            "recovery kit to it, so a stolen phone cannot be opened with the code " +
                            "alone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )

                state.hasKit -> KitStatusBlock(createdAt = state.createdAt)

                else -> Text(
                    text = "Without one, forgetting your passphrase means losing every secret in " +
                        "the vault - including your backups, because those are sealed with the " +
                        "same passphrase.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(BubuSpacing.lg))

            state.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isNoticeAnError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(BubuSpacing.sm))
            }

            if (state.revealedCode != null) {
                /*
                 * Writing it down is the primary button, and saving a file is the quieter one.
                 *
                 * The emphasis is the advice. Paper cannot be read by an app, cannot sync to a cloud
                 * drive, and cannot be found by someone holding the unlocked phone - which is the one
                 * threat a file on *this* device creates and no other copy does.
                 */
                BubuButton(
                    text = if (state.isAcknowledged) "Done" else "I have written it down",
                    onClick = {
                        if (state.isAcknowledged) {
                            onDone()
                        } else {
                            onAcknowledge()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(BubuSpacing.xs))
                BubuOutlinedButton(
                    text = "Save as a file instead",
                    onClick = { confirmingSave = true },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.isAcknowledged) {
                    Spacer(Modifier.height(BubuSpacing.xs))
                    Text(
                        text = "Keep it somewhere private that you will still find in five years.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                BubuButton(
                    text = if (state.hasKit) "Create a new kit" else "Create my recovery kit",
                    onClick = onCreate,
                    // Disabled rather than hidden: the user should see that the feature exists and
                    // what unlocks it, not wonder where it went.
                    enabled = state.canGuardKit,
                    isBusy = state.isBusy,
                    busyText = "Creating",
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.hasKit) {
                    Spacer(Modifier.height(BubuSpacing.xs))
                    BubuOutlinedButton(
                        text = "Delete this kit",
                        onClick = { confirmingDiscard = true },
                        contentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(BubuSpacing.xs))
                BubuOutlinedButton(
                    text = "Not now",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(BubuSpacing.xl))
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Delete this recovery kit?") },
            text = {
                Text(
                    "Any printed copy stops working straight away. If you forget your " +
                        "passphrase after this, there is no way back into the vault."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    onDiscard()
                }) { Text("Delete it") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text("Keep it") }
            }
        )
    }

    /*
     * Saving the kit is confirmed, because where it lands decides whether it is a safeguard or a hole.
     *
     * The file this writes opens the vault on its own. On any other device or on paper that is the
     * point of it; on *this* phone it means an unlocked handset is enough for a thief to read the code
     * and take the vault - and the file itself says so, which would make an unguarded button a
     * contradiction. The recovery screen is also gated by a fingerprint now, but that is
     * defence-in-depth for a mistake, not a licence to invite one.
     */
    if (confirmingSave) {
        AlertDialog(
            onDismissRequest = { confirmingSave = false },
            title = { Text("Where will this file go?") },
            text = {
                Text(
                    "This file opens your vault on its own, with no passphrase. Save it somewhere " +
                        "off this phone - a printer, a USB drive, a computer you trust. Leaving it " +
                        "in Downloads means anyone holding your unlocked phone can read it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSave = false
                    onSaveToFile()
                }) { Text("Choose where") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSave = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Leave without saving the code?") },
            text = { Text("Bubu cannot show this code again. You would need to create a new kit.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    onDone()
                }) { Text("Leave anyway") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("Stay") }
            }
        )
    }
}

/**
 * The code itself.
 *
 * Monospace and widely tracked, in groups of four. Someone is going to copy this onto paper character
 * by character, so ambiguity in the *typeface* would undo the work the alphabet does - the whole
 * reason [com.personal.bubuprotect.core.crypto.RecoveryCode] drops `I`, `L`, `O` and `U` is to make
 * this moment survivable.
 */
@Composable
private fun RevealedCodeBlock(code: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.bubu.champagneContainer.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.bubu.champagne.copy(alpha = 0.45f))
    ) {
        Text(
            text = code,
            modifier = Modifier
                .fillMaxWidth()
                .padding(BubuSpacing.md),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                letterSpacing = 1.5.sp,
                lineHeight = 32.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun KitStatusBlock(createdAt: Long, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "A kit exists",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(BubuSpacing.xxs))
        Text(
            text = if (createdAt > 0) "Created ${relativeAge(createdAt)}" else "Created earlier",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(BubuSpacing.sm))
        Text(
            // Says the thing people get wrong: creating a new kit is how you revoke an old one.
            text = "Creating a new kit immediately stops the old code from working. Do that if " +
                "you cannot find your page, or think someone else has seen it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// --- Previews ----------------------------------------------------------------------------------

@Preview(name = "No kit yet", showBackground = true)
@Composable
private fun RecoveryKitNonePreview() {
    BubuProtectTheme {
        RecoveryKitScreen(
            state = RecoveryKitUiState(hasKit = false),
            onCreate = {}, onSaveToFile = {}, onAcknowledge = {}, onDiscard = {}, onDone = {}
        )
    }
}

@Preview(name = "Code revealed", showBackground = true)
@Composable
private fun RecoveryKitRevealedPreview() {
    BubuProtectTheme {
        RecoveryKitScreen(
            state = RecoveryKitUiState(
                hasKit = true,
                createdAt = System.currentTimeMillis(),
                revealedCode = "BP1-4K7M-9QRT-2WXZ-5H3N-8PVD-6JYF"
            ),
            onCreate = {}, onSaveToFile = {}, onAcknowledge = {}, onDiscard = {}, onDone = {}
        )
    }
}

@Preview(name = "No biometric on this phone", showBackground = true)
@Composable
private fun RecoveryKitNoBiometricPreview() {
    BubuProtectTheme {
        RecoveryKitScreen(
            state = RecoveryKitUiState(hasKit = false, canGuardKit = false),
            onCreate = {}, onSaveToFile = {}, onAcknowledge = {}, onDiscard = {}, onDone = {}
        )
    }
}

@Preview(name = "Kit already exists", showBackground = true)
@Composable
private fun RecoveryKitExistsPreview() {
    BubuProtectTheme {
        RecoveryKitScreen(
            state = RecoveryKitUiState(
                hasKit = true,
                createdAt = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            ),
            onCreate = {}, onSaveToFile = {}, onAcknowledge = {}, onDiscard = {}, onDone = {}
        )
    }
}
