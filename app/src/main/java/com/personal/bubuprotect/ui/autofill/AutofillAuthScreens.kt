package com.personal.bubuprotect.ui.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.R
import com.personal.bubuprotect.core.autofill.AutofillTarget
import com.personal.bubuprotect.core.autofill.CapturedCredential
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.ui.components.KindBadge
import com.personal.bubuprotect.ui.screens.UnlockScreen
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.vm.UnlockViewModel
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
import org.koin.androidx.compose.koinViewModel

/**
 * Routes [Stage] to a screen.
 *
 * [Stage.Working] renders nothing at all, and the activity's theme is translucent, so the common
 * path - tap a suggestion, get a field filled - never puts a window over the app the user is
 * actually using.
 */
@Composable
internal fun AutofillAuthHost(
    stage: Stage,
    onPick: (VaultItem) -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (stage) {
        Stage.Working -> Unit

        Stage.Unlocking -> AutofillUnlockPane(modifier = modifier)

        is Stage.Picking -> AutofillPickerScreen(
            targetName = stage.targetName,
            items = stage.items,
            onPick = onPick,
            onCancel = onCancel,
            modifier = modifier
        )

        is Stage.Saving -> AutofillSavePrompt(
            capture = stage.capture,
            onSave = onSave,
            onDismiss = onCancel,
            modifier = modifier
        )
    }
}

/**
 * The vault's own unlock screen, reused verbatim.
 *
 * Deliberately not a stripped-down variant. Someone typing their master passphrase should be looking
 * at the screen they always type it into: a second, simpler-looking prompt that appears over other
 * apps is precisely the thing a phishing overlay would imitate, and training people to accept an
 * unfamiliar one is training them to be phished.
 */
@Composable
private fun AutofillUnlockPane(modifier: Modifier = Modifier) {
    val viewModel: UnlockViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberBiometricGate()

    // The ViewModel is scoped to this activity and may have been built before the vault existed.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        UnlockScreen(
            state = state,
            onPassphraseChange = viewModel::onPassphraseChange,
            onConfirmationChange = viewModel::onConfirmationChange,
            onSubmit = viewModel::unlockWithPassphrase,
            onBiometricUnlock = { viewModel.unlockWithBiometrics(gate) }
        )
    }
}

/**
 * Choose an entry for an app or site the vault did not match on its own.
 *
 * Reached from the "Search Bubu Protect" row, and it is where a native app earns its link: whatever
 * is picked here is remembered against the requesting package, so this screen is a one-time cost per
 * app rather than a permanent one.
 */
@Composable
internal fun AutofillPickerScreen(
    targetName: String,
    items: List<VaultItem>,
    onPick: (VaultItem) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(items, query) { items.matching(query) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = BubuSpacing.lg, end = BubuSpacing.xs, top = BubuSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.autofill_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.autofill_picker_link_note, targetName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics {
                        contentDescription = "Close without filling"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.autofill_picker_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BubuSpacing.lg, vertical = BubuSpacing.sm)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (visible.isEmpty()) {
                PickerEmptyState(hasAnyEntries = items.isNotEmpty())
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = VaultItem::id) { item ->
                        PickerRow(item = item, onClick = { onPick(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(item: VaultItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Comfortably past the 48dp minimum, because this list is tapped in a hurry on top of
            // another app.
            .heightIn(min = 64.dp)
            .padding(horizontal = BubuSpacing.lg, vertical = BubuSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindBadge(kind = item.kind, size = 40.dp)
        Spacer(Modifier.width(BubuSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PickerEmptyState(hasAnyEntries: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(BubuSpacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
        ) {
            Text(
                text = stringResource(R.string.autofill_picker_empty),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                // Two different situations, and telling them apart matters: an empty vault needs an
                // entry added, while a search that found nothing just needs different words.
                text = if (hasAnyEntries) {
                    "No entry matches that search."
                } else {
                    stringResource(R.string.autofill_picker_empty_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Offers to store a credential the user just typed into another app.
 *
 * The password itself is never shown. It is already on the screen behind this dialog if the app
 * chose to reveal it, and repeating it here would put it on top of an app that could be
 * screen-recording - for no benefit, since the user typed it seconds ago.
 */
@Composable
internal fun AutofillSavePrompt(
    capture: CapturedCredential,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var label by rememberSaveable(capture.target.key) {
        mutableStateOf(capture.target.suggestedLabel())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.autofill_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)) {
                Text(
                    text = stringResource(
                        R.string.autofill_save_body,
                        capture.target.displayName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!capture.username.isNullOrBlank()) {
                    Text(
                        text = capture.username,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.autofill_save_label_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label) },
                enabled = label.isNotBlank()
            ) {
                Text(stringResource(R.string.autofill_save_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autofill_save_discard))
            }
        }
    )
}

/** `accounts.google.com` -> `Google`. A starting point the user can edit, not a decision. */
private fun AutofillTarget.suggestedLabel(): String {
    val source = webDomain?.substringBefore('.') ?: packageName.substringAfterLast('.')
    return source.replaceFirstChar(Char::uppercaseChar)
}

private fun List<VaultItem>.matching(query: String): List<VaultItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter {
        it.label.contains(trimmed, ignoreCase = true) ||
            it.subtitle.contains(trimmed, ignoreCase = true) ||
            it.website.orEmpty().contains(trimmed, ignoreCase = true)
    }
}

// --- Previews ----------------------------------------------------------------------------------

private val PreviewItems = listOf(
    VaultItem(
        id = "1",
        kind = ItemKind.LOGIN,
        label = "Reddit",
        subtitle = "bubu@example.com",
        website = "reddit.com"
    ),
    VaultItem(
        id = "2",
        kind = ItemKind.LOGIN,
        label = "Reddit (work)",
        subtitle = "work@example.com",
        website = "reddit.com"
    )
)

@Preview(name = "Picker", showBackground = true)
@Composable
private fun AutofillPickerPreview() {
    BubuProtectTheme {
        AutofillPickerScreen(
            targetName = "reddit.com",
            items = PreviewItems,
            onPick = {},
            onCancel = {}
        )
    }
}

@Preview(name = "Picker - empty vault", showBackground = true)
@Composable
private fun AutofillPickerEmptyPreview() {
    BubuProtectTheme {
        AutofillPickerScreen(
            targetName = "com.example.app",
            items = emptyList(),
            onPick = {},
            onCancel = {}
        )
    }
}

@Preview(name = "Save prompt", showBackground = true)
@Composable
private fun AutofillSavePromptPreview() {
    BubuProtectTheme {
        AutofillSavePrompt(
            capture = CapturedCredential(
                target = AutofillTarget("com.reddit.frontpage", "reddit.com", null),
                username = "bubu@example.com",
                secret = "not shown",
                capturedAt = 0L
            ),
            onSave = {},
            onDismiss = {}
        )
    }
}
