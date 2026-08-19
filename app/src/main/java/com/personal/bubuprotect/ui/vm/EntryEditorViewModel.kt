package com.personal.bubuprotect.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.crypto.SecureBytes
import com.personal.bubuprotect.domain.model.FieldSlot
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.ScannedCard
import com.personal.bubuprotect.domain.model.VaultDraft
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.model.fields
import com.personal.bubuprotect.domain.model.supportsGeneratedSecret
import com.personal.bubuprotect.domain.model.supportsNfcScan
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

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
    /** Transient, snackbar-shaped. A refused reveal, not a problem with the form. */
    val notice: String? = null,
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

    /** Whether the *kind* can be scanned. Whether the *phone* can scan is the screen's question. */
    val canScanCard: Boolean get() = kind.supportsNfcScan

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
    private val authorizer: RevealAuthorizer,
    private val entryId: String?
) : ViewModel() {

    private val _state = MutableStateFlow(EntryEditorUiState(isNewEntry = entryId == null))
    val state: StateFlow<EntryEditorUiState> = _state.asStateFlow()

    /**
     * Whether a reveal has already been authorised while this editor has been open.
     *
     * Lives on the ViewModel rather than in [EntryEditorUiState] so it survives a rotation - being
     * asked for a fingerprint again because the phone turned sideways would read as a bug - and dies
     * with the editor, which is the point: it is a per-session allowance, not a standing one.
     * Ignored entirely when strict mode is on.
     */
    private var hasAuthorisedReveal = false

    init {
        if (entryId == null) _state.update { it.copy(isLoading = false) } else load(entryId)
    }

    private fun load(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadError = null) }
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
                Timber.tag(TAG).e(failure, "Could not load entry")
                _state.update { it.copy(isLoading = false, loadError = "Could not open that entry.") }
            }
        }
    }

    fun retryLoad() {
        entryId?.let(::load)
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

    /**
     * Fills the form from a contactless read.
     *
     * Two fields, and only two, because that is what an EMV chip carries - see [ScannedCard]. The
     * CVV and PIN rows are left untouched and stay required-looking, which is the honest outcome: a
     * scan is a shortcut past the sixteen digits, not a way to skip the form.
     *
     * The number is revealed rather than left masked. The user is still holding the card, and the
     * one moment they can check a scan against the embossed digits is now.
     */
    fun onCardScanned(card: ScannedCard) {
        // Read out here, not inside update{}. The sheet wipes the digits the instant this returns,
        // and MutableStateFlow.update re-runs its lambda under contention - a second pass would find
        // a zeroed array and write blanks over the number it had just stored.
        val number = String(card.pan)
        val expiry = card.formattedExpiry
        val holder = card.holderName
        val suggested = card.suggestedLabel

        _state.update { current ->
            if (!current.canScanCard) return@update current

            val values = current.values.toMutableMap()
            values[FieldSlot.Secret] = number
            if (expiry.isNotEmpty()) values[EXPIRY_SLOT] = expiry
            // Never over a name the user typed - a scan is a suggestion, and the card's own
            // rendering of a name is frequently the worse of the two.
            if (!holder.isNullOrBlank() && values[FieldSlot.Identity].isNullOrBlank()) {
                values[FieldSlot.Identity] = holder
            }

            current.copy(
                values = values,
                label = current.label.ifBlank { suggested },
                // Left masked. Checking a scan against the card in your hand is worth one tap on
                // the reveal toggle, and that tap is authenticated; putting a full card number on
                // screen unprompted is not something a tap on the *back* of the phone should buy.
                saveError = null
            )
        }
    }

    /**
     * Unmasks one field, after proving it is still you.
     *
     * Hiding is free; only showing costs an authentication. Which authentications are actually
     * charged for is [RevealAuthorizer]'s decision - by default the first reveal in an editor
     * session pays and the rest ride on it, under strict mode every one pays.
     *
     * Note what this does *not* do: it never decrypts anything. The values are already in
     * [EntryEditorUiState] because the editor has to be able to write them back. This gates what is
     * legible on a screen someone else might be standing in front of, which is the threat that
     * survives having already unlocked the vault.
     */
    fun requestReveal(spec: FieldSpec, gate: BiometricGate) {
        val slot = spec.slot
        if (slot in _state.value.revealedSlots) {
            _state.update { it.copy(revealedSlots = it.revealedSlots - slot) }
            return
        }

        viewModelScope.launch {
            when (val outcome = authorizer.authorize(gate, "Show ${spec.label}", hasAuthorisedReveal)) {
                RevealOutcome.Allowed -> {
                    hasAuthorisedReveal = true
                    _state.update { it.copy(revealedSlots = it.revealedSlots + slot) }
                }

                RevealOutcome.Refused -> Unit

                is RevealOutcome.Blocked -> _state.update { it.copy(notice = outcome.message) }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

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
                // Normally shown, because a password you cannot read is one you cannot check before
                // committing to it. Under strict mode nothing appears without a fresh prompt, and a
                // generator that quietly exempted itself would be the hole in that rule.
                revealedSlots = if (authorizer.isStrict) {
                    it.revealedSlots
                } else {
                    it.revealedSlots + FieldSlot.Secret
                },
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
                Timber.tag(TAG).e(failure, "Save failed")
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

        /**
         * Mirrors the extras key [ItemKind.CARD] declares for its expiry row.
         *
         * Writing a slot the kind does not declare is not dangerous - `toDraft` filters extras down
         * to the kind's own field list before saving - but it would be silently ignored, so this
         * has to stay in step with [com.personal.bubuprotect.domain.model.fields].
         */
        val EXPIRY_SLOT = FieldSlot.Extra("expiry")

        const val GENERATED_LENGTH = 20
        const val PASSWORD_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#\$%^&*-_=+?"
    }
}
