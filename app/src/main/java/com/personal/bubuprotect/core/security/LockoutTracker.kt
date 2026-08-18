package com.personal.bubuprotect.core.security

import com.personal.bubuprotect.data.local.VaultKeyStore
import kotlin.math.min

/**
 * Exponential back-off on failed passphrase attempts.
 *
 * PBKDF2 at 210k iterations already caps an on-device guessing rate at a few attempts per second,
 * which is the real defence. This adds the piece the KDF cannot: it makes *interactive* guessing by
 * someone holding the phone impractical, and it does so with state that survives a force-stop,
 * because the counter lives in [VaultKeyStore] rather than in memory.
 *
 * Nothing here wipes the vault after N failures. An auto-wipe turns a shoulder-surfing incident, or
 * a toddler with the phone, into permanent loss of every password the user owns - a worse outcome
 * than the attack it prevents, given the vault is already sealed by a hardware key and a passphrase.
 */
class LockoutTracker(
    private val keyStore: VaultKeyStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    data class Status(val isLockedOut: Boolean, val remainingMillis: Long)

    fun status(): Status {
        val until = keyStore.lockoutUntil
        if (until == 0L) return Status(false, 0L)

        val current = now()
        // A clock wound backwards past the moment the penalty was set means the wall clock is being
        // gamed; keep the lockout in force rather than believing the new reading.
        if (current < keyStore.lockoutSetAt) return Status(true, until - keyStore.lockoutSetAt)

        return if (current >= until) {
            keyStore.recordLockout(0L, 0L)
            Status(false, 0L)
        } else {
            Status(true, until - current)
        }
    }

    /** @return the back-off now in force, or `null` while still inside the free-attempt budget. */
    fun recordFailure(): Status? {
        val attempts = keyStore.failedAttempts + 1
        keyStore.failedAttempts = attempts
        if (attempts <= FREE_ATTEMPTS) return null

        val penaltyStep = attempts - FREE_ATTEMPTS
        val delay = min(BASE_DELAY_MILLIS shl (penaltyStep - 1), MAX_DELAY_MILLIS)
        val current = now()
        keyStore.recordLockout(current + delay, current)
        return Status(true, delay)
    }

    fun recordSuccess() = keyStore.clearLockout()

    val failedAttempts: Int get() = keyStore.failedAttempts

    private companion object {
        /** Typos happen; the first few cost nothing. */
        const val FREE_ATTEMPTS = 3
        const val BASE_DELAY_MILLIS = 30_000L
        const val MAX_DELAY_MILLIS = 60 * 60 * 1000L
    }
}
