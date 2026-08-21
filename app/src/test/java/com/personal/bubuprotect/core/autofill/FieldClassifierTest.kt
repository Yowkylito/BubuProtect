package com.personal.bubuprotect.core.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The component that decides where a password gets typed.
 *
 * Worth being thorough about: every false positive here is a secret typed into a field that was not
 * asking for one, and the cases that matter are exactly the ones that are awkward to reach by hand
 * on a device - an invisible field, a search box on a login page, a one-time-code input.
 */
class FieldClassifierTest {

    // --- Declared signals -----------------------------------------------------------------------

    @Test
    fun `honours autofill hints`() {
        assertEquals(
            FieldRole.USERNAME,
            FieldClassifier.classify(node(autofillHints = listOf("username")))
        )
        assertEquals(
            FieldRole.PASSWORD,
            FieldClassifier.classify(node(autofillHints = listOf("password")))
        )
        assertEquals(
            FieldRole.PASSWORD,
            FieldClassifier.classify(node(autofillHints = listOf("newPassword")))
        )
        assertEquals(
            FieldRole.CARD_NUMBER,
            FieldClassifier.classify(node(autofillHints = listOf("creditCardNumber")))
        )
    }

    @Test
    fun `reads the autocomplete attribute, including token lists`() {
        assertEquals(
            FieldRole.PASSWORD,
            FieldClassifier.classify(node(html = mapOf("autocomplete" to "current-password")))
        )
        assertEquals(
            FieldRole.CARD_NUMBER,
            FieldClassifier.classify(node(html = mapOf("autocomplete" to "section-blue billing cc-number")))
        )
    }

    @Test
    fun `treats a declared password input as a password in either dialect`() {
        assertEquals(
            FieldRole.PASSWORD,
            FieldClassifier.classify(node(html = mapOf("type" to "password")))
        )
        assertEquals(
            FieldRole.PASSWORD,
            FieldClassifier.classify(node(inputKind = InputKind(isPassword = true)))
        )
    }

    /**
     * The ordering bug this guards against is real: "Confirm your email password" contains `email`,
     * and a classifier that reached its keyword scan before honouring a declared password type would
     * put the username in the password box.
     */
    @Test
    fun `a declared password type beats a username keyword in the label`() {
        val role = FieldClassifier.classify(
            node(
                idEntry = "email_password_confirm",
                html = mapOf("type" to "password")
            )
        )
        assertEquals(FieldRole.PASSWORD, role)
    }

    // --- Keyword heuristics ---------------------------------------------------------------------

    @Test
    fun `falls back to keywords across separators and cases`() {
        assertEquals(FieldRole.PASSWORD, FieldClassifier.classify(node(idEntry = "user_password")))
        assertEquals(FieldRole.USERNAME, FieldClassifier.classify(node(idEntry = "loginId")))
        assertEquals(FieldRole.CARD_NUMBER, FieldClassifier.classify(node(html = mapOf("name" to "cc-number"))))
        assertEquals(
            FieldRole.CARD_SECURITY_CODE,
            FieldClassifier.classify(node(hint = "CVV"))
        )
        assertEquals(
            FieldRole.CARD_EXPIRY,
            FieldClassifier.classify(node(html = mapOf("placeholder" to "Expiration date")))
        )
        assertEquals(
            FieldRole.CARD_HOLDER,
            FieldClassifier.classify(node(idEntry = "name_on_card"))
        )
    }

    @Test
    fun `password wins over username when a label contains both`() {
        assertEquals(FieldRole.PASSWORD, FieldClassifier.classify(node(idEntry = "email_password")))
    }

    @Test
    fun `an unmarked email input in a browser is offered as a username`() {
        assertEquals(
            FieldRole.USERNAME,
            FieldClassifier.classify(node(htmlTag = "input", html = mapOf("type" to "email")))
        )
    }

    // --- Refusals -------------------------------------------------------------------------------

