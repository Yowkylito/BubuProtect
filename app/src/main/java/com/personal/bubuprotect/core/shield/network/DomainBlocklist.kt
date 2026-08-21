package com.personal.bubuprotect.core.shield.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hostnames belonging to mobile ad and attribution networks.
 *
 * ### It does two jobs and only one of them is blocking
 *
 * **Attribution** runs for every app: a hit is recorded against whichever app asked, which is what
 * turns "an ad appeared" into "this app made 1,847 requests to 12 ad networks in the last hour". This
 * is the more valuable half and it happens whether or not anything is blocked.
 *
 * **Blocking** applies only to apps the user has convicted and chosen to filter. A device-wide ad block
 * would break legitimate free apps whose only revenue is the ad they were about to show, and that is
 * not a decision this app has any business making on the user's behalf.
 *
 * ### Suffix matching, and why not a regex or a trie
 *
 * A hostname matches if it equals an entry or ends with `.entry`, so `doubleclick.net` covers
 * `stats.g.doubleclick.net` without also matching `notdoubleclick.net`. That last case is the reason the
 * dot is required rather than using a plain `endsWith`.
 *
 * The list is a few dozen entries against a few DNS queries per second, so a `Set` lookup over the
 * candidate's parent domains is comfortably fast enough - it walks at most as many labels as the
 * hostname has. A trie would be the right structure for the hundred-thousand-entry lists that general
 * ad-blockers ship; it would be premature here, and the shipped list is deliberately small.
 */
class DomainBlocklist private constructor(private val hosts: Set<String>) {

    val size: Int get() = hosts.size

    /**
     * @param hostname already lowercased by [DnsMessage.questionName]. Not re-normalised here, because
     *   this runs once per DNS query on the device and the caller is the only source.
     */
    fun matches(hostname: String): Boolean {
        if (hosts.isEmpty()) return false
        if (hostname in hosts) return true

        // Walk the parent domains: a.b.example.com tries b.example.com, then example.com, then com.
        var index = hostname.indexOf('.')
        while (index in 0 until hostname.length - 1) {
            if (hostname.substring(index + 1) in hosts) return true
            index = hostname.indexOf('.', index + 1)
        }
        return false
    }

    companion object {

        private const val ASSET = "shield/ad_hosts.txt"

        val Empty = DomainBlocklist(emptySet())

        /**
         * Reads the shipped list off the main thread.
         *
         * An unreadable asset yields [Empty], and the filter then attributes nothing and blocks nothing.
         * The tunnel still forwards DNS correctly, so the user's internet is unaffected - which is the
         * important half of that failure mode.
         */
        suspend fun load(context: Context): DomainBlocklist = withContext(Dispatchers.IO) {
            val parsed = try {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    lines.mapNotNullTo(mutableSetOf()) { line ->
                        line.substringBefore('#')
                            .trim()
                            .trimStart('.')
                            .lowercase()
                            .takeIf { it.isNotEmpty() && it.contains('.') }
                    }
                }
            } catch (_: Exception) {
                emptySet()
            }

            if (parsed.isEmpty()) Empty else DomainBlocklist(parsed)
        }

        fun of(hosts: Iterable<String>): DomainBlocklist =
            DomainBlocklist(hosts.mapTo(mutableSetOf()) { it.trim().trimStart('.').lowercase() })
    }
}
