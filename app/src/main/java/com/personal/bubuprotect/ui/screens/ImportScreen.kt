package com.personal.bubuprotect.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.bubuprotect.core.importer.ImportPreview
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.ImportStage
import com.personal.bubuprotect.ui.vm.ImportViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun ImportRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    /*
     * A wildcard filter, not `text/csv`.
     *
     * An export copied through Drive, a chat app or another phone routinely comes back typed as
     * `application/octet-stream`, `text/plain`, or nothing at all. A strict filter would grey out the
     * user's own file in the picker with no explanation. The header row is the real check, and it runs
     * before anything is written.
     */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::onFilePicked) }

    ImportScreen(
        stage = state.stage,
        onPickFile = { picker.launch(arrayOf("*/*")) },
        onConfirm = viewModel::confirm,
        onDeleteSource = viewModel::deleteSource,
        onKeepSource = viewModel::keepSource,
        onRetry = viewModel::reset,
        onDone = onDone,
        modifier = modifier
    )
}

/**
 * Import from another password manager.
 *
 * Four steps, and the middle one is the point: nothing is written until the user has seen counts they
 * can sanity-check. See [ImportViewModel] for why an import in particular must not be a single tap.
 */
@Composable
fun ImportScreen(
    stage: ImportStage,
    onPickFile: () -> Unit,
    onConfirm: () -> Unit,
    onDeleteSource: () -> Unit,
    onKeepSource: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ResponsiveContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = BubuSpacing.lg),
            maxContentWidth = 520.dp,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(BubuSpacing.md))

            BubuMascot(
                mood = when (stage) {
                    is ImportStage.Failed -> BubuMood.WORRIED
                    is ImportStage.Done -> BubuMood.CELEBRATING
                    ImportStage.Reading, ImportStage.Writing -> BubuMood.THINKING
                    else -> BubuMood.GREETING
                },
                size = 132.dp,
                breathing = stage != ImportStage.Reading && stage != ImportStage.Writing
            )

            Spacer(Modifier.height(BubuSpacing.md))

            Text(
                text = when (stage) {
                    is ImportStage.Done -> "Brought in"
                    is ImportStage.Failed -> "That did not work"
                    else -> "Bring your passwords in"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(BubuSpacing.md))

            when (stage) {
                ImportStage.Idle -> IdlePane(onPickFile = onPickFile, onDone = onDone)

                ImportStage.Reading -> BusyPane("Reading the file")

                is ImportStage.Confirming -> ConfirmPane(
                    preview = stage.preview,
                    onConfirm = onConfirm,
                    onCancel = onRetry
                )

                ImportStage.Writing -> BusyPane("Adding them to your vault")

                is ImportStage.Done -> DonePane(
                    stage = stage,
                    onDeleteSource = onDeleteSource,
                    onKeepSource = onKeepSource,
                    onDone = onDone
                )

                is ImportStage.Failed -> FailedPane(
                    message = stage.message,
                    onRetry = onRetry,
                    onDone = onDone
                )
            }

            Spacer(Modifier.height(BubuSpacing.xl))
        }
    }
}

