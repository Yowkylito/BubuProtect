package com.personal.bubuprotect.core.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import timber.log.Timber

/** One field the service is prepared to act on. */
data class DetectedField(
    val autofillId: AutofillId,
    val role: FieldRole,
    val isFocused: Boolean,
    /**
     * The field's current contents.
     *
     * Populated only so [onSaveRequest][BubuAutofillService.onSaveRequest] can read back what the
     * user typed. It is never fed to [FieldClassifier] - see [ParsedNode] for why the decision about
     * where a password goes must not depend on values the requesting app controls.
     */
    val value: String? = null
)

/**
 * What one autofill request turned out to be.
 *
 * @param crossOriginFieldsDropped true when the structure contained fillable fields belonging to a
 *   different site than the one being filled. Not an error - a payment iframe is a normal thing for
 *   a page to contain - but worth a log line, because it is also what a credential-stealing frame
 *   looks like.
 */
data class ParsedStructure(
    val target: AutofillTarget,
    val fields: List<DetectedField>,
    val crossOriginFieldsDropped: Boolean = false
) {
    val autofillIds: Array<AutofillId> get() = fields.map(DetectedField::autofillId).toTypedArray()

    fun idOf(role: FieldRole): AutofillId? = fields.firstOrNull { it.role == role }?.autofillId

    fun valueOf(role: FieldRole): String? =
        fields.firstOrNull { it.role == role && !it.value.isNullOrEmpty() }?.value

    val hasLoginFields: Boolean
        get() = fields.any { it.role == FieldRole.USERNAME || it.role == FieldRole.PASSWORD }

    val hasCardFields: Boolean
        get() = fields.any { it.role.servedBy == com.personal.bubuprotect.domain.model.ItemKind.CARD }
}

/**
 * Turns the platform's view tree into something the rest of this package can reason about.
 *
 * All the Android-specific unpacking lives here so that [FieldClassifier] and [AutofillMatcher] stay
 * pure and testable. This file is the part that cannot be unit tested without an emulator, so it is
 * kept to walking, unpacking and two safety rules that are stated once each.
 */
internal object StructureParser {

    fun parse(structure: AssistStructure, signature: String?): ParsedStructure? {
        val packageName = structure.activityComponent?.packageName ?: return null

        val candidates = mutableListOf<Candidate>()
        for (index in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(index).rootViewNode ?: continue
            collect(root, parentVisible = true, inheritedDomain = null, into = candidates)
        }
        if (candidates.isEmpty()) return null

        // The origin being filled is the one the user is standing in. Falling back to the first
        // field with a domain covers the case where nothing is focused yet - a fill request fired
        // as the page settles rather than on a tap.
        val primaryDomain = candidates.firstOrNull { it.isFocused && it.domain != null }?.domain
            ?: candidates.firstNotNullOfOrNull { it.domain }

        /*
         * The cross-origin rule.
         *
         * A page can embed a frame from anywhere, and that frame's fields arrive in the same
         * structure as the host page's. Treating the whole structure as one identity is how a
         * manager can be talked into typing the host site's password into a frame served by someone
         * else - the user sees their bank, the field belongs to the attacker.
         *
         * So a field is only ever filled when its own origin matches the origin being filled.
         * Native fields carry no domain at all and are matched by package instead, which is why a
         * null domain is kept rather than dropped.
         */
        val (kept, dropped) = candidates.partition { candidate ->
            val domain = candidate.domain
            domain == null || primaryDomain == null || WebDomains.sameSite(domain, primaryDomain)
        }

        if (dropped.isNotEmpty()) {
            Timber.tag(TAG).i(
                "Ignored %d field(s) from another origin while filling %s",
                dropped.size,
                primaryDomain
            )
        }

        val fields = kept
            // One field per role. A form with `password` and `confirm password` gets the first, and
            // the confirmation is left for the user - filling both would be a guess about intent,
            // and on a change-password form it would be the wrong guess.
            .distinctBy { it.role }
            .map { DetectedField(it.autofillId, it.role, it.isFocused, it.value) }

        if (fields.isEmpty()) return null

        return ParsedStructure(
            target = AutofillTarget.of(packageName, primaryDomain, signature),
            fields = fields,
            crossOriginFieldsDropped = dropped.isNotEmpty()
        )
    }

