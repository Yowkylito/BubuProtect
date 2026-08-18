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
}
