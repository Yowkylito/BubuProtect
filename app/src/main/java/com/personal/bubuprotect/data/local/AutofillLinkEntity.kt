package com.personal.bubuprotect.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

/**
 * "This entry is the one for this app or site."
 *
 * Written when the user picks an entry in the autofill picker for a target that was not already
 * matched. It is what makes autofill work for native apps at all: `com.reddit.frontpage` can be
 * guessed from its package name, but `com.google.android.gm` cannot be guessed into `gmail.com` by
 * any rule that is not itself a hardcoded list of every app in the world. Rather than ship that
 * list and watch it rot, the vault learns from the one source that is always right - the user, once.
 *
 * ### Why the signer is stored
 *
 * A package name is not an identity outside Play. Any sideloaded APK may declare
 * `com.mybank.android`, and if that were enough to inherit a link, an app installed after the real
 * one was removed would be handed the bank credential on its first login screen without the user
 * being asked anything.
 *
 * [signature] is the SHA-256 of the requesting package's signing certificate, captured when the link
 * was made. A request whose signer does not match is treated as an unknown target: no link, no
 * automatic offer, and the user gets asked again. Null means the link was made for a *website*,
 * where the domain is the identity and there is no APK to sign it.
 *
 * ### What this table gives away
 *
 * The columns are plain, so anyone who defeats SQLCipher learns which apps and sites the vault holds
 * credentials for. That is the same class of metadata [PasswordEntity] already keeps in plain
 * `label` and `website` columns, and for the same reason: a lookup that has to decrypt every row to
 * find one link would run on every keystroke of every login screen on the device.
 */
@Entity(
    tableName = "autofill_links",
    // Composite key, because two accounts on one site is ordinary. Keying on the target alone would
    // make the second link silently replace the first, and the user would watch their work login
    // stop being offered the moment they saved a personal one.
    primaryKeys = ["target_key", "entry_id"],
    indices = [Index(value = ["entry_id"])],
    foreignKeys = [
        ForeignKey(
            entity = PasswordEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            // Deleting an entry has to take its links with it. Without this, a link would outlive
            // its entry and the next fill request would resolve to an id that is no longer there -
            // harmless, but it would also let a *new* entry that reused the id inherit a trust
            // decision the user made about something else.
            onDelete = ForeignKey.CASCADE
        )
    ]
)
class AutofillLinkEntity(
    /** [com.personal.bubuprotect.core.autofill.AutofillTarget.key] - `web:reddit.com`, `app:com.x`. */
    @ColumnInfo(name = "target_key")
    val targetKey: String,

    @ColumnInfo(name = "entry_id")
    val entryId: String,

    @ColumnInfo(name = "signature")
    val signature: String?,

    @ColumnInfo(name = "linked_at")
    val linkedAt: Long
)

@Dao
interface AutofillLinkDao {

    /**
     * A target can have more than one entry - two accounts on the same site is ordinary - so the
     * key is the target and the row set is the answer, not a single row.
     *
     * `target_key` leads the composite primary key, so this is a prefix scan of that index rather
     * than a table walk. It runs while the user is looking at a login screen waiting for a
     * suggestion to appear, so it has to stay that cheap.
     */
    @Query("SELECT * FROM autofill_links WHERE target_key = :targetKey")
    suspend fun findByTarget(targetKey: String): List<AutofillLinkEntity>

    @Upsert
    suspend fun upsert(link: AutofillLinkEntity)

    @Query("DELETE FROM autofill_links WHERE target_key = :targetKey AND entry_id = :entryId")
    suspend fun delete(targetKey: String, entryId: String)

    @Query("DELETE FROM autofill_links WHERE entry_id = :entryId")
    suspend fun deleteForEntry(entryId: String)
}
