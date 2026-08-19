package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.nfc.NfcCardScanner
import com.personal.bubuprotect.domain.model.FieldSlot
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.ScannedCard
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuTopBar
import com.personal.bubuprotect.ui.components.ErrorPane
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.NfcCardScanSheet
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.components.SecretStrengthMeter
import com.personal.bubuprotect.ui.components.VaultTextField
import com.personal.bubuprotect.ui.components.accent
import com.personal.bubuprotect.ui.components.iconRes
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.motion.squish
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.EntryEditorUiState
import com.personal.bubuprotect.ui.vm.EntryEditorViewModel
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * @param onDiscard left without writing anything.
 * @param onSaved the write landed. Kept separate from [onDiscard] so the caller can refresh only
 *   when there is actually something new to read - closing the editor with the X must not make the
 *   screen underneath re-decrypt an entry that did not change.
 */
@Composable
fun EntryEditorRoute(
    entryId: String?,
    onDiscard: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntryEditorViewModel = koinViewModel { parametersOf(entryId) },
    scanner: NfcCardScanner = koinInject()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gate = rememberBiometricGate()

    // Asked once, here rather than inside the screen, so [EntryEditorScreen] stays previewable -
    // a Koin lookup in the screen body would take every @Preview in this file down with it.
    val isNfcAvailable = remember(context) { scanner.isSupported(context) }

    EntryEditorScreen(
        state = state,
        onLabelChange = viewModel::onLabelChange,
        onKindChange = viewModel::onKindChange,
        onFieldChange = viewModel::onFieldChange,
        onToggleReveal = { slot ->
            state.fields.firstOrNull { it.slot == slot }?.let { spec ->
                viewModel.requestReveal(spec, gate)
            }
        },
        onGenerate = { viewModel.generateSecret() },
        onCardScanned = viewModel::onCardScanned,
        isNfcAvailable = isNfcAvailable,
        onSave = viewModel::save,
        onRetry = viewModel::retryLoad,
        onClose = onDiscard,
        onSaveAnimationFinished = onSaved,
        modifier = modifier
    )
}

/**
 * Add and edit, for every kind.
 *
 * The form is rendered from [EntryEditorUiState.fields], which comes from the selected
 * [ItemKind] - so this file contains no reference to a CVV or an SSID, and a new kind appears here
 * with no change at all.
 */
@Composable
fun EntryEditorScreen(
    state: EntryEditorUiState,
    onLabelChange: (String) -> Unit,
    onKindChange: (ItemKind) -> Unit,
    onFieldChange: (FieldSlot, String) -> Unit,
    onToggleReveal: (FieldSlot) -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit = {},
    onClose: () -> Unit,
    onSaveAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onCardScanned: (ScannedCard) -> Unit = {},
    /**
     * Whether this phone has an NFC controller at all. Defaulted off so previews - which have no
     * Koin graph to ask - render the form exactly as a non-NFC device would.
     */
    isNfcAvailable: Boolean = false
) {
    // Purely presentational, so it stays here rather than in the ViewModel: nothing about a sheet
    // being open survives a process death worth restoring, and routing it through state would make
    // the editor's state class describe its own UI.
    var isScanning by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        BubuTopBar(
            title = if (state.isNewEntry) "A new secret" else "Refine this secret",
            subtitle = "${state.kind.title} · Sealed on this device",
            leading = {
                BubuIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Discard and go back",
                    onClick = onClose,
                    tonal = true
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
        ) {
            when {
                state.isLoading -> LoadingPane(label = "Unsealing")

                state.loadError != null -> ErrorPane(message = state.loadError, onRetry = onRetry)

                else -> ResponsiveContainer(Modifier.fillMaxSize()) {
                    EditorForm(
                        state = state,
                        onLabelChange = onLabelChange,
                        onKindChange = onKindChange,
                        onFieldChange = onFieldChange,
                        onToggleReveal = onToggleReveal,
                        onGenerate = onGenerate,
                        canScanCard = state.canScanCard && isNfcAvailable,
                        onScanCard = { isScanning = true },
                        modifier = Modifier.weight(1f)
                    )
                    SaveDock(
                        isNewEntry = state.isNewEntry,
                        saveError = state.saveError,
                        enabled = state.isSubmitEnabled,
                        isBusy = state.isSaving,
                        onSave = onSave
                    )
                }
            }

            // The celebration is the confirmation. It covers the form so the user cannot double-save
            // during the beat before navigation, and it holds long enough to be read as "yes, done".
            SaveCelebration(
                visible = state.savedEntryId != null,
                onFinished = onSaveAnimationFinished
            )
        }
    }

    // Composed only while open, which is also what scopes NFC reader mode to the sheet's lifetime.
    if (isScanning) {
        NfcCardScanSheet(
            onScanned = onCardScanned,
            onDismiss = { isScanning = false }
        )
    }
}

