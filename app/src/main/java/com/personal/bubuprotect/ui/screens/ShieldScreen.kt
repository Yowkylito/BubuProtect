package com.personal.bubuprotect.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.core.shield.ShieldCapabilities
import com.personal.bubuprotect.core.shield.enforce.ShizukuGateway
import com.personal.bubuprotect.core.shield.network.ShieldVpnService
import com.personal.bubuprotect.domain.model.AppScanReport
import com.personal.bubuprotect.domain.model.AppVerdict
import com.personal.bubuprotect.domain.model.RemediationTier
import com.personal.bubuprotect.domain.model.RiskSignal
import com.personal.bubuprotect.domain.model.ShieldEvent
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.BubuTopBar
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.HeroCardShape
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.ShieldEvidence
import com.personal.bubuprotect.ui.vm.UndoableAction
import com.personal.bubuprotect.ui.vm.ShieldUiState
import com.personal.bubuprotect.ui.vm.ShieldViewModel

/**
 * The stateful half.
 *
 * Owns the two things the screen below cannot: rescanning when the user returns from a Settings page,
 * and turning an action into a `startActivity`. Everything below is a pure function of [ShieldUiState]
 * and therefore previewable in every state, including the states that are hard to reach on a device.
 */
@Composable
fun ShieldRoute(
    viewModel: ShieldViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Resume is when the answer is most likely to have changed: the user has just come back from
    // granting - or declining - something in Settings.
    LifecycleResumeEffect(Unit) {
        viewModel.rescan()
        onPauseOrDispose { }
    }

    /*
     * Opens a Settings destination, or says so when it is not there.
     *
     * OEM ROMs genuinely remove and rename these activities. Swallowing the failure would give the user
     * a button that does nothing at all, which reads as the app being broken rather than the shortcut
     * being missing - so the intent is resolved first and the miss is reported by name.
     */
    val open: (Intent, String) -> Unit = { intent, where ->
        if (viewModel.canOpen(intent)) {
            runCatching { context.startActivity(intent) }
                .onFailure { viewModel.reportMissingSettingsScreen(where) }
        } else {
            viewModel.reportMissingSettingsScreen(where)
        }
    }

    /*
     * The VPN consent dance.
     *
     * `VpnService.prepare` returns an Intent that MUST be launched for a result from an Activity - it
     * cannot be started from a Service or with NEW_TASK, and Android will not let a tunnel be
     * established until the user has accepted that system dialog. So the launcher lives here, in the
     * only place with an Activity behind it, and the service is only started once the result comes
     * back OK.
     *
     * A declined consent does nothing on purpose. The capability row simply stays off, and the user can
     * try again; nagging them about a dialog they just dismissed would be the wrong response to a clear
     * "no".
     */
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ShieldVpnService.start(context)
            viewModel.rescan()
        }
    }

    val toggleDnsFilter: (Boolean) -> Unit = { wanted ->
        if (!wanted) {
            ShieldVpnService.stop(context)
            viewModel.rescan()
        } else {
            when (val consent = ShieldVpnService.consentIntent(context)) {
                null -> {
                    // Already consented - a tunnel was established before and the grant persists.
                    ShieldVpnService.start(context)
                    viewModel.rescan()
                }
                else -> consentLauncher.launch(consent)
            }
        }
    }

    ShieldScreen(
        state = state,
        onBack = onBack,
        onPanic = viewModel::replayRecent,
        onDismissReplay = viewModel::dismissReplay,
        onTolerate = viewModel::tolerate,
        onUntolerate = viewModel::untolerate,
        onNeuter = viewModel::neuter,
        onDisable = viewModel::disable,
        onRemove = viewModel::remove,
        onClearNotifications = viewModel::dismissNotifications,
        onToggleFilter = viewModel::setFiltered,
        onGrantWindowWatcher = { open(viewModel.accessibilityIntent(), "Accessibility") },
        onGrantNotificationTap = { open(viewModel.notificationAccessIntent(), "Notification access") },
        onGrantUsage = { open(viewModel.usageAccessIntent(), "Usage access") },
        onRequestShizuku = viewModel::requestShizukuPermission,
        onToggleDnsFilter = toggleDnsFilter,
        onUninstall = { open(viewModel.uninstallIntent(it), "Apps") },
        onOpenAppSettings = { open(viewModel.appSettingsIntent(it), "Apps") },
        onUndo = viewModel::undo,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier
    )
}

