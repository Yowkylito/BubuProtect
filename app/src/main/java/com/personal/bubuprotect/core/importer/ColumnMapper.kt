package com.personal.bubuprotect.core.importer

/** What an imported column turned out to hold. */
enum class ImportField {
    LABEL,
    USERNAME,
    PASSWORD,
    URL,
    NOTES,

    /**
     * A one-time-password seed, usually an `otpauth://` URI.
     *
     * Recognised even though this app cannot yet generate codes, because the alternative is dropping
     * it. Someone migrating a vault with 2FA seeds in it would lose them silently and only find out
     * when locked out of an account - see [CredentialImporter] for where they are put instead.
     */
    TOTP,

    /** Recognised so it can be reported, not stored. */
    CARD_NUMBER
}

/**
 * Works out which column is which from the header row.
 *
 * ### Why one mapper rather than a parser per product
 *
 * The obvious design is a `ChromeCsvParser`, a `BitwardenCsvParser`, a `LastPassCsvParser`, and so
 * on. It works, and it is wrong: every one of those products can rename a column in its next
 * release, there are dozens more managers, and a user whose export is not on the list gets nothing.
 *
 * Every one of these formats is, underneath, the same handful of fields with different spellings. So
 * this maps *header names* to meanings through a synonym table, and a format nobody has heard of
 * imports correctly as long as it labels its columns in a recognisable way. Adding support for
 * another product is usually adding a string.
 *
 * ### Ordering matters more than it looks
 *
 * Bitwarden emits both `name` and `login_username`; 1Password emits `Title` and `Username`. A rule
 * that matched `name` for the username would grab the entry title. So the table is keyed on exact
 * normalised names first, and only falls back to substring matching for the handful of cases where
 * that is safe - with the qualified forms (`login_username`) checked before the bare ones.
 */
internal object ColumnMapper {

    /** @return column index by field, for the fields present. */
    fun map(header: List<String>): Map<ImportField, Int> {
        val normalised = header.map(::normalize)
        val result = mutableMapOf<ImportField, Int>()
        val claimedColumns = mutableSetOf<Int>()

        // Exact matches first, so a qualified header always beats a loose one.
        normalised.forEachIndexed { index, name ->
            val field = EXACT[name] ?: return@forEachIndexed
            if (result.putIfAbsent(field, index) == null) claimedColumns.add(index)
        }

        /*
         * Then the loose pass - but never over a column the exact pass already understood.
         *
         * One column means one thing. Without that rule, Bitwarden's `login_uri` is claimed as the
         * URL by the exact table and then *also* matched by the `login` fragment here, so an export
         * that happened to have no username column would fill usernames with URLs. Skipping claimed
         * columns makes that impossible rather than merely unlikely.
         */
        normalised.forEachIndexed { index, name ->
            if (index in claimedColumns) return@forEachIndexed
            LOOSE.firstOrNull { (fragment, _) -> name.contains(fragment) }
                ?.let { (_, field) ->
                    if (result.putIfAbsent(field, index) == null) claimedColumns.add(index)
                }
        }

        return result
    }

    /**
     * Whether a mapping is usable at all.
     *
     * A password alone is enough. A row with a password and nothing else is still worth importing -
     * the user can name it later - whereas a file with no password column is not a credential export
     * and importing it would produce a vault full of empty entries.
     */
    fun isUsable(mapping: Map<ImportField, Int>): Boolean = ImportField.PASSWORD in mapping

    private fun normalize(header: String): String =
        header.trim().lowercase().replace(" ", "").replace("-", "_")

    /**
     * Exact header names, from the exports these actually come from.
     *
     * Grouped by product so the next person adding one can see the shape rather than guessing.
     */
    private val EXACT: Map<String, ImportField> = buildMap {
        // Chrome, Edge, Brave, Safari, Firefox
        put("name", ImportField.LABEL)
        put("url", ImportField.URL)
        put("username", ImportField.USERNAME)
        put("password", ImportField.PASSWORD)
        put("note", ImportField.NOTES)
        put("notes", ImportField.NOTES)
        put("httprealm", ImportField.NOTES)

        // Bitwarden
        put("login_username", ImportField.USERNAME)
        put("login_password", ImportField.PASSWORD)
        put("login_uri", ImportField.URL)
        put("login_totp", ImportField.TOTP)

        // 1Password
        put("title", ImportField.LABEL)
        put("otpauth", ImportField.TOTP)
        put("website", ImportField.URL)

        // LastPass
        put("extra", ImportField.NOTES)
        put("grouping", ImportField.NOTES)
        put("totp", ImportField.TOTP)

        // KeePass / KeePassXC
        put("account", ImportField.LABEL)
        put("loginname", ImportField.USERNAME)
        put("login_name", ImportField.USERNAME)
        put("web_site", ImportField.URL)
        put("comments", ImportField.NOTES)

        // Dashlane, NordPass, Proton Pass, Apple Passwords
        put("otpsecret", ImportField.TOTP)
        put("otp_secret", ImportField.TOTP)
        put("totpsecret", ImportField.TOTP)
        put("urls", ImportField.URL)
        put("user_name", ImportField.USERNAME)
        put("email", ImportField.USERNAME)
        put("cardnumber", ImportField.CARD_NUMBER)
        put("card_number", ImportField.CARD_NUMBER)
        put("ccnumber", ImportField.CARD_NUMBER)
    }

    /**
     * Substring fallbacks, in priority order.
     *
     * Kept small and deliberately unambitious. Each one earns its place by covering a real header
     * that the exact table cannot, and none of them may match a header the exact table already
     * claims for something else - `password` before `user`, because `password_username` exists in
     * the wild and the reverse order would put a username in the password column.
     */
    private val LOOSE: List<Pair<String, ImportField>> = listOf(
        "password" to ImportField.PASSWORD,
        "passwd" to ImportField.PASSWORD,
        "totp" to ImportField.TOTP,
        "otpauth" to ImportField.TOTP,
        // Ahead of "login", so `login_uri` reads as a URL even on the loose path. Belt and braces
        // with the claimed-column rule in map() - either one alone would be enough, and this is the
        // cheaper of the two to get wrong.
        "url" to ImportField.URL,
        "uri" to ImportField.URL,
        "domain" to ImportField.URL,
        "username" to ImportField.USERNAME,
        "userid" to ImportField.USERNAME,
        "login" to ImportField.USERNAME,
        "title" to ImportField.LABEL,
        "note" to ImportField.NOTES,
        "comment" to ImportField.NOTES
    )
}
