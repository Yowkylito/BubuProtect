package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.FieldSlot
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.model.totpSource
import com.personal.bubuprotect.domain.model.TOTP_EXTRA_KEY
import com.personal.bubuprotect.ui.components.BreachBadge
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.BubuSnackbarHost
import com.personal.bubuprotect.ui.components.BubuTopBar
import com.personal.bubuprotect.ui.components.ErrorPane
import com.personal.bubuprotect.ui.components.KindBadge
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.VaultFieldRow
import com.personal.bubuprotect.ui.components.TotpCodeCard
import com.personal.bubuprotect.ui.components.accent
import com.personal.bubuprotect.ui.components.formatExposure
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.HeroCardShape
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.EntryDetailContent
import com.personal.bubuprotect.ui.vm.EntryDetailUiState
import com.personal.bubuprotect.ui.vm.EntryDetailViewModel
import com.personal.bubuprotect.ui.vm.PasswordBreachState
import com.personal.bubuprotect.ui.vm.RevealedField
import com.personal.bubuprotect.ui.vm.TotpDisplay
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun EntryDetailRoute(
    entryId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenBreachReport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntryDetailViewModel = koinViewModel { parametersOf(entryId) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberBiometricGate()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    EntryDetailScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onEdit = { onEdit(entryId) },
        onDelete = viewModel::delete,
        onReveal = { spec -> viewModel.reveal(spec, gate) },
        onHide = viewModel::hide,
        onCopy = { spec -> viewModel.copy(spec, gate) },
        onCheckPasswordBreach = { viewModel.checkPasswordBreach(gate) },
        onShowTotp = { viewModel.showTotpCode(gate) },
        onHideTotp = viewModel::hideTotpCode,
        onCopyTotp = { viewModel.copyTotpCode(gate) },
        onOpenBreachReport = onOpenBreachReport,
        onRetry = viewModel::load,
        modifier = modifier
    )
}

/**
 * One entry, opened.
 *
 * The rows are generated from [VaultEntry.populatedFields], so a card shows expiry, CVV and PIN and
 * a note shows one long body, without this file knowing anything about either. Empty optional fields
 * are not rendered at all - a column of blank labels tells the user nothing and makes the real
 * content harder to find.
 */
