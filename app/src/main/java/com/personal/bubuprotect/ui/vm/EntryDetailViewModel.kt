package com.personal.bubuprotect.ui.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.BiometricAvailability
import com.personal.bubuprotect.core.security.BiometricOutcome
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.domain.model.FieldSlot
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EntryDetailContent {
    data object Loading : EntryDetailContent

    data class Ready(val entry: VaultEntry) : EntryDetailContent

    data class Failed(val message: String, val isTampering: Boolean) : EntryDetailContent
}

/** The one field currently on screen in the clear, and how long it has left. */
data class RevealedField(val slot: FieldSlot, val secondsRemaining: Int)

data class EntryDetailUiState(
    val content: EntryDetailContent = EntryDetailContent.Loading,
    val revealed: RevealedField? = null,
    val isDeleted: Boolean = false,
    val notice: String? = null
) {
    val entry: VaultEntry? get() = (content as? EntryDetailContent.Ready)?.entry
}

/**
 * One open entry.
 *
 * ### Why the whole decrypted entry is held here
 *
 * A detail screen has to know which fields are populated before it can lay itself out, and that is
 * not knowable without decrypting them - the ciphertext lengths are all this app can see otherwise.
 * So the entry is decrypted once, on open, and lives in this ViewModel's state.
 *
 * Two things keep that from being a regression on the "never hold plaintext" goal. First, only one
 * entry is ever in memory this way - the list still never decrypts a secret. Second, [observeLock]
 * drops the state the instant [VaultSession] locks, so the plaintext does not outlive the keys that
 * produced it; a screen left open while the phone sleeps has nothing left in it to dump.
 *
 * What that buys over decrypting per reveal is that the layout is stable and the reveal is instant.
 * The alternative - decrypt one field at a time - would still hold that field's plaintext, and would
 * leak the entry's shape through which rows exist.
 */
class EntryDetailViewModel(
    private val repository: VaultRepository,
    private val session: VaultSession,
    private val biometrics: BiometricAuthenticator,
    private val clipboard: SecureClipboard,
    private val entryId: String
) : ViewModel() {

    private val _state = MutableStateFlow(EntryDetailUiState())
    val state: StateFlow<EntryDetailUiState> = _state.asStateFlow()

    private var revealTimer: Job? = null

    init {
        load()
        observeLock()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(content = EntryDetailContent.Loading) }
            try {
                val entry = repository.getEntry(entryId)
                _state.update {
                    it.copy(
                        content = if (entry == null) {
                            EntryDetailContent.Failed("That entry is no longer here.", false)
                        } else {
                            EntryDetailContent.Ready(entry)
                        }
                    )
                }
            } catch (tampered: VaultTamperedException) {
                _state.update {
                    it.copy(
                        content = EntryDetailContent.Failed(
                            "This entry failed its integrity check, so Bubu will not show it. " +
                                "The database may have been modified outside the app.",
                            isTampering = true
                        )
                    )
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "Could not open entry", failure)
                _state.update {
                    it.copy(content = EntryDetailContent.Failed("Could not open that entry.", false))
                }
            }
        }
    }

    /** Drops every decrypted byte the moment the session's keys are wiped. */
    private fun observeLock() {
        viewModelScope.launch {
            session.isUnlocked.collectLatest { unlocked ->
                if (!unlocked) {
                    revealTimer?.cancel()
                    _state.update {
                        it.copy(
                            content = EntryDetailContent.Loading,
                            revealed = null
                        )
                    }
                }
            }
        }
    }

    /**
     * Puts one field on screen in the clear for [REVEAL_SECONDS], after a fresh authentication.
     *
     * Only one field at a time: revealing a second replaces the first. A card with its number, CVV
     * and PIN all showing at once is a photograph away from being a usable card, and the countdown
     * only protects what is still hidden.
     */
    fun reveal(spec: FieldSpec, gate: BiometricGate) {
        viewModelScope.launch {
            if (!authorize(gate, "Show ${spec.label}")) return@launch
            startCountdown(spec.slot)
        }
    }

    fun hide() {
        revealTimer?.cancel()
        _state.update { it.copy(revealed = null) }
    }

    fun copy(spec: FieldSpec, gate: BiometricGate) {
        viewModelScope.launch {
            val entry = _state.value.entry ?: return@launch
            val value = entry.valueOf(spec)
            if (value.isBlank()) return@launch

            // Already on screen and already authenticated for - no second prompt.
            val alreadyOpen = _state.value.revealed?.slot == spec.slot
            if (!alreadyOpen && !authorize(gate, "Copy ${spec.label}")) return@launch

            if (clipboard.copySensitive(spec.label, value)) {
                notify("Copied. Bubu clears it in ${SecureClipboard.CLEAR_AFTER_SECONDS}s.")
            } else {
                notify("This device has no clipboard available.")
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { repository.delete(entryId) }
                .onSuccess { _state.update { it.copy(isDeleted = true) } }
                .onFailure {
                    Log.e(TAG, "Delete failed", it)
                    notify("Could not delete that entry.")
                }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private suspend fun authorize(gate: BiometricGate, title: String): Boolean =
        when (biometrics.availability()) {
            BiometricAvailability.AVAILABLE ->
                when (val outcome = gate.authenticate(title, "Confirm it is you", null)) {
                    is BiometricOutcome.Success -> true
                    BiometricOutcome.Cancelled -> false
                    is BiometricOutcome.Error -> {
                        notify(outcome.message)
                        false
                    }
                }
            // No usable sensor: the unlock the user already passed is the authentication.
            else -> true
        }

    private fun startCountdown(slot: FieldSlot) {
        revealTimer?.cancel()
        revealTimer = viewModelScope.launch {
            for (remaining in REVEAL_SECONDS downTo 1) {
                _state.update { it.copy(revealed = RevealedField(slot, remaining)) }
                delay(1_000)
            }
            _state.update { it.copy(revealed = null) }
        }
    }

    private fun notify(message: String) = _state.update { it.copy(notice = message) }

    companion object {
        const val REVEAL_SECONDS = 20
        private const val TAG = "EntryDetailViewModel"
    }
}
