package com.personal.bubuprotect.ui.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.crypto.SecureBytes
import com.personal.bubuprotect.domain.model.FieldSlot
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultDraft
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.model.fields
import com.personal.bubuprotect.domain.model.supportsGeneratedSecret
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The editor's state, keyed by [FieldSlot] rather than by named properties.
 *
 * A map is the right shape here because the form is data-driven: [ItemKind.fields] decides which
 * rows exist, and a fixed set of `username`/`password`/`cvv` properties would have to grow every
 * time a kind is added, with most of them null for most entries. Keying by slot means adding a kind
 * touches [ItemKind] and nothing else.
 */
data class EntryEditorUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isNewEntry: Boolean = true,
    val kind: ItemKind = ItemKind.LOGIN,
    val label: String = "",
    val category: String = VaultEntry.DEFAULT_CATEGORY,
    val values: Map<FieldSlot, String> = emptyMap(),
    val revealedSlots: Set<FieldSlot> = emptySet(),
    val loadError: String? = null,
    val saveError: String? = null,
    val savedEntryId: String? = null,
    /**
     * Set once the user has tried to save. Required-field errors stay hidden until then, so a form
     * the user has not touched yet does not open covered in red.
     */
    val showValidation: Boolean = false
) {
    val fields: List<FieldSpec> get() = kind.fields

    /** Kind is fixed after creation: changing it would strand the extras already encrypted. */
    val canChangeKind: Boolean get() = isNewEntry

    val canGenerateSecret: Boolean get() = kind.supportsGeneratedSecret

    fun valueOf(spec: FieldSpec): String = values[spec.slot].orEmpty()

    fun isRevealed(spec: FieldSpec): Boolean = spec.slot in revealedSlots

    fun errorFor(spec: FieldSpec): String? = when {
        !showValidation -> null
        spec.isRequired && valueOf(spec).isBlank() -> "${spec.label} is required"
        else -> null
    }

    val labelError: String?
        get() = if (showValidation && label.isBlank()) "Give this entry a name" else null

    /** Completeness, independent of whether the errors are being displayed yet. */
    private val isComplete: Boolean
        get() = label.isNotBlank() && fields.none { it.isRequired && valueOf(it).isBlank() }

    /**
     * Save stays tappable while the form is incomplete; tapping it is what reveals what is missing.
     *
     * A greyed-out Save says "no" without saying why, and TalkBack skips disabled controls outright -
     * so the one affordance that could explain the problem becomes unreachable for the users most
     * likely to need it.
     */
    val isSubmitEnabled: Boolean get() = !isSaving && !isLoading && loadError == null

    val canSave: Boolean get() = isSubmitEnabled && isComplete
}

