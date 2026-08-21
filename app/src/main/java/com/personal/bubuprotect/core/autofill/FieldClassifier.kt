package com.personal.bubuprotect.core.autofill

/**
 * Decides what a single form field is for.
 *
 * A pure function over [ParsedNode], which is the point: this is the component that decides where a
 * password gets typed, and it is exercised by JVM unit tests rather than by installing the app and
 * trying websites by hand.
 *
 * ### Signals, strongest first
 *
 * 1. **Autofill hints.** The app or page explicitly said what the field is. Nothing beats it.
 * 2. **`autocomplete`.** The HTML equivalent, and near enough the same vocabulary.
 * 3. **Declared input type.** `type="password"`, or the platform's password input variations.
 * 4. **Keywords** in the resource id, hint, placeholder and content description.
 *
 * Only the last one is guesswork, and it runs last precisely because it is. The order matters in
 * practice: a field labelled "Confirm your email password" contains the word `email`, and reaching
 * the keyword scan before honouring a declared `type="password"` is how a manager ends up typing a
 * username into a password box.
 *
 * ### Where it deliberately gives up
 *
 * [DENY] is checked before anything except the strongest signals. A search box on a login page looks
 * a great deal like a username field to a keyword scan, and a one-time-code field looks like a
 * short numeric password. Filling either is worse than filling nothing: the search box publishes the
 * username into a query string and probably a server log, and the OTP box takes a value that was
 * never a code. When the evidence is ambiguous this returns null and the field is left alone.
 */
internal object FieldClassifier {

    /** @return the field's role, or null when it is not something this vault should touch. */
    fun classify(node: ParsedNode): FieldRole? {
        if (!node.isVisible || !node.acceptsText) return null

        // Declared intent wins outright, and is trusted even against the deny list: a page that went
        // to the trouble of tagging a field `current-password` has told us more than its id ever
        // will.
        node.autofillHints.forEach { hint -> fromVocabulary(hint)?.let { return it } }
        node.htmlAttributes["autocomplete"]?.let { declared ->
            // The attribute is a token list: "section-blue shipping cc-number".
            declared.split(' ', ',').forEach { token -> fromVocabulary(token)?.let { return it } }
        }

        if (isDenied(node)) return null

        // A declared password input is unambiguous, in either dialect.
        if (node.htmlAttributes["type"].equals("password", ignoreCase = true)) return FieldRole.PASSWORD
        if (node.inputKind.isPassword) return FieldRole.PASSWORD

        keywordRole(node)?.let { return it }

        // Last resort, and only for a browser: an `<input type="email">` with no other marking is a
        // username often enough to be worth offering. The platform equivalent is not treated the
        // same way, because a native email field is as likely to be a "send to" box as a login.
        if (node.htmlTag == "input" &&
            node.htmlAttributes["type"].equals("email", ignoreCase = true)
        ) {
            return FieldRole.USERNAME
        }

        return null
    }

    /**
     * The shared vocabulary of `View.AUTOFILL_HINT_*` and HTML `autocomplete`.
     *
     * They overlap almost exactly, which is not a coincidence - the platform constants were named
     * after the HTML spec - so one table serves both. Compared case- and separator-insensitively
     * because the two dialects disagree on `creditCardNumber` versus `cc-number`.
     */
    private fun fromVocabulary(token: String): FieldRole? = when (normalize(token)) {
        "username", "newusername", "email", "emailaddress" -> FieldRole.USERNAME
        // `one-time-code` is the standard autocomplete token, and browsers and iOS both emit it.
        "onetimecode", "otp" -> FieldRole.OTP
        "password", "newpassword", "currentpassword" -> FieldRole.PASSWORD
        "creditcardnumber", "ccnumber", "cardnumber" -> FieldRole.CARD_NUMBER
        "creditcardexpirationdate", "creditcardexpirationmonth", "creditcardexpirationyear",
        "ccexp", "ccexpmonth", "ccexpyear" -> FieldRole.CARD_EXPIRY
        "creditcardsecuritycode", "cccsc" -> FieldRole.CARD_SECURITY_CODE
        "ccname", "creditcardname" -> FieldRole.CARD_HOLDER
        else -> null
    }