    /**
     * A hostile layout can carry an offscreen password field purely so a manager fills it and the
     * app reads the value back. Nothing about the field itself looks wrong - only its visibility.
     */
    @Test
    fun `refuses an invisible field even when it is plainly a password`() {
        assertNull(
            FieldClassifier.classify(
                node(
                    autofillHints = listOf("password"),
                    inputKind = InputKind(isPassword = true),
                    isVisible = false
                )
            )
        )
    }

    @Test
    fun `refuses a field that cannot take text`() {
        assertNull(
            FieldClassifier.classify(node(autofillHints = listOf("password"), acceptsText = false))
        )
    }

    @Test
    fun `leaves search boxes alone`() {
        assertNull(FieldClassifier.classify(node(idEntry = "search_user_query")))
        assertNull(FieldClassifier.classify(node(html = mapOf("name" to "search"))))
    }

    /**
     * One-time-code boxes are now recognised rather than refused.
     *
     * They used to be on the deny list, and the reason was sound while the vault held no seeds: the
     * only thing that could be typed into one was a password, and a password in an OTP box burns an
     * attempt against a rate limit the user cannot see.
     *
     * Nothing has been loosened by naming them. [com.personal.bubuprotect.core.autofill.AutofillResponder]
     * only builds a code dataset for an entry that actually carries a seed, so a vault with no 2FA
     * still offers nothing here - and a password is never a candidate for this role at all.
     */
    @Test
    fun `recognises one-time-code fields`() {
        assertEquals(FieldRole.OTP, FieldClassifier.classify(node(idEntry = "otp_code")))
        assertEquals(FieldRole.OTP, FieldClassifier.classify(node(hint = "Verification code")))
        assertEquals(FieldRole.OTP, FieldClassifier.classify(node(idEntry = "twoFactorInput")))
        assertEquals(
            FieldRole.OTP,
            FieldClassifier.classify(node(html = mapOf("autocomplete" to "one-time-code")))
        )
    }

    /**
     * A checkout page carries both, and "code" is in both vocabularies. Getting this backwards puts
     * a card's security code into a 2FA box, or a rolling code into the CVV field.
     */
    @Test
    fun `tells a one-time code apart from a card security code`() {
        assertEquals(FieldRole.CARD_SECURITY_CODE, FieldClassifier.classify(node(hint = "CVV")))
        assertEquals(
            FieldRole.CARD_SECURITY_CODE,
            FieldClassifier.classify(node(idEntry = "card_security_code"))
        )
        assertEquals(FieldRole.OTP, FieldClassifier.classify(node(idEntry = "sms_code")))
    }

    /** A captcha stays refused: no stored value can ever answer one. */
    @Test
    fun `leaves captchas alone`() {
        assertNull(FieldClassifier.classify(node(idEntry = "captcha_answer")))
    }

    @Test
    fun `returns nothing when there is no evidence at all`() {
        assertNull(FieldClassifier.classify(node()))
        assertNull(FieldClassifier.classify(node(idEntry = "field_3")))
    }

    /** `pan` and similar three-letter fragments used to match `panel`. They must not. */
    @Test
    fun `does not fire on incidental substrings`() {
        assertNull(FieldClassifier.classify(node(idEntry = "panel_input")))
        assertNull(FieldClassifier.classify(node(idEntry = "company_name")))
    }

    private fun node(
        autofillHints: List<String> = emptyList(),
        htmlTag: String? = null,
        html: Map<String, String> = emptyMap(),
        idEntry: String? = null,
        hint: String? = null,
        contentDescription: String? = null,
        inputKind: InputKind = InputKind(),
        isVisible: Boolean = true,
        acceptsText: Boolean = true
    ) = ParsedNode(
        autofillHints = autofillHints,
        htmlTag = htmlTag,
        htmlAttributes = html,
        idEntry = idEntry,
        hint = hint,
        contentDescription = contentDescription,
        inputKind = inputKind,
        isVisible = isVisible,
        acceptsText = acceptsText
    )
}
