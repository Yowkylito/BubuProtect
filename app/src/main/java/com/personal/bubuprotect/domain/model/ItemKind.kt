package com.personal.bubuprotect.domain.model

/**
 * What sort of secret an entry holds.
 *
 * [storageKey] is persisted in a plain (SQLCipher-protected) column so the list can be filtered in
 * SQL without decrypting anything. It is written to disk, so **never rename one** - add a new
 * constant instead. The enum name is free to change; the key is not.
 */
enum class ItemKind(val storageKey: String, val title: String, val tagline: String) {
    LOGIN("login", "Login", "A site or app you sign in to"),
    CARD("card", "Payment card", "Card number, expiry, CVV and PIN"),
    NOTE("note", "Secure note", "Anything that is just words"),
    IDENTITY("identity", "ID document", "Passport, licence, national ID"),
    WIFI("wifi", "Wi-Fi network", "A network name and its key");

    companion object {
        /**
         * Unknown keys fall back to [NOTE] rather than throwing.
         *
         * A row written by a newer build must not make the whole list uncrashable-but-empty on an
         * older one. [NOTE] is the safe landing spot: it renders whatever plain text the row has and
         * claims nothing about structure it cannot verify.
         */
        fun fromStorage(key: String): ItemKind =
            entries.firstOrNull { it.storageKey == key } ?: NOTE
    }
}

/**
 * Where a field's value physically lives.
 *
 * Four of these map onto fixed columns; [Extra] shares one encrypted JSON blob. That split is the
 * seam that makes new kinds cheap: adding "Crypto wallet" tomorrow is an [ItemKind] constant and a
 * list of [FieldSpec]s, with no schema migration, because its seed phrase and derivation path land
 * in [Extra] slots inside the existing blob.
 *
 * The three fixed columns are kept rather than folding everything into the blob because they are the
 * fields every kind has some version of, and giving them their own AAD-bound ciphertext means a
 * password can never be swapped with a note by editing the database file.
 */
sealed interface FieldSlot {
    /** The non-secret-ish identifier: username, cardholder, SSID, full name. */
    data object Identity : FieldSlot

    /** The primary secret: password, card number, document number, network key. */
    data object Secret : FieldSlot

    /** Free text. For [ItemKind.NOTE] this is the entry's whole point. */
    data object Notes : FieldSlot

    /** The one genuinely non-secret field, stored in the clear inside the encrypted database. */
    data object Website : FieldSlot

    /** A kind-specific field inside the encrypted extras blob. [key] is persisted - never rename. */
    data class Extra(val key: String) : FieldSlot
}

enum class FieldKeyboard { TEXT, NUMBER, EMAIL, URI }

/**
 * One row in an entry's form, and one row in its detail view.
 *
 * @param isSecret masked in the detail view until the user authenticates, and offered a reveal
 *   toggle in the editor. Multi-line secrets are *not* masked while editing - you cannot proof-read
 *   a note you cannot see, and you already authenticated to open the editor.
 */
data class FieldSpec(
    val slot: FieldSlot,
    val label: String,
    val isSecret: Boolean = false,
    val isRequired: Boolean = false,
    val isMultiline: Boolean = false,
    val keyboard: FieldKeyboard = FieldKeyboard.TEXT,
    val hint: String? = null
)

/**
 * The form for each kind, in the order it is shown.
 *
 * Deliberately a plain function over data rather than per-kind subclasses: the editor and the detail
 * screen both walk this list to build themselves, so the two can never drift out of sync, and adding
 * a field is a one-line change in one place.
 */
val ItemKind.fields: List<FieldSpec>
    get() = when (this) {
        ItemKind.LOGIN -> listOf(
            FieldSpec(FieldSlot.Identity, "Username or email", keyboard = FieldKeyboard.EMAIL),
            FieldSpec(FieldSlot.Secret, "Password", isSecret = true, isRequired = true),
            FieldSpec(FieldSlot.Website, "Website", keyboard = FieldKeyboard.URI, hint = "bubu.example.com"),
            FieldSpec(FieldSlot.Notes, "Notes", isMultiline = true)
        )

        ItemKind.CARD -> listOf(
            FieldSpec(FieldSlot.Identity, "Name on card"),
            FieldSpec(
                FieldSlot.Secret,
                "Card number",
                isSecret = true,
                isRequired = true,
                keyboard = FieldKeyboard.NUMBER
            ),
            FieldSpec(FieldSlot.Extra("expiry"), "Expires", hint = "MM/YY"),
            FieldSpec(FieldSlot.Extra("cvv"), "CVV", isSecret = true, keyboard = FieldKeyboard.NUMBER),
            FieldSpec(FieldSlot.Extra("pin"), "PIN", isSecret = true, keyboard = FieldKeyboard.NUMBER),
            FieldSpec(FieldSlot.Extra("issuer"), "Bank or issuer"),
            FieldSpec(FieldSlot.Notes, "Notes", isMultiline = true)
        )

        ItemKind.NOTE -> listOf(
            FieldSpec(
                FieldSlot.Notes,
                "Your note",
                isSecret = true,
                isRequired = true,
                isMultiline = true
            )
        )

        ItemKind.IDENTITY -> listOf(
            FieldSpec(FieldSlot.Identity, "Full name", isRequired = true),
            FieldSpec(FieldSlot.Extra("docType"), "Document type", hint = "Passport"),
            FieldSpec(FieldSlot.Secret, "Document number", isSecret = true, isRequired = true),
            FieldSpec(FieldSlot.Extra("country"), "Issuing country"),
            FieldSpec(FieldSlot.Extra("issued"), "Issued on", hint = "2024-01-31"),
            FieldSpec(FieldSlot.Extra("expires"), "Expires on", hint = "2034-01-30"),
            FieldSpec(FieldSlot.Notes, "Notes", isMultiline = true)
        )

        ItemKind.WIFI -> listOf(
            FieldSpec(FieldSlot.Identity, "Network name (SSID)", isRequired = true),
            FieldSpec(FieldSlot.Secret, "Network key", isSecret = true, isRequired = true),
            FieldSpec(FieldSlot.Extra("security"), "Security", hint = "WPA3"),
            FieldSpec(FieldSlot.Notes, "Notes", isMultiline = true)
        )
    }

/** The one field a generated password makes sense for. Drives whether the dice button appears. */
val ItemKind.supportsGeneratedSecret: Boolean
    get() = this == ItemKind.LOGIN || this == ItemKind.WIFI
