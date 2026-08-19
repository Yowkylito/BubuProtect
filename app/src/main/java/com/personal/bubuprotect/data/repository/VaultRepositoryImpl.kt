package com.personal.bubuprotect.data.repository

import com.personal.bubuprotect.core.crypto.FieldCipher
import com.personal.bubuprotect.data.local.AutofillLinkEntity
import com.personal.bubuprotect.data.local.PasswordEntity
import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultDraft
import com.personal.bubuprotect.domain.model.VaultEntry
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.domain.repository.VaultRepository
import com.personal.bubuprotect.domain.repository.VaultTamperedException
import com.personal.bubuprotect.session.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.crypto.AEADBadTagException

class VaultRepositoryImpl(
    private val session: VaultSession,
    private val clock: () -> Long = System::currentTimeMillis
) : VaultRepository {

    /**
     * Follows the session rather than the database. When the vault locks, the handle goes null and
     * this switches to an empty list, so any collector still on screen loses its data at the same
     * instant the keys are wiped - there is no window where a stale list survives the lock.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeItems(): Flow<List<VaultItem>> =
        session.handle.flatMapLatest { handle ->
            if (handle == null) {
                flowOf(emptyList())
            } else {
                handle.dao.observeAll().map { rows ->
                    rows.map { it.toItem(handle.fieldCipher) }
                }
            }
        }

    override suspend fun getEntry(id: String): VaultEntry? = withContext(Dispatchers.IO) {
        val handle = session.requireHandle()
        handle.dao.findById(id)?.toEntry(handle.fieldCipher)
    }

    override suspend fun save(draft: VaultDraft): String = withContext(Dispatchers.IO) {
        val handle = session.requireHandle()
        val cipher = handle.fieldCipher
        val now = clock()

        // A new row's id has to be minted before the fields are sealed: the id is part of the AAD,
        // so the ciphertexts are only valid for the row they were created for.
        val id = draft.id ?: UUID.randomUUID().toString()
        val existing = draft.id?.let { handle.dao.findById(it) }

        // Blank extras are dropped rather than stored empty, so an untouched optional field does not
        // show up as a present-but-empty row in the detail view.
        val extras = draft.extras.filterValues { it.isNotBlank() }

        // Does this save actually change the password? Renaming an entry or fixing its notes must
        // not throw away a breach verdict that is still true, so the existing secret is decrypted
        // once and compared. Ciphertext cannot be compared instead: AES-GCM uses a fresh IV every
        // time, so re-sealing the same password produces entirely different bytes.
        val previousSecret = existing?.let {
            runCatching { it.decrypt(cipher, FieldCipher.Field.PASSWORD, it.passwordCipher) }
                .getOrNull()
        }
        val secretUnchanged = existing != null && previousSecret == draft.secret

        handle.dao.upsert(
            PasswordEntity(
                id = id,
                label = draft.label.trim(),
                website = draft.website?.trim()?.takeIf(String::isNotEmpty),
                category = draft.category.trim().ifEmpty { VaultEntry.DEFAULT_CATEGORY },
                kind = draft.kind.storageKey,
                usernameCipher = cipher.encrypt(id, FieldCipher.Field.USERNAME, draft.identity),
                passwordCipher = cipher.encrypt(id, FieldCipher.Field.PASSWORD, draft.secret),
                notesCipher = draft.notes
                    ?.takeIf(String::isNotBlank)
                    ?.let { cipher.encrypt(id, FieldCipher.Field.NOTES, it) },
                extrasCipher = extras
                    .takeIf { it.isNotEmpty() }
                    ?.let { cipher.encrypt(id, FieldCipher.Field.EXTRAS, it.toJson()) },
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                secretUpdatedAt = if (secretUnchanged) existing.secretUpdatedAt else now,
                // A changed password has no verdict, and must not inherit the old one - that is the
                // single most dangerous thing this file could get wrong, because it would show a
                // green "safe" badge over a password nothing has ever checked.
                breachCount = if (secretUnchanged) existing.breachCount else BreachStatus.NEVER_CHECKED,
                breachCheckedAt = if (secretUnchanged) existing.breachCheckedAt else 0L,
                breachAcknowledgedAt = if (secretUnchanged) existing.breachAcknowledgedAt else 0L
            )
        )
        id
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val handle = session.requireHandle()
        // The autofill_links foreign key already cascades. This is deliberate belt-and-braces: the
        // cascade only fires while SQLite has foreign key enforcement switched on, and that is a
        // connection pragma rather than a property of the file. A stale link is not merely untidy -
        // it is a stored trust decision pointing at an id that is free to be reused.
        handle.linkDao.deleteForEntry(id)
        handle.dao.deleteById(id)
    }

    override suspend fun linkedEntryIds(targetKey: String, signature: String?): Set<String> =
        withContext(Dispatchers.IO) {
            session.requireHandle().linkDao.findByTarget(targetKey)
                .filter { link ->
                    // Null on both sides is a website link, where the domain is the identity and
                    // there is no APK to sign anything. Anything else has to match exactly.
                    val matches = link.signature == signature
                    if (!matches) {
                        Timber.tag(AUTOFILL_TAG).w(
                            "Ignoring link for %s: the requesting app is signed by a different key",
                            targetKey
                        )
                    }
                    matches
                }
                .map { it.entryId }
                .toSet()
        }

    override suspend fun rememberAutofillLink(
        targetKey: String,
        entryId: String,
        signature: String?
    ) = withContext(Dispatchers.IO) {
        session.requireHandle().linkDao.upsert(
            AutofillLinkEntity(
                targetKey = targetKey,
                entryId = entryId,
                signature = signature,
                linkedAt = clock()
            )
        )
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        session.requireHandle().dao.count()
    }

    override suspend fun recordBreachCheck(
        id: String,
        exposureCount: Long,
        secretUpdatedAt: Long
    ): Boolean = withContext(Dispatchers.IO) {
        session.requireHandle().dao.recordBreachCheck(
            id = id,
            exposureCount = exposureCount,
            checkedAt = clock(),
            secretUpdatedAt = secretUpdatedAt
        ) > 0
    }

    override suspend fun acknowledgeBreach(id: String) = withContext(Dispatchers.IO) {
        session.requireHandle().dao.acknowledgeBreach(id, clock())
    }

    override suspend fun acknowledgeAllBreaches() = withContext(Dispatchers.IO) {
        session.requireHandle().dao.acknowledgeAllBreaches(clock())
    }

    override suspend fun exportEntries(): List<VaultEntry> = withContext(Dispatchers.IO) {
        val handle = session.requireHandle()
        handle.dao.findAll().map { it.toEntry(handle.fieldCipher) }
    }

    override suspend fun restoreEntries(entries: List<VaultEntry>): Int =
        withContext(Dispatchers.IO) {
            val handle = session.requireHandle()
            val cipher = handle.fieldCipher

            entries.forEach { entry ->
                val extras = entry.extras.filterValues { it.isNotBlank() }
                handle.dao.upsert(
                    PasswordEntity(
                        // The id is re-used, not regenerated. It is part of the GCM AAD, so the
                        // fields are re-sealed against it here under the *new* vault's field key -
                        // which is why a restore cannot simply copy ciphertext across.
                        id = entry.id,
                        label = entry.label,
                        website = entry.website?.takeIf(String::isNotEmpty),
                        category = entry.category.ifEmpty { VaultEntry.DEFAULT_CATEGORY },
                        kind = entry.kind.storageKey,
                        usernameCipher = cipher.encrypt(
                            entry.id,
                            FieldCipher.Field.USERNAME,
                            entry.identity
                        ),
                        passwordCipher = cipher.encrypt(
                            entry.id,
                            FieldCipher.Field.PASSWORD,
                            entry.secret
                        ),
                        notesCipher = entry.notes
                            ?.takeIf(String::isNotBlank)
                            ?.let { cipher.encrypt(entry.id, FieldCipher.Field.NOTES, it) },
                        extrasCipher = extras
                            .takeIf { it.isNotEmpty() }
                            ?.let { cipher.encrypt(entry.id, FieldCipher.Field.EXTRAS, it.toJson()) },
                        createdAt = entry.createdAt,
                        updatedAt = entry.updatedAt,
                        secretUpdatedAt = entry.secretUpdatedAt,
                        breachCount = entry.breach.storedCount(),
                        breachCheckedAt = entry.breach.checkedAt,
                        breachAcknowledgedAt = if (entry.breach.isAcknowledged) {
                            entry.breach.checkedAt
                        } else {
                            0L
                        }
                    )
                )
            }
            entries.size
        }

    private fun BreachStatus.storedCount(): Long = when (verdict) {
        BreachVerdict.UNCHECKED -> BreachStatus.NEVER_CHECKED
        BreachVerdict.SAFE -> 0L
        BreachVerdict.BREACHED -> exposureCount
    }

    private fun PasswordEntity.toItem(cipher: FieldCipher) = VaultItem(
        id = id,
        kind = ItemKind.fromStorage(kind),
        label = label,
        subtitle = decrypt(cipher, FieldCipher.Field.USERNAME, usernameCipher),
        website = website,
        category = category,
        breach = breachStatus(),
        updatedAt = updatedAt
    )

    private fun PasswordEntity.toEntry(cipher: FieldCipher) = VaultEntry(
        id = id,
        kind = ItemKind.fromStorage(kind),
        label = label,
        identity = decrypt(cipher, FieldCipher.Field.USERNAME, usernameCipher),
        secret = decrypt(cipher, FieldCipher.Field.PASSWORD, passwordCipher),
        website = website,
        notes = notesCipher?.let { decrypt(cipher, FieldCipher.Field.NOTES, it) },
        extras = extrasCipher
            ?.let { decrypt(cipher, FieldCipher.Field.EXTRAS, it) }
            ?.let(::parseExtras)
            ?: emptyMap(),
        category = category,
        breach = breachStatus(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        secretUpdatedAt = secretUpdatedAt
    )

    private fun PasswordEntity.breachStatus(): BreachStatus = BreachStatus.from(
        exposureCount = breachCount,
        checkedAt = breachCheckedAt,
        acknowledgedAt = breachAcknowledgedAt,
        secretUpdatedAt = secretUpdatedAt
    )

    private fun PasswordEntity.decrypt(cipher: FieldCipher, field: FieldCipher.Field, box: ByteArray): String =
        try {
            cipher.decrypt(id, field, box)
        } catch (badTag: AEADBadTagException) {
            // Right key, wrong bytes: the row was edited outside the app, or a ciphertext was moved
            // between rows or columns. Fail loudly instead of showing the user a plausible wrong
            // password for their bank.
            throw VaultTamperedException(id, badTag)
        }

    /**
     * `org.json` rather than a serialization library: it is on the platform, so the offline vault
     * gains no dependency to hold a handful of string pairs.
     */
    private fun Map<String, String>.toJson(): String =
        JSONObject().apply { forEach { (key, value) -> put(key, value) } }.toString()

    private fun parseExtras(json: String): Map<String, String> = runCatching {
        val obj = JSONObject(json)
        buildMap {
            obj.keys().forEach { key -> put(key, obj.optString(key)) }
        }
    }.getOrDefault(emptyMap())
}

private const val AUTOFILL_TAG = "Autofill"
