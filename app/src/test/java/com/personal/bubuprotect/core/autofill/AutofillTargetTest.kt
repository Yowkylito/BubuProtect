package com.personal.bubuprotect.core.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutofillTargetTest {

    @Test
    fun `a web target is keyed on the registrable domain, not the browser`() {
        val chrome = AutofillTarget.of("com.android.chrome", "login.reddit.com", "chrome-signer")
        val firefox = AutofillTarget.of("org.mozilla.firefox", "www.reddit.com", "firefox-signer")

        assertEquals("web:reddit.com", chrome.key)
        assertEquals(chrome.key, firefox.key)
    }

    /**
     * A link learned in one browser has to work in the next one. Pinning the signer here would make
     * the credential quietly stop being offered after switching browsers, which is indistinguishable
     * from the matcher being broken.
     */
    @Test
    fun `a web target carries no signer`() {
        val target = AutofillTarget.of("com.android.chrome", "reddit.com", "chrome-signer")
        assertNull(target.signature)
    }

    /**
     * The opposite for native apps: the package name is a label anyone can claim, so the signer is
     * what a learned link is actually pinned to.
     */
    @Test
    fun `an app target keeps its signer`() {
        val target = AutofillTarget.of("com.reddit.frontpage", null, "reddit-signer")
        assertEquals("app:com.reddit.frontpage", target.key)
        assertEquals("reddit-signer", target.signature)
    }

    @Test
    fun `an unusable web domain falls back to the package`() {
        val target = AutofillTarget.of("com.example.app", "   ", "signer")
        assertEquals("app:com.example.app", target.key)
        assertEquals("signer", target.signature)
    }
}
