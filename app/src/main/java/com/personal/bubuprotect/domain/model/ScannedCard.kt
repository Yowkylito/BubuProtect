package com.personal.bubuprotect.domain.model

/**
 * What a contactless tap actually yields.
 *
 * ### What is deliberately absent
 *
 * There is no CVV and no PIN, and there is no room in this class to add one later by accident. The
 * number printed on the back of a card is not on its chip - the chip holds an *iCVV* used only in
 * the contactless authorisation cryptogram, which is a different value and useless for typing into a
 * checkout. The PIN is verified by the chip, never disclosed by it. A card scan therefore always
 * leaves those two fields for the user to fill in, and the UI has to say so rather than let a blank
 * CVV read as a failed scan.
 *
 * ### Why [pan] is a `CharArray`
 *
 * So the reader can zero it. The digits pass through this object on their way to the editor, and
 * [wipe] is called as soon as the editor has copied them - see
 * [com.personal.bubuprotect.ui.components.NfcCardScanSheet]. That bounds how long the NFC layer
 * keeps a card number alive; it does not, and cannot, do anything about the immutable `String` the
 * editor's form state necessarily holds afterwards. A `String` here would simply have added one more
 * copy nobody can reclaim.
 */
class ScannedCard(
    val pan: CharArray,
    val expiryMonth: Int,
    val expiryYear: Int,
    val holderName: String?,
    /**
     * The application label the card advertises - "VISA CREDIT", "Debit Mastercard". A product name,
     * not a bank name, so it seeds the entry's title rather than the "Bank or issuer" field.
     */
    val applicationLabel: String?
) {
    val hasExpiry: Boolean get() = expiryMonth in 1..12 && expiryYear >= 2000

    /** `MM/YY`, matching the hint on the editor's expiry field. Empty when the card withheld it. */
    val formattedExpiry: String
        get() = if (hasExpiry) {
            "%02d/%02d".format(expiryMonth, expiryYear % 100)
        } else {
            ""
        }

    val maskedNumber: String
        get() = if (pan.size >= LAST_FOUR) {
            "•••• " + String(pan, pan.size - LAST_FOUR, LAST_FOUR)
        } else {
            "••••"
        }

    /** Seeds the entry title when the user has not typed one yet. */
    val suggestedLabel: String
        get() = applicationLabel?.takeIf(String::isNotBlank) ?: "Card $maskedNumber"

    fun wipe() {
        pan.fill('\u0000')
    }

    /**
     * Masked, always.
     *
     * Not cosmetic: this is the difference between a stray log line or a crash report containing
     * four digits and one containing a live card number. There is no code path that should ever be
     * printing this object, and if one appears anyway it must not be the one that leaks the PAN.
     */
    override fun toString(): String = "ScannedCard($maskedNumber)"

    private companion object {
        const val LAST_FOUR = 4
    }
}