@Composable
private fun EditorForm(
    state: EntryEditorUiState,
    onLabelChange: (String) -> Unit,
    onKindChange: (ItemKind) -> Unit,
    onFieldChange: (FieldSlot, String) -> Unit,
    onToggleReveal: (FieldSlot) -> Unit,
    onGenerate: () -> Unit,
    canScanCard: Boolean,
    onScanCard: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = BubuSpacing.xs,
            bottom = BubuSpacing.lg
        ),
        verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
    ) {
        if (state.canChangeKind) {
            item(key = "kinds") {
                KindPicker(selected = state.kind, onSelect = onKindChange)
            }
        }

        item(key = "label") {
            VaultTextField(
                value = state.label,
                onValueChange = onLabelChange,
                label = "What is this?",
                errorText = state.labelError,
                supportingText = "The only part Bubu shows without asking for your fingerprint",
                imeAction = ImeAction.Next,
                modifier = Modifier.padding(horizontal = BubuSpacing.screen)
            )
        }

        itemsIndexed(state.fields, key = { _, spec -> spec.label }) { index, spec ->
            Column(modifier = Modifier.enterStaggered(index).padding(horizontal = BubuSpacing.screen)) {
                VaultTextField(
                    value = state.valueOf(spec),
                    onValueChange = { onFieldChange(spec.slot, it) },
                    label = spec.label,
                    isSecret = spec.isSecret,
                    isVisible = state.isRevealed(spec),
                    onVisibilityToggle = if (spec.isSecret && !spec.isMultiline) {
                        { onToggleReveal(spec.slot) }
                    } else {
                        null
                    },
                    isMultiline = spec.isMultiline,
                    keyboard = spec.keyboard,
                    errorText = state.errorFor(spec),
                    supportingText = spec.hint?.let { "e.g. $it" },
                    imeAction = if (index == state.fields.lastIndex) ImeAction.Done else ImeAction.Next,
                    // The primary secret gets whichever shortcut its kind has - a generator for a
                    // password, a card reader for a card number. Never both: no kind has both, and
                    // two icons in one field would crowd out the reveal toggle beside them.
                    trailingSlot = when {
                        spec.slot != FieldSlot.Secret -> null
                        state.canGenerateSecret -> {
                            { GenerateButton(onGenerate) }
                        }
                        canScanCard -> {
                            { ScanCardButton(onScanCard) }
                        }
                        else -> null
                    }
                )

                // Only under the primary secret, and only where a generated password makes sense -
                // a card number has no "strength" and scoring one would be noise.
                AnimatedVisibility(
                    visible = spec.slot == FieldSlot.Secret &&
                        state.canGenerateSecret &&
                        state.valueOf(spec).isNotEmpty(),
                    enter = fadeIn(tween(BubuMotion.FAST)) + expandVertically(tween(BubuMotion.FAST)),
                    exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
                ) {
                    SecretStrengthMeter(
                        secret = state.valueOf(spec),
                        modifier = Modifier.padding(horizontal = BubuSpacing.screen+4.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveDock(
    isNewEntry: Boolean,
    saveError: String?,
    enabled: Boolean,
    isBusy: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = BubuElevation.floating,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = BubuSpacing.screen, vertical = BubuSpacing.sm)
        ) {
            AnimatedVisibility(
                visible = saveError != null,
                enter = fadeIn(tween(BubuMotion.FAST)) + expandVertically(tween(BubuMotion.FAST)),
                exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
            ) {
                Text(
                    text = saveError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = BubuSpacing.xs)
                )
            }
            BubuButton(
                text = if (isNewEntry) "Give it to Bubu" else "Save changes",
                onClick = onSave,
                enabled = enabled,
                isBusy = isBusy,
                busyText = "Sealing",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The kind picker.
 *
 * Icons plus names rather than a dropdown: picking the kind is the first decision and it changes the
 * whole form underneath, so it should be visible and one tap away, not hidden behind a menu.
 */
@Composable
private fun KindPicker(
    selected: ItemKind,
    onSelect: (ItemKind) -> Unit,
    modifier: Modifier = Modifier
) {
    val kinds = remember { ItemKind.entries.toList() }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "What kind of secret?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = BubuSpacing.xs)
                .padding(horizontal = BubuSpacing.screen)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = BubuSpacing.sm)) {
            items(kinds, key = { it.storageKey }) { kind ->
                KindCard(
                    kind = kind,
                    isSelected = kind == selected,
                    onClick = { onSelect(kind) }
                )
                Spacer(Modifier.width(BubuSpacing.sm))
            }
        }
        Spacer(Modifier.height(BubuSpacing.xs))
        Text(
            text = selected.tagline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier= Modifier.padding(horizontal = BubuSpacing.screen)
        )
    }
}

@Composable
private fun KindCard(
    kind: ItemKind,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = kind.accent()
    val interactionSource = remember { MutableInteractionSource() }

    val container by animateColorAsState(
        targetValue = if (isSelected) accent.container else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(BubuMotion.MEDIUM),
        label = "kindContainer"
    )
    val content by animateColorAsState(
        targetValue = if (isSelected) accent.content else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(BubuMotion.MEDIUM),
        label = "kindContent"
    )
    // Selection lifts the card. Read inside graphicsLayer so the spring never leaves the draw phase.
    val lift = animateFloatAsState(
        targetValue = if (isSelected) 1.06f else 1f,
        animationSpec = BubuMotion.Playful,
        label = "kindLift"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = lift.value
                scaleY = lift.value
            }
            .shadow(
                elevation = if (isSelected) BubuElevation.hero else BubuElevation.card,
                shape = MaterialTheme.shapes.large
            )
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .border(
                width = 1.dp,
                color = if (isSelected) {
                    accent.content.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.bubu.cardBorder.copy(alpha = 0.68f)
                },
                shape = MaterialTheme.shapes.large
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .squish(interactionSource, pressedScale = 0.9f)
            .width(96.dp)
            .padding(vertical = 14.dp)
            // One node for the whole card, announced as a selectable option.
            .semantics(mergeDescendants = true) {
                contentDescription = kind.title
                role = Role.RadioButton
                this.selected = isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(kind.iconRes),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = kind.title,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun ScanCardButton(onScan: () -> Unit) {
    BubuIconButton(
        icon = ImageVector.vectorResource(R.drawable.ic_nfc),
        // Says what it reads and what it cannot, because a user who expects the CVV to arrive with
        // it will read the blank field as a broken scan.
        contentDescription = "Tap your card to fill in its number and expiry",
        onClick = onScan,
        tonal = true,
        tint = MaterialTheme.bubu.champagne
    )
}

@Composable
private fun GenerateButton(onGenerate: () -> Unit) {
    BubuIconButton(
        icon = ImageVector.vectorResource(R.drawable.ic_dice),
        contentDescription = "Let Bubu invent a strong password",
        onClick = onGenerate,
        tonal = true,
        tint = MaterialTheme.bubu.champagne
    )
}

/**
 * The save confirmation.
 *
 * Deliberately timed rather than instant. Navigating away the microsecond the write returns leaves
 * the user unsure whether anything happened; a held beat with a celebrating bear is unambiguous, and
 * it is short enough not to be in the way.
 */
@Composable
private fun SaveCelebration(
    visible: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(visible) {
        if (visible) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            delay(CELEBRATION_MILLIS)
            onFinished()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(BubuMotion.FAST)) + scaleIn(BubuMotion.Bouncy, initialScale = 0.6f),
        exit = fadeOut(tween(BubuMotion.FAST)) + scaleOut(tween(BubuMotion.FAST)),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.bubu.champagneContainer.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BubuMascot(mood = BubuMood.CELEBRATING, size = 170.dp, contentDescription = null)
                Spacer(Modifier.height(BubuSpacing.md))
                Text(
                    text = "Sealed and safe",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(BubuSpacing.xxs))
                Text(
                    text = "Bubu and Dudu have it now",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val CELEBRATION_MILLIS = 900L

@Preview(showBackground = true, name = "New login")
@Preview(showBackground = true, name = "New login · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, name = "New login · large text", fontScale = 1.3f)
@Composable
private fun EntryEditorNewPreview() {
    BubuProtectTheme {
        EntryEditorScreen(
            state = EntryEditorUiState(
                isLoading = false,
                isNewEntry = true,
                kind = ItemKind.LOGIN,
                label = "Gmail",
                values = mapOf(
                    FieldSlot.Identity to "bubu@example.com",
                    FieldSlot.Secret to "Zq7-mK2p!vT9xR4w"
                ),
                revealedSlots = setOf(FieldSlot.Secret)
            ),
            onLabelChange = {},
            onKindChange = {},
            onFieldChange = { _, _ -> },
            onToggleReveal = {},
            onGenerate = {},
            onSave = {},
            onClose = {},
            onSaveAnimationFinished = {}
        )
    }
}

/** With NFC present, so the card-number row shows its scan action. */
@Preview(showBackground = true, name = "New card · NFC phone")
@Composable
private fun EntryEditorCardPreview() {
    BubuProtectTheme {
        EntryEditorScreen(
            state = EntryEditorUiState(
                isLoading = false,
                isNewEntry = true,
                kind = ItemKind.CARD,
                label = ""
            ),
            onLabelChange = {},
            onKindChange = {},
            onFieldChange = { _, _ -> },
            onToggleReveal = {},
            onGenerate = {},
            onSave = {},
            onClose = {},
            onSaveAnimationFinished = {},
            isNfcAvailable = true
        )
    }
}

/** The same form on a phone with no NFC: no scan action, no dead affordance. */
@Preview(showBackground = true, name = "New card · no NFC")
@Composable
private fun EntryEditorCardNoNfcPreview() {
    BubuProtectTheme {
        EntryEditorScreen(
            state = EntryEditorUiState(
                isLoading = false,
                isNewEntry = true,
                kind = ItemKind.CARD,
                label = "Travel card",
                values = mapOf(FieldSlot.Secret to "4242424242424242"),
                revealedSlots = setOf(FieldSlot.Secret)
            ),
            onLabelChange = {},
            onKindChange = {},
            onFieldChange = { _, _ -> },
            onToggleReveal = {},
            onGenerate = {},
            onSave = {},
            onClose = {},
            onSaveAnimationFinished = {}
        )
    }
}

@Preview(showBackground = true, name = "Validation shown")
@Composable
private fun EntryEditorInvalidPreview() {
    BubuProtectTheme {
        EntryEditorScreen(
            state = EntryEditorUiState(
                isLoading = false,
                isNewEntry = true,
                kind = ItemKind.WIFI,
                showValidation = true
            ),
            onLabelChange = {},
            onKindChange = {},
            onFieldChange = { _, _ -> },
            onToggleReveal = {},
            onGenerate = {},
            onSave = {},
            onClose = {},
            onSaveAnimationFinished = {}
        )
    }
}
