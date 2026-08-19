package com.personal.bubuprotect.domain.model

/**
 * What Bubu currently knows about one secret's presence in public breach corpora.
 *
 * Deliberately three states rather than a `Boolean?`. "Not known to be breached" and "never looked"
 * are different claims, and a vault that renders them the same way tells the user their untouched
 * passwords are safe when nothing has ever been checked.
 */
enum class BreachVerdict {
    /** Never checked, or checked before the secret was last edited. */
    UNCHECKED,

    /** Absent from the corpus at [BreachStatus.checkedAt]. Not a claim that it is a good password. */
    SAFE,

    /** Present in the corpus. The password is burned and must be replaced everywhere. */
    BREACHED
}

/**
 * The persisted verdict for one entry.
 *
 * ### Why a verdict is stored at all
 *
 * The check itself is a network round trip, and a status the user cannot see between sessions is a
 * status that does not exist - they would have to re-check every password by hand to learn anything
 * about the vault as a whole. Storing it is what turns a one-off lookup into a standing answer to
 * "is anything in here compromised".
 *
 * ### What storing it costs
 *
 * [exposureCount] lives in a plain column inside the SQLCipher-encrypted database, alongside `label`
 * and `kind`, for the same reason those are plain: the whole-vault view has to be assembled without
 * decrypting a single secret. It is not AAD-bound the way the credential columns are, so someone who
 * can already write to the database file could downgrade a `BREACHED` row to `SAFE`. That is
 * accepted: an attacker with write access to the vault file has strictly better options than lying
 * about a breach flag, and binding the flag would mean re-sealing the row - and therefore holding
 * the field key - every time a background check completes.
 *
 * A count is not the password, and is not a meaningful hint toward it: tens of millions of distinct
 * passwords share the same exposure counts.
 */
data class BreachStatus(
    val verdict: BreachVerdict = BreachVerdict.UNCHECKED,
    /** How many times the secret appears in the corpus. Meaningful only when [BreachVerdict.BREACHED]. */
    val exposureCount: Long = 0L,
    /** Wall clock of the last completed check, or 0. */
    val checkedAt: Long = 0L,
    /** True once the user has seen this exact verdict and chosen to leave it. */
    val isAcknowledged: Boolean = false
) {
    val isBreached: Boolean get() = verdict == BreachVerdict.BREACHED

    /** Drives the alert dialog: breached, and the user has not already waved it away. */
    val needsAttention: Boolean get() = isBreached && !isAcknowledged

    /**
     * A verdict has a shelf life. The corpus only grows, so a `SAFE` from a year ago is a statement
     * about a corpus that no longer exists.
     */
    fun isDueForRecheck(now: Long): Boolean =
        verdict == BreachVerdict.UNCHECKED || now - checkedAt >= RECHECK_AFTER_MILLIS

    companion object {
        val Unchecked = BreachStatus()

        /** Sentinel for the `breach_count` column: no check has ever completed for this row. */
        const val NEVER_CHECKED = -1L

        val RECHECK_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * Rebuilds a status from its columns.
         *
         * [secretUpdatedAt] is what makes the verdict self-invalidating: a check that predates the
         * row's last edit describes a password that is no longer there, so it collapses back to
         * [BreachVerdict.UNCHECKED] rather than reporting a stale answer about a secret the user has
         * already replaced. That is also why no fingerprint of the password is stored - the row's own
         * timestamp answers "is this verdict still about the same secret" without keeping anything
         * derived from the password on disk.
         */
        fun from(
            exposureCount: Long,
            checkedAt: Long,
            acknowledgedAt: Long,
            secretUpdatedAt: Long
        ): BreachStatus {
            val isCurrent = checkedAt > 0L &&
                exposureCount >= 0L &&
                checkedAt >= secretUpdatedAt
            if (!isCurrent) return Unchecked

            return BreachStatus(
                verdict = if (exposureCount > 0L) BreachVerdict.BREACHED else BreachVerdict.SAFE,
                exposureCount = exposureCount,
                checkedAt = checkedAt,
                // Acknowledging an older verdict must not silence a newer one: a password that was
                // dismissed as "I'll deal with it later" and then turns up in a fresh breach has to
                // raise the alert again.
                isAcknowledged = acknowledgedAt > 0L && acknowledgedAt >= checkedAt
            )
        }
    }
}
