package com.personal.bubuprotect.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu

/**
 * Asks for the master passphrase before a backup is written or read.
 *
 * ### Why export asks at all
 *
 * The session does not keep the passphrase - it is wiped as soon as the KDF has run - so it has to
 * be typed again. That is a feature rather than a tax: someone who unlocks with a fingerprint every
 * day may quietly have forgotten it, and the moment to discover that is while they still have a
 * working vault, not months later in front of a backup file they cannot open.
 *
 * ### The passphrase never leaves this dialog as anything but a `String`
 *
 * Compose text fields produce immutable `String`s, which cannot be wiped. The value is handed
 * straight to the ViewModel, converted to a `CharArray` and zeroed at the KDF boundary, and this
 * composable leaves composition immediately afterwards. Same accepted limit as the unlock screen,
 * and the same mitigation - keep the window short.
 */
@Composable
fun BackupPassphraseDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    mood: BubuMood = BubuMood.HIDING,
    footnote: String? = null
) {
    var passphrase by remember { mutableStateOf("") }
    // A reveal toggle matters more here than on the unlock screen. A typo there costs one retry; a
    // typo sealed into a backup file is only discovered when the user needs the file, by which point
    // the vault it came from may be gone.
    var isVisible by remember { mutableStateOf(false) }

    AlertDialog(
        // Not dismissible mid-write: cancelling a running export would leave a half-written document
        // that still passes the magic check and then fails its tag, which reads as a corrupt backup
        // rather than an abandoned one.
        onDismissRequest = { if (!isBusy) onDismiss() },
        modifier = modifier
            .shadow(BubuElevation.hero, MaterialTheme.shapes.extraLarge)
            .border(
                1.dp,
                MaterialTheme.bubu.champagne.copy(alpha = 0.42f),
                MaterialTheme.shapes.extraLarge
            ),
        icon = {
            BubuMascot(
                mood = mood,
                size = 96.dp,
                breathing = false,
                showBackdrop = false,
                contentDescription = null
            )
        },
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SecretTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = "Master passphrase",
                    isVisible = isVisible,
                    onVisibilityToggle = { isVisible = !isVisible },
                    enabled = !isBusy,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                if (footnote != null) {
                    Text(
                        text = footnote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            BubuButton(
                text = confirmLabel,
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty(),
                isBusy = isBusy,
                busyText = "Working"
            )
        },
        dismissButton = {
            BubuOutlinedButton(text = "Cancel", onClick = onDismiss, enabled = !isBusy)
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    )
}

@Preview(showBackground = true, name = "Export backup")
@Preview(
    showBackground = true,
    name = "Export backup · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ExportBackupDialogPreview() {
    BubuProtectTheme {
        BackupPassphraseDialog(
            title = "Save a backup",
            body = "Bubu will seal every secret into one file, locked with your master passphrase. " +
                "Put it somewhere you trust.",
            confirmLabel = "Save it",
            footnote = "If you forget this passphrase, nobody can open the file. Not even Bubu.",
            onConfirm = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true, name = "Restore backup")
@Composable
private fun RestoreBackupDialogPreview() {
    BubuProtectTheme {
        BackupPassphraseDialog(
            title = "Restore this backup",
            body = "Enter the master passphrase that was protecting this file when it was saved.",
            confirmLabel = "Restore",
            mood = BubuMood.THINKING,
            onConfirm = {},
            onDismiss = {}
        )
    }
}
