package com.personal.bubuprotect.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.domain.model.DeviceFinding
import com.personal.bubuprotect.domain.model.DeviceProbe
import com.personal.bubuprotect.domain.model.DeviceScanReport
import com.personal.bubuprotect.domain.model.ProbeResult
import com.personal.bubuprotect.domain.model.RiskLevel
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.BubuTopBar
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.HeroCardShape
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.DeviceCheckUiState
import com.personal.bubuprotect.ui.vm.DeviceCheckViewModel

/**
 * The stateful half.
 *
 * Owns the two things a screen cannot: the rescan-on-resume trigger, and turning a finding into a
 * `startActivity` for the Settings page that fixes it. Everything below this is a pure function of
 * [DeviceCheckUiState] and therefore previewable.
 */
@Composable
fun DeviceCheckRoute(
    onBack: () -> Unit,
    viewModel: DeviceCheckViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * Rescan every time this screen comes back to the front.
     *
     * That is the whole point of the fix buttons: the user is sent to a Settings page, turns something
     * off, and comes back. A report captured once would still be showing the finding they just fixed,
     * and the natural reading of that is "it did not work" rather than "the screen is stale".
     */
    LifecycleResumeEffect(Unit) {
        viewModel.rescan()
        onPauseOrDispose { }
    }

    DeviceCheckScreen(
        state = state,
        onBack = onBack,
        onRescan = viewModel::rescan,
        onAcknowledge = viewModel::acknowledge,
        onUnacknowledge = viewModel::unacknowledge,
        onDismissNotice = viewModel::dismissNotice,
        onFix = { probe ->
            probe.settingsShortcut()?.let { shortcut ->
                val intent = Intent(shortcut.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Manufacturer ROMs do rename and remove these activities. Failing
                // loudly-but-politely beats a button that appears to do nothing, so the ViewModel
                // turns the miss into a line of copy naming where to look instead.
                runCatching { context.startActivity(intent) }
                    .onFailure { viewModel.reportMissingSettingsScreen(shortcut.whereToLook) }
            }
        },
        modifier = modifier
    )
}

/**
 * What else on this phone could reach the vault.
 *
 * ### Why every check is listed, not just the failures
 *
 * Same argument as the breach report, and it matters more here. A security screen showing an empty
 * list is indistinguishable from a security screen that is broken, and the user has no way to tell
 * "nothing found" from "nothing ran". So the clear checks are shown too, quietly, as the denominator
 * that makes a clean result mean something.
 *
 * ### Why the limits are on the screen rather than in a help page
 *
 * This check cannot do what the phrase "spyware scan" implies - see
 * [com.personal.bubuprotect.domain.model.RiskLevel] for the platform reasons. A user who believes
 * this screen clears their phone of malware is *worse protected* than one who knows exactly what it
 * covers, because they will trust a green result they should not. The footer is therefore part of the
 * feature, not a disclaimer bolted onto it.
 */
@Composable
fun DeviceCheckScreen(
    state: DeviceCheckUiState,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onAcknowledge: (DeviceFinding) -> Unit,
    onUnacknowledge: (DeviceFinding) -> Unit,
    onDismissNotice: () -> Unit,
    onFix: (DeviceProbe) -> Unit,
    modifier: Modifier = Modifier
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Hoisted: both are filters over the finding list, and the lazy list body reads each of them
    // twice - once to decide whether the section exists and once to emit it.
    val clear = state.report.clear
    val unavailable = state.report.unavailable

    Column(modifier.fillMaxSize()) {
        BubuTopBar(
            title = "Device check",
            subtitle = "What else on this phone can reach your secrets",
            leading = {
                BubuIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to the vault",
                    onClick = onBack,
                    tonal = true
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        ResponsiveContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BubuSpacing.screen,
                    end = BubuSpacing.screen,
                    top = BubuSpacing.xs,
                    bottom = BubuSpacing.lg + navBottom
                ),
                verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
            ) {
                item(key = "hero") { DeviceHero(state = state) }

                if (state.outstanding.isNotEmpty()) {
                    item(key = "outstandingHeader") {
                        SectionHeading(
                            title = if (state.criticalOutstanding > 0) {
                                "Deal with these"
                            } else {
                                "Worth a look"
                            },
                            detail = "Each one is something on this phone, not something in your " +
                                "vault. Bubu can take you to the switch that turns it off."
                        )
                    }
                    itemsIndexed(
                        state.outstanding,
                        key = { _, finding -> "flagged-${finding.fingerprint}" }
                    ) { index, finding ->
                        FindingCard(
                            finding = finding,
                            onFix = { onFix(finding.probe) },
                            onAcknowledge = { onAcknowledge(finding) },
                            modifier = Modifier.enterStaggered(index)
                        )
                    }
                }

                if (state.accepted.isNotEmpty()) {
                    item(key = "acceptedHeader") {
                        SectionHeading(
                            title = "You said these are fine",
                            detail = "Still true, still listed. If a different app turns up under " +
                                "one of them, Bubu will raise it again."
                        )
                    }
                    itemsIndexed(
                        state.accepted,
                        key = { _, finding -> "accepted-${finding.fingerprint}" }
                    ) { _, finding ->
                        QuietRow(
                            title = finding.probe.title(),
                            body = finding.details.joinToString("  ·  ")
                                .ifBlank { finding.probe.flaggedMeaning() },
                            dot = MaterialTheme.colorScheme.onSurfaceVariant,
                            trailing = {
                                TextButton(onClick = { onUnacknowledge(finding) }) {
                                    Text("Watch again", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        )
                    }
                }

                if (clear.isNotEmpty()) {
                    item(key = "clearHeader") {
                        SectionHeading(
                            title = "Checked and clear",
                            detail = "Asked, answered, nothing there."
                        )
                    }
                    itemsIndexed(
                        clear,
                        key = { _, finding -> "clear-${finding.probe.name}" }
                    ) { _, finding ->
                        QuietRow(
                            title = finding.probe.title(),
                            body = finding.probe.clearNote(),
                            dot = MaterialTheme.bubu.strong
                        )
                    }
                }

                if (unavailable.isNotEmpty()) {
                    item(key = "unavailableHeader") {
                        SectionHeading(
                            title = "Could not check",
                            detail = "This phone would not answer. That is not the same as clear."
                        )
                    }
                    itemsIndexed(
                        unavailable,
                        key = { _, finding -> "unavailable-${finding.probe.name}" }
                    ) { _, finding ->
                        QuietRow(
                            title = finding.probe.title(),
                            body = (finding.result as? ProbeResult.Unavailable)?.why.orEmpty(),
                            dot = Color.Transparent
                        )
                    }
                }

                item(key = "actions") {
                    Spacer(Modifier.height(BubuSpacing.xs))
                    Column(verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)) {
                        BubuButton(
                            text = "Check again",
                            onClick = onRescan,
                            isBusy = state.isScanning,
                            busyText = "Checking",
                            modifier = Modifier.fillMaxWidth()
                        )
                        state.notice?.let { notice ->
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                            TextButton(onClick = onDismissNotice) { Text("Got it") }
                        }
                    }
                }

                item(key = "limits") { LimitsNote() }
            }
        }
    }
}

/**
 * The headline verdict.
 *
 * The mascot's mood is the fastest thing on screen to read, so it carries the level rather than
 * decorating it - and it stops at [BubuMood.SUSPICIOUS] for a warning instead of going straight to
 * worried, because the single most common true finding here is a screen reader the user installed
 * themselves.
 */
@Composable
private fun DeviceHero(state: DeviceCheckUiState, modifier: Modifier = Modifier) {
    val report = state.report
    val isClean = report.hasRun && state.outstanding.isEmpty()

    val mood = when (state.worstOutstanding) {
        RiskLevel.CRITICAL -> BubuMood.WORRIED
        RiskLevel.WARNING, RiskLevel.INFO -> BubuMood.SUSPICIOUS
        null -> if (isClean) BubuMood.CELEBRATING else BubuMood.THINKING
    }
    val container = when (state.worstOutstanding) {
        RiskLevel.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        RiskLevel.WARNING, RiskLevel.INFO -> MaterialTheme.bubu.champagneContainer
        null -> MaterialTheme.bubu.card.container
    }
    val content = when (state.worstOutstanding) {
        RiskLevel.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
        RiskLevel.WARNING, RiskLevel.INFO -> MaterialTheme.colorScheme.onSurface
        null -> MaterialTheme.bubu.card.content
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = HeroCardShape,
        color = container,
        contentColor = content,
        shadowElevation = BubuElevation.hero,
        border = BorderStroke(1.dp, content.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(BubuSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BubuMascot(
                mood = mood,
                size = 128.dp,
                showBackdrop = false,
                contentDescription = null
            )
            Spacer(Modifier.height(BubuSpacing.xs))
            Text(
                text = when {
                    !report.hasRun -> "Looking around"
                    state.criticalOutstanding == 1 -> "1 thing to deal with"
                    state.criticalOutstanding > 1 -> "${state.criticalOutstanding} things to deal with"
                    state.outstanding.size == 1 -> "1 thing worth a look"
                    state.outstanding.isNotEmpty() -> "${state.outstanding.size} things worth a look"
                    else -> "Nothing is watching"
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(BubuSpacing.xxs))
            Text(
                text = when {
                    !report.hasRun -> "Bubu is asking this phone a few questions."
                    state.outstanding.isEmpty() ->
                        "${report.clear.size} of ${report.findings.size} checks came back clear, " +
                            "and nothing on this phone holds a permission that could read your " +
                            "vault."
                    else ->
                        "Out of ${report.findings.size} checks. " +
                            "${report.clear.size} clear" +
                            if (state.accepted.isEmpty()) {
                                "."
                            } else {
                                ", ${state.accepted.size} you already accepted."
                            }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.82f),
                textAlign = TextAlign.Center
            )

            if (report.hasRun) {
                Spacer(Modifier.height(BubuSpacing.xxs))
                Text(
                    text = "Checked ${relativeAge(report.checkedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = content.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * One flagged finding, with the two things the user can do about it.
 *
 * "This is fine" is not a nag-dismiss button - it is how the screen stays credible for the many people
 * whose findings are real, correct, and theirs. Without it, a TalkBack user gets a permanent red row
 * and learns to ignore the whole screen; see
 * [com.personal.bubuprotect.data.local.UserPreferences.acknowledgedDeviceRisks].
 */
@Composable
private fun FindingCard(
    finding: DeviceFinding,
    onFix: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tone = finding.level.tone()
    val shortcut = finding.probe.settingsShortcut()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = BubuElevation.card,
        border = BorderStroke(1.dp, tone.accent.copy(alpha = 0.45f))
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(tone.accent)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(BubuSpacing.md)
            ) {
                RiskPill(label = tone.label, accent = tone.accent)
                Spacer(Modifier.height(BubuSpacing.xs))
                Text(
                    text = finding.probe.title(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(BubuSpacing.xxs))
                Text(
                    text = finding.probe.flaggedMeaning(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (finding.details.isNotEmpty()) {
                    Spacer(Modifier.height(BubuSpacing.xs))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xxs),
                        verticalArrangement = Arrangement.spacedBy(BubuSpacing.xxs)
                    ) {
                        finding.details.forEach { detail ->
                            DetailChip(text = detail)
                        }
                    }
                }

                Spacer(Modifier.height(BubuSpacing.sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(BubuSpacing.xxs)
                ) {
                    if (shortcut != null) {
                        BubuOutlinedButton(
                            text = shortcut.buttonLabel,
                            onClick = onFix,
                            borderColor = tone.accent
                        )
                    }
                    TextButton(onClick = onAcknowledge) {
                        Text("This is fine", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** A compact row for the states that are not asking for anything. */
@Composable
private fun QuietRow(
    title: String,
    body: String,
    dot: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = BubuSpacing.md,
                vertical = BubuSpacing.sm
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(dot, CircleShape)
            )
            Spacer(Modifier.width(BubuSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (body.isNotBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(BubuSpacing.xs))
                trailing()
            }
        }
    }
}

@Composable
private fun RiskPill(label: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = accent.copy(alpha = 0.16f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = BubuSpacing.xs, vertical = 3.dp)
        )
    }
}

/**
 * One named offender.
 *
 * A chip rather than a bullet because the list is the actionable part - "Screen readers: TalkBack" is
 * something the user can make a decision about, where "an accessibility service is running" is not.
 */
@Composable
private fun DetailChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.6f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = BubuSpacing.xs, vertical = BubuSpacing.xxs)
        )
    }
}

/** @see DeviceCheckScreen for why this is on the screen instead of in a help page. */
@Composable
private fun LimitsNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .padding(BubuSpacing.md)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.bubu.champagne.copy(alpha = 0.72f), PillShape)
            )
            Column {
                Text(
                    text = "What this check is, and is not",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(BubuSpacing.xxs))
                Text(
                    text = "This is not a virus scan, and \"nothing is watching\" does not mean " +
                        "\"this phone has no malware\". Android does not let any app read the list " +
                        "of what is installed or running on your phone, and Bubu will not ask for " +
                        "the permission that would come closest - it would mean Bubu could inventory " +
                        "everything you use.\n\n" +
                        "What it does instead is check the handful of permissions and settings that " +
                        "could actually reach a password: reading your screen, reading your " +
                        "notifications, admin control, debugging, root. Those are the doors. Bubu " +
                        "checks whether any of them are open.\n\n" +
                        "Screen recording is not on the list because it is already impossible here - " +
                        "every screen in this app is marked unrecordable, so screenshots, recorders " +
                        "and screen mirroring all come out blank. Everything runs on this phone; " +
                        "nothing about your device is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = BubuSpacing.xs, start = BubuSpacing.xxs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Copy and tone -------------------------------------------------------------------------------

private data class RiskTone(val accent: Color, val label: String)

/**
 * One place that decides what a level looks like.
 *
 * Same reasoning as [com.personal.bubuprotect.ui.components.BreachTone]: a user who sees two
 * different reds on two security surfaces learns to read neither of them as urgent.
 */
@Composable
private fun RiskLevel?.tone(): RiskTone = when (this) {
    RiskLevel.CRITICAL -> RiskTone(MaterialTheme.colorScheme.error, "Act on this")
    RiskLevel.WARNING -> RiskTone(MaterialTheme.bubu.fair, "Worth a look")
    RiskLevel.INFO -> RiskTone(MaterialTheme.colorScheme.onSurfaceVariant, "Just so you know")
    null -> RiskTone(MaterialTheme.bubu.strong, "Clear")
}

private fun DeviceProbe.title(): String = when (this) {
    DeviceProbe.SCREEN_READERS -> "Apps that can read your screen"
    DeviceProbe.NOTIFICATION_READERS -> "Apps that can read your notifications"
    DeviceProbe.DEVICE_ADMINS -> "Apps with admin control of this phone"
    DeviceProbe.ROOT_ACCESS -> "Root access"
    DeviceProbe.DEBUG_BRIDGE -> "Developer and debugging switches"
    DeviceProbe.DEBUGGER_ATTACHED -> "A debugger is attached right now"
    DeviceProbe.NETWORK_INTERCEPTION -> "Something sits between this app and the internet"
    DeviceProbe.SCREEN_LOCK -> "This phone's own lock"
    DeviceProbe.INSTALL_SOURCE -> "Where this copy of Bubu came from"
    DeviceProbe.UNTRUSTED_BUILD -> "What kind of device this is"
}

/**
 * What it means *for the vault*, in one breath.
 *
 * Written to answer "so what" rather than to restate the finding. "An accessibility service is
 * enabled" is a fact the user cannot act on; "it can read a password the moment you reveal it" is a
 * decision they can make.
 */
private fun DeviceProbe.flaggedMeaning(): String = when (this) {
    DeviceProbe.SCREEN_READERS ->
        "These can read the text on any screen, including a password the moment you reveal it. " +
            "Bubu blocks screenshots and recording, but it cannot block this - Android gives no " +
            "app a way to opt out. Normal for a screen reader you chose; a red flag for anything you " +
            "do not recognise."

    DeviceProbe.NOTIFICATION_READERS ->
        "These can read every notification, including the one-time codes and sign-in alerts that " +
            "protect your accounts. Your vault is untouched, but a leaked password plus a readable " +
            "code is a full takeover. Smartwatch and car apps need this legitimately."

    DeviceProbe.DEVICE_ADMINS ->
        "An admin app can lock, wipe and control this phone, and cannot be uninstalled until it is " +
            "switched off - which is why apps that want to stay hidden ask for it. Normal on a work " +
            "phone. Not normal for an app you have never heard of."

    DeviceProbe.ROOT_ACCESS ->
        "Root defeats everything else on this list. Any rooted process can read this app's memory " +
            "while the vault is open, which means it can read decrypted passwords. If you did not " +
            "root this phone yourself, treat every password in the vault as exposed and change them " +
            "from a different device."

    DeviceProbe.DEBUG_BRIDGE ->
        "Debugging lets a computer pull data out of this phone. Wireless debugging is the serious " +
            "one: it listens over the network, so it does not need a cable in your hand - anyone on " +
            "the same Wi-Fi can try to connect. Turn it off unless you are actively developing."

    DeviceProbe.DEBUGGER_ATTACHED ->
        "Something is inspecting this app's memory as it runs and can read your secrets straight out " +
            "of it. Close it before unlocking the vault again."

    DeviceProbe.NETWORK_INTERCEPTION ->
        "Your vault never leaves this phone, so this cannot expose a stored password. What it can see " +
            "is the one request Bubu makes - the leaked-password check - and that only ever contains " +
            "five characters of a scrambled fingerprint. Worth knowing if you did not set up the VPN."

    DeviceProbe.SCREEN_LOCK ->
        "There is no PIN, pattern or password on this phone, so anyone holding it is already past the " +
            "front door. Your vault still needs its master passphrase, but fingerprint unlock cannot " +
            "work without a screen lock behind it."

    DeviceProbe.INSTALL_SOURCE ->
        "This copy did not come from the Play Store. Completely normal if you built or sideloaded it " +
            "yourself. If you did not, be sure it is really Bubu Protect - a repackaged password " +
            "manager is the oldest trick there is."

    DeviceProbe.UNTRUSTED_BUILD ->
        "This looks like an emulator or a development build. Key storage may be simulated in software " +
            "rather than protected by real hardware, so the vault is less well defended here than on " +
            "a normal phone."
}

/** The reassurance, when a probe comes back clean. Specific, because "OK" reassures nobody. */
private fun DeviceProbe.clearNote(): String = when (this) {
    DeviceProbe.SCREEN_READERS -> "Nothing here can read text off your screen."
    DeviceProbe.NOTIFICATION_READERS -> "No app is allowed to read your notifications."
    DeviceProbe.DEVICE_ADMINS -> "No app has admin control of this phone."
    DeviceProbe.ROOT_ACCESS -> "No sign of root."
    DeviceProbe.DEBUG_BRIDGE -> "USB and wireless debugging are both off."
    DeviceProbe.DEBUGGER_ATTACHED -> "No debugger is attached to Bubu."
    DeviceProbe.NETWORK_INTERCEPTION -> "No VPN or proxy in the way."
    DeviceProbe.SCREEN_LOCK -> "This phone has a lock of its own."
    DeviceProbe.INSTALL_SOURCE -> "Installed from the Play Store."
    DeviceProbe.UNTRUSTED_BUILD -> "A real device with real hardware key storage."
}

/**
 * The Settings page that fixes a finding.
 *
 * ### Why deep links rather than instructions
 *
 * A finding the user cannot act on is a finding they scroll past. "Settings, then Accessibility, then
 * Downloaded apps, then..." is four chances to give up, and the path is different on every
 * manufacturer's skin. One tap that lands on the right page is the difference between a screen that
 * informs and a screen that protects.
 *
 * [whereToLook] is the fallback wording for ROMs that removed the activity - see [DeviceCheckRoute].
 * Probes with no shortcut return null: nothing in Settings turns off root or detaches a debugger, and
 * a button that leads somewhere useless is worse than no button.
 */
private data class SettingsShortcut(
    val action: String,
    val buttonLabel: String,
    val whereToLook: String
)

private fun DeviceProbe.settingsShortcut(): SettingsShortcut? = when (this) {
    DeviceProbe.SCREEN_READERS -> SettingsShortcut(
        action = Settings.ACTION_ACCESSIBILITY_SETTINGS,
        buttonLabel = "Open accessibility settings",
        whereToLook = "Accessibility"
    )

    DeviceProbe.NOTIFICATION_READERS -> SettingsShortcut(
        action = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        buttonLabel = "Open notification access",
        whereToLook = "Notification access"
    )

    DeviceProbe.DEVICE_ADMINS -> SettingsShortcut(
        action = Settings.ACTION_SECURITY_SETTINGS,
        buttonLabel = "Open security settings",
        whereToLook = "Device admin apps"
    )

    DeviceProbe.DEBUG_BRIDGE -> SettingsShortcut(
        action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        buttonLabel = "Open developer options",
        whereToLook = "Developer options"
    )

    DeviceProbe.NETWORK_INTERCEPTION -> SettingsShortcut(
        action = Settings.ACTION_VPN_SETTINGS,
        buttonLabel = "Open VPN settings",
        whereToLook = "VPN"
    )

    DeviceProbe.SCREEN_LOCK -> SettingsShortcut(
        action = Settings.ACTION_SECURITY_SETTINGS,
        buttonLabel = "Set a screen lock",
        whereToLook = "Screen lock"
    )

    // Nothing in Settings resolves these.
    DeviceProbe.ROOT_ACCESS,
    DeviceProbe.DEBUGGER_ATTACHED,
    DeviceProbe.INSTALL_SOURCE,
    DeviceProbe.UNTRUSTED_BUILD -> null
}

// --- Previews ------------------------------------------------------------------------------------

private fun report(vararg findings: DeviceFinding, at: Long = 1L) =
    DeviceScanReport(findings = findings.toList(), checkedAt = at)

private fun clean(): DeviceScanReport = report(
    *DeviceProbe.entries.map { DeviceFinding(it, ProbeResult.Clear) }.toTypedArray()
)

private fun messy(): DeviceScanReport = report(
    DeviceFinding(
        DeviceProbe.SCREEN_READERS,
        ProbeResult.Flagged(RiskLevel.CRITICAL, listOf("TalkBack", "Honey Keyboard"))
    ),
    DeviceFinding(
        DeviceProbe.DEBUG_BRIDGE,
        ProbeResult.Flagged(RiskLevel.CRITICAL, listOf("Wireless debugging is on"))
    ),
    DeviceFinding(
        DeviceProbe.NOTIFICATION_READERS,
        ProbeResult.Flagged(RiskLevel.WARNING, listOf("Bear Watch"))
    ),
    DeviceFinding(
        DeviceProbe.NETWORK_INTERCEPTION,
        ProbeResult.Flagged(RiskLevel.WARNING, listOf("A VPN tunnel is active"))
    ),
    DeviceFinding(
        DeviceProbe.INSTALL_SOURCE,
        ProbeResult.Flagged(RiskLevel.INFO, listOf("Installed directly, not from a store"))
    ),
    DeviceFinding(DeviceProbe.DEVICE_ADMINS, ProbeResult.Clear),
    DeviceFinding(DeviceProbe.ROOT_ACCESS, ProbeResult.Clear),
    DeviceFinding(DeviceProbe.DEBUGGER_ATTACHED, ProbeResult.Clear),
    DeviceFinding(DeviceProbe.UNTRUSTED_BUILD, ProbeResult.Clear),
    DeviceFinding(
        DeviceProbe.SCREEN_LOCK,
        ProbeResult.Unavailable("This device has no keyguard.")
    )
)

@Composable
private fun PreviewScreen(state: DeviceCheckUiState) {
    BubuProtectTheme {
        DeviceCheckScreen(
            state = state,
            onBack = {},
            onRescan = {},
            onAcknowledge = {},
            onUnacknowledge = {},
            onDismissNotice = {},
            onFix = {}
        )
    }
}

@Preview(showBackground = true, name = "Device check · findings")
@Preview(
    showBackground = true,
    name = "Device check · findings · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Preview(showBackground = true, name = "Device check · findings · tablet", widthDp = 840, heightDp = 900)
@Composable
private fun DeviceCheckFindingsPreview() {
    PreviewScreen(DeviceCheckUiState(report = messy()))
}

@Preview(showBackground = true, name = "Device check · all clear")
@Composable
private fun DeviceCheckCleanPreview() {
    PreviewScreen(DeviceCheckUiState(report = clean()))
}

@Preview(showBackground = true, name = "Device check · acknowledged")
@Composable
private fun DeviceCheckAcknowledgedPreview() {
    val scan = messy()
    PreviewScreen(
        DeviceCheckUiState(
            report = scan,
            acknowledged = scan.flagged.map { it.fingerprint }.toSet()
        )
    )
}

@Preview(showBackground = true, name = "Device check · first scan")
@Composable
private fun DeviceCheckLoadingPreview() {
    PreviewScreen(DeviceCheckUiState(isScanning = true))
}
