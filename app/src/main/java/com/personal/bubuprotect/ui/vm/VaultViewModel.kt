package com.personal.bubuprotect.ui.vm

import android.net.Uri
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.backup.EmptyVaultException
import com.personal.bubuprotect.core.backup.VaultBackupService
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.crypto.wipe
import com.personal.bubuprotect.core.crypto.WrongPassphraseException
import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.BiometricAvailability
import com.personal.bubuprotect.core.security.BiometricOutcome
import com.personal.bubuprotect.core.security.BreachScanState
import com.personal.bubuprotect.core.security.VaultBreachScanner
import com.personal.bubuprotect.core.util.SecureClipboard
import com.personal.bubuprotect.data.local.UserPreferences
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

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

    // --- Breach status -------------------------------------------------------------------------

    /**
     * The whole vault, with no search text or kind chip applied.
     *
     * [list] is what the vault screen renders and is filtered; this is what the security report
     * reasons over. A report that inherited the vault's search box could tell the user "all clear"
     * because they happened to have typed something into a text field on a different screen.
     */
    val allItems: List<VaultItem> = emptyList(),

    /**
     * Every breached entry in the vault, worst first - *unfiltered*.
     *
     * Deliberately not derived from [list]: that one has the search box and the kind chips applied
     * to it, and an alert that disappeared because the user happened to be filtering on "Wi-Fi"
     * would be a safety feature defeated by a text field.
     */
    val breached: List<VaultItem> = emptyList(),
    val scan: BreachScanState = BreachScanState.Idle,
    val breachMonitoringEnabled: Boolean = false,
    /** @see com.personal.bubuprotect.data.local.UserPreferences.strictRevealEnabled */
    val strictRevealEnabled: Boolean = false,
    /**
     * Whether the alert dialog should be on screen right now.
     *
     * Separate from `breached.isNotEmpty()` so that dismissing it is instant and local, rather than
     * waiting for an acknowledgement write to round-trip through the database and back up the flow.
     */
    val breachAlertVisible: Boolean = false,

    /** True while a backup is being sealed and written. Drives the button's spinner. */
    val isBackupRunning: Boolean = false,

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

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModel(
    private val repository: VaultRepository,
    private val session: VaultSession,
    private val keyManager: VaultKeyManager,
    private val biometrics: BiometricAuthenticator,
    private val clipboard: SecureClipboard,
    private val breachScanner: VaultBreachScanner,
    private val backupService: VaultBackupService,
    private val preferences: UserPreferences
) : ViewModel() {

    private sealed interface Load {
        data object Pending : Load
        data class Ready(val items: List<VaultItem>) : Load
        data class Broken(val error: Throwable) : Load
    }

    private val query = MutableStateFlow("")
    private val kindFilter = MutableStateFlow<ItemKind?>(null)
    private val reloadSignal = MutableStateFlow(0)
    private val local = MutableStateFlow(
        VaultUiState(
            biometricUnlockEnabled = keyManager.isBiometricUnlockEnabled,
            canOfferBiometricUnlock = biometrics.availability() == BiometricAvailability.AVAILABLE,
            breachMonitoringEnabled = preferences.breachMonitoringEnabled,
            strictRevealEnabled = preferences.strictRevealEnabled
        )
    )

    private var scanJob: Job? = null

    /**
     * Guards the automatic scan and the automatic alert to once per unlocked session.
     *
     * This ViewModel is scoped to the unlocked shell, so it is cleared on lock and both flags reset
     * with it - which is the behaviour we want. Without them, every return from the entry editor
     * would re-raise a dialog the user already answered.
     */
    private var hasAutoScanned = false
    private var hasRaisedAlert = false

    val state: StateFlow<VaultUiState> = combine(
        reloadSignal.flatMapLatest {
            repository.observeItems()
                .map<List<VaultItem>, Load> { Load.Ready(it) }
                .onStart { emit(Load.Pending) }
                .catch { emit(Load.Broken(it)) }
        },
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
            list = load.toListState(text, kind),
            allItems = all,
            // Worst first: the report screen and the dialog both lead with the most exposed entry,
            // and ordering here rather than in each of them keeps the two telling the same story.
            breached = all.filter { it.breach.isBreached }
                .sortedByDescending { it.breach.exposureCount },
            // The dialog is only allowed to appear once the user has been shown it for this set of
            // verdicts. `breachAlertVisible` is raised by raiseAlertIfNeeded, never here, so a list
            // re-emission - a copy, a rename, a scroll - cannot resurrect a dismissed dialog.
            breachAlertVisible = base.breachAlertVisible &&
                all.any { it.breach.needsAttention }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUiState())

    // Declared after [state], not in an init block above it. `viewModelScope` dispatches on
    // Main.immediate, so a launch during construction can run its body before the constructor
    // finishes - and this one reads `state`, which would still be null at that point.
    init {
        observeAutoScanTrigger()
    }

    // --- Breach checking -----------------------------------------------------------------------

    /**
     * Raises the alert, and optionally starts a scan, the first time the vault list settles.
     *
     * Split from [state] because both are one-shot effects rather than derived values, and putting a
     * side effect inside a `combine` lambda means it fires again on every recombination - which here
     * would be every keystroke in the search box.
     */
    private fun observeAutoScanTrigger() {
        viewModelScope.launch {
            state
                .map { it.allItems }
                .filter { it.isNotEmpty() }
                .collect { items ->
                    if (!hasAutoScanned && preferences.breachMonitoringEnabled) {
                        hasAutoScanned = true
                        startScan(items, force = false)
                    }
                    raiseAlertIfNeeded()
                }
        }
    }

    /**
     * Shows the alert dialog if anything is breached and unacknowledged.
     *
     * Once per session. A dialog that reappears every time the user navigates back to the list is a
     * dialog that gets dismissed without being read, and the badge on the row plus the entry point
     * in settings are there to carry the message the rest of the time.
     */
    private fun raiseAlertIfNeeded() {
        if (hasRaisedAlert) return
        val outstanding = state.value.breached.any { it.breach.needsAttention }
        if (!outstanding) return
        hasRaisedAlert = true
        local.update { it.copy(breachAlertVisible = true) }
    }

    /**
     * Runs a check over the vault's password-shaped secrets.
     *
     * One authentication for the whole scan rather than one per entry. Every other secret-touching
     * action in this app re-authenticates, and the reason it does not here is that the alternative -
     * forty fingerprint prompts in a row - is not a security control, it is a way of guaranteeing
     * nobody ever runs the scan. The scan also never puts a secret on screen, which is what the
     * per-entry prompts are actually protecting.
     *
     * @param force re-check even entries with a recent verdict.
     */
    fun runBreachScan(gate: BiometricGate, force: Boolean = false) {
        if (state.value.scan is BreachScanState.Running) return
        viewModelScope.launch {
            if (!authorize(gate, "Check your passwords")) return@launch
            startScan(state.value.allItems, force)
        }
    }

    private fun startScan(items: List<VaultItem>, force: Boolean) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            breachScanner.scan(items, force = force)
                .onCompletion { cause ->
                    // A cancelled scan must not leave a spinner up forever. Cancellation happens on
                    // lock and on a second scan starting, and neither has a result to report.
                    if (cause != null && state.value.scan is BreachScanState.Running) {
                        local.update { it.copy(scan = BreachScanState.Idle) }
                    }
                }
                .collect { scanState ->
                    local.update { it.copy(scan = scanState) }
                    if (scanState is BreachScanState.Finished && scanState.breached > 0) {
                        // A scan that just found something re-arms the alert even if one has already
                        // been shown this session: this is new information, not a repeat.
                        hasRaisedAlert = false
                        raiseAlertIfNeeded()
                    }
                }
        }
    }

    /** The dialog's "Ignore" path: silences today's verdicts, and only today's. */
    fun ignoreBreachAlert() {
        local.update { it.copy(breachAlertVisible = false) }
        viewModelScope.launch {
            runCatching { repository.acknowledgeAllBreaches() }
                .onFailure { Timber.tag(TAG).e(it, "Could not acknowledge breaches") }
        }
    }

    /**
     * The dialog's "Check them" path.
     *
     * Deliberately does *not* acknowledge: the user is going to look at the report, and marking the
     * verdicts as seen would clear the badges out from under them on the way there.
     */
    fun dismissBreachAlert() = local.update { it.copy(breachAlertVisible = false) }

    fun setBreachMonitoring(enabled: Boolean) {
        preferences.breachMonitoringEnabled = enabled
        local.update { it.copy(breachMonitoringEnabled = enabled) }
        notify(
            if (enabled) {
                "Bubu will check your passwords once each time you unlock."
            } else {
                "Automatic checks are off. You can still check any password yourself."
            }
        )
    }

    /**
     * Turns off every "you proved it a moment ago" shortcut.
     *
     * Refused outright without an enrolled biometric rather than stored and ignored: a switch that
     * reads as on while changing nothing is worse than no switch, and this is the one setting in the
     * app whose entire value is that the user can trust what it says.
     */
    fun setStrictReveal(enabled: Boolean) {
        if (enabled && !local.value.canOfferBiometricUnlock) {
            notify("Add a fingerprint or face unlock first - strict mode has nothing to ask for without one.")
            return
        }
        preferences.strictRevealEnabled = enabled
        local.update { it.copy(strictRevealEnabled = enabled) }
        notify(
            if (enabled) {
                "Bubu will ask every single time a secret is shown or copied."
            } else {
                "Back to asking once per entry."
            }
        )
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onKindFilterChange(kind: ItemKind?) {
        kindFilter.value = kind
    }

    fun retryLoad() {
        reloadSignal.update { it + 1 }
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
                Timber.tag(TAG).e(failure, "Could not enable biometric unlock")
                notify("Could not enable fingerprint unlock.")
            }
        }
    }

    fun disableBiometricUnlock() {
        keyManager.disableBiometricUnlock()
        local.update { it.copy(biometricUnlockEnabled = false) }
        notify("Fingerprint unlock removed. Passphrase only.")
    }

    // --- Backup --------------------------------------------------------------------------------

    /**
     * Seals the whole vault into a document the user picked.
     *
     * ### Why the passphrase is asked for again
     *
     * The session does not keep it - it is wiped the moment the KDF has run - so it genuinely has to
     * be re-entered. That turns out to be the right behaviour anyway, for two reasons. It proves the
     * user still *knows* the passphrase, which someone who unlocks with a fingerprint every day may
     * quietly have stopped doing; and a backup they cannot open is worse than no backup, because
     * they will stop worrying about the thing it was supposed to protect them from.
     *
     * It is verified against the vault before anything is written, for the same reason: sealing
     * under a typo produces a file that looks like a backup and is not one.
     */
    fun exportBackup(destination: Uri, passphrase: String) {
        if (state.value.isBackupRunning) return
        viewModelScope.launch {
            local.update { it.copy(isBackupRunning = true) }
            val chars = passphrase.toCharArray()
            try {
                withContext(Dispatchers.Default) { keyManager.unlockWithPassphrase(chars) }.close()
                val count = backupService.export(destination, chars)
                notify(
                    "Saved $count ${if (count == 1) "secret" else "secrets"} to your backup file. " +
                        "It can only be opened with that passphrase."
                )
            } catch (wrong: WrongPassphraseException) {
                notify("That is not your master passphrase, so nothing was written.")
            } catch (empty: EmptyVaultException) {
                notify("There is nothing in the vault to back up yet.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // The throwable can name the chosen document; keep it out of the user-facing text
                // and log only the type, as everywhere else that touches secrets.
                Timber.tag(TAG).e("Backup export failed (%s)", failure::class.java.simpleName)
                notify("Bubu could not write that file. Nothing was saved.")
            } finally {
                chars.wipe()
                local.update { it.copy(isBackupRunning = false) }
            }
        }
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
        Timber.tag(TAG).e(failure, "Could not read entry")
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
            Timber.tag(TAG).e(error, "Vault list failed")
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
