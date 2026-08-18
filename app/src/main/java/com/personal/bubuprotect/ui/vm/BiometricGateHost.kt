package com.personal.bubuprotect.ui.vm

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.BiometricOutcome
import org.koin.compose.koinInject

/**
 * Builds the [BiometricGate] the ViewModels ask through.
 *
 * This is the composable half of the arrangement documented on [BiometricGate]: the activity is
 * captured here, where it is torn down with the composition, rather than in a ViewModel that would
 * outlive it across a rotation and leak the whole view hierarchy.
 *
 * Keyed on the activity so a recreated activity produces a fresh gate rather than one holding a
 * destroyed `FragmentActivity` that `BiometricPrompt` would refuse to attach to.
 */
@Composable
fun rememberBiometricGate(): BiometricGate {
    val activity = LocalActivity.current as? FragmentActivity
    val authenticator: BiometricAuthenticator = koinInject()

    return remember(activity, authenticator) {
        BiometricGate { title, subtitle, cryptoObject ->
            if (activity == null) {
                // Should not happen in this app - the single activity is a FragmentActivity - but
                // returning an outcome beats crashing on a cast inside an authentication path.
                BiometricOutcome.Error(code = -1, message = "No activity to show the prompt on.")
            } else {
                authenticator.authenticate(
                    activity = activity,
                    title = title,
                    subtitle = subtitle,
                    cryptoObject = cryptoObject
                )
            }
        }
    }
}
