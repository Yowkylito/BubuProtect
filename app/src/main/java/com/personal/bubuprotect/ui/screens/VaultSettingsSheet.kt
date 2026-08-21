package com.personal.bubuprotect.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.session.VaultSession
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.theme.bubu

/**
 * Settings, as a sheet rather than a screen.
 *
 * A whole destination for a handful of controls would need a back stack entry, a transition and a
 * title bar, all to say "fingerprint: on".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettingsSheet(
    biometricUnlockEnabled: Boolean,
    canOfferBiometricUnlock: Boolean,
    entryCount: Int,
    onToggleBiometricUnlock: (Boolean) -> Unit,
    strictRevealEnabled: Boolean,
    onToggleStrictReveal: (Boolean) -> Unit,
    hasRecoveryKit: Boolean,
    /**
     * True when a kit exists but cannot currently be used, because this phone's biometric enrollment
     * changed and there is nothing to re-arm the guard with.
     */
    recoveryKitSealed: Boolean,
    recoveryKitCreatedAt: Long,
    onOpenRecoveryKit: () -> Unit,
    onOpenImport: () -> Unit,
    autofillSupported: Boolean,
    autofillEnabled: Boolean,
    onOpenAutofillSettings: () -> Unit,
    onOpenSecurityGuide: () -> Unit,
    breachMonitoringEnabled: Boolean,
    breachedCount: Int,
    onToggleBreachMonitoring: (Boolean) -> Unit,
    onOpenBreachReport: () -> Unit,
    deviceRiskCount: Int,
    hasCriticalDeviceRisk: Boolean,
    onOpenDeviceCheck: () -> Unit,
    adCulpritCount: Int,
    onOpenShield: () -> Unit,
    onExportBackup: () -> Unit,
    onLock: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = BubuSpacing.lg)
                    .padding(bottom = BubuSpacing.lg)
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.bubu.champagneContainer.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, MaterialTheme.bubu.champagne.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(BubuSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BubuMascot(
                        mood = BubuMood.HIDING,
                        size = 96.dp,
                        breathing = false,
                        showBackdrop = false,
                        contentDescription = null
                    )
                    Spacer(Modifier.height(BubuSpacing.xs))
                    Text(
                        text = "Bubu and Dudu's settings",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(BubuSpacing.xxs))
                    Text(
                        text = if (entryCount == 1) {
                            "1 secret in the vault"
                        } else {
                            "$entryCount secrets in the vault"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.screen))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Row(
                    modifier = Modifier
                        .toggleable(
                            value = biometricUnlockEnabled,
                            enabled = canOfferBiometricUnlock,
                            role = Role.Switch,
                            onValueChange = onToggleBiometricUnlock
                        )
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_fingerprint),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(BubuSpacing.md))
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
                    Spacer(Modifier.width(BubuSpacing.sm))
                    Switch(
                        checked = biometricUnlockEnabled,
                        onCheckedChange = null,
                        enabled = canOfferBiometricUnlock,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.bubu.champagne,
                            checkedTrackColor = MaterialTheme.bubu.champagneContainer,
                            checkedBorderColor = MaterialTheme.bubu.champagne,
                            uncheckedBorderColor = MaterialTheme.bubu.cardBorder
                        ),
                        // The Row's text already names the control; a switch announcing itself again
                        // would make TalkBack read the whole block twice.
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * Strict mode sits directly under the fingerprint switch because it is the same subject
             * read the other way round: that one is about getting *in* with less friction, this one
             * is about seeing what is already inside with more.
             *
             * It shares `canOfferBiometricUnlock` as its gate, and that is not a shortcut - both
             * controls need the same thing, a usable strong sensor. Turning this on without one
             * would produce a vault that refuses to show its own contents, so the switch simply
             * cannot be armed until there is something to ask with.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Row(
                    modifier = Modifier
                        .toggleable(
                            value = strictRevealEnabled,
                            enabled = canOfferBiometricUnlock,
                            role = Role.Switch,
                            onValueChange = onToggleStrictReveal
                        )
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(BubuSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Strict mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                !canOfferBiometricUnlock ->
                                    "Needs a fingerprint or face unlock on this device."
                                // Names the cost as plainly as the benefit. Someone who turns this
                                // on and then finds themselves authenticating four times to read one
                                // card should have been told that here, not discovered it there.
                                else -> "Ask for a fingerprint every single time a secret is shown " +
                                    "or copied - never once per entry."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(BubuSpacing.sm))
                    Switch(
                        checked = strictRevealEnabled,
                        onCheckedChange = null,
                        enabled = canOfferBiometricUnlock,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.bubu.champagne,
                            checkedTrackColor = MaterialTheme.bubu.champagneContainer,
                            checkedBorderColor = MaterialTheme.bubu.champagne,
                            uncheckedBorderColor = MaterialTheme.bubu.cardBorder
                        ),
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * The recovery kit sits above everything else here, and the ordering is the message.
             *
             * Every other row on this screen trades convenience against exposure. This one is the
             * only one that decides whether the vault can be recovered at all, and its unset state is
             * the single way a user of this app can lose everything without an attacker being
             * involved - so it is not buried under three switches about fingerprints.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (hasRecoveryKit && !recoveryKitSealed) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    // Tinted, not a banner. It has to read as unfinished at a glance without turning
                    // the settings sheet into an alarm every time it is opened.
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
                },
                shadowElevation = BubuElevation.card,
                border = BorderStroke(
                    1.dp,
                    if (hasRecoveryKit && !recoveryKitSealed) {
                        MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onOpenRecoveryKit)
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = if (hasRecoveryKit && !recoveryKitSealed) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(BubuSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Recovery kit",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                // Stated plainly, because a sealed kit looks exactly like a working
                                // one from the outside and the user has no other way to find out.
                                recoveryKitSealed ->
                                    "Sealed - this phone has no fingerprint set up"

                                hasRecoveryKit && recoveryKitCreatedAt > 0 ->
                                    "Created ${relativeAge(recoveryKitCreatedAt)}"

                                hasRecoveryKit -> "Ready"

                                // The consequence, not the feature. "Create a recovery kit" tells
                                // someone what a button does; this tells them what happens if they
                                // do not press it.
                                else -> "Forget your passphrase and the vault is gone for good"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasRecoveryKit && !recoveryKitSealed) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    Spacer(Modifier.width(BubuSpacing.sm))
                    Text(
                        text = when {
                            recoveryKitSealed -> "Fix"
                            hasRecoveryKit -> "Manage"
                            else -> "Set up"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * Import sits next to export because they are the same subject read in both directions,
             * and because someone arriving from another app will look for it near the backup rows
             * rather than under security.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onOpenImport)
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_kind_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(BubuSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Import passwords",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Bring a CSV in from Chrome, Bitwarden, 1Password and others",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(BubuSpacing.sm))
                    Text(
                        text = "Open",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * Autofill is a row, not a switch, and that is not a styling choice.
             *
             * Only one autofill service can be active on a device, so turning this one on turns
             * whatever the user had before *off*. A switch here would either have to hide that or
             * do it silently, so the control reports state and hands the decision to the system
             * picker, where the user can see what they are replacing.
             *
             * It sits above breach monitoring because it is the setting that changes how the vault
             * is used every day rather than what it knows.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Row(
                    modifier = Modifier
                        .clickable(
                            enabled = autofillSupported,
                            role = Role.Button,
                            onClick = onOpenAutofillSettings
                        )
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_kind_login),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(BubuSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.autofill_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            // Says what it is *for*, not what it is. "Fill logins without the
                            // clipboard" is the reason someone should want this; "enable autofill
                            // service" is a description of a checkbox.
                            text = stringResource(
                                when {
                                    !autofillSupported -> R.string.autofill_settings_unsupported
                                    autofillEnabled -> R.string.autofill_settings_on
                                    else -> R.string.autofill_settings_off
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (autofillSupported) {
                        Spacer(Modifier.width(BubuSpacing.sm))
                        Text(
                            text = stringResource(
                                if (autofillEnabled) {
                                    R.string.autofill_settings_action_manage
                                } else {
                                    R.string.autofill_settings_action_enable
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * The breach-monitoring switch sits next to the biometric one because they are the same
             * kind of decision: both trade a little of something for a little of something else, and
             * both are the user's call rather than a default this app is entitled to pick. The copy
             * says what it costs - network traffic - rather than only what it gives.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .toggleable(
                                value = breachMonitoringEnabled,
                                role = Role.Switch,
                                onValueChange = onToggleBreachMonitoring
                            )
                            .semantics(mergeDescendants = true) {}
                            .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(BubuSpacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Watch for leaked passwords",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Checks your login and Wi-Fi passwords once each time you " +
                                    "unlock. This is the only thing in Bubu that uses the internet, " +
                                    "and it never sends a password.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(BubuSpacing.sm))
                        Switch(
                            checked = breachMonitoringEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.bubu.champagne,
                                checkedTrackColor = MaterialTheme.bubu.champagneContainer,
                                checkedBorderColor = MaterialTheme.bubu.champagne,
                                uncheckedBorderColor = MaterialTheme.bubu.cardBorder
                            ),
                            modifier = Modifier.clearAndSetSemantics { }
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = BubuSpacing.md),
                        color = MaterialTheme.bubu.cardBorder.copy(alpha = 0.5f)
                    )

                    Row(
                        modifier = Modifier
                            .clickable(onClick = onOpenBreachReport)
                            .semantics(mergeDescendants = true) { role = Role.Button }
                            .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Security check",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (breachedCount) {
                                    0 -> "See which passwords have been checked, and check the rest."
                                    1 -> "1 password has turned up in a leak. Change it."
                                    else -> "$breachedCount passwords have turned up in leaks. " +
                                        "Change them."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (breachedCount > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (breachedCount > 0) {
                            Spacer(Modifier.width(BubuSpacing.sm))
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * The device check gets its own card rather than joining the breach one above.
             *
             * They answer different questions and would blur into each other if stacked: that card is
             * about the passwords *inside* the vault, this is about the phone the vault is sitting on.
             * A user who conflated them would read "all clear" on leaked passwords as a statement
             * about their device.
             *
             * The dot is red only for a critical finding. A sideloaded build and an active keylogger
             * both belong on that screen; giving them the same badge here would make the badge
             * meaningless.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenDeviceCheck)
                        .semantics(mergeDescendants = true) { role = Role.Button }
                        .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(BubuSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Device check",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                deviceRiskCount == 0 ->
                                    "Nothing on this phone holds a permission that could read your " +
                                        "vault. Tap to see what Bubu checked."
                                deviceRiskCount == 1 ->
                                    "1 thing on this phone is worth a look."
                                else ->
                                    "$deviceRiskCount things on this phone are worth a look."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasCriticalDeviceRisk) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (deviceRiskCount > 0) {
                        Spacer(Modifier.width(BubuSpacing.sm))
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (hasCriticalDeviceRisk) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.bubu.fair
                                    },
                                    CircleShape
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * The ad shield.
             *
             * Sits beside the device check because they answer adjacent questions - that one asks what
             * could reach the vault, this one asks what is spamming the user - but they are kept as two
             * rows rather than merged. A capability audit and an accusation are different claims, and a
             * single screen holding both would blur the line this feature depends on.
             *
             * The count is of *convicted* apps only. A suspect never reaches this badge, because a badge
             * is a claim that something is wrong and a permission list is not evidence that anything is.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenShield)
                        .semantics(mergeDescendants = true) { role = Role.Button }
                        .padding(horizontal = BubuSpacing.md, vertical = BubuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(BubuSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Ad shield",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when (adCulpritCount) {
                                0 -> "Find the app that is spamming ads on this phone."
                                1 -> "1 app has been caught spamming you."
                                else -> "$adCulpritCount apps have been caught spamming you."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (adCulpritCount > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (adCulpritCount > 0) {
                        Spacer(Modifier.width(BubuSpacing.sm))
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }
                }
            }

            Spacer(Modifier.height(BubuSpacing.md))

            Text(
                text = "If you lose this phone",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(BubuSpacing.sm))

            /*
             * Stated plainly rather than buried. Uninstalling this app destroys the vault - there is
             * no cloud copy and no reset link, which is the same property that makes the vault worth
             * having. A user who does not know that finds out at the worst possible moment, so the
             * only honest place for that sentence is next to the button that fixes it.
             */
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Column(
                    Modifier.padding(BubuSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
                ) {
                    SettingsNote(
                        title = "There is no copy anywhere else",
                        body = "Uninstalling Bubu, or clearing its data, erases every secret in the " +
                            "vault for good. Nobody can recover it - not Bubu, not your phone, not " +
                            "a support email. A backup file is the only way back."
                    )
                    BubuOutlinedButton(
                        text = "Save an encrypted backup",
                        onClick = onExportBackup,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "One file, sealed with your master passphrase. Put it somewhere you " +
                            "trust - it is safe in cloud storage precisely because it is useless " +
                            "without that passphrase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.md))

            Text(
                text = "About the app",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(BubuSpacing.sm))

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = BubuElevation.card,
                border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
            ) {
                Column(
                    Modifier.padding(BubuSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(BubuSpacing.md)
                ) {
                    SettingsNote(
                        title = "Locks itself",
                        body = "After ${VaultSession.BACKGROUND_GRACE_MILLIS / 1000}s in the background, " +
                            "or the moment the screen goes off. Copied secrets clear after " +
                            "${SecureClipboard.CLEAR_AFTER_SECONDS}s."
                    )
                    SettingsNote(
                        title = "Keeps your vault private",
                        body = "Your saved passwords and other secrets stay encrypted on this phone. " +
                            "Bubu does not upload or sync your vault."
                    )
                    SettingsNote(
                        title = "Checks known data leaks safely",
                        body = "You choose when to check a login password. Your password, account, and " +
                            "website stay on this phone. Only the safety result is kept in your encrypted " +
                            "vault so Bubu can remind you later."
                    )
                    BubuOutlinedButton(
                        text = "See how the safety check works",
                        onClick = onOpenSecurityGuide,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(BubuSpacing.lg))

            BubuOutlinedButton(
                text = "Lock the vault now",
                onClick = onLock,
                leadingIcon = Icons.Filled.Lock,
                modifier = Modifier.fillMaxWidth()
            )
        }
        }
    }
}

@Composable
private fun SettingsNote(title: String, body: String) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.bubu.champagne.copy(alpha = 0.72f), PillShape)
        )
        Column(verticalArrangement = Arrangement.spacedBy(BubuSpacing.xxs)) {
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

@Preview(showBackground = true, name = "Settings sheet")
@Preview(showBackground = true, name = "Settings sheet · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun VaultSettingsSheetPreview() {
    BubuProtectTheme {
        VaultSettingsSheet(
            biometricUnlockEnabled = true,
            canOfferBiometricUnlock = true,
            entryCount = 12,
            onToggleBiometricUnlock = {},
            strictRevealEnabled = true,
            onToggleStrictReveal = {},
            hasRecoveryKit = false,
            recoveryKitSealed = false,
            recoveryKitCreatedAt = 0L,
            onOpenRecoveryKit = {},
            onOpenImport = {},
            autofillSupported = true,
            autofillEnabled = false,
            onOpenAutofillSettings = {},
            onOpenSecurityGuide = {},
            breachMonitoringEnabled = true,
            breachedCount = 2,
            onToggleBreachMonitoring = {},
            onOpenBreachReport = {},
            deviceRiskCount = 1,
            hasCriticalDeviceRisk = true,
            onOpenDeviceCheck = {},
            adCulpritCount = 1,
            onOpenShield = {},
            onExportBackup = {},
            onLock = {},
            onDismiss = {}
        )
    }
}
