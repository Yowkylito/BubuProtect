package com.personal.bubuprotect.ui.vm

import androidx.biometric.BiometricPrompt
import com.personal.bubuprotect.core.security.BiometricOutcome

/**
 * How a ViewModel asks for a biometric prompt without holding an `Activity`.
 *
 * `BiometricPrompt` needs a `FragmentActivity`, and a ViewModel that captured one would outlive it
 * across a configuration change and leak the whole view hierarchy. So the composable layer - which
 * legitimately has the activity and is torn down with it - supplies this, and the ViewModel just
 * awaits a result.
 */
fun interface BiometricGate {
    suspend fun authenticate(
        title: String,
        subtitle: String,
        cryptoObject: BiometricPrompt.CryptoObject?
    ): BiometricOutcome
}
