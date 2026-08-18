package com.personal.bubuprotect.data.repository

import com.personal.bubuprotect.core.crypto.FieldCipher
import com.personal.bubuprotect.data.local.PasswordEntity
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
                updatedAt = now
            )
        )
        id
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        session.requireHandle().dao.deleteById(id)
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        session.requireHandle().dao.count()
    }

    private fun PasswordEntity.toItem(cipher: FieldCipher) = VaultItem(
        id = id,
        kind = ItemKind.fromStorage(kind),
        label = label,
        subtitle = decrypt(cipher, FieldCipher.Field.USERNAME, usernameCipher),
        website = website,
        category = category,
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
        createdAt = createdAt,
        updatedAt = updatedAt
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
