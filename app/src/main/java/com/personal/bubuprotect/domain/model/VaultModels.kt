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
    val breach: BreachStatus = BreachStatus.Unchecked,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * When [secret] itself last changed. Carried up from storage so a breach check can be written
     * back against the exact password it was computed from - see
     * [com.personal.bubuprotect.domain.repository.VaultRepository.recordBreachCheck].
     */
    val secretUpdatedAt: Long = 0L
) {
    /** True when this entry's secret is a password worth looking up in breach data. */
    val isBreachCheckable: Boolean get() = kind.supportsBreachCheck && secret.isNotEmpty()

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
 *
 * [breach] rides along for the same reason [kind] does: it is read from a plain column, so the list
 * can show every entry's safety status without opening a single sealed box.
 */
data class VaultItem(
    val id: String,
    val kind: ItemKind,
    val label: String,
    val subtitle: String,
    val website: String? = null,
    val category: String = VaultEntry.DEFAULT_CATEGORY,
    val breach: BreachStatus = BreachStatus.Unchecked,
    val updatedAt: Long = 0L
) {
    /**
     * Whether a scan should even try this row.
     *
     * The list has no access to the secret, so "is it non-empty" cannot be answered here. Every
     * checkable kind marks its secret required in [fields], so a stored row of that kind always has
     * one; the scanner re-checks after decrypting anyway.
     */
    val isBreachCheckable: Boolean get() = kind.supportsBreachCheck
}

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