@Composable
fun EntryDetailScreen(
    state: EntryDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReveal: (FieldSpec) -> Unit,
    onHide: () -> Unit,
    onCopy: (FieldSpec) -> Unit,
    onCheckPasswordBreach: () -> Unit = {},
    onOpenBreachReport: (() -> Unit)? = null,
    onShowTotp: () -> Unit = {},
    onHideTotp: () -> Unit = {},
    onCopyTotp: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val entry = state.entry

    Column(modifier.fillMaxSize()) {
        BubuTopBar(
            title = entry?.label ?: "Secret details",
            subtitle = entry?.let { "${it.kind.title} · Protected on this device" }
                ?: "Protected on this device",
            leading = {
                BubuIconButton(
                    // AutoMirrored: the manifest declares RTL support, and a back arrow that
                    // still points left in an RTL layout points the wrong way.
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to the vault",
                    onClick = onBack,
                    tonal = true
                )
            },
            actions = {
                if (entry != null) {
                    BubuIconButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "Edit ${entry.label}",
                        onClick = onEdit,
                        tonal = true
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = state.content,
                transitionSpec = {
                    (fadeIn(tween(BubuMotion.MEDIUM)) +
                        scaleIn(tween(BubuMotion.MEDIUM), initialScale = 0.97f)) togetherWith
                        fadeOut(tween(BubuMotion.FAST))
                },
                contentKey = { it::class },
                label = "detailPane",
                modifier = Modifier.fillMaxSize()
            ) { content ->
                when (content) {
                    EntryDetailContent.Loading -> LoadingPane(label = "Unsealing")

                    is EntryDetailContent.Failed -> ErrorPane(
                        message = content.message,
                        onRetry = onRetry.takeUnless { content.isTampering }
                    )

                    is EntryDetailContent.Ready -> EntryFields(
                        entry = content.entry,
                        revealed = state.revealed,
                        onReveal = onReveal,
                        onHide = onHide,
                        onCopy = onCopy,
                        passwordBreach = state.passwordBreach,
                        onCheckPasswordBreach = onCheckPasswordBreach,
                        onOpenBreachReport = onOpenBreachReport,
                        totp = state.totp,
                        onShowTotp = onShowTotp,
                        onHideTotp = onHideTotp,
                        onCopyTotp = onCopyTotp,
                        onRequestDelete = { confirmingDelete = true }
                    )
                }
            }
            BubuSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }

    if (confirmingDelete && entry != null) {
        DeleteConfirmation(
            label = entry.label,
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
            onDismiss = { confirmingDelete = false }
        )
    }
}

@Composable
private fun EntryFields(
    entry: VaultEntry,
    revealed: RevealedField?,
    onReveal: (FieldSpec) -> Unit,
    onHide: () -> Unit,
    onCopy: (FieldSpec) -> Unit,
    passwordBreach: PasswordBreachState,
    onCheckPasswordBreach: () -> Unit,
    onOpenBreachReport: (() -> Unit)?,
    totp: TotpDisplay?,
    onShowTotp: () -> Unit,
    onHideTotp: () -> Unit,
    onCopyTotp: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    /*
     * The 2FA seed is dropped from the generic rows on purpose.
     *
     * Every other field is worth reading as text. This one is an `otpauth://` URI whose only use is
     * to be turned into a code, so rendering it here would put the thing that generates every future
     * code on screen in place of the code the user actually came for. [TotpCodeCard] takes its slot.
     */
    val hasSeed = remember(entry) { entry.totpSource() != null }
    val fields = remember(entry) {
        entry.populatedFields().filterNot { it.slot == FieldSlot.Extra(TOTP_EXTRA_KEY) }
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    ResponsiveContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = BubuSpacing.screen,
                end = BubuSpacing.screen,
                top = BubuSpacing.xs,
                bottom = BubuSpacing.xs + navBottom
            ),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
        ) {
        item(key = "header") {
            EntryHeader(entry)
        }

        // Logins and Wi-Fi keys, not just logins: a network key is a password, and the corpus knows
        // plenty of them. Card numbers and passport numbers still get no card - see
        // ItemKind.supportsBreachCheck.
        if (entry.isBreachCheckable) {
            item(key = "breachCheck") {
                PasswordBreachCard(
                    stored = entry.breach,
                    transient = passwordBreach,
                    onCheck = onCheckPasswordBreach,
                    onOpenReport = onOpenBreachReport
                )
            }
        }

        if (hasSeed) {
            item(key = "totp") {
                TotpCodeCard(
                    display = totp,
                    onShow = onShowTotp,
                    onHide = onHideTotp,
                    onCopy = onCopyTotp
                )
            }
        }

        itemsIndexed(fields, key = { _, spec -> spec.label }) { index, spec ->
            VaultFieldRow(
                label = spec.label,
                value = entry.valueOf(spec),
                isSecret = spec.isSecret,
                isRevealed = revealed?.slot == spec.slot,
                isMultiline = spec.isMultiline,
                secondsRemaining = revealed?.takeIf { it.slot == spec.slot }?.secondsRemaining,
                totalSeconds = EntryDetailViewModel.REVEAL_SECONDS,
                onReveal = if (spec.isSecret) {
                    { onReveal(spec) }
                } else {
                    null
                },
                onHide = if (spec.isSecret) onHide else null,
                onCopy = { onCopy(spec) },
                modifier = Modifier.enterStaggered(index)
            )
        }

        item(key = "footer") {
            Spacer(Modifier.height(BubuSpacing.xs))
            Text(
                text = "Updated ${relativeAge(entry.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(BubuSpacing.xs))
            BubuOutlinedButton(
                text = "Delete this secret",
                onClick = onRequestDelete,
                leadingIcon = Icons.Filled.Delete,
                contentColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
                borderColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(BubuSpacing.sm))
        }
        }
    }
}

/**
 * The stored verdict, plus whatever is happening to it right now.
 *
 * Explicit rather than automatic: opening a local secret must not silently create network traffic.
 * The copy states exactly what leaves the device so consent is informed at the moment it matters.
 *
 * Two sources, on purpose. [stored] survives locks, navigation and reboots, so an entry checked last
 * week still says so. [transient] is only ever "a request is in flight" or "the last one failed" -
 * states that would be a lie if they outlived the screen.
 */
@Composable
private fun PasswordBreachCard(
    stored: BreachStatus,
    transient: PasswordBreachState,
    onCheck: () -> Unit,
    onOpenReport: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isChecking = transient == PasswordBreachState.Checking
    val hasFailed = transient == PasswordBreachState.Failed
    val isCompromised = stored.isBreached && !isChecking
    val isSafe = stored.verdict == BreachVerdict.SAFE && !isChecking

    val containerColor = when {
        isCompromised -> MaterialTheme.colorScheme.errorContainer
        isSafe -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = when {
        isCompromised -> MaterialTheme.colorScheme.onErrorContainer
        isSafe -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val title = when {
        isChecking -> "Checking breach records"
        isCompromised -> "Change this password now"
        hasFailed -> "Could not complete the check"
        isSafe -> "No known breach found"
        else -> "Check for breached passwords"
    }
    val description = when {
        isChecking -> "Comparing the rest of the password hash locally on this device."
        isCompromised ->
            "This password appears ${stored.exposureCount.formatExposure()} in known breach data, " +
                "so it is already on the lists attackers try first. Replace it here and anywhere " +
                "else you used it."
        hasFailed -> "Check your connection and try again. No password or complete hash was sent."
        isSafe ->
            "Checked ${relativeAge(stored.checkedAt)} and not found in known leaks. That is not a " +
                "promise it is strong or unique - only that it has not turned up yet."
        else ->
            "Bubu sends Have I Been Pwned only an anonymous 5-character hash prefix. Your " +
                "password, account, and website never leave this device."
    }
    val buttonText = when {
        isChecking -> "Checking"
        hasFailed -> "Try again"
        stored.verdict == BreachVerdict.UNCHECKED -> "Check password safety"
        else -> "Check again"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(BubuSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (!isChecking) {
                    BreachBadge(status = stored)
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.82f)
            )
            Spacer(Modifier.height(BubuSpacing.xs))
            BubuButton(
                text = buttonText,
                onClick = onCheck,
                isBusy = isChecking,
                busyText = "Checking",
                modifier = Modifier.fillMaxWidth()
            )
            // Only offered when there is something to see. On a clean entry this would be a button
            // to a screen that says "all clear", which is a detour dressed as an action.
            if (isCompromised && onOpenReport != null) {
                BubuOutlinedButton(
                    text = "See everything that needs changing",
                    onClick = onOpenReport,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    borderColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun EntryHeader(entry: VaultEntry, modifier: Modifier = Modifier) {
    val accent = entry.kind.accent()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = BubuSpacing.xs),
        shape = HeroCardShape,
        color = accent.container,
        shadowElevation = BubuElevation.hero,
        border = BorderStroke(1.dp, accent.content.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = BubuSpacing.screen, vertical = BubuSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KindBadge(kind = entry.kind, size = 64.dp)
            Spacer(Modifier.width(BubuSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = accent.content
                )
                Text(
                    text = entry.kind.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent.content.copy(alpha = 0.78f)
                )
            }
        }
    }
}

/**
 * Deleting is the one action here with no undo - the row and its ciphertext are gone, and there is
 * no backup anywhere by design. So it gets a dialog, a named target, and a sad bear.
 */
@Composable
private fun DeleteConfirmation(
    label: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .shadow(BubuElevation.hero, MaterialTheme.shapes.extraLarge)
            .border(
                1.dp,
                MaterialTheme.bubu.cardBorder.copy(alpha = 0.82f),
                MaterialTheme.shapes.extraLarge
            ),
        icon = {
            BubuMascot(
                mood = BubuMood.SULKING,
                size = 96.dp,
                showBackdrop = false,
                contentDescription = null
            )
        },
        title = { Text("Delete “$label”?") },
        text = {
            Text(
                "This cannot be undone. There is no copy of it anywhere else - that is rather the " +
                    "point of the vault."
            )
        },
        confirmButton = {
            BubuOutlinedButton(
                text = "Delete",
                onClick = onConfirm,
                contentColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
                borderColor = MaterialTheme.colorScheme.error
            )
        },
        dismissButton = {
            BubuOutlinedButton(text = "Keep it", onClick = onDismiss)
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    )
}

@Preview(showBackground = true, name = "Card entry")
@Preview(showBackground = true, name = "Card entry · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "Card entry · tablet", widthDp = 840, heightDp = 900)
@Composable
private fun EntryDetailPreview() {
    BubuProtectTheme {
        EntryDetailScreen(
            state = EntryDetailUiState(
                content = EntryDetailContent.Ready(
                    VaultEntry(
                        id = "1",
                        kind = ItemKind.CARD,
                        label = "Travel card",
                        identity = "R. Bear",
                        secret = "4242 4242 4242 4242",
                        extras = mapOf("expiry" to "07/29", "cvv" to "123", "issuer" to "Bubu Bank"),
                        notes = "Only for holidays.",
                        updatedAt = System.currentTimeMillis() - 86_400_000
                    )
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onEdit = {},
            onDelete = {},
            onReveal = {},
            onHide = {},
            onCopy = {}
        )
    }
}

@Preview(showBackground = true, name = "Compromised login")
@Preview(
    showBackground = true,
    name = "Compromised login · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun CompromisedLoginPreview() {
    BubuProtectTheme {
        EntryDetailScreen(
            state = EntryDetailUiState(
                content = EntryDetailContent.Ready(
                    VaultEntry(
                        id = "login",
                        kind = ItemKind.LOGIN,
                        label = "Bear mail",
                        identity = "bubu@example.com",
                        secret = "not-shown",
                        website = "example.com",
                        breach = BreachStatus(
                            verdict = BreachVerdict.BREACHED,
                            exposureCount = 3_861_493L,
                            checkedAt = System.currentTimeMillis() - 3_600_000
                        ),
                        updatedAt = System.currentTimeMillis() - 86_400_000
                    )
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onEdit = {},
            onDelete = {},
            onReveal = {},
            onHide = {},
            onCopy = {}
        )
    }
}

@Preview(showBackground = true, name = "Tampered")
@Composable
private fun EntryDetailTamperedPreview() {
    BubuProtectTheme {
        EntryDetailScreen(
            state = EntryDetailUiState(
                content = EntryDetailContent.Failed(
                    "This entry failed its integrity check, so Bubu will not show it.",
                    isTampering = true
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onEdit = {},
            onDelete = {},
            onReveal = {},
            onHide = {},
            onCopy = {}
        )
    }
}
