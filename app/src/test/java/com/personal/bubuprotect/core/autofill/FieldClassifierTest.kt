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
     * A one-time-code box is short and numeric and will happily swallow a PIN or a card security
     * code. Filling it is worse than doing nothing: it burns an attempt against a rate limit the
     * user cannot see.
     */
    @Test
    fun `leaves one-time-code fields alone`() {
        assertNull(FieldClassifier.classify(node(idEntry = "otp_code")))
        assertNull(FieldClassifier.classify(node(hint = "Verification code")))
        assertNull(FieldClassifier.classify(node(idEntry = "twoFactorInput")))
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
