package com.personal.bubuprotect.core.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RecoveryKitTest {

    private val zone = ZoneId.of("UTC")
    private val moment = ZonedDateTime.of(2026, 8, 20, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val code = "BP1-4K7M-9QRT-2WXZ-5H3N-8PVD-6JYF"

    @Test
    fun `carries the code and the date`() {
        val page = RecoveryKit.render(code, moment, zone)
        assertTrue(page.contains(code))
        assertTrue(page.contains("2026"))
        assertTrue(page.contains("BUBU PROTECT - RECOVERY KIT"))
    }

    @Test
    fun `tells the user how to use it and where to keep it`() {
        val page = RecoveryKit.render(code, moment, zone)
        // The three things someone reading this page cold actually needs.
        assertTrue(page.contains("Forgot your passphrase?"))
        assertTrue(page.contains("new master passphrase"))
        assertTrue(page.contains("create a new recovery kit"))
    }

    /**
     * A page that says "recovery kit, 84 accounts" tells whoever finds it that it is worth pursuing.
     * The only secret on it should be the code itself.
     */
    @Test
    fun `says nothing about what the vault holds`() {
        val page = RecoveryKit.render(code, moment, zone).lowercase()
        listOf("entries", "accounts:", "username", "email address", "device", "android")
            .forEach { leak -> assertFalse(leak, page.contains(leak)) }
    }

    @Test
    fun `warns against keeping it on the same phone`() {
        val page = RecoveryKit.render(code, moment, zone)
        assertTrue(page.contains("Do not store it as a photo"))
    }

    @Test
    fun `file name is sortable and says nothing extra`() {
        assertEquals("2026-08-20", RecoveryKit.dateStamp(moment, zone))
        assertEquals(
            "bubu-recovery-kit-2026-08-20.txt",
            RecoveryKit.fileName(RecoveryKit.dateStamp(moment, zone))
        )
    }
}
