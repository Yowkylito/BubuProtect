package com.personal.bubuprotect.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.crypto.PassphraseKdf
import com.personal.bubuprotect.core.crypto.RecoveryCode
import com.personal.bubuprotect.core.crypto.VaultKeyManager
import com.personal.bubuprotect.core.crypto.VaultKeys
import com.personal.bubuprotect.core.crypto.WrongRecoveryCodeException
import com.personal.bubuprotect.core.crypto.wipe
import com.personal.bubuprotect.core.security.LockoutTracker
import com.personal.bubuprotect.data.local.UserPreferences
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class RecoveryStage {
    /** Typing the code off the printed kit. */
    CODE,

    /** Choosing the passphrase that replaces the forgotten one. */
    NEW_PASSPHRASE
}

data class RecoveryUnlockUiState(
    val stage: RecoveryStage = RecoveryStage.CODE,
    val code: String = "",
    val passphrase: String = "",
    val confirmation: String = "",
    val isBusy: Boolean = false,
    val message: String? = null,
    val isMessageAnError: Boolean = true,
    /** Bumped on every rejection so the UI can replay its shake even for an identical message. */
    val failureToken: Int = 0
) {
    /**
     * Enabled only for something the right shape.
     *
     * A local length check rather than a round trip: it turns "nothing happens when I press the
     * button" into a visibly disabled button, and it keeps a half-typed code from being counted as a
     * failed attempt against the lockout.
     */
    val canSubmitCode: Boolean get() = !isBusy && RecoveryCode.looksWellFormed(code)

    val canSubmitPassphrase: Boolean
        get() = !isBusy &&
            passphrase.length >= PassphraseKdf.MIN_PASSPHRASE_LENGTH &&
            confirmation.isNotEmpty()
}

/**
 * Getting back in with a printed recovery kit.
 *
 * ### Why setting a new passphrase is part of the same flow, not a suggestion afterwards
 *
 * Someone reaching this screen has forgotten their passphrase. Unlocking them and stopping there
 * would leave a vault they still cannot open the *next* time - the recovery code would become the
 * only way in, and a paper credential used daily is a paper credential that gets photographed and
 * left on a desk. So [RecoveryStage.NEW_PASSPHRASE] is mandatory and the session is only opened at
 * the end of it: the user cannot leave this flow without a passphrase they know.
 *
 * ### Why the recovered keys are held here between the two steps
 *
 * The root key has to survive from "code accepted" to "new passphrase written", because re-wrapping
 * needs it. That is a live [VaultKeys] sitting in a ViewModel, which is exactly the thing
 * [VaultSession] exists to avoid - so it is wiped on cancel, wiped on [onCleared], and ownership is
 * handed to the session the instant the flow completes. [recovered] is nulled at handoff so no path
 * can close a key set the session now owns.
 */
class RecoveryUnlockViewModel(
    private val keyManager: VaultKeyManager,
    private val session: VaultSession,
    private val lockout: LockoutTracker,
    private val preferences: UserPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(RecoveryUnlockUiState())
    val state: StateFlow<RecoveryUnlockUiState> = _state.asStateFlow()

    private var recovered: VaultKeys? = null

    fun onCodeChange(value: String) = _state.update { it.copy(code = value, message = null) }

    fun onPassphraseChange(value: String) =
        _state.update { it.copy(passphrase = value, message = null) }

    fun onConfirmationChange(value: String) =
        _state.update { it.copy(confirmation = value, message = null) }

    fun submitCode() {
        val current = _state.value
        if (!current.canSubmitCode) return

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            try {
                val parsed = RecoveryCode.parse(current.code)
                if (parsed == null) {
                    // Should be unreachable - canSubmitCode ran the same check - but a shape failure
                    // must never be reported as a wrong code. See the message below for why.
                    fail("That code is not the right length. It has 24 characters after BP1.")
                    return@launch
                }

                val keys = parsed.use { code ->
                    withContext(Dispatchers.Default) { keyManager.unlockWithRecoveryCode(code) }
                }
                recovered = keys
                lockout.recordSuccess()
                _state.update {
                    it.copy(
                        stage = RecoveryStage.NEW_PASSPHRASE,
                        code = "",
                        isBusy = false,
                        message = null
                    )
                }
            } catch (wrong: WrongRecoveryCodeException) {
                val penalty = lockout.recordFailure()
                fail(
                    if (penalty != null) {
                        "Too many attempts. Try again in ${penalty.remainingMillis / 1000}s."
                    } else {
                        // A code of the right shape that fails is usually the wrong kit rather than a
                        // typo, because a typo normally breaks the length first - and sending someone
                        // to re-check 24 characters they typed correctly is a miserable dead end.
                        "That code does not open this vault. Check it is the kit for this phone."
                    }
                )
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Recovery unlock failed")
                fail("Bubu could not use that code just now. Please try again.")
            }
        }
    }

    /** Re-wraps the recovered root key under a new passphrase, then opens the vault. */
    fun completeRecovery() {
        val current = _state.value
        if (!current.canSubmitPassphrase) return
        if (current.passphrase != current.confirmation) {
            fail("Those two passphrases do not match.")
            return
        }
        val keys = recovered ?: run {
            fail("That recovery session expired. Start again.")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null) }
            val passphrase = current.passphrase.toCharArray()
            try {
                withContext(Dispatchers.Default) { keyManager.changePassphrase(keys, passphrase) }
                // Ownership moves to the session here. Nulled first so a cancel racing this cannot
                // wipe keys the session is now using.
                recovered = null
                session.open(keys)
                lockout.recordSuccess()
                // Every backup on disk is still sealed with the passphrase that was just replaced -
                // see UserPreferences.backupRefreshNeeded for why staying quiet about that would be
                // the worst part of this whole feature.
                preferences.backupRefreshNeeded = true
                _state.update {
                    RecoveryUnlockUiState(
                        message = "Your passphrase has been changed.",
                        isMessageAnError = false
                    )
                }
            } catch (failure: Throwable) {
                Timber.tag(TAG).e(failure, "Could not re-wrap the vault under a new passphrase")
                fail("Bubu could not set that passphrase. Please try again.")
            } finally {
                passphrase.wipe()
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    /**
     * Abandons the flow.
     *
     * Wipes the recovered keys, which is the whole reason this exists rather than the screen simply
     * navigating away: a back press mid-recovery would otherwise leave the root key live in a
     * retained ViewModel behind a lock screen.
     */
    fun cancel() {
        recovered?.close()
        recovered = null
        _state.value = RecoveryUnlockUiState()
    }

    override fun onCleared() {
        recovered?.close()
        recovered = null
        super.onCleared()
    }

    private fun fail(message: String) = _state.update {
        it.copy(
            isBusy = false,
            message = message,
            isMessageAnError = true,
            failureToken = it.failureToken + 1
        )
    }

    private companion object {
        const val TAG = "RecoveryUnlock"
    }
}
