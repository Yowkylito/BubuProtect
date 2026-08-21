package com.personal.bubuprotect.core.shield.intel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signing certificates belonging to known adware families, loaded from a shipped asset.
 *
 * ### Why a certificate list rather than a package-name list
 *
 * Package-name blocklists are what every free scanner ships and they are close to worthless, because
 * a package name is a string the author picks. Rebuilding the same APK under a new name defeats one
 * in seconds. A signing certificate cannot be changed without losing the ability to update the
 * installs the author already has, which is why adware families keep one key across dozens of
 * releases - and why one listed certificate identifies the whole family, releases that do not exist
 * yet included.
 *
 * ### It ships empty, and that is a working state
 *
 * There is no seeded list in this repository. Populating one means either paying for a threat feed or
 * publishing hashes collected from samples this project has not verified, and a blocklist entry
 * asserted without evidence is exactly the false-positive machine this design exists to avoid - it
 * would convict an app outright, with no behavioural signal required.
 *
 * So the file is a real loader over an empty list, and the shield works without it: an unlisted family
 * is caught by the flight recorder the first time it draws an overlay, and every sibling signed with
 * the same key is then caught by [com.personal.bubuprotect.domain.model.RiskSignal.SIGNER_SIBLING]
 * without any list at all. The blocklist makes the *first* catch instant. It is not what makes the
 * shield work.
 *
 * Adding entries later needs no code change: one 64-character hex hash per line in the asset, `#` for
 * comments. [SignerFingerprinter.normalize] accepts the separator styles public sources publish in.
 */
class SignerBlocklist private constructor(private val hashes: Set<String>) {

    val size: Int get() = hashes.size

    val isEmpty: Boolean get() = hashes.isEmpty()

    /**
     * Whether any certificate this app is signed with is listed.
     *
     * Takes the whole set from [SignerFingerprinter.fingerprints] rather than a single hash, so an
     * author who rotated their signing key is still matched on the retired one.
     */
    fun matches(fingerprints: Set<String>): Boolean =
        fingerprints.isNotEmpty() && fingerprints.any(hashes::contains)

    companion object {

        private const val ASSET = "shield/signer_blocklist.txt"

        val Empty = SignerBlocklist(emptySet())

        /**
         * Reads the asset off the main thread.
         *
         * A missing or unreadable asset yields [Empty] rather than throwing. The shield degrades to
         * behavioural detection only, which is a weaker product and a working one; taking the whole
         * feature down because a text file could not be opened would be the worse trade.
         *
         * Malformed lines are dropped by [SignerFingerprinter.normalize] rather than loaded as
         * entries that could never match - a blocklist that silently contains an unmatchable string
         * reports every app it was meant to catch as clean.
         */
        suspend fun load(context: Context): SignerBlocklist = withContext(Dispatchers.IO) {
            val parsed = try {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    lines.mapNotNullTo(mutableSetOf()) { line ->
                        line.substringBefore('#').trim()
                            .takeIf(String::isNotEmpty)
                            ?.let(SignerFingerprinter::normalize)
                    }
                }
            } catch (_: Exception) {
                emptySet()
            }

            if (parsed.isEmpty()) Empty else SignerBlocklist(parsed)
        }

        /** For tests and for callers assembling a list from somewhere other than the asset. */
        fun of(hashes: Iterable<String>): SignerBlocklist =
            SignerBlocklist(hashes.mapNotNullTo(mutableSetOf(), SignerFingerprinter::normalize))
    }
}
