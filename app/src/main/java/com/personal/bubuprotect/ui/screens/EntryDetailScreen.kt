package com.personal.bubuprotect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.ErrorPane
import com.personal.bubuprotect.ui.components.KindBadge
import com.personal.bubuprotect.ui.components.LoadingPane
import com.personal.bubuprotect.ui.components.VaultFieldRow
import com.personal.bubuprotect.ui.components.relativeAge
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.enterStaggered
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.vm.EntryDetailContent
import com.personal.bubuprotect.ui.vm.EntryDetailUiState
import com.personal.bubuprotect.ui.vm.EntryDetailViewModel
import com.personal.bubuprotect.ui.vm.RevealedField
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun EntryDetailRoute(
    entryId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
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
@OptIn(ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val entry = state.entry

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    BubuIconButton(
                        // AutoMirrored: the manifest declares RTL support, and a back arrow that
                        // still points left in an RTL layout points the wrong way.
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to the vault",
                        onClick = onBack
                    )
                },
                actions = {
                    if (entry != null) {
                        BubuIconButton(
                            icon = Icons.Filled.Edit,
                            contentDescription = "Edit ${entry.label}",
                            onClick = onEdit
                        )
                        BubuIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "Delete ${entry.label}",
                            onClick = { confirmingDelete = true },
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = state.content,
            transitionSpec = {
                (fadeIn(tween(BubuMotion.MEDIUM)) +
                    scaleIn(tween(BubuMotion.MEDIUM), initialScale = 0.97f)) togetherWith
                    fadeOut(tween(BubuMotion.FAST))
            },
            contentKey = { it::class },
            label = "detailPane",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { content ->
            when (content) {
                EntryDetailContent.Loading -> LoadingPane(label = "Unsealing")

                is EntryDetailContent.Failed -> ErrorPane(message = content.message)

                is EntryDetailContent.Ready -> EntryFields(
                    entry = content.entry,
                    revealed = state.revealed,
                    onReveal = onReveal,
                    onHide = onHide,
                    onCopy = onCopy
                )
            }
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
    modifier: Modifier = Modifier
) {
    val fields = remember(entry) { entry.populatedFields() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "header") {
            EntryHeader(entry)
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
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Updated ${relativeAge(entry.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EntryHeader(entry: VaultEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindBadge(kind = entry.kind, size = 60.dp)
        Spacer(Modifier.padding(horizontal = 8.dp))
        Column {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = entry.kind.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep it") }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Preview(showBackground = true, name = "Card entry")
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