    private fun collect(
        node: AssistStructure.ViewNode,
        parentVisible: Boolean,
        inheritedDomain: String?,
        into: MutableList<Candidate>
    ) {
        // Visibility has to be accumulated down the tree, not read off the node. `getVisibility()`
        // reports what the view itself asked for, so a perfectly VISIBLE field inside a GONE
        // container reads as visible - which is exactly the shape a harvesting layout takes.
        val visible = parentVisible && node.visibility == View.VISIBLE

        // A frame's domain applies to everything inside it; only the frame node itself declares one.
        val domain = WebDomains.normalize(node.webDomain) ?: inheritedDomain

        val autofillId = node.autofillId
        if (autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val role = FieldClassifier.classify(
                ParsedNode(
                    autofillHints = node.autofillHints?.toList().orEmpty(),
                    htmlTag = node.htmlInfo?.tag?.lowercase(),
                    htmlAttributes = node.htmlAttributes(),
                    idEntry = node.idEntry,
                    hint = node.hint,
                    contentDescription = node.contentDescription?.toString(),
                    inputKind = node.inputType.toInputKind(),
                    isVisible = visible,
                    acceptsText = true
                )
            )
            if (role != null) {
                into += Candidate(
                    autofillId = autofillId,
                    role = role,
                    isFocused = node.isFocused,
                    domain = domain,
                    value = node.currentText()
                )
            }
        }

        for (index in 0 until node.childCount) {
            collect(node.getChildAt(index), visible, domain, into)
        }
    }

    private fun AssistStructure.ViewNode.htmlAttributes(): Map<String, String> {
        val attributes = htmlInfo?.attributes ?: return emptyMap()
        return buildMap {
            attributes.forEach { pair ->
                val name = pair.first?.lowercase() ?: return@forEach
                put(name, pair.second.orEmpty())
            }
        }
    }

    /** The value actually in the field, for the save path only. */
    private fun AssistStructure.ViewNode.currentText(): String? {
        val fromValue = autofillValue?.takeIf { it.isText }?.textValue?.toString()
        return fromValue?.takeIf { it.isNotEmpty() } ?: text?.toString()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Unpacks `android.text.InputType`.
     *
     * The variation bits are only meaningful within their class, so each is masked against its own
     * class rather than tested globally - `TYPE_NUMBER_VARIATION_PASSWORD` and
     * `TYPE_TEXT_VARIATION_URI` share a bit pattern, and reading them class-blind would classify
     * every URL box as a PIN.
     */
    private fun Int.toInputKind(): InputKind {
        val klass = this and InputType.TYPE_MASK_CLASS
        val variation = this and InputType.TYPE_MASK_VARIATION

        val isTextPassword = klass == InputType.TYPE_CLASS_TEXT && variation in TEXT_PASSWORD_VARIATIONS
        val isNumberPassword = klass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

        return InputKind(
            isPassword = isTextPassword || isNumberPassword,
            isEmail = klass == InputType.TYPE_CLASS_TEXT && variation in TEXT_EMAIL_VARIATIONS,
            isNumeric = klass == InputType.TYPE_CLASS_NUMBER || klass == InputType.TYPE_CLASS_PHONE,
            isMultiline = (this and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        )
    }

    private val TEXT_PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    )

    private val TEXT_EMAIL_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    )

    private const val TAG = "Autofill"

    private data class Candidate(
        val autofillId: AutofillId,
        val role: FieldRole,
        val isFocused: Boolean,
        val domain: String?,
        val value: String?
    )
}
