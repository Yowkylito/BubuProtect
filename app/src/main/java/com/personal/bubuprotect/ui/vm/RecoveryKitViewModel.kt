package com.personal.bubuprotect.ui.vm

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.BiometricAvailability
import com.personal.bubuprotect.core.recovery.RecoveryKit
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

data class RecoveryKitUiState(
    val hasKit: Boolean = false,
    val createdAt: Long = 0L,
    /**
     * Whether this device can guard a kit at all.
     *
     * False with no enrolled fingerprint or face. The screen says so up front rather than letting the
     * user print something that would be refused the day they needed it.
     */
    val canGuardKit: Boolean = true,
    /**
     * The code, present only while the user is looking at the screen that shows it.
     *
     * Null at every other moment, including after the screen is dismissed. A recovery code is a
     * complete credential, so it exists in this state for exactly as long as it is being read and no
     * longer - see [forget].
     */
    val revealedCode: String? = null,
    val isBusy: Boolean = false,
    val notice: String? = null,
    val isNoticeAnError: Boolean = false,
    /** True once the code has been saved to a file or acknowledged, so the screen can move on. */
    val isAcknowledged: Boolean = false
)

/**
 * Creating, replacing and deleting the recovery kit.
 *
 * ### Why this needs the session, not just the key manager
 *
 * [VaultKeyManager.createRecoveryKit] takes the unlocked [com.personal.bubuprotect.core.crypto.VaultKeys],
 * because minting a kit means wrapping the live root key. That is a deliberate constraint rather than
 * an inconvenience: a kit that could be generated from a locked vault would be a way of issuing full
 * access without ever authenticating.
 */
class RecoveryKitViewModel(
    private val keyManager: VaultKeyManager,
    private val session: VaultSession,
    private val biometrics: BiometricAuthenticator,
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(RecoveryKitUiState())
    val state: StateFlow<RecoveryKitUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = _state.update {
        it.copy(
            hasKit = keyManager.hasRecoveryKit,
            createdAt = keyManager.recoveryKitCreatedAt,
            canGuardKit = biometrics.availability() == BiometricAvailability.AVAILABLE
        )
    }

    /**
     * Mints a kit, replacing any existing one.
     *
     * The code is put on screen and nowhere else. It is never written to preferences, never logged,
     * and never handed to the clipboard - the app's own
     * [com.personal.bubuprotect.core.util.SecureClipboard] documents why the clipboard is the one
     * place a secret leaves the sandbox, and this is the single most valuable secret the vault has.
     */
    fun createKit() {
        if (_state.value.isBusy) return

        /*
         * Refused outright with no biometric enrolled.
         *
         * The recovery screen is guarded by a Keystore key bound to the current biometric enrollment -
         * see VaultKeyManager.beginRecoveryGuardCheck - and there is nothing to bind to here. Minting
         * the code anyway would produce a printed page that is refused on the one day it is needed,
         * and the user would have spent months believing they had a way back.
         */
        if (biometrics.availability() != BiometricAvailability.AVAILABLE) {
            fail(
                "Set up a fingerprint or face unlock on this phone first. Bubu uses it to stop a " +
                    "stolen phone being opened with your recovery code."
            )
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, notice = null) }
            try {
                val formatted = withContext(Dispatchers.Default) {
                    // The KDF here is HKDF, so this is fast - but the wrap still runs off the main
                    // thread, because Keystore and preference writes both can block.
                    session.withKeys { keys ->
                        keyManager.createRecoveryKit(keys).use { it.formatted() }
                    }
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        hasKit = true,
                        createdAt = keyManager.recoveryKitCreatedAt,
                        revealedCode = formatted,
                        isAcknowledged = false,
                        notice = null
                    )
                }
            } catch (invalidSpec: java.security.InvalidAlgorithmParameterException) {
                // The backstop for the check above: some OEM builds report a usable sensor and then
                // refuse to mint an auth-bound key.
                Timber.tag(TAG).w(invalidSpec, "Keystore refused to arm the recovery guard")
                fail(
                    "This phone would not let Bubu lock the recovery kit to your fingerprint, so " +
                        "no kit was made."
                )
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Could not create a recovery kit")
                fail("Bubu could not create a recovery kit just now. Try again.")
            }
        }
    }

    /**
     * Writes the kit to a document the user picked.
     *
     * Through the Storage Access Framework, so the app names no directory and holds no storage
     * permission - the same reasoning as the vault backup. It matters as much here: this file is a
     * key to everything, and an app that could quietly drop one somewhere would have undone its own
     * threat model.
     */
    fun saveKit(destination: Uri) {
        val code = _state.value.revealedCode ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, notice = null) }
            try {
                withContext(Dispatchers.IO) {
                    val document = RecoveryKit.render(code, _state.value.createdAt)
                    appContext.contentResolver.openOutputStream(destination, "wt")
                        ?.use { it.write(document.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Could not open the chosen file for writing")
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        isAcknowledged = true,
                        notice = "Recovery kit saved.",
                        isNoticeAnError = false
                    )
                }
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Could not write the recovery kit")
                fail("Bubu could not write to that file. Pick another place and try again.")
            }
        }
    }

    fun suggestedFileName(): String =
        RecoveryKit.fileName(RecoveryKit.dateStamp(System.currentTimeMillis()))

    /** The user says they have written it down. Trusted, because the alternative is nagging. */
    fun acknowledge() = _state.update { it.copy(isAcknowledged = true) }

    /**
     * Drops the on-screen copy.
     *
     * Called when the screen leaves the composition, so the code does not sit in a retained
     * ViewModel after the user has walked away from it. Once gone it cannot be shown again - the
     * wrapper is one-way - and the screen says so before this can happen.
     */
    fun forget() = _state.update { it.copy(revealedCode = null, notice = null) }

    fun discardKit() {
        viewModelScope.launch {
            keyManager.discardRecoveryKit()
            _state.update {
                it.copy(
                    hasKit = false,
                    createdAt = 0L,
                    revealedCode = null,
                    isAcknowledged = false,
                    notice = "Recovery kit deleted. Printed copies no longer work.",
                    isNoticeAnError = false
                )
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun fail(message: String) = _state.update {
        it.copy(isBusy = false, notice = message, isNoticeAnError = true)
    }

    private companion object {
        const val TAG = "RecoveryKit"
    }
}
