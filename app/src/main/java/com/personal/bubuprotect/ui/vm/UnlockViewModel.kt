package com.personal.bubuprotect.ui.vm

import android.content.Context
import android.net.Uri
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.crypto.BiometricKeyInvalidatedException
import com.personal.bubuprotect.core.backup.CorruptBackupException
import com.personal.bubuprotect.core.backup.UnsupportedBackupVersionException
import com.personal.bubuprotect.core.backup.VaultBackupService
import com.personal.bubuprotect.core.backup.WrongBackupPassphraseException
import com.personal.bubuprotect.core.crypto.PassphraseKdf
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.crypto.WrongPassphraseException
import com.personal.bubuprotect.core.crypto.wipe
import com.personal.bubuprotect.core.security.BiometricAvailability
import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.BiometricOutcome
import com.personal.bubuprotect.core.security.IntegrityChecker
import com.personal.bubuprotect.core.security.LockoutTracker
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.withContext

enum class UnlockStage { CHECKING, SETUP, LOCKED }

data class UnlockUiState(
    val stage: UnlockStage = UnlockStage.CHECKING,
    val passphrase: String = "",
    val confirmation: String = "",
    val isBusy: Boolean = false,
    val biometricUnlockOffered: Boolean = false,
    val message: String? = null,
    val isMessageAnError: Boolean = true,
    /**
     * Bumped on every rejection, so the UI can fire its shake animation again.
     *
     * The message alone is not enough to key an animation on: two wrong passphrases in a row produce
     * the same string, Compose sees no state change, and the second attempt fails silently.
     */
    val failureToken: Int = 0,
    val lockoutSecondsRemaining: Int = 0,
    val warnings: Set<IntegrityChecker.Finding> = emptySet()
) {
    val isLockedOut: Boolean get() = lockoutSecondsRemaining > 0

    val canSubmit: Boolean
        get() = !isBusy && !isLockedOut && when (stage) {
            UnlockStage.SETUP ->
                passphrase.length >= PassphraseKdf.MIN_PASSPHRASE_LENGTH && confirmation.isNotEmpty()
            UnlockStage.LOCKED -> passphrase.isNotEmpty()
            UnlockStage.CHECKING -> false
        }
}

/**
 * Drives first-run setup and unlock.
 *
 * The passphrase lives here as a `String` because that is what a Compose text field produces, and an
 * immutable String cannot be wiped. That is an accepted limit of the platform's text input rather
 * than an oversight: it is converted to a `CharArray` and zeroed the moment it reaches the KDF, and
 * [clearInput] drops the UI copy as soon as the attempt resolves, so the window is one unlock long
 * rather than the whole session.
 */
