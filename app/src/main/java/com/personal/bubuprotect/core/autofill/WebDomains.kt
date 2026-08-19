package com.personal.bubuprotect.core.autofill

/**
 * Host normalisation for autofill matching.
 *
 * Matching a stored `website` against the site the user is actually looking at is the step that turns
 * autofill from a convenience into an anti-phishing control, so the comparison has to be done on
 * something stable. A user types `https://www.reddit.com/login` into the entry; the browser reports
 * `login.reddit.com`. Both have to reduce to the same key or the credential silently stops being
 * offered - and a user whose manager stops offering the right entry learns to paste instead, which
 * is the habit this whole feature exists to remove.
 *
 * ### Why a curated suffix list and not the Public Suffix List
 *
 * Reducing `login.reddit.com` to `reddit.com` needs to know where the registrable name starts, and
 * that is not derivable from the string: `bbc.co.uk` and `foo.co.uk` are different owners, while
 * `a.example.com` and `b.example.com` are the same one. The authoritative answer is Mozilla's Public
 * Suffix List, which is some fifteen thousand entries that go stale and would have to be shipped and
 * refreshed.
 *
 * The list below covers the multi-label suffixes that appear in practice. What makes shipping a
 * partial list acceptable is [registrable]'s failure mode: it never returns a bare public suffix. If
 * the split would leave nothing but a suffix, the *whole host* is returned instead. So an unknown
 * suffix makes matching stricter - `shop.example.pt` then matches only itself, not `blog.example.pt`
 * - and never more generous.
 *
 * That direction is the whole point. The dangerous mistake is collapsing `evil.co.uk` and
 * `bank.co.uk` onto a shared `co.uk` key, because that hands a bank credential to whoever registered
 * a name under the same suffix. Being too strict costs the user one tap in the picker; being too
 * loose costs them the account.
 */
internal object WebDomains {

    /**
     * Turns whatever the assist structure reported into a bare lowercase host, or null.
     *
     * `ViewNode.getWebDomain()` is usually a host already, but browsers have been observed reporting
     * a full URL, and a hostile page has a hand in what its own fields declare - so this parses
     * defensively rather than trusting the shape. Anything that does not reduce to something
     * plausibly a host is dropped, because a value that gets past here becomes an identity that
     * credentials are keyed to.
     */
    fun normalize(raw: String?): String? {
        var value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null

        value = value.substringAfter("://")
        // Path, query and fragment go before the port is looked for, so `host:8080/a?b` reduces
        // correctly; credentials go after, so `user:pw@host` does not lose its host to the colon.
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        value = value.substringAfterLast('@')

        // IPv6 literals are bracketed and full of colons that are not a port separator.
        value = if (value.startsWith("[")) {
            value.substringBefore(']').removePrefix("[")
        } else {
            value.substringBefore(':')
        }

        value = value.trimEnd('.').removePrefix("www.")

        if (value.isEmpty()) return null
        if (value.any(Char::isWhitespace)) return null
        return value
    }

    /**
     * The registrable name plus its suffix - the unit of ownership two hosts have to share before
     * they can be treated as the same site.
     *
     * Single-label hosts (`localhost`, an intranet name) and IP literals come back unchanged: there
     * is no suffix to strip, and inventing one would merge hosts that have nothing to do with each
     * other.
     */
    fun registrable(host: String): String {
        if (host.isEmpty()) return host
        if (!host.contains('.')) return host
        if (isIpLiteral(host)) return host

        val labels = host.split('.')
        val take = knownSuffixLength(labels) + 1

        // The guard the class doc describes: never return a bare public suffix. `co.uk` is not a
        // site, and treating it as one would make every `.co.uk` domain match every other.
        if (take > labels.size) return host

        return labels.takeLast(take).joinToString(".")
    }

    /** Whether two hosts belong to the same registrable site. */
    fun sameSite(host: String, other: String): Boolean =
        registrable(host) == registrable(other)

    /** How many trailing labels form the public suffix: two for `co.uk`, otherwise one. */
    private fun knownSuffixLength(labels: List<String>): Int {
        if (labels.size >= 3 && labels.takeLast(2).joinToString(".") in MULTI_LABEL_SUFFIXES) {
            return 2
        }
        return 1
    }

