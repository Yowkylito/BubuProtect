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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import com.personal.bubuprotect.domain.model.FieldSlot
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.ErrorPane
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.SecretStrengthMeter
import com.personal.bubuprotect.ui.components.VaultTextField
import com.personal.bubuprotect.ui.components.accent
import com.personal.bubuprotect.ui.components.iconRes
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.motion.squish
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.vm.EntryEditorUiState
import com.personal.bubuprotect.ui.vm.EntryEditorViewModel
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
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
    viewModel: EntryEditorViewModel = koinViewModel { parametersOf(entryId) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EntryEditorScreen(
        state = state,
        onLabelChange = viewModel::onLabelChange,
        onKindChange = viewModel::onKindChange,
        onFieldChange = viewModel::onFieldChange,
        onToggleReveal = viewModel::toggleReveal,
        onGenerate = { viewModel.generateSecret() },
        onSave = viewModel::save,
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorScreen(
    state: EntryEditorUiState,
    onLabelChange: (String) -> Unit,
    onKindChange: (ItemKind) -> Unit,
    onFieldChange: (FieldSlot, String) -> Unit,
    onToggleReveal: (FieldSlot) -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onSaveAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isNewEntry) "Something new" else "Edit",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    BubuIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Discard and go back",
                        onClick = onClose
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                state.isLoading -> LoadingPane(label = "Unsealing")

                state.loadError != null -> ErrorPane(message = state.loadError)

                else -> EditorForm(
                    state = state,
                    onLabelChange = onLabelChange,
                    onKindChange = onKindChange,
                    onFieldChange = onFieldChange,
                    onToggleReveal = onToggleReveal,
                    onGenerate = onGenerate,
                    onSave = onSave
                )
            }

            // The celebration is the confirmation. It covers the form so the user cannot double-save
            // during the beat before navigation, and it holds long enough to be read as "yes, done".
            SaveCelebration(
                visible = state.savedEntryId != null,
                onFinished = onSaveAnimationFinished
            )
        }
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
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                imeAction = ImeAction.Next
            )
        }

        itemsIndexed(state.fields, key = { _, spec -> spec.label }) { index, spec ->
            Column(modifier = Modifier.enterStaggered(index)) {
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
                    trailingSlot = if (spec.slot == FieldSlot.Secret && state.canGenerateSecret) {
                        { GenerateButton(onGenerate) }
                    } else {
                        null
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
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }
        }

        item(key = "save") {
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(
                visible = state.saveError != null,
                enter = fadeIn(tween(BubuMotion.FAST)) + expandVertically(tween(BubuMotion.FAST)),
                exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
            ) {
                Text(
                    text = state.saveError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            BubuButton(
                text = if (state.isNewEntry) "Give it to Bubu" else "Save changes",
                onClick = onSave,
                enabled = state.isSubmitEnabled,
                isBusy = state.isSaving,
                busyText = "Sealing",
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            )
            Spacer(Modifier.height(24.dp))
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
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(kinds, key = { it.storageKey }) { kind ->
                KindCard(
                    kind = kind,
                    isSelected = kind == selected,
                    onClick = { onSelect(kind) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = selected.tagline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
        targetValue = if (isSelected) accent.container else MaterialTheme.colorScheme.surfaceContainer,
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
            .clip(MaterialTheme.shapes.large)
            .background(container)
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
private fun GenerateButton(onGenerate: () -> Unit) {
    IconButton(onClick = onGenerate) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_dice),
            contentDescription = "Let Bubu invent a strong password",
            tint = MaterialTheme.colorScheme.secondary
        )
    }
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
    LaunchedEffect(visible) {
        if (visible) {
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
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                BubuMascot(mood = BubuMood.CELEBRATING, size = 170.dp, contentDescription = null)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Sealed and safe",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

private const val CELEBRATION_MILLIS = 900L

@Preview(showBackground = true, name = "New login")
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

@Preview(showBackground = true, name = "New card")
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
