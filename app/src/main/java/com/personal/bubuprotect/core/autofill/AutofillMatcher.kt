package com.personal.bubuprotect.core.autofill

import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem

/** Why an entry was offered, strongest first. Ordering the enum *is* the ranking. */
enum class MatchConfidence {
    /** The user has picked this entry for this exact target before. */
    LINKED,

    /** The stored website is the site being filled. */
    EXACT_DOMAIN,

    /** The stored website is a sibling of it - `accounts.google.com` against `google.com`. */
    SAME_SITE,

    /** Package name or label suggests it. A guess, shown but never acted on unprompted. */
    HEURISTIC
}

data class AutofillMatch(val item: VaultItem, val confidence: MatchConfidence)

/**
 * Chooses which entries to offer for a request.
 *
 * Pure, and separate from everything that touches the platform, because this is where the security
 * property of autofill actually lives. Clipboard copy-paste asks the user to decide whether the app
 * in front of them is the one the password belongs to; this decides it with a string comparison the
 * user cannot be socially engineered out of.
 *
 * ### Why cards ignore the target entirely
 *
 * A payment card is not bound to a site - the whole point of a card is that it works at any
 * merchant. Domain matching a card would mean the user is offered nothing at every shop they have
 * not bought from before, which trains them to reach for the physical card and type it in. So a card
 * request is answered with the vault's cards, and the *user* picks. There is no phishing property to
 * lose here that the card's own presence on a page does not already concede.
 *
 * Logins are the opposite, and get the strict treatment.
 */
internal object AutofillMatcher {

    /**
     * @param linkedEntryIds entries the user has already chosen for this target. Ranked first, and
     *   the reason a native app the heuristics cannot read gets better every time it is used.
     */
    fun match(
        items: List<VaultItem>,
        target: AutofillTarget,
        linkedEntryIds: Set<String>,
        kind: ItemKind
    ): List<AutofillMatch> = when (kind) {
        ItemKind.CARD -> items
            .filter { it.kind == ItemKind.CARD }
            .map { AutofillMatch(it, confidenceOfLink(it, linkedEntryIds)) }
            .sortedWith(compareBy({ it.confidence.ordinal }, { -it.item.updatedAt }))

        else -> items
            .filter { it.kind == ItemKind.LOGIN }
            .mapNotNull { item ->
                confidenceFor(item, target, linkedEntryIds)?.let { AutofillMatch(item, it) }
            }
            .sortedWith(compareBy({ it.confidence.ordinal }, { it.item.label.lowercase() }))
    }

    private fun confidenceOfLink(item: VaultItem, linked: Set<String>): MatchConfidence =
        if (item.id in linked) MatchConfidence.LINKED else MatchConfidence.HEURISTIC

    private fun confidenceFor(
        item: VaultItem,
        target: AutofillTarget,
        linked: Set<String>
    ): MatchConfidence? {
        if (item.id in linked) return MatchConfidence.LINKED

        val stored = WebDomains.normalize(item.website)

        if (target.webDomain != null) {
            if (stored != null) {
                if (stored == target.webDomain) return MatchConfidence.EXACT_DOMAIN
                if (WebDomains.sameSite(stored, target.webDomain)) return MatchConfidence.SAME_SITE
            }
            // An entry with no website, or one that points somewhere else, can still be the right
            // entry - "Reddit" against reddit.com. Matched on the site's registrable name only, so
            // this cannot fire on a shared suffix.
            if (labelMatches(item, siteName(target.webDomain))) return MatchConfidence.HEURISTIC
            return null
        }

        // Native app. There is no domain to compare, so the package name is all there is.
        val tokens = packageTokens(target.packageName)
        if (tokens.isEmpty()) return null

        if (stored != null && siteName(stored) in tokens) return MatchConfidence.HEURISTIC
        if (tokens.any { labelMatches(item, it) }) return MatchConfidence.HEURISTIC
        return null
    }

    /** `reddit.com` -> `reddit`. The registrable name without its suffix. */
    private fun siteName(host: String): String =
        WebDomains.registrable(host).substringBefore('.')

    /**
     * Whether the entry's own label names [candidate].
     *
     * Compared on a stripped form so "Reddit", "reddit " and "Reddit!" are one thing, and only on
     * equality - a `contains` here would match the entry labelled "Work" against any package with
     * `work` anywhere in it, which is how a manager starts offering the wrong credential.
     */
    private fun labelMatches(item: VaultItem, candidate: String): Boolean {
        if (candidate.length < MIN_TOKEN_LENGTH) return false
        return item.label.strip() == candidate
    }

    /**
     * The parts of a package name that could plausibly name the product.
     *
     * `com.reddit.frontpage` yields `reddit` and `frontpage`. The noise list is what is left when
     * the reverse-DNS convention is stripped: TLDs at the front, and the words every vendor uses for
     * "this is the app". Short tokens are dropped too, because a two-letter fragment matches far too
     * much to be evidence of anything.
     */
    private fun packageTokens(packageName: String): List<String> =
        packageName.lowercase()
            .split('.')
            .filter { it.length >= MIN_TOKEN_LENGTH && it !in PACKAGE_NOISE }

    private fun String.strip(): String =
        lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private const val MIN_TOKEN_LENGTH = 3

    private val PACKAGE_NOISE = setOf(
        "com", "org", "net", "www", "app", "apps", "android", "mobile", "client", "free",
        "main", "ltd", "inc", "gmbh", "corp", "group", "com2", "beta", "prod", "release"
    )
}
