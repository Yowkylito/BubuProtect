package com.personal.bubuprotect.core.crypto

import javax.crypto.spec.SecretKeySpec

/**
 * The second encryption layer, applied to individual credential columns *before* they reach the
 * already-encrypted database.
 *
 * SQLCipher protects the file at rest. This protects the row from everything that gets past that:
 * a `.dump` taken while the vault happens to be open, an ADB backup of a debug build, a future bug
 * that logs a query result, or a Room migration that copies rows through a temp table. In all of
 * those the credential stays a sealed box.
 *
 * The AAD is what makes this more than double encryption. Every box is bound to the entry id *and*
 * the column name, so an attacker with write access to the database file cannot:
 *  - copy the password box from a throwaway account into the bank entry's row, or
 *  - swap the username and password columns to make the UI reveal the password in a field that is
 *    displayed without authentication.
 * Either edit fails the GCM tag check and surfaces as tampering rather than as a wrong secret.
 */
class FieldCipher(private val key: SecretKeySpec) {

    fun encrypt(entryId: String, field: Field, value: String): ByteArray {
        val plaintext = value.toByteArray(Charsets.UTF_8)
        return try {
            AesGcm.seal(key, plaintext, aad = aad(entryId, field))
        } finally {
            // Best effort: the String itself is immutable and stays in the heap until GC, which is
            // an accepted limit of taking input from a Compose text field.
            plaintext.wipe()
        }
    }

    fun decrypt(entryId: String, field: Field, box: ByteArray): String {
        val plaintext = AesGcm.open(key, box, aad = aad(entryId, field))
        return try {
            String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.wipe()
        }
    }

    private fun aad(entryId: String, field: Field): ByteArray =
        "$AAD_VERSION|$entryId|${field.label}".toByteArray(Charsets.UTF_8)

    /** Columns that get the second layer. The label is part of the AAD - never rename one. */
    enum class Field(val label: String) {
        USERNAME("username"),
        PASSWORD("password"),
        NOTES("notes"),

        /** The kind-specific JSON blob. Sealed as one unit, so its keys are covered by the tag too. */
        EXTRAS("extras")
    }

    private companion object {
        const val AAD_VERSION = "bubu/field/v1"
    }
}
