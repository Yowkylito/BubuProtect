package com.personal.bubuprotect.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.security.PwnedPasswordChecker
import com.personal.bubuprotect.core.security.PwnedPasswordResult
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.domain.model.FieldSlot
import com.personal.bubuprotect.domain.model.FieldSpec
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface EntryDetailContent {
    data object Loading : EntryDetailContent

    data class Ready(val entry: VaultEntry) : EntryDetailContent

    data class Failed(val message: String, val isTampering: Boolean) : EntryDetailContent
}

/** The one field currently on screen in the clear, and how long it has left. */
data class RevealedField(val slot: FieldSlot, val secondsRemaining: Int)

/**
 * The *transient* half of the breach UI. The verdict itself lives on
 * [com.personal.bubuprotect.domain.model.VaultEntry.breach], loaded from the vault.
 *
 * The two used to be one enum with `NotFound` and `Found` members, and that is what made the check
 * feel like it had not happened: the result died with the screen, so re-opening an entry showed
 * "never checked" over a password that had been verified a minute earlier. Now only the states that
 * genuinely have no home on disk - "a request is in flight", "the last one failed" - live here.
 */
sealed interface PasswordBreachState {
    /** Nothing in flight. The card renders whatever the stored verdict says. */
    data object Idle : PasswordBreachState

    data object Checking : PasswordBreachState

    data object Failed : PasswordBreachState
}

data class EntryDetailUiState(
    val content: EntryDetailContent = EntryDetailContent.Loading,
    val revealed: RevealedField? = null,
    val passwordBreach: PasswordBreachState = PasswordBreachState.Idle,
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
    private val authorizer: RevealAuthorizer,
    private val clipboard: SecureClipboard,
    private val pwnedPasswordChecker: PwnedPasswordChecker,
    private val entryId: String
) : ViewModel() {

    private val _state = MutableStateFlow(EntryDetailUiState())
    val state: StateFlow<EntryDetailUiState> = _state.asStateFlow()

    private var revealTimer: Job? = null
    private var breachCheckJob: Job? = null

    init {
        load()
        observeLock()
    }

    fun load() {
        breachCheckJob?.cancel()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    content = EntryDetailContent.Loading,
                    passwordBreach = PasswordBreachState.Idle
                )
            }
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
                Timber.tag(TAG).e(failure, "Could not open entry")
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
                    breachCheckJob?.cancel()
                    _state.update {
                        it.copy(
                            content = EntryDetailContent.Loading,
                            revealed = null,
                            passwordBreach = PasswordBreachState.Idle
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

            // Already on screen and already authenticated for, so normally no second prompt. Strict
            // mode is exactly the setting that withdraws that allowance - see RevealAuthorizer.
            val alreadyOpen = _state.value.revealed?.slot == spec.slot
            if (!authorize(gate, "Copy ${spec.label}", isAlreadyAuthorised = alreadyOpen)) {
                return@launch
            }

            if (clipboard.copySensitive(spec.label, value)) {
                notify("Copied. Bubu clears it in ${SecureClipboard.CLEAR_AFTER_SECONDS}s.")
            } else {
                notify("This device has no clipboard available.")
            }
        }
    }

    /**
     * Checks only login passwords, only after a deliberate tap and fresh authentication.
     *
     * The checker sends HIBP five hexadecimal characters from a SHA-1 digest. The password, website,
     * username, entry id, and remaining digest never enter the request. No result is persisted.
     */
    fun checkPasswordBreach(gate: BiometricGate) {
        if (_state.value.passwordBreach == PasswordBreachState.Checking) return

        breachCheckJob?.cancel()
        breachCheckJob = viewModelScope.launch {
            val entry = _state.value.entry
                ?.takeIf { it.isBreachCheckable }
                ?: return@launch
            if (!authorize(gate, "Check password safety")) return@launch

            // Authentication can outlive a lock or navigation event, so re-read the state before
            // allowing any password-derived network request.
            val authenticatedEntry = _state.value.entry
                ?.takeIf { it.id == entry.id && it.isBreachCheckable }
                ?: return@launch

            _state.update { it.copy(passwordBreach = PasswordBreachState.Checking) }
            try {
                val result = pwnedPasswordChecker.check(authenticatedEntry.secret)
                if (!session.isUnlocked.value || _state.value.entry?.id != authenticatedEntry.id) {
                    return@launch
                }
                val exposureCount = when (result) {
                    PwnedPasswordResult.NotFound -> 0L
                    is PwnedPasswordResult.Found -> result.exposureCount
                }

                // Persist first, then mirror into the on-screen entry. The write is what makes the
                // list badge, the security report and the alert dialog agree with this card; the
                // local mirror is only so this screen updates without a second decrypt.
                //
                // It is dropped by the repository if the password changed while the request was in
                // flight - in which case the mirror below is skipped too, and the card correctly
                // falls back to "not checked" for the password that is actually there now.
                val stored = repository.recordBreachCheck(
                    id = authenticatedEntry.id,
                    exposureCount = exposureCount,
                    secretUpdatedAt = authenticatedEntry.secretUpdatedAt
                )

                _state.update { current ->
                    val onScreen = current.entry
                    if (!stored || onScreen?.id != authenticatedEntry.id) {
                        current.copy(passwordBreach = PasswordBreachState.Idle)
                    } else {
                        current.copy(
                            content = EntryDetailContent.Ready(
                                onScreen.copy(
                                    breach = BreachStatus(
                                        verdict = if (exposureCount > 0L) {
                                            BreachVerdict.BREACHED
                                        } else {
                                            BreachVerdict.SAFE
                                        },
                                        exposureCount = exposureCount,
                                        checkedAt = System.currentTimeMillis()
                                    )
                                )
                            ),
                            passwordBreach = PasswordBreachState.Idle
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Exception messages from HTTP stacks can contain the requested URL and therefore
                // the hash prefix. Log only the type, never the throwable or its message.
                Timber.tag(TAG).w(
                    "Password breach check failed (%s)",
                    failure::class.java.simpleName
                )
                if (session.isUnlocked.value) {
                    _state.update { it.copy(passwordBreach = PasswordBreachState.Failed) }
                }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { repository.delete(entryId) }
                .onSuccess { _state.update { it.copy(isDeleted = true) } }
                .onFailure {
                    Timber.tag(TAG).e(it, "Delete failed")
                    notify("Could not delete that entry.")
                }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * @param isAlreadyAuthorised see [RevealAuthorizer.authorize] - a live authentication this
     *   screen already holds. Ignored under strict mode.
     */
    private suspend fun authorize(
        gate: BiometricGate,
        title: String,
        isAlreadyAuthorised: Boolean = false
    ): Boolean = when (val outcome = authorizer.authorize(gate, title, isAlreadyAuthorised)) {
        RevealOutcome.Allowed -> true
        RevealOutcome.Refused -> false
        is RevealOutcome.Blocked -> {
            notify(outcome.message)
            false
        }
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
