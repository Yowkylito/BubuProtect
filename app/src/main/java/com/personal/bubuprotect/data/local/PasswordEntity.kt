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
    val updatedAt: Long,

    /**
     * When [passwordCipher] last changed contents - not when the row was last written.
     *
     * Separate from [updatedAt] so that renaming an entry or fixing a typo in its notes does not
     * throw away a breach verdict that is still perfectly true about the password. Without this,
     * every cosmetic edit would silently mark the entry unchecked and the next scan would spend a
     * network round trip re-learning something it already knew.
     */
    @ColumnInfo(name = "secret_updated_at", defaultValue = "0")
    val secretUpdatedAt: Long = 0L,

    /**
     * How many times this row's secret appears in the Pwned Passwords corpus, or
     * [com.personal.bubuprotect.domain.model.BreachStatus.NEVER_CHECKED].
     *
     * Plain rather than sealed, for the same reason [label] is: the security report has to count and
     * sort breached rows across the whole vault, and doing that over AAD-bound ciphertext would mean
     * decrypting every secret in the vault to render a badge. See
     * [com.personal.bubuprotect.domain.model.BreachStatus] for what that trade-off does and does not
     * give away.
     */
    @ColumnInfo(name = "breach_count", defaultValue = "-1")
    val breachCount: Long = -1L,

    /**
     * When the check that produced [breachCount] finished.
     *
     * Compared against [updatedAt] to decide whether the verdict still describes the secret that is
     * actually in the row - which is why no hash of the password needs to be kept to detect a change.
     */
    @ColumnInfo(name = "breach_checked_at", defaultValue = "0")
    val breachCheckedAt: Long = 0L,

    /** When the user last dismissed this row's breach alert. Compared against [breachCheckedAt]. */
    @ColumnInfo(name = "breach_ack_at", defaultValue = "0")
    val breachAcknowledgedAt: Long = 0L
) {
    // Not a data class: the generated equals() would compare the ByteArray columns by identity, so
    // two reads of the same row would come out unequal and quietly break any diffing on the list.
    //
    // The breach columns are part of identity because a completed check changes the row without
    // touching updatedAt - editing the secret is what moves that. Leaving them out would make a
    // freshly scanned row compare equal to its pre-scan self and the badge would never appear.
    override fun equals(other: Any?): Boolean = this === other ||
        (other is PasswordEntity &&
            id == other.id &&
            updatedAt == other.updatedAt &&
            secretUpdatedAt == other.secretUpdatedAt &&
            breachCount == other.breachCount &&
            breachCheckedAt == other.breachCheckedAt &&
            breachAcknowledgedAt == other.breachAcknowledgedAt)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + secretUpdatedAt.hashCode()
        result = 31 * result + breachCount.hashCode()
        result = 31 * result + breachCheckedAt.hashCode()
        result = 31 * result + breachAcknowledgedAt.hashCode()
        return result
    }
}
