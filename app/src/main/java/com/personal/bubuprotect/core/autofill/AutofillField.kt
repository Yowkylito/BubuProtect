package com.personal.bubuprotect.core.autofill

import com.personal.bubuprotect.domain.model.ItemKind

/**
 * What a form field is for, as far as this vault can serve it.
 *
 * Deliberately short. Every role here maps onto something an entry actually stores, because a role
 * the vault cannot fill is a role that only adds ways to guess wrong. Address, name and phone
 * autofill are all things the framework supports and this app has no data for, so they are not
 * modelled at all - the fields are simply left alone.
 */
enum class FieldRole {
    USERNAME,
    PASSWORD,

    /**
     * A one-time-code box.
     *
     * This used to be on the classifier's deny list, and the reason was sound: with no seed in the
     * vault, the only thing that could be typed here was a password, and a password in an OTP box
     * burns an attempt against a rate limit the user cannot see.
     *
     * Now that the vault can hold seeds it becomes a role - but a *conditional* one. A dataset for it
     * is only ever offered when the matched entry actually has a seed, so the old protection still
     * holds for every entry that does not. See [AutofillResponder] for the second rule that goes with
     * it: a code is never filled in the same action as the password.
     */
    OTP,

    CARD_NUMBER,
    CARD_EXPIRY,
    CARD_SECURITY_CODE,
    CARD_HOLDER;

    val servedBy: ItemKind
        get() = when (this) {
            USERNAME, PASSWORD, OTP -> ItemKind.LOGIN
            CARD_NUMBER, CARD_EXPIRY, CARD_SECURITY_CODE, CARD_HOLDER -> ItemKind.CARD
        }
}

/**
 * How a field takes input, reduced to the handful of distinctions that matter here.
 *
 * The platform expresses this as a packed `android.text.InputType` bitfield. It is unpacked at the
 * edge, in [StructureParser], so that [FieldClassifier] stays a pure function over data and can be
 * tested on the JVM without an emulator - which for the component that decides where passwords get
 * typed is worth the extra type.
 */
data class InputKind(
    val isPassword: Boolean = false,
    val isEmail: Boolean = false,
    val isNumeric: Boolean = false,
    val isMultiline: Boolean = false
)

/**
 * One node of the assist structure, flattened to the facts a classifier can use.
 *
 * Note what is *absent*: the node's current text. A field's existing value is user data - possibly
 * the secret already in it - and classifying on it would mean the decision about where a password
 * goes depends on content the requesting app chose. Only labels, ids and declared types are read.
 */
data class ParsedNode(
    /** `View.getAutofillHints()` - the app's own declaration, and the most trustworthy signal. */
    val autofillHints: List<String> = emptyList(),
    /** Lowercase HTML tag when the node came from a WebView or browser, else null. */
    val htmlTag: String? = null,
    /** HTML attributes, keys lowercased. `autocomplete`, `type`, `name`, `id`, `placeholder`. */
    val htmlAttributes: Map<String, String> = emptyMap(),
    /** The resource entry name - the `password_field` of `@id/password_field`. */
    val idEntry: String? = null,
    val hint: String? = null,
    val contentDescription: String? = null,
    val inputKind: InputKind = InputKind(),
    /**
     * Whether the node is actually on screen.
     *
     * Load-bearing, not cosmetic. A hostile app can put an invisible password field in its layout
     * purely so that a manager fills it and the app can read the value back without the user seeing
     * anything. Refusing to classify an invisible node is the defence, and it belongs here rather
     * than at the call site so no future caller can forget it.
     */
    val isVisible: Boolean = true,
    /** False for anything the framework will not accept a text value for. */
    val acceptsText: Boolean = true
)
