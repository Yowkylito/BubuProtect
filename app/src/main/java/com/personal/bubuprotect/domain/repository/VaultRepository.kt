package com.personal.bubuprotect.domain.repository

import com.personal.bubuprotect.domain.model.VaultDraft
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.model.VaultItem
import kotlinx.coroutines.flow.Flow

/** Thrown when a read or write is attempted while the vault is locked. */
class VaultLockedException : IllegalStateException("Vault is locked")

/**
 * Thrown when a row fails its GCM tag check: the ciphertext, the key, or the AAD binding it to its
 * row and column does not match. Surfaced separately from a generic failure because it means the
 * database file was modified out from under the app, which the user should be told about rather than
 * seeing as "could not load".
 */
class VaultTamperedException(entryId: String, cause: Throwable) :
    IllegalStateException("Entry $entryId failed its integrity check", cause)

interface VaultRepository {

    /** Emits an empty list while locked, and repopulates on unlock. Never carries a secret. */
    fun observeItems(): Flow<List<VaultItem>>

    /** Decrypts one entry in full. Gate this behind authentication at the call site. */
    suspend fun getEntry(id: String): VaultEntry?

    /** @return the entry's id. */
    suspend fun save(draft: VaultDraft): String

    suspend fun delete(id: String)

    suspend fun count(): Int

    // --- Breach status -------------------------------------------------------------------------

    /**
     * Stores the outcome of one Pwned Passwords lookup.
     *
     * @param secretUpdatedAt the [VaultEntry.secretUpdatedAt] the check was run against. The write is
     *   dropped if the entry's password has changed since, so a verdict can never be attached to a
     *   password it was not computed from.
     * @return true if the verdict was stored.
     */
    suspend fun recordBreachCheck(
        id: String,
        exposureCount: Long,
        secretUpdatedAt: Long
    ): Boolean

    /** Silences one entry's alert until a newer verdict arrives. */
    suspend fun acknowledgeBreach(id: String)

    /** Silences every outstanding alert - the dialog's "not right now" path. */
    suspend fun acknowledgeAllBreaches()

    // --- Autofill ------------------------------------------------------------------------------

    /**
     * Entries the user has already chosen for this autofill target.
     *
     * @param targetKey [com.personal.bubuprotect.core.autofill.AutofillTarget.key].
     * @param signature SHA-256 of the requesting package's signing certificate, or null for a
     *   website. A link whose recorded signer disagrees is **not** returned: a package name can be
     *   claimed by any sideloaded APK, so honouring it would let a replacement app inherit a trust
     *   decision the user made about the original.
     */
    suspend fun linkedEntryIds(targetKey: String, signature: String?): Set<String>

    /**
     * Records that this entry is the right one for this target.
     *
     * Called when the user picks an entry in the autofill picker - never inferred. The whole value
     * of the link is that a human confirmed it once, so writing one the user did not choose would
     * turn the strongest match signal into another guess.
     */
    suspend fun rememberAutofillLink(targetKey: String, entryId: String, signature: String?)

    // --- Backup --------------------------------------------------------------------------------

    /**
     * Decrypts the entire vault.
     *
     * The one method here that breaks the "only ever one entry in the clear" rule, and the only one
     * that ever should. An export is by definition the whole vault in one buffer; there is no way to
     * write an encrypted archive of N entries without holding N entries. Call it from the backup
     * path only, serialise immediately, and wipe.
     */
    suspend fun exportEntries(): List<VaultEntry>

    /**
     * Writes entries back verbatim - original ids, timestamps and breach verdicts.
     *
     * Not [save]: that mints `createdAt`/`updatedAt` from the clock and clears the breach columns,
     * which is right for an edit and wrong for a restore. A restored vault should be the vault that
     * was exported, not a set of entries that all claim to have been created the moment the user
     * reinstalled.
     *
     * @return how many entries were written.
     */
    suspend fun restoreEntries(entries: List<VaultEntry>): Int
}
