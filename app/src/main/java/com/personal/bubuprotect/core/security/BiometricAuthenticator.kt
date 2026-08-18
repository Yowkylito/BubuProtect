package com.personal.bubuprotect.core.security

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume

enum class BiometricAvailability {
    AVAILABLE,

    /** Hardware is present but no fingerprint/face is enrolled. Offer [enrollmentIntent]. */
    NOT_ENROLLED,

    /** No strong biometric sensor, or it is disabled or busy. Passphrase only. */
    UNAVAILABLE
}

sealed interface BiometricOutcome {
    /** [cipher] is the same object handed in, now authorised by the Keystore for one operation. */
    data class Success(val cipher: Cipher?) : BiometricOutcome

    /** Terminal error - the prompt has already dismissed itself. */
    data class Error(val code: Int, val message: String) : BiometricOutcome

    data object Cancelled : BiometricOutcome
}

/**
 * Coroutine wrapper around [BiometricPrompt].
 *
 * `BIOMETRIC_STRONG` only, with no `DEVICE_CREDENTIAL` fallback. That is a deliberate restriction:
 * device-credential auth cannot gate a per-use Keystore key the way a strong biometric can, and
 * allowing it would mean a shoulder-surfed PIN opens the vault. The master passphrase is the
 * intended non-biometric route, and it is a secret this app owns rather than the lockscreen's.
 */
class BiometricAuthenticator(private val context: Context) {

    fun availability(): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }

    /**
     * Intent to the system enrollment screen. Returned rather than launched, so that starting an
     * activity stays the caller's decision instead of a side effect of asking a question.
     */
    fun enrollmentIntent(): Intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
        putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG)
    }

    /**
     * Shows the prompt and suspends until it resolves.
     *
     * `onAuthenticationFailed` (a finger that did not match) is intentionally *not* resolved: the
     * system prompt stays open and lets the user try again, so resuming there would both leak a
     * dangling continuation and dismiss a prompt the user is still using. Only success and terminal
     * errors complete the coroutine.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButton: String = "Use passphrase",
        cryptoObject: BiometricPrompt.CryptoObject? = null
    ): BiometricOutcome = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) {
                            continuation.resume(BiometricOutcome.Success(result.cryptoObject?.cipher))
                        }
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                                code == BiometricPrompt.ERROR_USER_CANCELED ||
                                code == BiometricPrompt.ERROR_CANCELED
                            ) {
                                BiometricOutcome.Cancelled
                            } else {
                                BiometricOutcome.Error(code, message.toString())
                            }
                        )
                    }
                }
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText(negativeButton)
                .setConfirmationRequired(false)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }

            if (cryptoObject != null) prompt.authenticate(info, cryptoObject) else prompt.authenticate(info)
        }
    }
}
