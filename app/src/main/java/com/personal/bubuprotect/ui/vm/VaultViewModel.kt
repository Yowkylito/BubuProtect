package com.personal.bubuprotect.ui.vm

import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.BiometricAvailability
import com.personal.bubuprotect.core.security.BiometricOutcome
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the list area renders. Distinct states rather than a pile of booleans. */
sealed interface VaultListState {
    data object Loading : VaultListState

    /** The vault genuinely has no entries - not a search that matched nothing. */
    data object Empty : VaultListState

    data class Content(val items: List<VaultItem>, val isFiltered: Boolean) : VaultListState

    data class Failed(val reason: String, val isTampering: Boolean) : VaultListState
}

data class VaultUiState(
    val list: VaultListState = VaultListState.Loading,
    val query: String = "",
    val kindFilter: ItemKind? = null,
    /** Whole-vault totals per kind, so a filter chip can show a count and grey out at zero. */
    val counts: Map<ItemKind, Int> = emptyMap(),
    val totalCount: Int = 0,
    val biometricUnlockEnabled: Boolean = false,
    val canOfferBiometricUnlock: Boolean = false,
    val notice: String? = null,
    /**
     * Set to an entry id once the user has re-authenticated to open it; the screen navigates and
     * then clears it via [VaultViewModel.consumeOpenGrant].
     *
     * Modelled as one-shot state rather than a callback because navigation must not be triggered
     * from inside the authentication coroutine: if the user rotates the device while the prompt is
     * up, that coroutine outlives the composable that would have handled the callback.
     */
    val openGrantedFor: String? = null
)

