package com.personal.bubuprotect.core.shield.recorder

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.personal.bubuprotect.domain.model.ShieldEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Counts notification spam per app, and is the one sensor that can also stop it.
 *
 * ### The only tier that can act rather than only observe
 *
 * Everything else in the shield either watches or asks the user to press something. `cancelNotification`
 * dismisses another app's notification outright, with no dialog and no uninstall - so ad-spam in the
 * shade can be cleared for an app the user still wants to keep. That is the remediation people
 * actually want and it is available here without Shizuku, without root, and without removing anything.
 *
 * ### What it reads
 *
 * Posting package, channel id, timestamp, and whether the notification is ongoing. Not the title, not
 * the text, not the extras. [ShieldEvent.NotificationPosted] has no field for content, which is the
 * enforcement rather than the intention - a log of what the user's notifications *said* would be a
 * worse object to hold than the ad-spam it was built to measure.
 *
 * ### Why nothing is cancelled automatically
 *
 * The temptation is to auto-dismiss anything from a convicted app. It is not taken, because a false
 * positive here silently eats notifications the user needed - a delivery code, a two-factor prompt -
 * and they would never learn it happened. Cancellation is offered as an action on the culprit screen,
 * per app, after the user has seen the evidence. [dismissAllFrom] exists to be called from there.
 */
class ShieldNotificationListener : NotificationListenerService(), KoinComponent {

    private val recorder: FlightRecorder by inject()

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
    }

    override fun onListenerDisconnected() {
        active = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName == packageName) return

        recorder.record(
            ShieldEvent.NotificationPosted(
                packageName = posted.packageName,
                at = posted.postTime,
                // Channel rather than notification id: an app spamming one channel is the pattern,
                // and ids are frequently randomised per post precisely to defeat replacement.
                channelId = posted.notification?.channelId,
                // Foreground-service and media notifications are permanent by design. They update
                // constantly and would otherwise read as a flood from a legitimately running app.
                ongoing = posted.isOngoing
            )
        )
    }

    /**
     * Clears everything currently posted by [target].
     *
     * @return how many were dismissed, or null when the listener is not connected - the caller shows
     *   a different thing for "nothing to clear" than for "Bubu cannot reach the shade", and folding
     *   them together would report success for an action that never ran.
     */
    fun dismissAllFrom(target: String): Int? = try {
        val theirs = activeNotifications?.filter { it.packageName == target } ?: return null
        theirs.forEach { cancelNotification(it.key) }
        theirs.size
    } catch (_: Exception) {
        // SecurityException if the grant was revoked between the check and the call, and
        // RuntimeException from the binder if the system side has already torn down.
        null
    }

    companion object {

        /**
         * The connected instance, or null.
         *
         * A `NotificationListenerService` is constructed by the system, so there is no way to hand a
         * reference to it out through Koin - the direction is inverted. This is the standard way to
         * reach one from application code, and it is a plain nullable rather than anything cleverer
         * because null is the *normal* state: the user has not granted notification access, and every
         * caller has to handle that anyway.
         *
         * Not a leak: the field is cleared in [onListenerDisconnected], and a service instance is
         * process-scoped rather than Activity-scoped.
         */
        @Volatile
        private var active: ShieldNotificationListener? = null

        val isConnected: Boolean get() = active != null

        /** @return dismissed count, or null when notification access is not granted or failed. */
        fun dismissAll(target: String): Int? = active?.dismissAllFrom(target)
    }
}
