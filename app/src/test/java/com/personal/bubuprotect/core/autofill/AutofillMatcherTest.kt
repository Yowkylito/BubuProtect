package com.personal.bubuprotect.core.autofill

import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which entries get offered to which app.
 *
 * This is where autofill's security property lives. Copy-and-paste asks the user to decide whether
 * the app in front of them is the one a password belongs to; these rules decide it instead, and the
 * cases that matter most are the ones where the answer has to be "nothing".
 */
class AutofillMatcherTest {

    private val reddit = item("reddit", ItemKind.LOGIN, "Reddit", "me@example.com", "reddit.com")
    private val redditWork = item("reddit-work", ItemKind.LOGIN, "Reddit work", "w@example.com", "reddit.com")
    private val bank = item("bank", ItemKind.LOGIN, "Bank", "me", "bank.co.uk")
    private val gmail = item("gmail", ItemKind.LOGIN, "Gmail", "me@gmail.com", null)
    private val visa = item("visa", ItemKind.CARD, "Visa", "R Tangonan", null)

    private val everything = listOf(reddit, redditWork, bank, gmail, visa)

    @Test
    fun `matches an exact domain`() {
        val matches = match(everything, webTarget("reddit.com"))
        assertEquals(listOf("reddit", "reddit-work"), matches.map { it.item.id })
        assertTrue(matches.all { it.confidence == MatchConfidence.EXACT_DOMAIN })
    }

    @Test
    fun `matches a subdomain as the same site`() {
        val matches = match(everything, webTarget("login.reddit.com"))
        assertEquals(
            listOf(MatchConfidence.SAME_SITE, MatchConfidence.SAME_SITE),
            matches.map { it.confidence }
        )
    }

    /**
     * The phishing case, stated as a test. A lookalike domain and a domain that merely shares a
     * public suffix must both come back empty.
     */
    @Test
    fun `offers nothing to a lookalike or a suffix neighbour`() {
        assertTrue(match(everything, webTarget("paypa1.com")).isEmpty())
        assertTrue(match(everything, webTarget("evil.co.uk")).isEmpty())
        assertTrue(match(everything, webTarget("reddit.com.evil.net")).isEmpty())
    }

    @Test
    fun `a learned link outranks everything and survives a mismatched domain`() {
        val matches = match(everything, webTarget("reddit.com"), linked = setOf("gmail"))
        assertEquals("gmail", matches.first().item.id)
        assertEquals(MatchConfidence.LINKED, matches.first().confidence)
    }

    @Test
    fun `falls back to the entry label for a site with no stored website`() {
        val matches = match(listOf(gmail), webTarget("gmail.com"))
        assertEquals(listOf("gmail"), matches.map { it.item.id })
        assertEquals(MatchConfidence.HEURISTIC, matches.first().confidence)
    }

    // --- Native apps ----------------------------------------------------------------------------

    @Test
    fun `guesses a native app from its package name`() {
        val matches = match(everything, appTarget("com.reddit.frontpage"))
        assertEquals(setOf("reddit", "reddit-work"), matches.mapTo(mutableSetOf()) { it.item.id })
    }

    @Test
    fun `offers nothing for a package that resembles nothing`() {
        assertTrue(match(everything, appTarget("com.google.android.gm")).isEmpty())
    }

    /** And that is exactly the gap the learned link closes. */
    @Test
    fun `a learned link rescues a package no rule could guess`() {
        val matches = match(everything, appTarget("com.google.android.gm"), linked = setOf("gmail"))
        assertEquals(listOf("gmail"), matches.map { it.item.id })
    }

    @Test
    fun `ignores noise words in a package name`() {
        // Every token here is on the noise list, so there is nothing left to match on and the
        // matcher must not fall back to matching everything.
        assertTrue(match(everything, appTarget("com.android.app")).isEmpty())
    }

    // --- Cards ----------------------------------------------------------------------------------

    /**
     * A card is not bound to a merchant. Domain matching it would offer nothing at every shop the
     * user has not bought from before, which teaches them to reach for the physical card instead.
     */
    @Test
    fun `cards are offered regardless of the site`() {
        val matches = AutofillMatcher.match(
            everything,
            AutofillTarget.of("com.some.shop", "shop.example.com"),
            emptySet(),
            ItemKind.CARD
        )
        assertEquals(listOf("visa"), matches.map { it.item.id })
    }

    @Test
    fun `a login request never returns a card`() {
        val matches = match(everything, webTarget("reddit.com"))
        assertTrue(matches.none { it.item.kind == ItemKind.CARD })
    }

    private fun match(
        items: List<VaultItem>,
        target: AutofillTarget,
        linked: Set<String> = emptySet()
    ) = AutofillMatcher.match(items, target, linked, ItemKind.LOGIN)

    private fun webTarget(domain: String) = AutofillTarget.of("com.android.chrome", domain)

    private fun appTarget(packageName: String) = AutofillTarget.of(packageName, null)

    private fun item(
        id: String,
        kind: ItemKind,
        label: String,
        subtitle: String,
        website: String?
    ) = VaultItem(id = id, kind = kind, label = label, subtitle = subtitle, website = website)
}
