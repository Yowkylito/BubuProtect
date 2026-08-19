package com.personal.bubuprotect.ui.vm

import com.personal.bubuprotect.core.security.BiometricAuthenticator
import com.personal.bubuprotect.core.security.BiometricAvailability
import com.personal.bubuprotect.core.security.BiometricOutcome
import com.personal.bubuprotect.data.local.UserPreferences

sealed interface RevealOutcome {
    data object Allowed : RevealOutcome

    /**
     * The user dismissed the prompt.
     *
     * Deliberately carries no message. Someone who just cancelled a prompt does not need to be told
     * that they cancelled a prompt, and a toast saying so is indistinguishable from a failure.
     */
    data object Refused : RevealOutcome

    /** Something the user can act on - a sensor lockout, or strict mode with nothing to check. */
    data class Blocked(val message: String) : RevealOutcome
}

/**
 * The one place that decides whether showing a secret needs a fresh biometric check.
 *
 * Both the editor and the detail screen ask through this, so "strict mode" cannot end up meaning two
 * different things on two screens - which is exactly what happens when each ViewModel keeps its own
 * copy of the rule.
 *
 * @see UserPreferences.strictRevealEnabled
 */
class RevealAuthorizer(
    private val biometrics: BiometricAuthenticator,
    private val preferences: UserPreferences
) {

    val isStrict: Boolean get() = preferences.strictRevealEnabled

    /**
     * @param isAlreadyAuthorised the caller has a live authentication it considers still good - a
     *   field already on screen, or an earlier prompt in the same editor session. Honoured normally,
     *   ignored entirely under strict mode, which is the whole of what strict mode does.
     */
    suspend fun authorize(
        gate: BiometricGate,
        title: String,
        isAlreadyAuthorised: Boolean = false
    ): RevealOutcome {
        if (isAlreadyAuthorised && !isStrict) return RevealOutcome.Allowed

        return when (biometrics.availability()) {
            BiometricAvailability.AVAILABLE ->
                when (val outcome = gate.authenticate(title, "Confirm it is you", null)) {
                    is BiometricOutcome.Success -> RevealOutcome.Allowed
                    BiometricOutcome.Cancelled -> RevealOutcome.Refused
                    is BiometricOutcome.Error -> RevealOutcome.Blocked(outcome.message)
                }

            /*
             * No usable sensor.
             *
             * Normally the unlock the user already passed is the authentication, and that is the
             * long-standing behaviour on this path - a phone with no fingerprint reader still has a
             * usable vault, because the master passphrase already gated the whole session.
             *
             * Strict mode cannot be honoured that way. Its entire promise is a fresh check every
             * time, and quietly allowing the reveal would make the switch a decoration. So it
             * refuses, and says why: re-enrol a biometric, or turn the setting off. Both routes stay
             * open, so this is a recoverable state rather than a vault that has locked its own
             * contents away.
             */
            else -> if (isStrict) RevealOutcome.Blocked(NO_SENSOR_MESSAGE) else RevealOutcome.Allowed
        }
    }

    private companion object {
        const val NO_SENSOR_MESSAGE =
            "Strict mode needs a fingerprint or face unlock on this phone. Add one, or turn " +
                "strict mode off in Settings."
    }
}
