package com.personal.bubuprotect.core.shield

import com.personal.bubuprotect.core.shield.intel.SignerBlocklist
import com.personal.bubuprotect.core.shield.network.DomainBlocklist
import com.personal.bubuprotect.core.shield.recorder.FlightRecorder
import com.personal.bubuprotect.domain.model.RiskSignal
import com.personal.bubuprotect.domain.model.ShieldEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suffix matching is where an ad-domain list quietly over- or under-blocks. */
class DomainBlocklistTest {

    private val list = DomainBlocklist.of(listOf("doubleclick.net", "applovin.com"))

    @Test
    fun `matches the entry itself and its subdomains`() {
        assertTrue(list.matches("doubleclick.net"))
        assertTrue(list.matches("stats.g.doubleclick.net"))
        assertTrue(list.matches("a.b.c.d.applovin.com"))
    }

    @Test
    fun `does not match a domain that merely ends with the same letters`() {
        // The reason matching requires a dot boundary rather than a plain endsWith. Without it this
        // would block an unrelated site whose name happens to end the same way.
        assertFalse(list.matches("notdoubleclick.net"))
        assertFalse(list.matches("mydoubleclick.net"))
    }

    @Test
    fun `does not match a parent of an entry`() {
        assertFalse(list.matches("net"))
        assertFalse(list.matches("com"))
    }

    @Test
    fun `an empty list matches nothing`() {
        assertFalse(DomainBlocklist.Empty.matches("doubleclick.net"))
    }
}

/**
 * The blocklist ships empty, and this pins that as a *working* state rather than a broken one.
 *
 * If loading an empty list ever started throwing or matching everything, the shield would either crash
 * on open or accuse every app on the phone.
 */
class SignerBlocklistTest {

    @Test
    fun `the empty list convicts nothing`() {
        assertTrue(SignerBlocklist.Empty.isEmpty)
        assertFalse(SignerBlocklist.Empty.matches(setOf("A".repeat(64))))
    }

    @Test
    fun `matches any certificate in the set so key rotation cannot evade it`() {
        val retired = "A".repeat(64)
        val current = "B".repeat(64)
        val list = SignerBlocklist.of(listOf(retired))

        // The app now signs with `current`, but its history still carries `retired`.
        assertTrue(list.matches(setOf(current, retired)))
    }

    @Test
    fun `malformed entries are dropped rather than loaded as unmatchable`() {
        val list = SignerBlocklist.of(listOf("not-a-hash", "", "0".repeat(63)))

        assertEquals(0, list.size)
    }

    @Test
    fun `accepts the separator styles public sources publish`() {
        val bare = "A".repeat(64)
        val colons = bare.chunked(2).joinToString(":")
        val list = SignerBlocklist.of(listOf(colons.lowercase()))

        assertTrue(list.matches(setOf(bare)))
    }

    @Test
    fun `an empty fingerprint set never matches`() {
        // An app whose signer could not be read must not be convicted by a list it was never compared
        // against.
        assertFalse(SignerBlocklist.of(listOf("A".repeat(64))).matches(emptySet()))
    }
}

/** The recorder is where "possible" becomes "observed", so its thresholds are load-bearing. */
class FlightRecorderTest {

    private var now = 1_000_000L
    private fun recorder(capacity: Int = 512) = FlightRecorder(capacity) { now }

    @Test
    fun `a small overlay does not count as drawing over another app`() {
        // Chat heads, screen recorders and volume sliders are all small overlays. Counting them would
        // flag every one of them and make the signal worthless.
        val recorder = recorder()
        recorder.record(ShieldEvent.OverlayDrawn("com.chat.heads", now, "com.browser", false))

        assertFalse(RiskSignal.DREW_OVERLAY in recorder.signalsFor("com.chat.heads"))
    }

    @Test
    fun `a full-screen overlay counts`() {
        val recorder = recorder()
        recorder.record(ShieldEvent.OverlayDrawn("com.bad.app", now, "com.browser", true))

        assertTrue(RiskSignal.DREW_OVERLAY in recorder.signalsFor("com.bad.app"))
        assertEquals(1, recorder.overlayCountFor("com.bad.app"))
    }

