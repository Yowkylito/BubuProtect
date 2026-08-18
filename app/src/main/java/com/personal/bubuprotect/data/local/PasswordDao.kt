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

    @Upsert
    suspend fun upsert(entity: PasswordEntity)

    @Query("DELETE FROM password_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM password_entries")
    suspend fun count(): Int
}
