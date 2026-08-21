package com.personal.bubuprotect.core.autofill

import android.content.Intent
import android.os.Build
import android.view.autofill.AutofillId
import com.personal.bubuprotect.domain.model.ItemKind

/**
 * Everything the authentication activity needs in order to answer a fill request.
 *
 * Carried explicitly through the `PendingIntent` rather than read back out of
 * `AutofillManager.EXTRA_ASSIST_STRUCTURE`. The framework does supply the structure, and re-parsing
 * it would work, but it would also mean the activity re-runs [FieldClassifier] and could reach a
 * *different* answer than the service did - the page has had time to change between the two. Deciding
 * once and carrying the decision means the field the user was offered a value for is the field that
 * receives it.
 *
 * Nothing here is a secret. Autofill ids are opaque view handles, the package and domain are the
 * identity of the app the user is already looking at, and the signature hash is public information
 * about an installed APK. The one thing that must never travel this way is a credential - see
 * [PendingCapture] for the save path, which has the same problem and solves it differently.
 */
internal data class FillSpec(
    val packageName: String,
    val webDomain: String?,
    val signature: String?,
    val fields: List<SpecField>,
    /**
     * Which vault kind answers this request.
     *
     * Decided by the service, which can see which field the user was standing in, and carried rather
     * than re-derived. The activity has only the field list, so re-deriving would lose the focus
     * signal and answer a tap on a CVV box with a list of logins.
     */
    val kind: ItemKind
) {
    val target: AutofillTarget
        get() = AutofillTarget(packageName, webDomain, signature)

    fun idOf(role: FieldRole): AutofillId? = fields.firstOrNull { it.role == role }?.autofillId

    val autofillIds: Array<AutofillId>
        get() = fields.map(SpecField::autofillId).toTypedArray()

    /** Every role present on the screen being filled. */
    val roles: Set<FieldRole> get() = fields.map(SpecField::role).toSet()

    companion object {
        fun from(parsed: ParsedStructure, kind: ItemKind): FillSpec = FillSpec(
            packageName = parsed.target.packageName,
            webDomain = parsed.target.webDomain,
            signature = parsed.target.signature,
            fields = parsed.fields.map { SpecField(it.autofillId, it.role) },
            kind = kind
        )
    }
}

internal data class SpecField(val autofillId: AutofillId, val role: FieldRole)

internal object AutofillIntents {

    const val EXTRA_MODE = "com.personal.bubuprotect.autofill.MODE"

    /** The vault was locked. Unlock, then answer the whole request. */
    const val MODE_UNLOCK = 1

    /** The user tapped a specific entry. Decrypt exactly that one and hand back its dataset. */
    const val MODE_RESOLVE = 2

    /** Nothing matched, or the user asked for something else. Show the vault and let them choose. */
    const val MODE_PICK = 3

    /** A credential was typed into another app. Offer to store it. */
    const val MODE_SAVE = 4

    private const val EXTRA_PACKAGE = "com.personal.bubuprotect.autofill.PACKAGE"
    private const val EXTRA_DOMAIN = "com.personal.bubuprotect.autofill.DOMAIN"
    private const val EXTRA_SIGNATURE = "com.personal.bubuprotect.autofill.SIGNATURE"
    private const val EXTRA_IDS = "com.personal.bubuprotect.autofill.IDS"
    private const val EXTRA_ROLES = "com.personal.bubuprotect.autofill.ROLES"
    private const val EXTRA_KIND = "com.personal.bubuprotect.autofill.KIND"

    const val EXTRA_ENTRY_ID = "com.personal.bubuprotect.autofill.ENTRY_ID"

    /**
     * Marks a dataset that fills *only* the one-time code.
     *
     * The separation is the point rather than a detail of plumbing - see [AutofillResponder] on why a
     * code and a password are never delivered by the same tap.
     */
    const val EXTRA_CODE_ONLY = "com.personal.bubuprotect.autofill.CODE_ONLY"
    const val EXTRA_CAPTURE_TOKEN = "com.personal.bubuprotect.autofill.CAPTURE_TOKEN"

    fun putSpec(intent: Intent, spec: FillSpec): Intent = intent.apply {
        putExtra(EXTRA_PACKAGE, spec.packageName)
        putExtra(EXTRA_DOMAIN, spec.webDomain)
        putExtra(EXTRA_SIGNATURE, spec.signature)
        putParcelableArrayListExtra(EXTRA_IDS, ArrayList(spec.fields.map(SpecField::autofillId)))
        putStringArrayListExtra(EXTRA_ROLES, ArrayList(spec.fields.map { it.role.name }))
        putExtra(EXTRA_KIND, spec.kind.storageKey)
    }

    fun readSpec(intent: Intent?): FillSpec? {
        val source = intent ?: return null
        val packageName = source.getStringExtra(EXTRA_PACKAGE) ?: return null
        val ids = source.parcelableIds() ?: return null
        val roles = source.getStringArrayListExtra(EXTRA_ROLES) ?: return null
        if (ids.size != roles.size || ids.isEmpty()) return null

        val fields = ids.indices.mapNotNull { index ->
            // An unrecognised role name means this intent was built by a different version of the
            // app. Dropping the field is right: filling it would require guessing what it was for.
            val role = FieldRole.entries.firstOrNull { it.name == roles[index] } ?: return@mapNotNull null
            SpecField(ids[index], role)
        }
        if (fields.isEmpty()) return null

        return FillSpec(
            packageName = packageName,
            webDomain = source.getStringExtra(EXTRA_DOMAIN),
            signature = source.getStringExtra(EXTRA_SIGNATURE),
            fields = fields,
            kind = ItemKind.fromStorage(source.getStringExtra(EXTRA_KIND).orEmpty())
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIds(): ArrayList<AutofillId>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(EXTRA_IDS, AutofillId::class.java)
        } else {
            getParcelableArrayListExtra(EXTRA_IDS)
        }
}
