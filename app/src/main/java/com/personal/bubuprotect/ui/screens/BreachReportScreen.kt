package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.core.security.BreachScanFailure
import com.personal.bubuprotect.core.security.BreachScanState
import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.ui.components.BreachBadge
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.BubuTopBar
import com.personal.bubuprotect.ui.components.KindBadge
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.formatExposure
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.HeroCardShape
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.theme.bubu

/**
 * Everything the vault knows about its own exposure, in one place.
 *
 * ### Why this is a destination and not a filter on the vault list
 *
 * A breached password is a *task*, not a category. The vault list answers "where is my Netflix
 * password"; this answers "what do I have to go and change tonight", and those want different
 * shapes - this one is ordered by severity rather than alphabetically, leads with a count, and its
 * rows are jobs to work through rather than things to open.
 *
 * ### Why unchecked entries are shown at all
 *
 * An empty breached list is only good news if something has actually looked. The second section
 * exists so "nothing found" can never be mistaken for "nothing checked", and so the one action that
 * fixes that - running a scan - is on the same screen as the doubt it resolves.
 */
@Composable
fun BreachReportScreen(
    items: List<VaultItem>,
    scanState: BreachScanState,
    onBack: () -> Unit,
    onOpenEntry: (VaultItem) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Severity order, not alphabetical: the password leaked four million times is the one to change
    // first, and making the user find it in a list sorted by name is making them do triage by hand.
    val breached = remember(items) {
        items.filter { it.breach.isBreached }
            .sortedByDescending { it.breach.exposureCount }
    }
    val checkable = remember(items) { items.filter { it.isBreachCheckable } }
    val unchecked = remember(checkable) {
        checkable.filter { it.breach.verdict == BreachVerdict.UNCHECKED }
    }
    val safe = remember(checkable) {
        checkable.count { it.breach.verdict == BreachVerdict.SAFE }
    }
    val isScanning = scanState is BreachScanState.Running
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier.fillMaxSize()) {
        BubuTopBar(
            title = "Security check",
            subtitle = "Passwords and Wi-Fi keys in known leaks",
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
                item(key = "hero") {
                    ReportHero(
                        breachedCount = breached.size,
                        safeCount = safe,
                        uncheckedCount = unchecked.size,
                        checkableCount = checkable.size,
                        scanState = scanState
                    )
                }

                if (breached.isNotEmpty()) {
                    item(key = "breachedHeader") {
                        SectionHeader(
                            title = "Change these",
                            detail = "Already published. Replace each one wherever you used it."
                        )
                    }
                    itemsIndexed(breached, key = { _, item -> "breached-${item.id}" }) { index, item ->
                        ReportRow(
                            item = item,
                            onClick = { onOpenEntry(item) },
                            modifier = Modifier.enterStaggered(index)
                        )
                    }
                }

                if (unchecked.isNotEmpty()) {
                    item(key = "uncheckedHeader") {
                        SectionHeader(
                            title = if (breached.isEmpty()) {
                                "Not checked yet"
                            } else {
                                "Also not checked yet"
                            },
                            detail = "Bubu has never looked these up. That is not the same as safe."
                        )
                    }
                    itemsIndexed(
                        unchecked.take(UNCHECKED_SHOWN),
                        key = { _, item -> "unchecked-${item.id}" }
                    ) { _, item ->
                        ReportRow(item = item, onClick = { onOpenEntry(item) })
                    }
                    if (unchecked.size > UNCHECKED_SHOWN) {
                        item(key = "uncheckedMore") {
                            Text(
                                text = "and ${unchecked.size - UNCHECKED_SHOWN} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = BubuSpacing.xs)
                            )
                        }
                    }
                }

                item(key = "actions") {
                    Spacer(Modifier.height(BubuSpacing.xs))
                    ScanControls(
                        scanState = scanState,
                        isScanning = isScanning,
                        hasAnythingToCheck = checkable.isNotEmpty(),
                        hasUnchecked = unchecked.isNotEmpty(),
                        onScan = onScan
                    )
                }

                item(key = "privacy") {
                    PrivacyNote()
                }
            }
        }
    }
}

/**
 * The one number the user came for, and the honest denominator underneath it.
 *
 * The mascot is doing real work here rather than decoration: on a screen that is otherwise a list of
 * bad news, a calm bear is the difference between "here is a task" and "you have been hacked". The
 * copy never uses fear language for the same reason - the finding is alarming enough on its own, and
 * a user who panics changes one password badly instead of several well.
 */
