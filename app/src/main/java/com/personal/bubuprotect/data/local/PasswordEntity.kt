package com.personal.bubuprotect.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A vault row as it sits on disk.
 *
 * The split is deliberate. [label], [website], [category] and [kind] stay searchable and sortable in
 * SQL, protected by SQLCipher's whole-file encryption. The credential columns are sealed boxes, so
 * they are protected twice and are opaque even to this app until a key is in memory.
 *
 * The trade-off that buys: an attacker who defeats the file encryption learns *which accounts exist*
 * but not a single credential. Encrypting the labels too would hide even that, at the cost of
 * loading and decrypting every row in the vault to sort a list or run a search. For a personal vault
 * of tens of entries the metadata is the lesser secret, and this keeps queries as plain indexed SQL.
 *
 * [id] is a UUID rather than an autoincrement integer because it is used as GCM additional
 * authenticated data: it must be stable for the row's whole life and never be reassigned.
 */
@Entity(
    tableName = "password_entries",
    indices = [Index(value = ["label"]), Index(value = ["category"]), Index(value = ["kind"])]
)
class PasswordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "website")
    val website: String?,

    @ColumnInfo(name = "category")
    val category: String,

    /**
     * [com.personal.bubuprotect.domain.model.ItemKind.storageKey].
     *
     * Defaulted at the SQL level so the v1 -> v2 migration can add it to existing rows without a
     * table rebuild; every row that predates the column was a login.
     */
    @ColumnInfo(name = "kind", defaultValue = "login")
    val kind: String,

    /** AES-256-GCM box: `iv || ciphertext || tag`, AAD-bound to [id] and the column. */
    @ColumnInfo(name = "username_cipher", typeAffinity = ColumnInfo.BLOB)
    val usernameCipher: ByteArray,

    @ColumnInfo(name = "password_cipher", typeAffinity = ColumnInfo.BLOB)
    val passwordCipher: ByteArray,

    @ColumnInfo(name = "notes_cipher", typeAffinity = ColumnInfo.BLOB)
    val notesCipher: ByteArray?,

    /**
     * The kind-specific fields, as one sealed JSON object.
     *
     * One blob rather than a child table: these are read and written together, always, and a joined
     * table of `(entry_id, key, value_cipher)` rows would publish the *shape* of every entry - how
     * many extra fields a card has, that this identity row carries a `pin` - in plain indexed
     * columns. A single opaque box leaks nothing but its length.
     */
    @ColumnInfo(name = "extras_cipher", typeAffinity = ColumnInfo.BLOB)
    val extrasCipher: ByteArray?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    // Not a data class: the generated equals() would compare the ByteArray columns by identity, so
    // two reads of the same row would come out unequal and quietly break any diffing on the list.
    override fun equals(other: Any?): Boolean = this === other ||
        (other is PasswordEntity && id == other.id && updatedAt == other.updatedAt)

    override fun hashCode(): Int = 31 * id.hashCode() + updatedAt.hashCode()
}
