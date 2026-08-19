package com.personal.bubuprotect.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules here are the ones that decide whether a user is shown a green badge over a password
 * nothing has verified, so each is pinned rather than left to reading.
 */
class BreachStatusTest {

    @Test
    fun `never checked is unchecked, not safe`() {
        val status = BreachStatus.from(
            exposureCount = BreachStatus.NEVER_CHECKED,
            checkedAt = 0L,
            acknowledgedAt = 0L,
            secretUpdatedAt = 100L
        )

        assertEquals(BreachVerdict.UNCHECKED, status.verdict)
        assertFalse(status.isBreached)
    }

    @Test
    fun `zero exposures with a real timestamp is safe`() {
        val status = BreachStatus.from(
            exposureCount = 0L,
            checkedAt = 200L,
            acknowledgedAt = 0L,
            secretUpdatedAt = 100L
        )

        assertEquals(BreachVerdict.SAFE, status.verdict)
    }

    @Test
    fun `any exposure is breached`() {
        val status = BreachStatus.from(
            exposureCount = 1L,
            checkedAt = 200L,
            acknowledgedAt = 0L,
            secretUpdatedAt = 100L
        )

        assertEquals(BreachVerdict.BREACHED, status.verdict)
        assertEquals(1L, status.exposureCount)
        assertTrue(status.needsAttention)
    }

    @Test
    fun `a verdict older than the secret it describes is discarded`() {
        val status = BreachStatus.from(
            exposureCount = 0L,
            checkedAt = 100L,
            acknowledgedAt = 0L,
            // The password was replaced after that check ran.
            secretUpdatedAt = 200L
        )

        assertEquals(BreachVerdict.UNCHECKED, status.verdict)
    }

    @Test
    fun `a breached verdict older than the secret does not survive either`() {
        val status = BreachStatus.from(
            exposureCount = 5_000L,
            checkedAt = 100L,
            secretUpdatedAt = 200L,
            acknowledgedAt = 0L
        )

        // The user did the right thing and changed it; the old verdict must not keep nagging.
        assertEquals(BreachVerdict.UNCHECKED, status.verdict)
        assertFalse(status.needsAttention)
    }

    @Test
    fun `acknowledging the current verdict silences it`() {
        val status = BreachStatus.from(
            exposureCount = 900L,
            checkedAt = 200L,
            acknowledgedAt = 210L,
            secretUpdatedAt = 100L
        )

        assertTrue(status.isBreached)
        assertTrue(status.isAcknowledged)
        assertFalse(status.needsAttention)
    }

    @Test
    fun `an older acknowledgement does not silence a newer verdict`() {
        val status = BreachStatus.from(
            exposureCount = 900L,
            // A fresh check found it again after the user dismissed the previous one.
            checkedAt = 300L,
            acknowledgedAt = 210L,
            secretUpdatedAt = 100L
        )

        assertFalse(status.isAcknowledged)
        assertTrue(status.needsAttention)
    }

    @Test
    fun `a check run at the same instant as an edit still counts`() {
        // Boundary: the scanner reads secretUpdatedAt and writes checkedAt from the same clock, so
        // equality has to mean "current" or a check on a just-saved entry would never stick.
        val status = BreachStatus.from(
            exposureCount = 0L,
            checkedAt = 100L,
            acknowledgedAt = 0L,
            secretUpdatedAt = 100L
        )

        assertEquals(BreachVerdict.SAFE, status.verdict)
    }

    @Test
    fun `a recent verdict is not due for a recheck, an ancient one is`() {
        val now = 10_000_000_000L
        val fresh = BreachStatus(BreachVerdict.SAFE, checkedAt = now - 1_000L)
        val stale = BreachStatus(
            verdict = BreachVerdict.SAFE,
            checkedAt = now - BreachStatus.RECHECK_AFTER_MILLIS - 1L
        )

        assertFalse(fresh.isDueForRecheck(now))
        assertTrue(stale.isDueForRecheck(now))
        assertTrue(BreachStatus.Unchecked.isDueForRecheck(now))
    }
}