class UnlockViewModel(
    private val keyManager: VaultKeyManager,
    private val session: VaultSession,
    private val lockout: LockoutTracker,
    private val biometrics: BiometricAuthenticator,
    private val backupService: VaultBackupService,
    integrityChecker: IntegrityChecker,
    appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(UnlockUiState())
    val state: StateFlow<UnlockUiState> = _state.asStateFlow()

    private var lockoutTicker: Job? = null

    private val warnings = integrityChecker.scan(appContext)

    init {
        refresh()
    }

    /**
     * Recomputes what the screen should offer.
     *
     * Called again every time the unlock screen appears, not just on construction: this ViewModel is
     * scoped to the activity and survives an unlock/auto-lock cycle, so a stage captured once at
     * startup would still say "set up your vault" after the user had already created one.
     */
    fun refresh() {
        _state.update {
            it.copy(
                stage = if (keyManager.isEnrolled) UnlockStage.LOCKED else UnlockStage.SETUP,
                biometricUnlockOffered = keyManager.isBiometricUnlockEnabled &&
                    biometrics.availability() == BiometricAvailability.AVAILABLE,
                warnings = warnings
            )
        }
        refreshLockout()
    }

    fun onPassphraseChange(value: String) =
        _state.update { it.copy(passphrase = value, message = null) }

    fun onConfirmationChange(value: String) =
        _state.update { it.copy(confirmation = value, message = null) }

    /** First run: mint the vault, then offer to add the hardware-backed shortcut. */
    fun completeSetup(gate: BiometricGate) {
        val current = _state.value
        if (!current.canSubmit) return
        if (current.passphrase != current.confirmation) {
            fail("Those two passphrases do not match.")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val passphrase = current.passphrase.toCharArray()
            try {
                val keys = withContext(Dispatchers.Default) { keyManager.enroll(passphrase) }
                session.open(keys)
                lockout.recordSuccess()
                clearInput()
                offerBiometricEnrollment(gate)
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Vault setup failed")
                // No class name in the message. R8 renames these types in release - the user would
                // be told "Could not create the vault: u73" - and the throwable is already in the log
                // above, so the diagnostic loses nothing.
                fail("Bubu could not build the vault. Nothing was saved, so it is safe to try again.")
            } finally {
                passphrase.wipe()
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    /**
     * Rebuilds a vault from an exported backup file.
     *
     * ### The order matters
     *
     * The file is decrypted *before* anything is enrolled. A wrong passphrase or a damaged file then
     * costs nothing but a failed tag check - there is no half-built vault to clean up, and the user
     * lands back on setup with their options intact. Enrolling first and discovering the file was
     * unreadable afterwards would leave a real, empty vault sitting on top of the one they were
     * trying to recover.
     *
     * ### The restored vault is a new vault
     *
     * [VaultKeyManager.enroll] mints a fresh MEK, fresh salts and a fresh database. The entries are
     * re-sealed under those new keys, each one bound to its original id as AAD. Nothing from the
     * backup file's own key hierarchy survives into the restored vault, which is why an old export
     * leaking later does not compromise the vault it was restored into.
     *
     * @param passphrase the one that protected the backup. It becomes the new vault's passphrase
     *   too, so there is one thing to remember rather than two; it can be changed afterwards.
     */
    fun restoreFromBackup(source: Uri, passphrase: String, gate: BiometricGate) {
        if (_state.value.isBusy) return
        if (passphrase.isEmpty()) {
            fail("Enter the passphrase that protects this backup.")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val chars = passphrase.toCharArray()
            try {
                val decoded = backupService.read(source, chars)
                if (decoded.entries.isEmpty()) {
                    fail("That backup is readable but has nothing in it.")
                    return@launch
                }

                val keys = withContext(Dispatchers.Default) { keyManager.enroll(chars) }
                session.open(keys)
                val restored = backupService.restoreInto(decoded.entries)
                lockout.recordSuccess()
                clearInput()
                Timber.tag(TAG).i("Restored %d entries from a backup", restored)
                offerBiometricEnrollment(gate)
            } catch (wrong: WrongBackupPassphraseException) {
                fail("That passphrase does not open this backup.")
            } catch (unsupported: UnsupportedBackupVersionException) {
                fail("This backup was made by a newer version of Bubu Protect. Update the app first.")
            } catch (corrupt: CorruptBackupException) {
                // Carries no attacker-influenced detail - the messages are all fixed strings.
                fail(corrupt.message ?: "That backup could not be read.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Timber.tag(TAG).e("Restore failed (%s)", failure::class.java.simpleName)
                fail("Bubu could not restore that backup. Nothing was changed.")
            } finally {
                chars.wipe()
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    fun unlockWithPassphrase() {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val passphrase = current.passphrase.toCharArray()
            try {
                // Off the main thread: the KDF is meant to be slow.
                val keys = withContext(Dispatchers.Default) { keyManager.unlockWithPassphrase(passphrase) }
                session.open(keys)
                lockout.recordSuccess()
                clearInput()
            } catch (wrong: WrongPassphraseException) {
                val penalty = lockout.recordFailure()
                fail(
                    if (penalty != null) {
                        "Too many attempts. Try again in ${penalty.remainingMillis / 1000}s."
                    } else {
                        "That passphrase is not right."
                    }
                )
                refreshLockout()
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Passphrase unlock failed")
                fail("Bubu could not open the vault. Please try again.")
            } finally {
                passphrase.wipe()
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    fun unlockWithBiometrics(gate: BiometricGate) {
        if (_state.value.isBusy || _state.value.isLockedOut) return

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            try {
                val cipher = keyManager.beginBiometricUnlock()
                when (val outcome = gate.authenticate(
                    title = "Unlock Bubu Protect",
                    subtitle = "Prove that you are my Bubu",
                    cryptoObject = BiometricPrompt.CryptoObject(cipher)
                )) {
                    is BiometricOutcome.Success -> {
                        val authorised = outcome.cipher ?: error("Prompt returned no cipher")
                        val keys = withContext(Dispatchers.Default) {
                            keyManager.finishBiometricUnlock(authorised)
                        }
                        session.open(keys)
                        lockout.recordSuccess()
                        clearInput()
                    }

                    BiometricOutcome.Cancelled -> Unit

                    is BiometricOutcome.Error -> fail(outcome.message)
                }
            } catch (invalidated: BiometricKeyInvalidatedException) {
                // Expected after a fingerprint change. The passphrase still works, which is exactly
                // why the second wrapper exists.
                _state.update {
                    it.copy(
                        biometricUnlockOffered = false,
                        message = "Your biometrics changed, so the fingerprint key was discarded. " +
                            "Unlock with your master passphrase, then re-enable it.",
                        isMessageAnError = false
                    )
                }
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Biometric unlock failed")
                fail("Fingerprint unlock is not available right now. Use your master passphrase.")
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    private suspend fun offerBiometricEnrollment(gate: BiometricGate) {
        if (biometrics.availability() != BiometricAvailability.AVAILABLE) return
        runCatching {
            val cipher = keyManager.beginBiometricEnable()
            val outcome = gate.authenticate(
                title = "Enable fingerprint unlock",
                subtitle = "Optional - your passphrase always works",
                cryptoObject = BiometricPrompt.CryptoObject(cipher)
            )
            if (outcome is BiometricOutcome.Success) {
                val authorised = outcome.cipher ?: error("Prompt returned no cipher")
                session.withKeys { keys -> keyManager.finishBiometricEnable(authorised, keys) }
            }
        }.onFailure {
            // Declining or failing here is not a setup failure - the vault is already open.
            Timber.tag(TAG).i("Biometric unlock not enabled: %s", it.javaClass.simpleName)
        }
    }

    private fun refreshLockout() {
        lockoutTicker?.cancel()
        val status = lockout.status()
        if (!status.isLockedOut) {
            _state.update { it.copy(lockoutSecondsRemaining = 0) }
            return
        }
        lockoutTicker = viewModelScope.launch {
            var remaining = (status.remainingMillis / 1000).toInt().coerceAtLeast(1)
            while (remaining > 0) {
                _state.update { it.copy(lockoutSecondsRemaining = remaining) }
                delay(1_000)
                remaining--
            }
            _state.update { it.copy(lockoutSecondsRemaining = 0, message = null) }
            lockout.status() // clears the persisted deadline once it has elapsed
        }
    }

    private fun clearInput() = _state.update { it.copy(passphrase = "", confirmation = "") }

    private fun fail(message: String) = _state.update {
        it.copy(
            message = message,
            isMessageAnError = true,
            failureToken = it.failureToken + 1
        )
    }

    private companion object {
        const val TAG = "UnlockViewModel"
    }
}