@Composable
fun ShieldScreen(
    state: ShieldUiState,
    onBack: () -> Unit,
    onPanic: () -> Unit,
    onDismissReplay: () -> Unit,
    onTolerate: (AppVerdict) -> Unit,
    onUntolerate: (AppVerdict) -> Unit,
    onNeuter: (AppVerdict) -> Unit,
    onDisable: (AppVerdict) -> Unit,
    onRemove: (AppVerdict) -> Unit,
    onClearNotifications: (AppVerdict) -> Unit,
    onToggleFilter: (String, Boolean) -> Unit,
    onGrantWindowWatcher: () -> Unit,
    onGrantNotificationTap: () -> Unit,
    onGrantUsage: () -> Unit,
    onRequestShizuku: () -> Unit,
    onToggleDnsFilter: (Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onOpenAppSettings: (String) -> Unit,
    onUndo: (UndoableAction) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ResponsiveContainer {
            BubuTopBar(
                title = "Ad shield",
                subtitle = state.subtitle,
                leading = {
                    BubuIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack
                    )
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(BubuSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
            ) {
                item {
                    HeroCard(state = state, onPanic = onPanic)
                }

                state.notice?.let { notice ->
                    item {
                        NoticeCard(
                            notice = notice,
                            undo = state.undo,
                            onUndo = onUndo,
                            onDismiss = onDismissNotice
                        )
                    }
                }

                if (state.replay.isNotEmpty()) {
                    item {
                        ReplayCard(events = state.replay, onDismiss = onDismissReplay)
                    }
                }

                if (state.isBlind) {
                    item {
                        BlindWarning(
                            onGrantWindowWatcher = onGrantWindowWatcher,
                            onGrantNotificationTap = onGrantNotificationTap
                        )
                    }
                }

                state.report.unavailableReason?.let { why ->
                    item { SectionHeading("Bubu could not check") }
                    item { PlainCard(why) }
                }

                if (state.culprits.isNotEmpty()) {
                    item { SectionHeading("Caught in the act") }
                    items(state.culprits, key = AppVerdict::packageName) { verdict ->
                        CulpritCard(
                            verdict = verdict,
                            evidence = state.evidenceFor[verdict.packageName],
                            tiers = state.tiers,
                            isFiltered = verdict.packageName in state.filtered,
                            isBusy = state.busyPackage == verdict.packageName,
                            onNeuter = { onNeuter(verdict) },
                            onDisable = { onDisable(verdict) },
                            onRemove = { onRemove(verdict) },
                            onClearNotifications = { onClearNotifications(verdict) },
                            onToggleFilter = { onToggleFilter(verdict.packageName, it) },
                            onUninstall = { onUninstall(verdict.packageName) },
                            onOpenSettings = { onOpenAppSettings(verdict.packageName) },
                            onTolerate = { onTolerate(verdict) }
                        )
                    }
                }

                if (state.culprits.isEmpty() && state.report.hasRun && !state.isBlind) {
                    item { AllClearCard() }
                }

                if (state.suspects.isNotEmpty()) {
                    item { SectionHeading("Worth a look") }
                    item { SuspectPreamble() }
                    items(state.suspects, key = AppVerdict::packageName) { verdict ->
                        SuspectRow(
                            verdict = verdict,
                            onOpenSettings = { onOpenAppSettings(verdict.packageName) }
                        )
                    }
                }

                if (state.tolerated.isNotEmpty()) {
                    item { SectionHeading("You said these are fine") }
                    items(state.tolerated, key = AppVerdict::packageName) { verdict ->
                        ToleratedRow(verdict = verdict, onUndo = { onUntolerate(verdict) })
                    }
                }

                item { SectionHeading("What Bubu can see") }
                item {
                    CapabilityLadder(
                        capabilities = state.capabilities,
                        onGrantWindowWatcher = onGrantWindowWatcher,
                        onGrantNotificationTap = onGrantNotificationTap,
                        onGrantUsage = onGrantUsage,
                        onRequestShizuku = onRequestShizuku,
                        onToggleDnsFilter = onToggleDnsFilter
                    )
                }

                state.report.ownRow?.let { own ->
                    item { SectionHeading("This app") }
                    item { OwnRowCard(own) }
                }

                item { Spacer(Modifier.height(BubuSpacing.xl)) }
            }
        }
    }
}

private val ShieldUiState.subtitle: String
    get() = when {
        isScanning -> "Looking at every app"
        report.unavailableReason != null -> "Could not check"
        isBlind -> "Watching nothing yet"
        culprits.isNotEmpty() -> "${culprits.size} caught"
        report.hasRun -> "Nothing caught"
        else -> "Not checked yet"
    }

@Composable
private fun HeroCard(state: ShieldUiState, onPanic: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(BubuSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BubuMascot(
                    mood = when {
                        state.isScanning -> BubuMood.THINKING
                        state.culprits.isNotEmpty() -> BubuMood.WORRIED
                        else -> BubuMood.GUARDING
                    },
                    size = 56.dp,
                    showBackdrop = false,
                    // The headline beside it already says what the mood means; repeating it would make
                    // TalkBack read the same fact twice.
                    contentDescription = null
                )
                Spacer(Modifier.width(BubuSpacing.sm))
                Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(
                        text = state.headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.explainer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(BubuSpacing.xs))
                    Text("Checking", style = MaterialTheme.typography.bodySmall)
                }
            }

            // The panic button. Deliberately the most prominent control on the screen: it is the one
            // the user reaches for in the moment the problem is actually happening, and every other
            // control here is something they browse to afterwards.
            BubuOutlinedButton(
                text = "An ad just hit me",
                onClick = onPanic,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private val ShieldUiState.headline: String
    get() = when {
        report.unavailableReason != null -> "Bubu could not check this phone"
        isBlind -> "Bubu is not watching yet"
        culprits.size == 1 -> "One app is spamming you"
        culprits.size > 1 -> "${culprits.size} apps are spamming you"
        report.hasRun -> "Nothing caught in the act"
        else -> "Checking your apps"
    }

private val ShieldUiState.explainer: String
    get() = when {
        report.unavailableReason != null -> "The app list could not be read."
        isBlind -> "Turn on a watcher below and Bubu can name the app drawing ads on you."
        culprits.isNotEmpty() -> "Each one below shows what Bubu watched it do."
        report.hasRun -> "Bubu is watching. Tap the button when an ad appears."
        else -> "One moment."
    }

@Composable
private fun CulpritCard(
    verdict: AppVerdict,
    evidence: ShieldEvidence?,
    tiers: Set<RemediationTier>,
    isFiltered: Boolean,
    isBusy: Boolean,
    onNeuter: () -> Unit,
    onDisable: () -> Unit,
    onRemove: () -> Unit,
    onClearNotifications: () -> Unit,
    onToggleFilter: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onTolerate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(
            modifier = Modifier.padding(BubuSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
        ) {
            Text(
                text = verdict.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = verdict.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.bubu.cardBorder)

            // The receipt. This is the part that makes the accusation checkable, so it comes before
            // any button that would act on it.
            Text(
                text = "What Bubu saw",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            verdict.evidence.forEach { signal ->
                Text(
                    text = "  ${signal.sentence(evidence)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (tiers.size == 1) {
                // ADVISE only. Say so, rather than showing four buttons that would fail.
                Text(
                    text = "Bubu can ask Android to uninstall this. For the gentler options - stopping " +
                        "the ads without removing the app - set up Shizuku below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xs)) {
                if (RemediationTier.NEUTER in tiers) {
                    BubuOutlinedButton(text = "Stop its ads", onClick = onNeuter, enabled = !isBusy)
                }
                if (RemediationTier.DISABLE in tiers) {
                    BubuOutlinedButton(text = "Disable", onClick = onDisable, enabled = !isBusy)
                }
                if (RemediationTier.REMOVE in tiers) {
                    BubuOutlinedButton(text = "Remove", onClick = onRemove, enabled = !isBusy)
                } else {
                    BubuOutlinedButton(text = "Uninstall", onClick = onUninstall, enabled = !isBusy)
                }
                BubuOutlinedButton(text = "Clear its notifications", onClick = onClearNotifications)
                BubuOutlinedButton(text = "App settings", onClick = onOpenSettings)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Block its ad networks", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Needs the DNS filter running",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isFiltered, onCheckedChange = onToggleFilter)
            }

            TextButton(onClick = onTolerate) { Text("This one is fine, leave it alone") }
        }
    }
}

/**
 * Turns a signal into a sentence the user can check against their own memory.
 *
 * Counts and hostnames are folded in where they exist, because "23 times" is what makes the claim
 * concrete. Where the evidence detail is missing the sentence stays true rather than inventing a
 * number - a fabricated count in the one place the user is being asked to trust this app would be the
 * worst possible place to be sloppy.
 */
private fun RiskSignal.sentence(evidence: ShieldEvidence?): String = when (this) {
    RiskSignal.DREW_OVERLAY -> evidence?.overlayCount
        ?.takeIf { it > 0 }
        ?.let { "Drew a full-screen window over another app $it time${if (it == 1) "" else "s"}" }
        ?: "Drew a full-screen window over another app"

    RiskSignal.AD_NETWORK_TRAFFIC -> evidence?.adHosts
        ?.takeIf(Set<String>::isNotEmpty)
        ?.let { "Contacted ${it.size} ad network${if (it.size == 1) "" else "s"}: ${it.take(3).joinToString()}" }
        ?: "Contacted known ad networks"

    RiskSignal.SELF_LAUNCHED -> "Put itself in the foreground while you were using something else"
    RiskSignal.NOTIFICATION_FLOOD -> "Flooded one notification channel"
    RiskSignal.KNOWN_BAD_SIGNER -> "Signed by a known adware developer"
    RiskSignal.SIGNER_SIBLING -> "Signed by the same developer as another app caught here"
    RiskSignal.SIDELOADED -> "Installed from outside a recognised store"
    RiskSignal.UNKNOWN_INSTALLER -> "Nothing recorded who installed it"
    RiskSignal.CAN_DRAW_OVERLAYS -> "Allowed to draw over other apps"
    RiskSignal.DECLARES_ACCESSIBILITY -> "Can read the contents of every screen"
    RiskSignal.DECLARES_NOTIFICATION_LISTENER -> "Can read your notifications"
    RiskSignal.ACTIVE_DEVICE_ADMIN -> "An active device administrator"
    RiskSignal.BOOT_PERSISTENCE -> "Starts itself at boot and runs in the background"
    RiskSignal.NO_LAUNCHER_ICON -> "Has no icon in your launcher"
    RiskSignal.DEBUGGABLE_BUILD -> "Built as a debug version"
    RiskSignal.AD_SDK_PRESENT -> "Carries an aggressive ad SDK"
    RiskSignal.ON_PROBATION -> "Installed in the last day"
}

@Composable
private fun ReplayCard(events: List<ShieldEvent>, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(BubuSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
        ) {
            Text(
                text = "The last minute",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (events.isEmpty()) {
                Text(
                    text = "Bubu did not record anything in the last minute. If an ad appeared, the " +
                        "watcher below is probably switched off.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                events.take(REPLAY_LIMIT).forEach { event ->
                    Text(event.line(), style = MaterialTheme.typography.bodySmall)
                }
            }

            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

private fun ShieldEvent.line(): String = when (this) {
    is ShieldEvent.OverlayDrawn ->
        "$packageName drew a window" + (overWhat?.let { " over $it" } ?: "")

    is ShieldEvent.SelfLaunched -> "$packageName launched itself"
    is ShieldEvent.NotificationPosted -> "$packageName posted a notification"
    is ShieldEvent.AdHostResolved ->
        "$packageName asked for $host" + if (blocked) " (blocked)" else ""
}

@Composable
private fun BlindWarning(
    onGrantWindowWatcher: () -> Unit,
    onGrantNotificationTap: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(
            modifier = Modifier.padding(BubuSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
        ) {
            Text(
                text = "Bubu cannot name a culprit yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Without a watcher, Bubu can only list apps that *could* show ads - which is " +
                    "guesswork, and guesswork is how other scanners end up telling you to uninstall " +
                    "your banking app. Turn one on and Bubu names the app that actually did it.",
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xs)) {
                BubuOutlinedButton(text = "Turn on ad watcher", onClick = onGrantWindowWatcher)
                BubuOutlinedButton(text = "Notification access", onClick = onGrantNotificationTap)
            }
        }
    }
}

@Composable
private fun CapabilityLadder(
    capabilities: ShieldCapabilities.Snapshot?,
    onGrantWindowWatcher: () -> Unit,
    onGrantNotificationTap: () -> Unit,
    onGrantUsage: () -> Unit,
    onRequestShizuku: () -> Unit,
    onToggleDnsFilter: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(BubuSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
        ) {
            if (capabilities == null) {
                Text("Checking what Bubu can see", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            Text(
                text = "${capabilities.grantedCount} of " +
                    "${ShieldCapabilities.Snapshot.TOTAL_CAPABILITIES} watchers on",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            CapabilityRow(
                title = "Ad watcher",
                detail = "Names the app that draws over your screen. Reads which windows exist, " +
                    "never what is on them.",
                granted = capabilities.windowWatcher,
                onGrant = onGrantWindowWatcher
            )
            CapabilityRow(
                title = "Notification access",
                detail = "Measures notification spam, and lets Bubu clear it without uninstalling.",
                granted = capabilities.notificationTap,
                onGrant = onGrantNotificationTap
            )
            CapabilityRow(
                title = "Usage access",
                detail = "Tells apps that launch themselves apart from ones you opened.",
                granted = capabilities.usageTimeline,
                onGrant = onGrantUsage
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (capabilities.dnsFilter) "Ad network filter - on"
                        else "Ad network filter",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (capabilities.dnsFilter) FontWeight.SemiBold
                        else FontWeight.Normal
                    )
                    Text(
                        text = "A local DNS filter. Only name lookups go through it and nothing " +
                            "leaves your phone - there is no server on the other end. Android will " +
                            "show a VPN key in your status bar, because that is the API this uses.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = capabilities.dnsFilter, onCheckedChange = onToggleDnsFilter)
            }

            HorizontalDivider(color = MaterialTheme.bubu.cardBorder)

            Text(
                text = when (capabilities.shizukuState) {
                    ShizukuGateway.Availability.Ready ->
                        "Shizuku is connected. Bubu can stop an app's ads without removing it."
                    ShizukuGateway.Availability.PermissionRequired ->
                        "Shizuku is running but has not granted Bubu access."
                    ShizukuGateway.Availability.NotRunning ->
                        "Shizuku is installed but not started. It needs restarting after each reboot."
                    ShizukuGateway.Availability.NotInstalled ->
                        "Without Shizuku, Bubu can only ask Android to uninstall an app. With it, " +
                            "Bubu can revoke an app's ability to draw ads and leave the app working."
                },
                style = MaterialTheme.typography.bodySmall
            )

            if (capabilities.shizukuState == ShizukuGateway.Availability.PermissionRequired) {
                BubuOutlinedButton(text = "Ask Shizuku for access", onClick = onRequestShizuku)
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    title: String,
    detail: String,
    granted: Boolean,
    onGrant: (() -> Unit)?
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (granted) "$title - on" else title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (granted) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted && onGrant != null) {
            TextButton(onClick = onGrant) { Text("Turn on") }
        }
    }
}

@Composable
private fun OwnRowCard(own: AppVerdict) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(BubuSpacing.md)) {
            Text(
                text = own.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Bubu holds the same watching permissions it warns you about in other apps. " +
                    "It is listed here so you can see that, and revoke it in Settings whenever you " +
                    "want. Matched by signing certificate, so a fake app using this name cannot " +
                    "hide here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SuspectPreamble() {
    Text(
        text = "These hold permissions that could show ads. Bubu has not seen any of them do it, so " +
            "this is not an accusation - most are legitimate apps that need what they asked for.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SuspectRow(verdict: AppVerdict, onOpenSettings: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(verdict.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = verdict.evidence.take(2).joinToString(" · ") { it.sentence(null) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onOpenSettings) { Text("Open") }
    }
}

@Composable
private fun ToleratedRow(verdict: AppVerdict, onUndo: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = verdict.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}

@Composable
private fun AllClearCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(BubuSpacing.md)) {
            Text(
                text = "Nothing caught in the act",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Bubu is watching. This means no app has drawn over your screen or hit an ad " +
                    "network while the watcher has been on - not that your phone is guaranteed clean.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoticeCard(
    notice: String,
    undo: UndoableAction?,
    onUndo: (UndoableAction) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(BubuSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            // Only present for the reversible rungs. REMOVE never sets it.
            undo?.let { action ->
                TextButton(onClick = { onUndo(action) }) { Text("Undo") }
            }
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun PlainCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(text = text, modifier = Modifier.padding(BubuSpacing.md))
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = BubuSpacing.sm)
            .semantics { heading() }
    )
}

private const val REPLAY_LIMIT = 12

// ---- Previews -------------------------------------------------------------------------------------
//
// One per state that is hard to reach on a device, which is most of them: catching a real culprit needs
// real adware, and the blind state needs a phone with every grant switched off.

private val previewCulprit = AppVerdict(
    packageName = "com.flashlight.pro",
    label = "Super Flashlight",
    signerSha256 = "A".repeat(64),
    signals = setOf(
        RiskSignal.DREW_OVERLAY,
        RiskSignal.CAN_DRAW_OVERLAYS,
        RiskSignal.SIDELOADED,
        RiskSignal.NO_LAUNCHER_ICON
    ),
    firstInstalledAt = 0L
)

@Preview(name = "Culprit caught")
@Composable
private fun ShieldScreenCaughtPreview() {
    BubuProtectTheme {
        ShieldScreen(
            state = ShieldUiState(
                report = AppScanReport(verdicts = listOf(previewCulprit), checkedAt = 1L),
                capabilities = ShieldCapabilities.Snapshot(
                    windowWatcher = true,
                    notificationTap = true,
                    usageTimeline = false,
                    dnsFilter = false,
                    shizukuState = ShizukuGateway.Availability.Ready
                ),
                evidenceFor = mapOf(
                    previewCulprit.packageName to ShieldEvidence(overlayCount = 23, adHosts = emptySet())
                )
            ),
            onBack = {}, onPanic = {}, onDismissReplay = {}, onTolerate = {}, onUntolerate = {},
            onNeuter = {}, onDisable = {}, onRemove = {}, onClearNotifications = {},
            onToggleFilter = { _, _ -> }, onGrantWindowWatcher = {}, onGrantNotificationTap = {},
            onGrantUsage = {}, onRequestShizuku = {}, onToggleDnsFilter = {},
            onUninstall = {}, onOpenAppSettings = {}, onUndo = {},
            onDismissNotice = {}
        )
    }
}

@Preview(name = "Nothing granted")
@Composable
private fun ShieldScreenBlindPreview() {
    BubuProtectTheme {
        ShieldScreen(
            state = ShieldUiState(
                report = AppScanReport(verdicts = emptyList(), checkedAt = 1L),
                capabilities = ShieldCapabilities.Snapshot(
                    windowWatcher = false,
                    notificationTap = false,
                    usageTimeline = false,
                    dnsFilter = false,
                    shizukuState = ShizukuGateway.Availability.NotInstalled
                )
            ),
            onBack = {}, onPanic = {}, onDismissReplay = {}, onTolerate = {}, onUntolerate = {},
            onNeuter = {}, onDisable = {}, onRemove = {}, onClearNotifications = {},
            onToggleFilter = { _, _ -> }, onGrantWindowWatcher = {}, onGrantNotificationTap = {},
            onGrantUsage = {}, onRequestShizuku = {}, onToggleDnsFilter = {},
            onUninstall = {}, onOpenAppSettings = {}, onUndo = {},
            onDismissNotice = {}
        )
    }
}

@Preview(name = "Scanning")
@Composable
private fun ShieldScreenScanningPreview() {
    BubuProtectTheme {
        ShieldScreen(
            state = ShieldUiState(isScanning = true),
            onBack = {}, onPanic = {}, onDismissReplay = {}, onTolerate = {}, onUntolerate = {},
            onNeuter = {}, onDisable = {}, onRemove = {}, onClearNotifications = {},
            onToggleFilter = { _, _ -> }, onGrantWindowWatcher = {}, onGrantNotificationTap = {},
            onGrantUsage = {}, onRequestShizuku = {}, onToggleDnsFilter = {},
            onUninstall = {}, onOpenAppSettings = {}, onUndo = {},
            onDismissNotice = {}
        )
    }
}
