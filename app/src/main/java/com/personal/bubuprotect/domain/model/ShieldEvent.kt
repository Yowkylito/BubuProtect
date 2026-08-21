package com.personal.bubuprotect.domain.model

/**
 * One thing an app was observed doing, attributed and timestamped.
 *
 * ### Why events instead of counters
 *
 * A counter answers "how many times", which is enough to score an app and not enough to convince
 * anybody. The user is being asked to uninstall something, and the only argument that lands is one
 * they can check against their own memory: *"this happened 40 seconds ago, while you were in
 * Chrome."* That needs the individual occurrence, with its time and its context, not a total.
 *
 * It is also what makes the panic button possible. The user taps "an ad just hit me", and the shield
 * replays the last minute rather than guessing from aggregates.
 *
 * ### What is deliberately not in here
 *
 * No content. Not notification text, not window text, not the URL that was resolved. The accessibility
 * service is configured with `canRetrieveWindowContent="false"` so it could not read screen text if
 * this model asked for it, and the notification listener reads only the posting package and channel.
 * There is no field below to put content in, which is the point: a forensic log of what the user's
 * apps displayed to them would be a far worse thing to hold than the problem it solves.
 */
sealed interface ShieldEvent {

    /** Package the event is attributed to. Never null - an unattributable event is not recorded. */
    val packageName: String

    /** Wall clock, from the recorder's injected clock. */
    val at: Long

    /**
     * A window belonging to [packageName] appeared over another app's.
     *
     * The strongest single observation the shield can make, because drawing over another app is what
     * the user is complaining about, described exactly.
     *
     * @param overWhat the package that was on screen underneath, when it could be determined. Not the
     *   accusation, but the part that makes the accusation checkable - "over Chrome" is something the
     *   user can confirm or deny from memory.
     * @param fullScreen whether the overlay covered most of the display. A small floating bubble is a
     *   different thing from a full-screen interstitial, and conflating them would flag every chat
     *   head and screen-recorder button on the phone.
     */
    data class OverlayDrawn(
        override val packageName: String,
        override val at: Long,
        val overWhat: String?,
        val fullScreen: Boolean
    ) : ShieldEvent

    /**
     * [packageName] came to the foreground without the user launching it.
     *
     * Inferred from `UsageStatsManager`: an activity resumed with no launcher interaction and no
     * preceding user-visible transition. Legitimate for alarms and calls, so it is corroboration
     * rather than a finding.
     */
    data class SelfLaunched(
        override val packageName: String,
        override val at: Long
    ) : ShieldEvent

    /**
     * [packageName] posted a notification.
     *
     * Recorded individually rather than counted, because the flood threshold is a rate over a window
     * and a rate cannot be recovered from a running total.
     *
     * @param channelId which channel it went to. An app flooding one channel is spamming; an app
     *   posting across many is more likely doing its job.
     * @param ongoing foreground-service and media notifications are permanent by design and must not
     *   count toward a flood.
     */
    data class NotificationPosted(
        override val packageName: String,
        override val at: Long,
        val channelId: String?,
        val ongoing: Boolean
    ) : ShieldEvent

    /**
     * [packageName] asked to resolve a known ad or tracker host.
     *
     * @param host the hostname asked for. The one piece of content-adjacent data in this model, and
     *   it is here because "made 1,847 requests to 12 ad networks" is unfalsifiable without naming
     *   them. Only hosts already on the shipped list are recorded - a hostname that did not match is
     *   discarded before it reaches the recorder, so this is never a browsing log.
     * @param blocked whether the answer was withheld. False is the normal case: attribution runs for
     *   every app, blocking only for apps the user convicted.
     */
    data class AdHostResolved(
        override val packageName: String,
        override val at: Long,
        val host: String,
        val blocked: Boolean
    ) : ShieldEvent

    /**
     * The signal this event contributes toward a verdict.
     *
     * [NotificationPosted] maps to null because one notification is not a signal - only a *rate* is,
     * and that is computed across the window by the recorder rather than carried by any single event.
     */
    val signal: RiskSignal?
        get() = when (this) {
            is OverlayDrawn -> RiskSignal.DREW_OVERLAY
            is SelfLaunched -> RiskSignal.SELF_LAUNCHED
            is AdHostResolved -> RiskSignal.AD_NETWORK_TRAFFIC
            is NotificationPosted -> null
        }
}