class VaultViewModel(
    private val repository: VaultRepository,
    private val session: VaultSession,
    private val keyManager: VaultKeyManager,
    private val biometrics: BiometricAuthenticator,
    private val clipboard: SecureClipboard
) : ViewModel() {

    private sealed interface Load {
        data object Pending : Load
        data class Ready(val items: List<VaultItem>) : Load
        data class Broken(val error: Throwable) : Load
    }

    private val query = MutableStateFlow("")
    private val kindFilter = MutableStateFlow<ItemKind?>(null)
    private val local = MutableStateFlow(
        VaultUiState(
            biometricUnlockEnabled = keyManager.isBiometricUnlockEnabled,
            canOfferBiometricUnlock = biometrics.availability() == BiometricAvailability.AVAILABLE
        )
    )

    val state: StateFlow<VaultUiState> = combine(
        repository.observeItems()
            .map<List<VaultItem>, Load> { Load.Ready(it) }
            .onStart { emit(Load.Pending) }
            .catch { emit(Load.Broken(it)) },
        query,
        kindFilter,
        local
    ) { load, text, kind, base ->
        val all = (load as? Load.Ready)?.items.orEmpty()
        base.copy(
            query = text,
            kindFilter = kind,
            counts = all.groupingBy { it.kind }.eachCount(),
            totalCount = all.size,
            list = load.toListState(text, kind)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onKindFilterChange(kind: ItemKind?) {
        kindFilter.value = kind
    }

    /**
     * Copies an entry's primary secret straight from the list.
     *
     * Re-authenticates first even though the vault is already open. The vault stays unlocked through
     * a minute of backgrounding so paste-and-return works, and that same minute is when a phone gets
     * handed to someone. A fingerprint per copy costs the owner a touch and costs an opportunist the
     * whole vault.
     */
    fun copySecret(entryId: String, label: String, gate: BiometricGate) {
        viewModelScope.launch {
            if (!authorize(gate, "Copy $label")) return@launch
            val secret = decrypt(entryId) ?: return@launch
            if (secret.isBlank()) {
                notify("There is nothing to copy in that one.")
                return@launch
            }
            if (clipboard.copySensitive(label, secret)) {
                notify("Copied. Bubu clears it in ${SecureClipboard.CLEAR_AFTER_SECONDS}s.")
            } else {
                notify("This device has no clipboard available.")
            }
        }
    }

    fun delete(entryId: String) {
        viewModelScope.launch {
            runCatching { repository.delete(entryId) }
                .onFailure { notify("Could not delete that entry.") }
                .onSuccess { notify("Gone for good.") }
        }
    }

    fun lock() = session.lock()

    /** Adds the hardware-backed unlock shortcut, including after a biometric re-enrollment. */
    fun enableBiometricUnlock(gate: BiometricGate) {
        viewModelScope.launch {
            try {
                val cipher = keyManager.beginBiometricEnable()
                val outcome = gate.authenticate(
                    title = "Enable fingerprint unlock",
                    subtitle = "Your master passphrase keeps working",
                    cryptoObject = BiometricPrompt.CryptoObject(cipher)
                )
                if (outcome is BiometricOutcome.Success) {
                    val authorised = outcome.cipher ?: error("Prompt returned no cipher")
                    session.withKeys { keys -> keyManager.finishBiometricEnable(authorised, keys) }
                    local.update { it.copy(biometricUnlockEnabled = true) }
                    notify("Fingerprint unlock enabled.")
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "Could not enable biometric unlock", failure)
                notify("Could not enable fingerprint unlock.")
            }
        }
    }

    fun disableBiometricUnlock() {
        keyManager.disableBiometricUnlock()
        local.update { it.copy(biometricUnlockEnabled = false) }
        notify("Fingerprint unlock removed. Passphrase only.")
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    /**
     * Re-authenticates before opening an entry, then grants navigation.
     *
     * Copying already costs a fingerprint, and opening an entry exposes strictly more - every field,
     * on screen, for as long as it is open. Without this, an unlocked vault left on a table gives up
     * everything to whoever taps the row, which would make the per-copy prompt security theatre.
     */
    fun requestOpen(entryId: String, label: String, gate: BiometricGate) {
        viewModelScope.launch {
            if (!authorize(gate, "Open $label")) return@launch
            local.update { it.copy(openGrantedFor = entryId) }
        }
    }

    fun consumeOpenGrant() = local.update { it.copy(openGrantedFor = null) }

    /** @return true when the user may see a secret. Falls through when there is no sensor. */
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

    private suspend fun decrypt(entryId: String): String? = try {
        repository.getEntry(entryId)?.secret
    } catch (tampered: VaultTamperedException) {
        notify("That entry failed its integrity check and was not shown.")
        null
    } catch (failure: Throwable) {
        Log.e(TAG, "Could not read entry", failure)
        notify("Could not read that entry.")
        null
    }

    private fun notify(message: String) = local.update { it.copy(notice = message) }

    private fun Load.toListState(text: String, kind: ItemKind?): VaultListState = when (this) {
        Load.Pending -> VaultListState.Loading
        is Load.Ready -> when {
            items.isEmpty() -> VaultListState.Empty
            else -> VaultListState.Content(
                items = items.filter { (kind == null || it.kind == kind) && it.matches(text) },
                isFiltered = text.isNotBlank() || kind != null
            )
        }
        is Load.Broken -> {
            Log.e(TAG, "Vault list failed", error)
            VaultListState.Failed(
                reason = if (error is VaultTamperedException) {
                    "An entry failed its integrity check. The database may have been modified."
                } else {
                    "Could not read the vault."
                },
                isTampering = error is VaultTamperedException
            )
        }
    }

    private fun VaultItem.matches(text: String): Boolean {
        if (text.isBlank()) return true
        return label.contains(text, ignoreCase = true) ||
            subtitle.contains(text, ignoreCase = true) ||
            category.contains(text, ignoreCase = true) ||
            kind.title.contains(text, ignoreCase = true) ||
            website?.contains(text, ignoreCase = true) == true
    }

    private companion object {
        const val TAG = "VaultViewModel"
    }
}
