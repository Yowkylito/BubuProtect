package com.personal.bubuprotect.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordDao {

    /**
     * `COLLATE NOCASE` so "gmail" and "Gmail" sort together. Ordering happens in SQLite rather than
     * in Kotlin because the credential columns must stay sealed while the list is assembled.
     */
    @Query("SELECT * FROM password_entries ORDER BY label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PasswordEntity>>

    @Query("SELECT * FROM password_entries WHERE id = :id")
    suspend fun findById(id: String): PasswordEntity?

    /**
     * Every row, for the backup export.
     *
     * A one-shot suspend read rather than the [observeAll] Flow: an export is a snapshot at a moment,
     * and a Flow here would keep the whole vault's ciphertext live for as long as anything collected
     * it.
     */
    @Query("SELECT * FROM password_entries ORDER BY label COLLATE NOCASE ASC")
    suspend fun findAll(): List<PasswordEntity>

    @Upsert
    suspend fun upsert(entity: PasswordEntity)

    @Query("DELETE FROM password_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM password_entries")
    suspend fun count(): Int

    /**
     * Records a completed breach check.
     *
     * A targeted `UPDATE` rather than a read-modify-upsert of the whole row, and deliberately so:
     * an upsert would rewrite every ciphertext column with a fresh IV and bump `updated_at`, so a
     * background scan would churn the entire vault's ciphertext to record four numbers.
     *
     * The `secret_updated_at = :secretUpdatedAt` guard closes the race between a slow network check
     * and the user editing that entry while it is in flight: if the password moved on, the verdict
     * is about one that no longer exists, and the write is dropped rather than pinned to the new
     * secret. @return the number of rows written - 0 means the verdict was discarded.
     *
     * `breach_ack_at = 0` because a new verdict is new news. An entry the user dismissed last month
     * has to be able to raise its hand again when a fresh corpus turns it up.
     */
    @Query(
        """
        UPDATE password_entries
        SET breach_count = :exposureCount, breach_checked_at = :checkedAt, breach_ack_at = 0
        WHERE id = :id AND secret_updated_at = :secretUpdatedAt
        """
    )
    suspend fun recordBreachCheck(
        id: String,
        exposureCount: Long,
        checkedAt: Long,
        secretUpdatedAt: Long
    ): Int

    @Query("UPDATE password_entries SET breach_ack_at = :acknowledgedAt WHERE id = :id")
    suspend fun acknowledgeBreach(id: String, acknowledgedAt: Long)

    /** Dismisses every outstanding alert at once - the dialog's "not now" path. */
    @Query(
        """
        UPDATE password_entries
        SET breach_ack_at = :acknowledgedAt
        WHERE breach_count > 0 AND breach_checked_at > 0
        """
    )
    suspend fun acknowledgeAllBreaches(acknowledgedAt: Long)
}
