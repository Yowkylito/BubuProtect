package com.personal.bubuprotect.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.importer.CredentialImportService
import com.personal.bubuprotect.core.importer.CredentialImporter
import com.personal.bubuprotect.core.importer.ImportPreview
import com.personal.bubuprotect.core.importer.UnreadableImportException
import com.personal.bubuprotect.domain.repository.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Where the import flow has got to. */
sealed interface ImportStage {
    /** Nothing picked yet. */
    data object Idle : ImportStage

    data object Reading : ImportStage

    /** Read and understood. Nothing has been written to the vault. */
    data class Confirming(val preview: ImportPreview) : ImportStage

    data object Writing : ImportStage

    /**
     * @param sourceDeleted null while the user has not been asked, true/false once they have.
     */
    data class Done(
        val imported: Int,
        val attempted: Int,
        val preview: ImportPreview,
        val sourceDeleted: Boolean? = null
    ) : ImportStage

    data class Failed(val message: String) : ImportStage
}

data class ImportUiState(
    val stage: ImportStage = ImportStage.Idle,
    val source: Uri? = null
)

/**
 * Bringing a vault in from another password manager.
 *
 * ### Why the preview step is not optional
 *
 * An import is the only operation in this app that can double the size of the vault in one tap, and
 * the only one where the *source* is a file the app has never seen and cannot validate beyond its
 * shape. So nothing is written until the user has been shown counts they can check against what they
 * expected - "214 to import, 6 already here, 2 unreadable" is a sentence that catches a wrong file
 * before it becomes a mess to unpick.
 *
 * ### The plaintext file is treated as the hazard it is
 *
 * The export sits in the clear on the device. This flow ends by offering to delete it, because a
 * migration that leaves every password in Downloads has moved them into a vault *and* left a copy
 * where anything can read it. See [CredentialImportService.delete].
 *
 * ### Why this holds every imported password in memory, and for how long
 *
 * [ImportStage.Confirming] carries the drafts, which means every credential in the file sits in the
 * clear on this heap between the preview and the confirmation. That is unavoidable for the same
 * reason a backup export is: the user is being shown a count they can check, and there is no way to
 * count N entries without having read N entries.
 *
 * What is controllable is the window. This ViewModel is scoped to the navigation destination, so
 * leaving the screen clears it; [reset] clears it on a retry; and [onCleared] drops the state
 * explicitly rather than waiting for the object to be collected. The `String`s themselves cannot be
 * wiped - Java strings are immutable, the same accepted limit the rest of the vault lives with - so
 * the bound is the process, which loses its keys on lock.
 */
class ImportViewModel(
    private val repository: VaultRepository,
    private val service: CredentialImportService
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun onFilePicked(source: Uri) {
        _state.value = ImportUiState(stage = ImportStage.Reading, source = source)
        viewModelScope.launch {
            try {
                val text = service.read(source)
                // The list carries no secrets, so this does not decrypt anything - see
                // CredentialImporter.preview on why duplicates are matched the way they are.
                val existing = repository.observeItems().first()
                val preview = withContext(Dispatchers.Default) {
                    CredentialImporter.preview(text, existing)
                }
                _state.update { it.copy(stage = ImportStage.Confirming(preview)) }
            } catch (unreadable: UnreadableImportException) {
                // Its message is written for the user and says what to do next, so it is shown as-is
                // rather than replaced by something generic.
                _state.update { it.copy(stage = ImportStage.Failed(unreadable.message.orEmpty())) }
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Could not read the import file")
                _state.update {
                    it.copy(
                        stage = ImportStage.Failed(
                            "Bubu could not read that file. Make sure it is the CSV your old " +
                                "password app exported."
                        )
                    )
                }
            }
        }
    }

    fun confirm() {
        val preview = (_state.value.stage as? ImportStage.Confirming)?.preview ?: return
        _state.update { it.copy(stage = ImportStage.Writing) }

        viewModelScope.launch {
            var imported = 0
            try {
                preview.drafts.forEach { draft ->
                    // One at a time, counting successes. A row that fails - a field the cipher
                    // rejects, a write that races a lock - must not take the other two hundred with
                    // it, and the user is told how many actually landed rather than assuming all did.
                    runCatching { repository.save(draft) }
                        .onSuccess { imported++ }
                        .onFailure { Timber.tag(TAG).w(it, "Skipped one row during import") }
                }
                _state.update {
                    it.copy(
                        stage = ImportStage.Done(
                            imported = imported,
                            attempted = preview.drafts.size,
                            preview = preview
                        )
                    )
                }
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Import failed part-way")
                _state.update {
                    it.copy(
                        stage = ImportStage.Failed(
                            "Bubu imported $imported of ${preview.drafts.size} entries and then " +
                                "hit a problem. The ones that landed are in your vault."
                        )
                    )
                }
            }
        }
    }

    /** Deletes the export file the user picked. */
    fun deleteSource() {
        val source = _state.value.source ?: return
        val done = _state.value.stage as? ImportStage.Done ?: return
        viewModelScope.launch {
            val deleted = service.delete(source)
            _state.update { it.copy(stage = done.copy(sourceDeleted = deleted)) }
        }
    }

    /** The user chose to keep the file. Recorded so the screen stops asking. */
    fun keepSource() {
        val done = _state.value.stage as? ImportStage.Done ?: return
        _state.update { it.copy(stage = done.copy(sourceDeleted = false)) }
    }

    fun reset() {
        _state.value = ImportUiState()
    }

    /**
     * Drops the drafts rather than leaving them for the collector.
     *
     * Every password in the file is in that list. Clearing on teardown is not a substitute for the
     * unwipeable `String`s inside it, but it removes the last reference at a known moment instead of
     * an arbitrary one.
     */
    override fun onCleared() {
        _state.value = ImportUiState()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Import"
    }
}
