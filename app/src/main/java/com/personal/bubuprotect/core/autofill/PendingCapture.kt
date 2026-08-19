package com.personal.bubuprotect.core.autofill

import android.os.SystemClock
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A credential the user typed into another app, on its way to the save prompt. */
data class CapturedCredential(
    val target: AutofillTarget,
    val username: String?,
    val secret: String,
    val capturedAt: Long
)

/**
 * Hands a captured credential from the autofill service to the save prompt without putting it in an
 * `Intent`.
 *
 * ### Why not just use intent extras
 *
 * The service and the activity are the same process, but an `Intent` between them is not an
 * in-process handoff: it is marshalled through `ActivityManager` in `system_server`, where the value
 * lands in another process's memory, may be logged by anything watching activity starts, and is
 * retained for as long as the system holds the launch record. For a password typed seconds ago that
 * is a needless second copy in a place this app does not control.
 *
 * So the credential stays on this heap and only a random token travels. The token is meaningless to
 * anyone who intercepts it, because [claim] is single-use and the map lives in this process only.
 *
 * ### Why entries expire
 *
 * A save prompt the user swipes away never claims its token, so without an expiry the credential
 * would sit in memory until the process died. [SWEEP_AFTER_MILLIS] bounds that to about the length
 * of time a save prompt can plausibly stay on screen. The clock is [SystemClock.elapsedRealtime]
 * for the same reason the vault's auto-lock uses it: it cannot be moved by changing the device time.
 */
object PendingCapture {

    private val entries = ConcurrentHashMap<String, CapturedCredential>()
    private val random = SecureRandom()

    /** @return the token to put in the launch intent. */
    fun offer(credential: CapturedCredential): String {
        sweep()
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }
        entries[token] = credential
        return token
    }

    /** Single-use: a claimed token is gone, so a replayed intent gets nothing. */
    fun claim(token: String?): CapturedCredential? {
        sweep()
        return token?.let(entries::remove)
    }

    /** Called on lock, so backgrounding the vault drops anything still waiting. */
    fun clear() = entries.clear()

    private fun sweep() {
        val now = SystemClock.elapsedRealtime()
        entries.entries.removeAll { (_, credential) ->
            now - credential.capturedAt > SWEEP_AFTER_MILLIS
        }
    }

    private const val TOKEN_BYTES = 16
    private const val SWEEP_AFTER_MILLIS = 5 * 60 * 1000L
}
