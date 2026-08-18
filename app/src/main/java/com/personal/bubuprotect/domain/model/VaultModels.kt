package com.personal.bubuprotect.domain.model

/**
 * A vault row with its secrets decrypted. Only ever built for the single entry the user asked to
 * open, never for a whole list - see [VaultItem].
 */
data class VaultEntry(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val identity: String,
    val secret: String,
    val website: String? = null,
    val notes: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val category: String = DEFAULT_CATEGORY,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    /** Reads whichever slot [spec] points at. Lets one composable render any field of any kind. */
    fun valueOf(spec: FieldSpec): String = when (val slot = spec.slot) {
        FieldSlot.Identity -> identity
        FieldSlot.Secret -> secret
        FieldSlot.Notes -> notes.orEmpty()
        FieldSlot.Website -> website.orEmpty()
        is FieldSlot.Extra -> extras[slot.key].orEmpty()
    }

    /** The fields of this entry's kind that actually hold something, in display order. */
    fun populatedFields(): List<FieldSpec> = kind.fields.filter { valueOf(it).isNotBlank() }

    companion object {
        const val DEFAULT_CATEGORY = "General"
    }
}

/**
 * What the list screen gets.
 *
 * Carries no secret, so scrolling the vault never decrypts one and a captured `List<VaultItem>`
 * cannot leak a credential. [subtitle] is the decrypted identity field - a username or an SSID - and
 * is blank for kinds that have none.
 */
data class VaultItem(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val subtitle: String,
    val website: String? = null,
    val category: String = VaultEntry.DEFAULT_CATEGORY,
    val updatedAt: Long = 0L
)

/** An add/edit form's contents. A null [id] means "create". */
data class VaultDraft(
    val id: String? = null,
    val kind: ItemKind,
    val label: String,
    val identity: String,
    val secret: String,
    val website: String? = null,
    val notes: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val category: String = VaultEntry.DEFAULT_CATEGORY
)