class EntryEditorViewModel(
    private val repository: VaultRepository,
    private val entryId: String?
) : ViewModel() {

    private val _state = MutableStateFlow(EntryEditorUiState(isNewEntry = entryId == null))
    val state: StateFlow<EntryEditorUiState> = _state.asStateFlow()

    init {
        if (entryId == null) _state.update { it.copy(isLoading = false) } else load(entryId)
    }

    private fun load(id: String) {
        viewModelScope.launch {
            try {
                val entry = repository.getEntry(id)
                if (entry == null) {
                    _state.update {
                        it.copy(isLoading = false, loadError = "That entry no longer exists.")
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        kind = entry.kind,
                        label = entry.label,
                        category = entry.category,
                        values = entry.toSlotValues()
                    )
                }
            } catch (tampered: VaultTamperedException) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        loadError = "This entry failed its integrity check. Editing it would " +
                            "overwrite data that may have been tampered with."
                    )
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "Could not load entry", failure)
                _state.update { it.copy(isLoading = false, loadError = "Could not open that entry.") }
            }
        }
    }

    /**
     * Switching kind on a new entry keeps whatever the user already typed into a slot the new kind
     * also has - retyping a username because you picked "Login" a moment too late is pure friction.
     * Extras are dropped, because their keys are kind-specific and a card's `cvv` means nothing on a
     * Wi-Fi entry.
     */
    fun onKindChange(kind: ItemKind) {
        _state.update { current ->
            if (!current.canChangeKind || kind == current.kind) return@update current
            val keep = kind.fields.map(FieldSpec::slot).toSet()
            current.copy(
                kind = kind,
                values = current.values.filterKeys { it in keep && it !is FieldSlot.Extra },
                revealedSlots = emptySet(),
                saveError = null
            )
        }
    }

    fun onLabelChange(value: String) = _state.update { it.copy(label = value, saveError = null) }

    fun onCategoryChange(value: String) =
        _state.update { it.copy(category = value, saveError = null) }

    fun onFieldChange(slot: FieldSlot, value: String) = _state.update {
        it.copy(values = it.values + (slot to value), saveError = null)
    }

    fun toggleReveal(slot: FieldSlot) = _state.update {
        it.copy(
            revealedSlots = if (slot in it.revealedSlots) {
                it.revealedSlots - slot
            } else {
                it.revealedSlots + slot
            }
        )
    }

    /**
     * Generates a password from [SecureBytes]'s CSPRNG.
     *
     * The alphabet drops the characters that get misread when a password is typed off a screen
     * (`O`/`0`, `l`/`1`/`I`), and the index is chosen by rejection sampling rather than
     * `% alphabet.size`: modulo would bias the first few characters of the set, which is a small
     * loss of entropy and a free one to avoid.
     */
    fun generateSecret(length: Int = GENERATED_LENGTH) {
        val alphabet = PASSWORD_ALPHABET
        val bound = 256 - (256 % alphabet.length)
        val generated = buildString(length) {
            while (this.length < length) {
                for (byte in SecureBytes.randomBytes(length)) {
                    if ((byte.toInt() and 0xFF) < bound) {
                        append(alphabet[(byte.toInt() and 0xFF) % alphabet.length])
                        if (this.length == length) break
                    }
                }
            }
        }
        _state.update {
            it.copy(
                values = it.values + (FieldSlot.Secret to generated),
                revealedSlots = it.revealedSlots + FieldSlot.Secret,
                saveError = null
            )
        }
    }

    fun save() {
        _state.update { it.copy(showValidation = true) }
        val current = _state.value
        if (!current.canSave) return

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            try {
                val id = repository.save(current.toDraft(entryId))
                _state.update { it.copy(isSaving = false, savedEntryId = id) }
            } catch (failure: Throwable) {
                Log.e(TAG, "Save failed", failure)
                _state.update { it.copy(isSaving = false, saveError = "Could not save this entry.") }
            }
        }
    }

    private fun VaultEntry.toSlotValues(): Map<FieldSlot, String> = buildMap {
        put(FieldSlot.Identity, identity)
        put(FieldSlot.Secret, secret)
        put(FieldSlot.Notes, notes.orEmpty())
        put(FieldSlot.Website, website.orEmpty())
        extras.forEach { (key, value) -> put(FieldSlot.Extra(key), value) }
    }

    private fun EntryEditorUiState.toDraft(id: String?) = VaultDraft(
        id = id,
        kind = kind,
        label = label,
        identity = values[FieldSlot.Identity].orEmpty(),
        secret = values[FieldSlot.Secret].orEmpty(),
        website = values[FieldSlot.Website]?.takeIf(String::isNotBlank),
        notes = values[FieldSlot.Notes]?.takeIf(String::isNotBlank),
        // Only slots this kind actually declares, so a value left behind by a kind switch on a new
        // entry cannot be written into the extras blob.
        extras = fields.map(FieldSpec::slot)
            .filterIsInstance<FieldSlot.Extra>()
            .mapNotNull { slot -> values[slot]?.takeIf(String::isNotBlank)?.let { slot.key to it } }
            .toMap(),
        category = category
    )

    private companion object {
        const val TAG = "EntryEditorViewModel"
        const val GENERATED_LENGTH = 20
        const val PASSWORD_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#\$%^&*-_=+?"
    }
}
