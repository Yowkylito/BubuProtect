package com.personal.bubuprotect.core.backup

import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultEntry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The cleartext inside [VaultBackupEnvelope] - every entry, every field, nothing left out.
 *
 * ### Everything is inside the sealed payload
 *
 * The vault database deliberately keeps `label`, `website`, `category` and `kind` in plain columns so
 * the list can be searched and sorted without decrypting a single secret. That trade is sound on a
 * device, where SQLCipher covers the whole file and an attacker needs the phone.
 *
 * It would be a bad trade in a file the user is encouraged to put in cloud storage. A backup with
 * readable labels would publish *which accounts a person holds* - which bank, which dating site,
 * which employer - to anyone who obtained the file, without them ever breaking a password. So the
 * envelope has no plaintext region at all beyond its own header, and every field below, metadata
 * included, is inside the sealed payload.
 *
 * ### Format stability
 *
 * [VERSION] is independent of the envelope's format version: the container and its contents can
 * change for different reasons. Unknown keys are ignored and missing optional keys fall back to
 * their model defaults, so a backup written by a build that adds a field still restores on a build
 * that does not know about it - minus that field, which is the honest outcome.
 *
 * Keys are short because this is serialised once per export and never read by a human, and the whole
 * payload is encrypted, so there is no debuggability argument for verbosity.
 */
object VaultBackupPayload {

    const val VERSION = 1

    /**
     * @param exportedAt stamped into the file so a restore screen can tell the user how old it is.
     *   Not security-relevant and not authenticated beyond the envelope's own tag.
     */
    fun encode(entries: List<VaultEntry>, exportedAt: Long): ByteArray {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }

        val root = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_EXPORTED_AT, exportedAt)
            .put(KEY_ENTRIES, array)

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * @throws CorruptBackupException if the payload is not the shape we wrote. Reached only after the
     *   GCM tag has already passed, so this means a genuine format problem rather than tampering.
     */
    fun decode(bytes: ByteArray): Decoded = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val version = root.optInt(KEY_VERSION, 0)
        if (version <= 0 || version > VERSION) {
            throw UnsupportedBackupVersionException(version)
        }

        val array = root.optJSONArray(KEY_ENTRIES) ?: JSONArray()
        val entries = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toEntry()?.let(::add)
            }
        }
        Decoded(entries = entries, exportedAt = root.optLong(KEY_EXPORTED_AT, 0L))
    } catch (unsupported: UnsupportedBackupVersionException) {
        throw unsupported
    } catch (malformed: JSONException) {
        throw CorruptBackupException("This backup could not be read")
    }

    data class Decoded(val entries: List<VaultEntry>, val exportedAt: Long)

    private fun VaultEntry.toJson(): JSONObject = JSONObject()
        .put(KEY_ID, id)
        .put(KEY_KIND, kind.storageKey)
        .put(KEY_LABEL, label)
        .put(KEY_IDENTITY, identity)
        .put(KEY_SECRET, secret)
        .putOrSkip(KEY_WEBSITE, website)
        .putOrSkip(KEY_NOTES, notes)
        .put(KEY_EXTRAS, JSONObject(extras))
        .put(KEY_CATEGORY, category)
        .put(KEY_CREATED_AT, createdAt)
        .put(KEY_UPDATED_AT, updatedAt)
        .put(KEY_SECRET_UPDATED_AT, secretUpdatedAt)
        .put(KEY_BREACH_COUNT, breach.storedCount())
        .put(KEY_BREACH_CHECKED_AT, breach.checkedAt)
        // The model exposes acknowledgement as a boolean, but it is stored as a timestamp compared
        // against checkedAt. Writing checkedAt back reproduces exactly the same comparison, so the
        // flag survives a round trip without the payload carrying a second timestamp.
        .put(KEY_BREACH_ACK_AT, if (breach.isAcknowledged) breach.checkedAt else 0L)

    private fun JSONObject.toEntry(): VaultEntry? {
        val id = optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val updatedAt = optLong(KEY_UPDATED_AT, 0L)
        return VaultEntry(
            id = id,
            kind = ItemKind.fromStorage(optString(KEY_KIND)),
            label = optString(KEY_LABEL),
            identity = optString(KEY_IDENTITY),
            secret = optString(KEY_SECRET),
            website = optStringOrNull(KEY_WEBSITE),
            notes = optStringOrNull(KEY_NOTES),
            extras = optJSONObject(KEY_EXTRAS)?.toStringMap() ?: emptyMap(),
            category = optString(KEY_CATEGORY).ifBlank { VaultEntry.DEFAULT_CATEGORY },
            breach = BreachStatus.from(
                exposureCount = optLong(KEY_BREACH_COUNT, BreachStatus.NEVER_CHECKED),
                checkedAt = optLong(KEY_BREACH_CHECKED_AT, 0L),
                acknowledgedAt = optLong(KEY_BREACH_ACK_AT, 0L),
                // Not updatedAt: a verdict is about the secret, and restoring must not silently
                // invalidate every verdict just because the row was written at a new moment.
                secretUpdatedAt = optLong(KEY_SECRET_UPDATED_AT, 0L)
            ),
            createdAt = optLong(KEY_CREATED_AT, 0L),
            updatedAt = updatedAt,
            secretUpdatedAt = optLong(KEY_SECRET_UPDATED_AT, updatedAt)
        )
    }

    /** Collapses the three-state verdict back to the single column it came from. */
    private fun BreachStatus.storedCount(): Long = when (verdict) {
        BreachVerdict.UNCHECKED -> BreachStatus.NEVER_CHECKED
        BreachVerdict.SAFE -> 0L
        BreachVerdict.BREACHED -> exposureCount
    }

    private fun JSONObject.putOrSkip(key: String, value: String?): JSONObject =
        if (value.isNullOrEmpty()) this else put(key, value)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        keys().forEach { key -> put(key, optString(key)) }
    }

    private const val KEY_VERSION = "v"
    private const val KEY_EXPORTED_AT = "t"
    private const val KEY_ENTRIES = "e"
    private const val KEY_ID = "id"
    private const val KEY_KIND = "k"
    private const val KEY_LABEL = "l"
    private const val KEY_IDENTITY = "u"
    private const val KEY_SECRET = "s"
    private const val KEY_WEBSITE = "w"
    private const val KEY_NOTES = "n"
    private const val KEY_EXTRAS = "x"
    private const val KEY_CATEGORY = "c"
    private const val KEY_CREATED_AT = "ca"
    private const val KEY_UPDATED_AT = "ua"
    private const val KEY_SECRET_UPDATED_AT = "sa"
    private const val KEY_BREACH_COUNT = "bc"
    private const val KEY_BREACH_CHECKED_AT = "bt"
    private const val KEY_BREACH_ACK_AT = "ba"
}
