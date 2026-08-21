package com.personal.bubuprotect.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the promotion rules in [AppVerdict].
 *
 * The whole product claim is that capability narrows the search and behaviour makes the accusation, so
 * the test that matters most is the one asserting a pile of permissions **cannot** convict an app. If
 * that ever loosens, this becomes the permission scorer it was built to beat.
 */
class AppRiskTest {

    private fun verdict(vararg signals: RiskSignal) = AppVerdict(
        packageName = "com.example.app",
        label = "Example",
        signerSha256 = null,
        signals = signals.toSet(),
        firstInstalledAt = 0L
    )

    @Test
    fun `capability signals alone never convict, however many pile up`() {
        val everyCapabilitySignal = RiskSignal.entries
            .filterNot(RiskSignal::behavioural)
            .filterNot { it == RiskSignal.KNOWN_BAD_SIGNER }
            .toTypedArray()

        val subject = verdict(*everyCapabilitySignal)

        assertTrue("should clear the threshold", subject.score >= RiskSignal.SUSPECT_THRESHOLD)
        assertEquals(Conviction.SUSPECT, subject.conviction)
    }

    @Test
    fun `an ordinary messenger stays clean`() {
        // Overlay for call bubbles, boot persistence for message sync. Both legitimate, and their
        // combined weight has to stay under the threshold or every messenger gets flagged.
        val subject = verdict(RiskSignal.CAN_DRAW_OVERLAYS, RiskSignal.BOOT_PERSISTENCE)

        assertTrue(subject.score < RiskSignal.SUSPECT_THRESHOLD)
        assertEquals(Conviction.CLEAN, subject.conviction)
    }

    @Test
    fun `behaviour plus capability convicts`() {
        val subject = verdict(
            RiskSignal.CAN_DRAW_OVERLAYS,
            RiskSignal.SIDELOADED,
            RiskSignal.DREW_OVERLAY
        )

        assertEquals(Conviction.CONVICTED, subject.conviction)
        assertTrue(subject.hasBehaviouralEvidence)
    }

    @Test
    fun `a blocklisted signer convicts on its own`() {
        // No local observation needed: the observation already happened on someone else's phone.
        assertEquals(Conviction.CONVICTED, verdict(RiskSignal.KNOWN_BAD_SIGNER).conviction)
    }

    @Test
    fun `one weak behavioural signal is not enough to convict`() {
        // A single self-launch could be a legitimate alarm firing. Behaviour is necessary for a
        // conviction, not sufficient for one.
        val subject = verdict(RiskSignal.SELF_LAUNCHED)

        assertTrue(subject.hasBehaviouralEvidence)
        assertEquals(Conviction.CLEAN, subject.conviction)
    }

    @Test
    fun `evidence is ordered heaviest first so the card leads with the strongest line`() {
        val subject = verdict(
            RiskSignal.ON_PROBATION,
            RiskSignal.DREW_OVERLAY,
            RiskSignal.SIDELOADED
        )

        assertEquals(
            listOf(RiskSignal.DREW_OVERLAY, RiskSignal.SIDELOADED, RiskSignal.ON_PROBATION),
            subject.evidence
        )
    }

    @Test
    fun `display name falls back to the package name only when no label resolved`() {
        assertEquals("Example", verdict().displayName)

        val unlabelled = verdict().copy(label = null)
        assertEquals("com.example.app", unlabelled.displayName)

        val blankLabel = verdict().copy(label = "   ")
        assertEquals("com.example.app", blankLabel.displayName)
    }
}