@Composable
private fun ReportHero(
    breachedCount: Int,
    safeCount: Int,
    uncheckedCount: Int,
    checkableCount: Int,
    scanState: BreachScanState,
    modifier: Modifier = Modifier
) {
    val isClean = breachedCount == 0 && uncheckedCount == 0 && checkableCount > 0
    val mood = when {
        breachedCount > 0 -> BubuMood.WORRIED
        isClean -> BubuMood.CELEBRATING
        else -> BubuMood.GUARDING
    }
    val accentContainer = when {
        breachedCount > 0 -> MaterialTheme.colorScheme.errorContainer
        isClean -> MaterialTheme.bubu.card.container
        else -> MaterialTheme.bubu.champagneContainer
    }
    val accentContent = when {
        breachedCount > 0 -> MaterialTheme.colorScheme.onErrorContainer
        isClean -> MaterialTheme.bubu.card.content
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = HeroCardShape,
        color = accentContainer,
        contentColor = accentContent,
        shadowElevation = BubuElevation.hero,
        border = BorderStroke(1.dp, accentContent.copy(alpha = 0.16f))
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
                    checkableCount == 0 -> "Nothing here to check"
                    breachedCount == 1 -> "1 password to change"
                    breachedCount > 1 -> "$breachedCount passwords to change"
                    isClean -> "All clear"
                    else -> "Not checked yet"
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(BubuSpacing.xxs))
            Text(
                text = when {
                    checkableCount == 0 ->
                        "Breach lists are lists of passwords. Cards, notes and ID documents are " +
                            "not looked up, so there is nothing to compare."
                    breachedCount > 0 ->
                        "Out of $checkableCount password${checkableCount.s()} in the vault. " +
                            "$safeCount clean, $uncheckedCount never checked."
                    isClean ->
                        "All $checkableCount password${checkableCount.s()} were checked and none " +
                            "appear in known leaks."
                    else ->
                        "$uncheckedCount of $checkableCount password${checkableCount.s()} have " +
                            "never been looked up."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = accentContent.copy(alpha = 0.82f),
                textAlign = TextAlign.Center
            )

            AnimatedVisibility(
                visible = scanState is BreachScanState.Running,
                enter = fadeIn(tween(BubuMotion.FAST)),
                exit = fadeOut(tween(BubuMotion.FAST))
            ) {
                val running = scanState as? BreachScanState.Running
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(BubuSpacing.sm))
                    ScanProgressBar(fraction = running?.fraction ?: 0f, tint = accentContent)
                    Spacer(Modifier.height(BubuSpacing.xxs))
                    Text(
                        text = "Checked ${running?.completed ?: 0} of ${running?.total ?: 0}",
                        style = MaterialTheme.typography.labelMedium,
                        color = accentContent.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/**
 * Progress as a draw-phase animation.
 *
 * The fraction is read inside the [Canvas] lambda rather than in the composable body, so a scan of
 * forty entries invalidates one 6dp strip per step instead of recomposing the hero card - see
 * [com.personal.bubuprotect.ui.motion.BubuMotion] for why that distinction is the whole budget.
 */
@Composable
private fun ScanProgressBar(
    fraction: Float,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val progress = animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(BubuMotion.MEDIUM),
        label = "scanProgress"
    )
    val track = tint.copy(alpha = 0.22f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        drawLine(
            color = track,
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            cap = StrokeCap.Round
        )
        if (progress.value > 0f) {
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                end = androidx.compose.ui.geometry.Offset(
                    size.width * progress.value,
                    size.height / 2
                ),
                strokeWidth = size.height,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String, modifier: Modifier = Modifier) {
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

@Composable
private fun ReportRow(
    item: VaultItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (item.breach.isBreached) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.bubu.cardBorder
    }
    val age = remember(item.breach.checkedAt, item.updatedAt) {
        if (item.breach.checkedAt > 0L) {
            "checked ${relativeAge(item.breach.checkedAt)}"
        } else {
            ""
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = BubuElevation.card,
        border = BorderStroke(1.dp, accent.copy(alpha = if (item.breach.isBreached) 0.4f else 0.72f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .semantics {
                    role = Role.Button
                    contentDescription = if (item.breach.isBreached) {
                        "Open ${item.label}, found in known breach data " +
                            "${item.breach.exposureCount.formatExposure()}"
                    } else {
                        "Open ${item.label}, never checked against breach data"
                    }
                }
                .clickable(onClick = onClick)
        ) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(
                        accent,
                        RoundedCornerShape(
                            topStart = BubuSpacing.lg,
                            bottomStart = BubuSpacing.lg
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(BubuSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KindBadge(kind = item.kind, size = 40.dp)
                Spacer(Modifier.width(BubuSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            item.subtitle.takeIf { it.isNotBlank() } ?: item.kind.title,
                            age.takeIf { it.isNotEmpty() }
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(BubuSpacing.xxs))
                    BreachBadge(status = item.breach, showExposureCount = true)
                }
            }
        }
    }
}

@Composable
private fun ScanControls(
    scanState: BreachScanState,
    isScanning: Boolean,
    hasAnythingToCheck: Boolean,
    hasUnchecked: Boolean,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!hasAnythingToCheck) return

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
    ) {
        BubuButton(
            text = if (hasUnchecked) "Check the rest" else "Check them all again",
            onClick = onScan,
            isBusy = isScanning,
            busyText = "Checking",
            modifier = Modifier.fillMaxWidth()
        )

        val message = when (scanState) {
            is BreachScanState.Finished -> when {
                scanState.checked == 0 && scanState.skipped == 0 ->
                    "Everything already had a recent result, so nothing needed re-checking."
                scanState.skipped > 0 ->
                    "Checked ${scanState.checked}. ${scanState.skipped} could not be reached and " +
                        "are still unchecked."
                scanState.breached > 0 ->
                    "Checked ${scanState.checked}. ${scanState.breached} turned up in leaked data."
                else -> "Checked ${scanState.checked}. None appear in known leaks."
            }

            is BreachScanState.Failed -> when (scanState.reason) {
                BreachScanFailure.UNREACHABLE ->
                    "Could not reach the breach service. Nothing was sent beyond the checks that " +
                        "completed, and no password ever leaves this device."
                BreachScanFailure.VAULT_LOCKED ->
                    "The vault locked partway through, so the check stopped there."
            }

            else -> null
        }

        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/**
 * Says exactly what leaves the device, on the screen where the user is deciding whether to let it.
 * Buried in a settings page it would be a disclaimer; here it is informed consent.
 */
@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
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
                    text = "How the check stays private",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(BubuSpacing.xxs))
                Text(
                    text = "Bubu scrambles each password into a fingerprint and sends only the " +
                        "first five characters of it. Thousands of unrelated passwords share " +
                        "those five characters, so the service cannot tell which one is yours. " +
                        "The rest of the comparison happens on your phone. Your passwords, " +
                        "usernames and websites never leave it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun Int.s(): String = if (this == 1) "" else "s"

private const val UNCHECKED_SHOWN = 5

// --- Previews ------------------------------------------------------------------------------------

private fun previewItems(): List<VaultItem> = listOf(
    VaultItem(
        id = "1",
        kind = ItemKind.LOGIN,
        label = "Bear mail",
        subtitle = "bubu@example.com",
        breach = BreachStatus(BreachVerdict.BREACHED, 3_861_493L, checkedAt = 1L),
        updatedAt = 1L
    ),
    VaultItem(
        id = "2",
        kind = ItemKind.LOGIN,
        label = "Honey shop",
        subtitle = "bubu@example.com",
        breach = BreachStatus(BreachVerdict.BREACHED, 812L, checkedAt = 1L),
        updatedAt = 1L
    ),
    VaultItem(
        id = "3",
        kind = ItemKind.LOGIN,
        label = "Bank",
        subtitle = "r.bear",
        breach = BreachStatus(BreachVerdict.SAFE, checkedAt = 1L),
        updatedAt = 1L
    ),
    VaultItem(
        id = "4",
        kind = ItemKind.WIFI,
        label = "Home Wi-Fi",
        subtitle = "DuduNet",
        updatedAt = 1L
    ),
    VaultItem(id = "5", kind = ItemKind.CARD, label = "Travel card", subtitle = "R. Bear")
)

@Preview(showBackground = true, name = "Breach report")
@Preview(
    showBackground = true,
    name = "Breach report · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Preview(showBackground = true, name = "Breach report · tablet", widthDp = 840, heightDp = 900)
@Composable
private fun BreachReportPreview() {
    BubuProtectTheme {
        BreachReportScreen(
            items = previewItems(),
            scanState = BreachScanState.Idle,
            onBack = {},
            onOpenEntry = {},
            onScan = {}
        )
    }
}

@Preview(showBackground = true, name = "Breach report · scanning")
@Composable
private fun BreachReportScanningPreview() {
    BubuProtectTheme {
        BreachReportScreen(
            items = previewItems(),
            scanState = BreachScanState.Running(completed = 2, total = 4),
            onBack = {},
            onOpenEntry = {},
            onScan = {}
        )
    }
}

@Preview(showBackground = true, name = "Breach report · all clear")
@Composable
private fun BreachReportCleanPreview() {
    BubuProtectTheme {
        BreachReportScreen(
            items = previewItems().map {
                if (it.isBreachCheckable) {
                    it.copy(breach = BreachStatus(BreachVerdict.SAFE, checkedAt = 1L))
                } else {
                    it
                }
            },
            scanState = BreachScanState.Finished(checked = 4, breached = 0, skipped = 0),
            onBack = {},
            onOpenEntry = {},
            onScan = {}
        )
    }
}

@Preview(showBackground = true, name = "Breach report · never scanned")
@Composable
private fun BreachReportUncheckedPreview() {
    BubuProtectTheme {
        BreachReportScreen(
            items = previewItems().map { it.copy(breach = BreachStatus.Unchecked) },
            scanState = BreachScanState.Idle,
            onBack = {},
            onOpenEntry = {},
            onScan = {}
        )
    }
}
