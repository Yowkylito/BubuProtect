package com.personal.bubuprotect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.session.VaultSession
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.theme.BubuProtectTheme

/**
 * Settings, as a sheet rather than a screen.
 *
 * There are three of them. A whole destination for three controls would need a back stack entry, a
 * transition and a title bar, all to say "fingerprint: on".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettingsSheet(
    biometricUnlockEnabled: Boolean,
    canOfferBiometricUnlock: Boolean,
    entryCount: Int,
    onToggleBiometricUnlock: (Boolean) -> Unit,
    onLock: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Bubu's settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (entryCount == 1) "1 secret in the vault" else "$entryCount secrets in the vault",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_fingerprint),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Unlock with a fingerprint",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            !canOfferBiometricUnlock ->
                                "No usable sensor on this device, so it is passphrase only."
                            // Says the important part out loud: this is a shortcut, not a
                            // replacement, and turning it on does not weaken the passphrase.
                            else -> "A shortcut, not a replacement. Your passphrase always works."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = biometricUnlockEnabled,
                    onCheckedChange = onToggleBiometricUnlock,
                    enabled = canOfferBiometricUnlock,
                    // The Row's text already names the control; a switch announcing itself again
                    // would make TalkBack read the whole block twice.
                    modifier = Modifier.clearAndSetSemantics { }
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            SettingsNote(
                title = "Locks itself",
                body = "After ${VaultSession.BACKGROUND_GRACE_MILLIS / 1000}s in the background, " +
                    "or the moment the screen goes off. Copied secrets clear after " +
                    "${SecureClipboard.CLEAR_AFTER_SECONDS}s."
            )
            Spacer(Modifier.height(14.dp))
            SettingsNote(
                title = "Never leaves this phone",
                body = "The app has no internet permission at all, so nothing in here can be sent " +
                    "anywhere - not by Bubu, and not by anything Bubu is built from."
            )

            Spacer(Modifier.height(24.dp))

            BubuOutlinedButton(
                text = "Lock the vault now",
                onClick = onLock,
                leadingIcon = Icons.Filled.Lock,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SettingsNote(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsNotePreview() {
    BubuProtectTheme {
        Column(Modifier.padding(20.dp)) {
            SettingsNote(
                title = "Locks itself",
                body = "After 60s in the background, or the moment the screen goes off."
            )
        }
    }
}
