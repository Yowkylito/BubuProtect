package com.personal.bubuprotect.domain.model

/**
 * The result of one pass over every installed app.
 *
 * Mirrors [DeviceScanReport] deliberately, including the three-state shape: a report that could not
 * run is not the same object as a report that found nothing, and a screen that paints them the same
 * colour tells the user their phone was checked when it was not.
 *
 * @param verdicts only apps that produced at least one signal, plus this app's own row. Several hundred
 *   empty verdicts would be several hundred objects nobody reads.
 * @param unavailableReason non-null when the pass could not run at all.
 */
data class AppScanReport(
    val verdicts: List<AppVerdict>,
    val checkedAt: Long,
    val unavailableReason: String? = null
) {

    /** Named culprits, worst first. This is what the screen leads with and the only actionable list. */
    val convicted: List<AppVerdict> = verdicts
        .filter { !it.isSelf && it.conviction == Conviction.CONVICTED }
        .sortedByDescending(AppVerdict::score)

    /**
     * Capability-only rows, kept in a collapsed section.
     *
     * Separated from [convicted] rather than merged and sorted, because the distinction is the product.
     * A list that ranks "this drew an ad over Chrome 23 times" next to "this holds a permission it has
     * not used" invites the user to uninstall the second one, and that is the failure mode of every
     * scanner this feature is trying to beat.
     */
    val suspects: List<AppVerdict> = verdicts
        .filter { !it.isSelf && it.conviction == Conviction.SUSPECT }
        .sortedByDescending(AppVerdict::score)

    /** This app's own row, so the screen can show it rather than quietly omitting it. */
    val ownRow: AppVerdict? = verdicts.firstOrNull(AppVerdict::isSelf)

    val hasRun: Boolean get() = checkedAt > 0L && unavailableReason == null

    val isClean: Boolean get() = hasRun && convicted.isEmpty() && suspects.isEmpty()

    /** Everything the user has not already waved away. */
    fun outstanding(ignored: Set<String>): List<AppVerdict> =
        convicted.filterNot { it.ignoreKey in ignored }

    companion object {

        val Empty = AppScanReport(verdicts = emptyList(), checkedAt = 0L)

        fun unavailable(why: String, at: Long) =
            AppScanReport(verdicts = emptyList(), checkedAt = at, unavailableReason = why)
    }
}

/**
 * Key for the "I know, leave it alone" set.
 *
 * ### Why it is a hash and why it includes the signals
 *
 * Same reasoning as [DeviceFinding.fingerprint], and the same two properties. Including the signal set
 * makes the decision self-invalidating: ignoring an app that merely holds overlay permission must not
 * also silence that app once it *starts drawing overlays*, because those are different facts about it.
 * The moment the signals change, the key changes and the row comes back on its own.
 *
 * Hashed because the ignore set lives in plain preferences, and a readable list of which apps the user
 * chose to keep despite a warning is a small inventory of their phone this app has no reason to write
 * down. `String.hashCode` is specified by the language, so the key is stable across processes and
 * upgrades. It is a change detector, not a security boundary; a collision costs one warning staying
 * quiet.
 */
val AppVerdict.ignoreKey: String
    get() = "$packageName:${signals.map(RiskSignal::name).sorted().joinToString("|").hashCode()}"