    /** Crude on purpose: both families only need to be recognised well enough to be left alone. */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }

    /**
     * Two-label public suffixes, by the registries that actually use them.
     *
     * Not exhaustive, and does not need to be - see the class doc on why an omission makes matching
     * stricter rather than unsafe. Entries are the `sld.tld` forms under which the public registers
     * names directly.
     */
    private val MULTI_LABEL_SUFFIXES: Set<String> = setOf(
        // United Kingdom and Ireland
        "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk", "ac.uk", "gov.uk",
        "co.ie",
        // South and South-East Asia
        "co.in", "net.in", "org.in", "firm.in", "gen.in", "ind.in", "ac.in", "gov.in",
        "co.id", "or.id", "ac.id", "web.id", "sch.id", "my.id", "biz.id", "go.id",
        "com.sg", "net.sg", "org.sg", "per.sg", "gov.sg", "edu.sg",
        "com.my", "net.my", "org.my", "name.my", "gov.my", "edu.my",
        "co.th", "in.th", "or.th", "ac.th", "go.th",
        "com.vn", "net.vn", "org.vn", "gov.vn", "edu.vn",
        "com.ph", "net.ph", "org.ph", "gov.ph", "edu.ph",
        "com.pk", "net.pk", "org.pk", "gov.pk", "edu.pk",
        "com.bd", "net.bd", "org.bd", "gov.bd", "edu.bd",
        "com.np", "com.lk", "com.kh", "com.mm",
        // East Asia
        "co.jp", "or.jp", "ne.jp", "ac.jp", "ad.jp", "ed.jp", "go.jp", "gr.jp", "lg.jp",
        "co.kr", "or.kr", "ne.kr", "re.kr", "pe.kr", "go.kr", "ac.kr",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.tw", "net.tw", "org.tw", "idv.tw", "game.tw", "gov.tw", "edu.tw",
        "com.hk", "net.hk", "org.hk", "idv.hk", "gov.hk", "edu.hk",
        // Oceania
        "com.au", "net.au", "org.au", "id.au", "asn.au", "gov.au", "edu.au",
        "co.nz", "net.nz", "org.nz", "geek.nz", "govt.nz", "ac.nz", "school.nz",
        // Latin America
        "com.br", "net.br", "org.br", "gov.br", "edu.br",
        "com.mx", "org.mx", "gob.mx",
        "com.ar", "net.ar", "org.ar", "gob.ar",
        "com.co", "net.co", "org.co", "gov.co",
        "com.pe", "com.ve", "com.ec", "com.uy", "com.bo", "com.py", "com.do", "com.gt",
        "com.sv", "com.hn", "com.ni", "com.pa", "com.cu", "co.cr", "com.cr",
        // Africa
        "co.za", "org.za", "net.za", "web.za", "gov.za", "ac.za",
        "com.ng", "org.ng", "net.ng", "gov.ng", "edu.ng",
        "co.ke", "or.ke", "ac.ke", "go.ke",
        "co.tz", "or.tz", "ac.tz", "go.tz",
        "com.gh", "com.eg", "org.eg", "net.eg", "gov.eg", "edu.eg",
        "com.ma", "co.ma", "com.tn", "com.dz", "com.et", "co.zw", "co.mz", "co.ug",
        // Middle East
        "co.il", "org.il", "net.il", "ac.il", "gov.il",
        "com.sa", "net.sa", "org.sa", "gov.sa", "edu.sa",
        "com.ae", "net.ae", "org.ae", "gov.ae", "ac.ae",
        "com.qa", "com.kw", "com.bh", "com.om", "com.jo", "com.lb",
        "com.tr", "gen.tr", "org.tr", "net.tr", "gov.tr",
        // Europe
        "com.es", "org.es", "gob.es", "com.pt", "com.it", "com.de",
        "com.pl", "net.pl", "org.pl",
        "com.ua", "net.ua", "org.ua",
        "com.ru", "net.ru", "org.ru", "com.by", "com.ge",
        "com.hr", "com.cy", "com.mt", "co.rs", "org.rs",
        "com.gr", "net.gr", "org.gr",
        "co.at", "or.at", "co.hu", "com.se",
        // Canada
        "co.ca", "gc.ca"
    )
}