@Composable
private fun IdlePane(onPickFile: () -> Unit, onDone: () -> Unit) {
    Text(
        text = "Export a CSV from your old password app or browser, then pick it here. Bubu " +
            "reads the exports from Chrome, Safari, Firefox, Bitwarden, 1Password, LastPass, " +
            "KeePass, Dashlane, NordPass and most others.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(BubuSpacing.md))

    /*
     * Said before the user creates the file, not after.
     *
     * The export is every password they own, in plain text, and it usually lands in Downloads - which
     * on most phones is synced to the cloud within the minute. Warning about it afterwards is too
     * late to change where it went.
     */
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
    ) {
        Text(
            text = "An export file holds every password in plain text. Keep it on this phone, " +
                "do not email it to yourself, and let Bubu delete it when the import is done.",
            modifier = Modifier.padding(BubuSpacing.md),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    Spacer(Modifier.height(BubuSpacing.md))

    BubuButton(text = "Choose a file", onClick = onPickFile, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(BubuSpacing.xs))
    BubuOutlinedButton(text = "Not now", onClick = onDone, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun BusyPane(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(BubuSpacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConfirmPane(preview: ImportPreview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.bubu.champagneContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.bubu.champagne.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(BubuSpacing.md)) {
            CountRow("Ready to import", preview.importable.toString(), emphasise = true)
            if (preview.duplicates > 0) {
                CountRow("Already in your vault", preview.duplicates.toString())
            }
            if (preview.withTotp > 0) {
                CountRow("Carry a 2FA seed", preview.withTotp.toString())
            }
            if (preview.cards > 0) {
                CountRow("Payment cards (skipped)", preview.cards.toString())
            }
            if (preview.unusable > 0) {
                CountRow("Rows Bubu could not read", preview.unusable.toString())
            }
        }
    }

    if (preview.withTotp > 0) {
        Spacer(Modifier.height(BubuSpacing.sm))
        Text(
            // Says exactly what happens to them, because a silently dropped 2FA seed is discovered
            // while locked out of an account.
            text = "Bubu cannot generate 2FA codes yet, so those seeds are saved in each entry's " +
                "notes rather than thrown away. Keep your authenticator app until then.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }

    if (preview.cards > 0) {
        Spacer(Modifier.height(BubuSpacing.sm))
        Text(
            text = "Cards are skipped on purpose: a CSV rarely has the security code, and a card " +
                "that looks saved but cannot be used is worse than none. Add those by tapping a " +
                "card to your phone instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }

    Spacer(Modifier.height(BubuSpacing.md))

    BubuButton(
        text = if (preview.hasAnything) {
            "Import ${preview.importable} ${if (preview.importable == 1) "entry" else "entries"}"
        } else {
            "Nothing to import"
        },
        onClick = onConfirm,
        enabled = preview.hasAnything,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(BubuSpacing.xs))
    BubuOutlinedButton(
        text = "Pick a different file",
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DonePane(
    stage: ImportStage.Done,
    onDeleteSource: () -> Unit,
    onKeepSource: () -> Unit,
    onDone: () -> Unit
) {
    Text(
        text = if (stage.imported == stage.attempted) {
            "${stage.imported} ${if (stage.imported == 1) "entry is" else "entries are"} in your vault."
        } else {
            "${stage.imported} of ${stage.attempted} entries made it in. The rest were skipped."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(BubuSpacing.xs))

    Text(
        // Every imported password is unchecked, and the badges will say so. Said here so a vault
        // that suddenly shows two hundred unchecked entries does not read as a fault.
        text = "None of them have been checked against breach data yet. Run a check from Settings " +
            "when you are ready.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(BubuSpacing.md))

    when (stage.sourceDeleted) {
        null -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            ) {
                Text(
                    text = "That export file is still on your phone, with every password readable " +
                        "in it. Delete it now?",
                    modifier = Modifier.padding(BubuSpacing.md),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(BubuSpacing.sm))
            BubuButton(
                text = "Delete the export file",
                onClick = onDeleteSource,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(BubuSpacing.xs))
            BubuOutlinedButton(
                text = "I will do it myself",
                onClick = onKeepSource,
                modifier = Modifier.fillMaxWidth()
            )
        }

        true -> {
            Text(
                text = "The export file has been deleted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(BubuSpacing.md))
            BubuButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
        }

        false -> {
            Text(
                // Covers both "the user declined" and "the provider refused", because the thing they
                // need to know is identical either way: the file is still there.
                text = "The export file is still on your phone. Delete it from your Files app when " +
                    "you can - it holds every password in plain text.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(BubuSpacing.md))
            BubuButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FailedPane(message: String, onRetry: () -> Unit, onDone: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(BubuSpacing.md))
    BubuButton(text = "Try another file", onClick = onRetry, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(BubuSpacing.xs))
    BubuOutlinedButton(text = "Back", onClick = onDone, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CountRow(label: String, value: String, emphasise: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BubuSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(0.75f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            style = if (emphasise) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

// --- Previews ----------------------------------------------------------------------------------

@Preview(name = "Start", showBackground = true)
@Composable
private fun ImportIdlePreview() {
    BubuProtectTheme {
        ImportScreen(
            stage = ImportStage.Idle,
            onPickFile = {}, onConfirm = {}, onDeleteSource = {}, onKeepSource = {},
            onRetry = {}, onDone = {}
        )
    }
}

@Preview(name = "Confirm", showBackground = true)
@Composable
private fun ImportConfirmPreview() {
    BubuProtectTheme {
        ImportScreen(
            stage = ImportStage.Confirming(
                ImportPreview(
                    drafts = List(214) { PreviewDraft },
                    duplicates = 6,
                    unusable = 2,
                    withTotp = 11,
                    cards = 3
                )
            ),
            onPickFile = {}, onConfirm = {}, onDeleteSource = {}, onKeepSource = {},
            onRetry = {}, onDone = {}
        )
    }
}

@Preview(name = "Done", showBackground = true)
@Composable
private fun ImportDonePreview() {
    BubuProtectTheme {
        ImportScreen(
            stage = ImportStage.Done(
                imported = 214,
                attempted = 214,
                preview = ImportPreview(emptyList(), 6, 2, 11, 3)
            ),
            onPickFile = {}, onConfirm = {}, onDeleteSource = {}, onKeepSource = {},
            onRetry = {}, onDone = {}
        )
    }
}

private val PreviewDraft = com.personal.bubuprotect.domain.model.VaultDraft(
    kind = com.personal.bubuprotect.domain.model.ItemKind.LOGIN,
    label = "Example",
    identity = "me@example.com",
    secret = "not shown"
)
