package com.personal.bubuprotect.core.shield.recorder

import com.personal.bubuprotect.domain.model.RiskSignal
import com.personal.bubuprotect.domain.model.ShieldEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A bounded, in-memory log of what the apps on this phone were observed doing.
 *
 * ### The one idea this whole feature rests on
 *
 * Every other adware tool scores permission lists. This records behaviour, and behaviour is what makes
 * an accusation checkable. When the user says "an ad just hit me", [replay] hands back the last minute
 * of attributed events and the culprit is a fact rather than a ranking.
 *
 * ### Why it is bounded and never written to disk
 *
 * Both for the same reason, and it is not performance. A durable, growing log of which apps the user
 * had on screen and when is a behavioural profile of the user - a far more sensitive object than the
 * adware it was built to catch, and one this app has no business creating. So the buffer is a fixed
 * ring in memory, it is dropped when the process dies, and there is no persistence path to add later
 * without revisiting that decision on purpose.
 *
 * [CAPACITY] events at the rate the sensors actually produce them covers well over the minute the
 * panic button asks about, with headroom for an app in a tight overlay loop. The oldest event falling
 * off the end is the intended behaviour, not a limit to work around.
 *
 * ### Thread safety
 *
 * Three sensors write from three different threads - the accessibility service's callback thread, the
 * notification listener's, and the VPN's packet loop - and the UI reads while they do. Every access
 * goes through one lock. The critical section is an array write and a flow publish, measured in
 * microseconds, and lock-free structures here would buy nothing while making the ordering guarantee
 * that [replay] depends on much harder to reason about.
 *
 * @param clock injected so the window arithmetic in [replay] and [notificationRate] can be tested
 *   without sleeping, matching [com.personal.bubuprotect.core.security.DeviceThreatScanner].
 */
class FlightRecorder(
    private val capacity: Int = CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = Any()

    /** Insertion-ordered, oldest first. Bounded by [capacity]. */
    private val buffer = ArrayDeque<ShieldEvent>(capacity)

    private val _latest = MutableStateFlow<List<ShieldEvent>>(emptyList())

    /**
     * The buffer, for the UI to observe.
     *
     * Published as an immutable snapshot on every write rather than as a mutable view, because a
     * Compose recomposition reading a list another thread is appending to is a race the type system
     * will not catch. Snapshotting costs one array copy per event, at event rates in the tens per
     * minute.
     */
    val latest: StateFlow<List<ShieldEvent>> = _latest.asStateFlow()

    fun record(event: ShieldEvent) {
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(event)
            _latest.value = buffer.toList()
        }
    }

    /** Drops everything. Called when the vault locks, so a session's observations do not outlive it. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _latest.value = emptyList()
        }
    }

    /**
     * Everything recorded in the last [windowMillis], newest first.
     *
     * This is the panic button. Newest first because the answer the user wants is almost always the
     * most recent event, and a list they have to scroll to the bottom of to find it is a worse answer.
     */
    fun replay(windowMillis: Long = PANIC_WINDOW_MILLIS): List<ShieldEvent> {
        val cutoff = clock() - windowMillis
        return synchronized(lock) { buffer.filter { it.at >= cutoff } }.reversed()
    }

    /**
     * Behavioural signals earned by [packageName] within [windowMillis].
     *
     * ### Why an overlay has to be full-screen to count
     *
     * A small floating window is how chat heads, screen recorders, volume sliders and accessibility
     * buttons work. Counting those as [RiskSignal.DREW_OVERLAY] would flag every one of them and the
     * signal would mean nothing. A full-screen window drawn over another app is the thing the user is
     * actually complaining about.
     *
     * ### Why the notification rate is a rate
     *
     * A messenger can legitimately post twenty notifications in a busy minute. What no honest app does
     * is post [NOTIFICATION_FLOOD_THRESHOLD] into a single channel inside the window, which is why the
     * count is per-channel and excludes ongoing notifications - a foreground service or media player
     * posts one permanent notification that would otherwise re-count on every update.
     */
    fun signalsFor(
        packageName: String,
        windowMillis: Long = SCORING_WINDOW_MILLIS
    ): Set<RiskSignal> {
        val cutoff = clock() - windowMillis
        val mine = synchronized(lock) {
            buffer.filter { it.packageName == packageName && it.at >= cutoff }
        }

        return buildSet {
            if (mine.any { it is ShieldEvent.OverlayDrawn && it.fullScreen }) {
                add(RiskSignal.DREW_OVERLAY)
            }
            if (mine.any { it is ShieldEvent.SelfLaunched }) add(RiskSignal.SELF_LAUNCHED)
            if (mine.any { it is ShieldEvent.AdHostResolved }) add(RiskSignal.AD_NETWORK_TRAFFIC)
            if (notificationRate(mine) >= NOTIFICATION_FLOOD_THRESHOLD) {
                add(RiskSignal.NOTIFICATION_FLOOD)
            }
        }
    }

    /** Distinct ad hosts [packageName] resolved in the window, for the evidence card's detail line. */
    fun adHostsFor(
        packageName: String,
        windowMillis: Long = SCORING_WINDOW_MILLIS
    ): Set<String> {
        val cutoff = clock() - windowMillis
        return synchronized(lock) {
            buffer.filterIsInstance<ShieldEvent.AdHostResolved>()
                .filter { it.packageName == packageName && it.at >= cutoff }
                .mapTo(mutableSetOf()) { it.host }
        }
    }

    /** How many times [packageName] was seen drawing a full-screen overlay, for the evidence card. */
    fun overlayCountFor(
        packageName: String,
        windowMillis: Long = SCORING_WINDOW_MILLIS
    ): Int {
        val cutoff = clock() - windowMillis
        return synchronized(lock) {
            buffer.count {
                it is ShieldEvent.OverlayDrawn &&
                    it.fullScreen &&
                    it.packageName == packageName &&
                    it.at >= cutoff
            }
        }
    }

    /** Highest per-channel notification count, ignoring the permanent ones. */
    private fun notificationRate(events: List<ShieldEvent>): Int = events
        .filterIsInstance<ShieldEvent.NotificationPosted>()
        .filterNot(ShieldEvent.NotificationPosted::ongoing)
        .groupingBy { it.channelId ?: "" }
        .eachCount()
        .values
        .maxOrNull()
        ?: 0

    companion object {

        /**
         * Ring size.
         *
         * Sized against the panic window rather than against memory: at the tens-of-events-per-minute
         * the sensors realistically produce, this holds far more than the last minute, and an app in a
         * pathological overlay loop still cannot push the interesting events out inside the window it
         * is being judged on.
         */
        const val CAPACITY = 512

        /** What "just now" means to the panic button. One minute of memory is what a user has. */
        const val PANIC_WINDOW_MILLIS = 60_000L

        /** Window the behavioural signals are computed over. */
        const val SCORING_WINDOW_MILLIS = 60 * 60_000L

        /**
         * Notifications into one channel, in one hour, that no honest app posts.
         *
         * Set high on purpose. A busy group chat can clear twenty; the cost of a false flood flag is
         * accusing a messenger the user depends on, and the cost of a missed one is that an ad-spammer
         * gets convicted a few minutes later on an overlay instead.
         */
        const val NOTIFICATION_FLOOD_THRESHOLD = 30
    }
}
