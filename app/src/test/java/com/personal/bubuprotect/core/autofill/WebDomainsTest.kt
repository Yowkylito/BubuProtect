package com.personal.bubuprotect.core.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDomainsTest {

    @Test
    fun `normalises the shapes browsers actually report`() {
        assertEquals("reddit.com", WebDomains.normalize("reddit.com"))
        assertEquals("reddit.com", WebDomains.normalize("WWW.Reddit.COM"))
        assertEquals("reddit.com", WebDomains.normalize("https://www.reddit.com/login?next=/"))
        assertEquals("reddit.com", WebDomains.normalize("reddit.com."))
        assertEquals("reddit.com", WebDomains.normalize("  reddit.com  "))
    }

    @Test
    fun `strips ports and credentials without losing the host`() {
        assertEquals("example.com", WebDomains.normalize("example.com:8443"))
        assertEquals("example.com", WebDomains.normalize("https://user:secret@example.com/x"))
        assertEquals("::1", WebDomains.normalize("http://[::1]:8080/"))
    }

    @Test
    fun `rejects what is not a host`() {
        assertNull(WebDomains.normalize(null))
        assertNull(WebDomains.normalize(""))
        assertNull(WebDomains.normalize("   "))
        assertNull(WebDomains.normalize("not a host"))
    }

    @Test
    fun `reduces subdomains to the registrable name`() {
        assertEquals("reddit.com", WebDomains.registrable("login.reddit.com"))
        assertEquals("reddit.com", WebDomains.registrable("a.b.c.reddit.com"))
        assertEquals("reddit.com", WebDomains.registrable("reddit.com"))
    }

    @Test
    fun `handles two-label public suffixes`() {
        assertEquals("bbc.co.uk", WebDomains.registrable("www.bbc.co.uk"))
        assertEquals("bbc.co.uk", WebDomains.registrable("bbc.co.uk"))
        assertEquals("nab.com.au", WebDomains.registrable("secure.nab.com.au"))
    }

    /**
     * The property the whole matcher rests on. If a bare suffix could ever be returned, every domain
     * under it would compare equal - so an attacker's `evil.co.uk` would be offered the credentials
     * saved for `bank.co.uk`.
     */
    @Test
    fun `never reduces a host to a bare public suffix`() {
        assertEquals("co.uk", WebDomains.registrable("co.uk"))
        assertFalse(WebDomains.sameSite("bank.co.uk", "evil.co.uk"))
        assertFalse(WebDomains.sameSite("bank.com.au", "evil.com.au"))
    }

    /** An unknown suffix must make matching stricter, never more generous. */
    @Test
    fun `unknown multi-label suffixes fail closed`() {
        assertFalse(WebDomains.sameSite("shop.example.zz", "blog.other.zz"))
    }

    @Test
    fun `leaves single-label hosts and IP literals alone`() {
        assertEquals("localhost", WebDomains.registrable("localhost"))
        assertEquals("192.168.1.10", WebDomains.registrable("192.168.1.10"))
        assertEquals("::1", WebDomains.registrable("::1"))
    }

    @Test
    fun `same site spans subdomains but not neighbours`() {
        assertTrue(WebDomains.sameSite("accounts.google.com", "google.com"))
        assertTrue(WebDomains.sameSite("mail.google.com", "drive.google.com"))
        assertFalse(WebDomains.sameSite("google.com", "google.com.evil.net"))
        assertFalse(WebDomains.sameSite("paypal.com", "paypa1.com"))
    }
}