    /**
     * Keyword scan over every label-ish string the node carries.
     *
     * Specific roles are tested before general ones. `cardholder` contains no password keyword, but
     * `cardnumber` and `number` both match a naive card rule, and `securitycode` would fall to a
     * `code` rule - so the order below is the specificity order, not an arbitrary one.
     */
    private fun keywordRole(node: ParsedNode): FieldRole? {
        val haystack = labelText(node)
        if (haystack.isEmpty()) return null

        return when {
            // Ahead of the card security code: "code" appears in both vocabularies, and a one-time
            // code box on a checkout page must not be mistaken for a CVV field.
            haystack.containsAny(ONE_TIME_CODE) -> FieldRole.OTP
            haystack.containsAny(CARD_SECURITY_CODE) -> FieldRole.CARD_SECURITY_CODE
            haystack.containsAny(CARD_EXPIRY) -> FieldRole.CARD_EXPIRY
            haystack.containsAny(CARD_HOLDER) -> FieldRole.CARD_HOLDER
            haystack.containsAny(CARD_NUMBER) -> FieldRole.CARD_NUMBER
            haystack.containsAny(PASSWORD) -> FieldRole.PASSWORD
            haystack.containsAny(USERNAME) -> FieldRole.USERNAME
            else -> null
        }
    }

    private fun isDenied(node: ParsedNode): Boolean = labelText(node).containsAny(DENY)

    /**
     * Every label-ish string on the node, normalised and joined.
     *
     * Joined with a separator so a keyword cannot be formed accidentally by two fields abutting -
     * an id ending `card` next to a hint starting `number` must not read as `cardnumber`.
     */
    private fun labelText(node: ParsedNode): String = buildList {
        add(node.idEntry)
        add(node.hint)
        add(node.contentDescription)
        add(node.htmlAttributes["name"])
        add(node.htmlAttributes["id"])
        add(node.htmlAttributes["placeholder"])
        add(node.htmlAttributes["aria-label"])
        add(node.htmlAttributes["label"])
    }.filterNotNull()
        .map(::normalize)
        .filter(String::isNotEmpty)
        .joinToString(" ")

    /** Lowercases and drops every separator, so `cc-number`, `cc_number` and `ccNumber` unify. */
    private fun normalize(value: String): String =
        value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private fun String.containsAny(keywords: List<String>): Boolean =
        keywords.any { contains(it) }

    /**
     * Fields that must never be filled even though they read like something fillable.
     *
     * The one-time-code family used to live here. It has moved to [ONE_TIME_CODE] now that the vault
     * can hold a seed - but nothing has been loosened: a dataset for an OTP field is only built when
     * the matched entry actually has one, so a vault with no seeds behaves exactly as it did before.
     *
     * `captcha` stays, because no stored value can ever answer one.
     */
    private val DENY = listOf(
        "search", "query", "captcha", "url", "website", "homepage"
    )

    private val ONE_TIME_CODE = listOf(
        "onetimecode", "onetimepass", "verificationcode", "smscode", "authcode", "twofactor",
        "2fa", "mfacode", "otpcode", "totp"
    )

    private val PASSWORD = listOf(
        "password", "passwd", "passphrase", "pwd", "senha", "contrasena", "motdepasse", "kennwort"
    )

    private val USERNAME = listOf(
        "username", "userid", "useremail", "loginid", "loginname", "emailaddress", "email",
        "signin", "login", "account", "identifier", "user"
    )

    // No bare "pan": three letters that sit inside "panel" and "company" is a false positive
    // waiting to happen, and a card number typed into an unrelated box is not a small mistake.
    private val CARD_NUMBER = listOf("cardnumber", "cardno", "creditcard", "ccnum")
    private val CARD_EXPIRY = listOf("expiry", "expiration", "expdate", "expmonth", "expyear", "validthru", "ccexp")
    private val CARD_SECURITY_CODE = listOf("cvv", "cvc", "csc", "cvn", "securitycode", "cardcode")
    private val CARD_HOLDER = listOf("cardholder", "nameoncard", "ccname", "cardname")
}