    @Test
    fun `events outside the scoring window are not counted`() {
        val recorder = recorder()
        recorder.record(ShieldEvent.OverlayDrawn("com.bad.app", now, null, true))

        now += FlightRecorder.SCORING_WINDOW_MILLIS + 1

        assertTrue(recorder.signalsFor("com.bad.app").isEmpty())
        assertEquals(0, recorder.overlayCountFor("com.bad.app"))
    }

    @Test
    fun `a busy messenger does not read as a notification flood`() {
        val recorder = recorder()
        repeat(FlightRecorder.NOTIFICATION_FLOOD_THRESHOLD - 1) {
            recorder.record(ShieldEvent.NotificationPosted("com.messenger", now, "chat", false))
        }

        assertFalse(RiskSignal.NOTIFICATION_FLOOD in recorder.signalsFor("com.messenger"))
    }

    @Test
    fun `crossing the threshold in one channel is a flood`() {
        val recorder = recorder()
        repeat(FlightRecorder.NOTIFICATION_FLOOD_THRESHOLD) {
            recorder.record(ShieldEvent.NotificationPosted("com.spam", now, "ads", false))
        }

        assertTrue(RiskSignal.NOTIFICATION_FLOOD in recorder.signalsFor("com.spam"))
    }

    @Test
    fun `ongoing notifications never count toward a flood`() {
        // A foreground service or media player posts one permanent notification that updates
        // constantly. Counting updates would flag every music app on the phone.
        val recorder = recorder()
        repeat(FlightRecorder.NOTIFICATION_FLOOD_THRESHOLD * 3) {
            recorder.record(ShieldEvent.NotificationPosted("com.player", now, "playback", true))
        }

        assertFalse(RiskSignal.NOTIFICATION_FLOOD in recorder.signalsFor("com.player"))
    }

    @Test
    fun `a flood is per channel, not across all of them`() {
        val recorder = recorder()
        repeat(FlightRecorder.NOTIFICATION_FLOOD_THRESHOLD) {
            recorder.record(ShieldEvent.NotificationPosted("com.app", now, "channel-$it", false))
        }

        assertFalse(RiskSignal.NOTIFICATION_FLOOD in recorder.signalsFor("com.app"))
    }

    @Test
    fun `signals are attributed to the right app`() {
        val recorder = recorder()
        recorder.record(ShieldEvent.OverlayDrawn("com.bad.app", now, null, true))

        assertTrue(recorder.signalsFor("com.innocent.app").isEmpty())
    }

    @Test
    fun `the ring buffer drops the oldest rather than growing`() {
        val recorder = recorder(capacity = 3)
        repeat(5) {
            recorder.record(ShieldEvent.SelfLaunched("com.app.$it", now))
        }

        val replayed = recorder.replay()
        assertEquals(3, replayed.size)
        // Newest first, so the most recent event is the first thing the user reads.
        assertEquals("com.app.4", replayed.first().packageName)
    }

    @Test
    fun `replay only returns the panic window`() {
        val recorder = recorder()
        recorder.record(ShieldEvent.SelfLaunched("com.old", now))

        now += FlightRecorder.PANIC_WINDOW_MILLIS + 1
        recorder.record(ShieldEvent.SelfLaunched("com.fresh", now))

        assertEquals(listOf("com.fresh"), recorder.replay().map(ShieldEvent::packageName))
    }

    @Test
    fun `ad hosts are collected per app for the evidence card`() {
        val recorder = recorder()
        recorder.record(ShieldEvent.AdHostResolved("com.bad", now, "doubleclick.net", false))
        recorder.record(ShieldEvent.AdHostResolved("com.bad", now, "applovin.com", true))
        recorder.record(ShieldEvent.AdHostResolved("com.bad", now, "doubleclick.net", false))

        assertEquals(setOf("doubleclick.net", "applovin.com"), recorder.adHostsFor("com.bad"))
    }

    @Test
    fun `clear drops everything`() {
        val recorder = recorder()
        recorder.record(ShieldEvent.OverlayDrawn("com.bad", now, null, true))
        recorder.clear()

        assertTrue(recorder.replay().isEmpty())
        assertTrue(recorder.signalsFor("com.bad").isEmpty())
    }
}
