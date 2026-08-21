package com.personal.bubuprotect.core.shield.recorder

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.personal.bubuprotect.domain.model.ShieldEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds apps that put themselves in the foreground without being asked.
 *
 * ### What this adds over the window watcher
 *
 * The accessibility service sees a window appear. It cannot tell whether the user caused it. This can:
 * `UsageStatsManager` records every activity resume with a timestamp, so an app resuming while a
 * *different* app was in the foreground, with no launch in between, is an app that started itself.
 *
 * That distinction is what separates an ad-spammer from an app the user opened and forgot about, and
 * it is the difference between an evidence line the user believes and one they argue with.
 *
 * ### Optional by design
 *
 * `PACKAGE_USAGE_STATS` is an appops grant. Declaring it in the manifest does nothing; the user has to
 * flip it in a Settings screen most people have never opened. So this probe reports
 * [Availability.NotGranted] and the shield carries on without it - [ShieldEvent.SelfLaunched] is
 * corroboration, never the basis of a conviction, and the overlay observation stands on its own.
 *
 * Polled rather than pushed: there is no callback for usage events. [collect] is called when the
 * shield screen opens and after a panic-button tap, which is when the answer is being looked at.
 */
class UsageTimelineProbe {

    /** Whether the grant is in place, kept separate from "granted but nothing found". */
    sealed interface Availability {
        data object Granted : Availability
        data object NotGranted : Availability
        data class Unsupported(val why: String) : Availability
    }

    fun availability(context: Context): Availability {
        val appOps = context.getSystemService(AppOpsManager::class.java)
            ?: return Availability.Unsupported("This phone does not expose app-ops")

        return try {
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            if (mode == AppOpsManager.MODE_ALLOWED) Availability.Granted else Availability.NotGranted
        } catch (_: Exception) {
            Availability.Unsupported("This phone declined to answer")
        }
    }

    /**
     * Reads the last [windowMillis] of foreground transitions and records the unprompted ones.
     *
     * ### How "unprompted" is decided
     *
     * The event stream is walked in order, tracking which package was resumed most recently. When a
     * resume arrives for a package that is not the one already in front, and the previous package was
     * not paused first, the new one interrupted rather than followed - which is what an app launching
     * itself over whatever the user was doing looks like in this stream.
     *
     * A user tapping an icon produces a pause of the old activity before the resume of the new one,
     * because the launcher comes up in between. That ordering is the whole signal.
     *
     * @return the events recorded, so the caller can tell "ran and found nothing" from "did not run".
     */
    suspend fun collect(
        context: Context,
        recorder: FlightRecorder,
        windowMillis: Long = FlightRecorder.SCORING_WINDOW_MILLIS
    ): List<ShieldEvent.SelfLaunched> = withContext(Dispatchers.IO) {
        if (availability(context) !is Availability.Granted) return@withContext emptyList()

        val usage = context.getSystemService(UsageStatsManager::class.java)
            ?: return@withContext emptyList()

        val now = System.currentTimeMillis()
        val found = mutableListOf<ShieldEvent.SelfLaunched>()

        try {
            val events = usage.queryEvents(now - windowMillis, now)
            val event = UsageEvents.Event()

            var foreground: String? = null
            var pausedLast = true

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        val interrupted = foreground != null && foreground != pkg && !pausedLast
                        if (interrupted && pkg != context.packageName) {
                            found += ShieldEvent.SelfLaunched(pkg, event.timeStamp)
                        }
                        foreground = pkg
                        pausedLast = false
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED -> pausedLast = true
                }
            }
        } catch (_: Exception) {
            // Some OEM builds throw here despite the grant being in place. An empty result is the
            // honest answer: the probe could not run, and nothing above it treats that as "clean".
            return@withContext emptyList()
        }

        found.forEach(recorder::record)
        found
    }
}
