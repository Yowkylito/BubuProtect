package com.personal.bubuprotect.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real header rows, verbatim, from the products people are migrating away from.
 *
 * These are the acceptance criteria for the feature: if one of them maps wrongly, a user's whole
 * vault arrives with usernames in the password column and they find out one failed login at a time.
 */
class ColumnMapperTest {

    @Test
    fun `chrome, edge and brave`() {
        val mapping = map("name,url,username,password,note")
        assertEquals(0, mapping[ImportField.LABEL])
        assertEquals(1, mapping[ImportField.URL])
        assertEquals(2, mapping[ImportField.USERNAME])
        assertEquals(3, mapping[ImportField.PASSWORD])
        assertEquals(4, mapping[ImportField.NOTES])
    }

    @Test
    fun `bitwarden`() {
        val mapping = map(
            "folder,favorite,type,name,notes,fields,reprompt," +
                "login_uri,login_username,login_password,login_totp"
        )
        assertEquals(3, mapping[ImportField.LABEL])
        assertEquals(4, mapping[ImportField.NOTES])
        assertEquals(7, mapping[ImportField.URL])
        assertEquals(8, mapping[ImportField.USERNAME])
        assertEquals(9, mapping[ImportField.PASSWORD])
        assertEquals(10, mapping[ImportField.TOTP])
    }

    @Test
    fun `1password`() {
        val mapping = map("Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes")
        assertEquals(0, mapping[ImportField.LABEL])
        assertEquals(1, mapping[ImportField.URL])
        assertEquals(2, mapping[ImportField.USERNAME])
        assertEquals(3, mapping[ImportField.PASSWORD])
        assertEquals(4, mapping[ImportField.TOTP])
        assertEquals(8, mapping[ImportField.NOTES])
    }

    @Test
    fun `lastpass`() {
        val mapping = map("url,username,password,totp,extra,name,grouping,fav")
        assertEquals(0, mapping[ImportField.URL])
        assertEquals(1, mapping[ImportField.USERNAME])
        assertEquals(2, mapping[ImportField.PASSWORD])
        assertEquals(3, mapping[ImportField.TOTP])
        // `extra` is LastPass's notes field and comes before `grouping`, so it wins.
        assertEquals(4, mapping[ImportField.NOTES])
        assertEquals(5, mapping[ImportField.LABEL])
    }

    /** KeePass quotes every header; [CsvReader] has already stripped those by this point. */
    @Test
    fun `keepass`() {
        val mapping = ColumnMapper.map(
            listOf("Account", "Login Name", "Password", "Web Site", "Comments")
        )
        assertEquals(0, mapping[ImportField.LABEL])
        assertEquals(1, mapping[ImportField.USERNAME])
        assertEquals(2, mapping[ImportField.PASSWORD])
        assertEquals(3, mapping[ImportField.URL])
        assertEquals(4, mapping[ImportField.NOTES])
    }

    @Test
    fun `is case and separator insensitive`() {
        val mapping = ColumnMapper.map(listOf("User Name", "PASSWORD", "Web-Site"))
        assertEquals(0, mapping[ImportField.USERNAME])
        assertEquals(1, mapping[ImportField.PASSWORD])
        assertEquals(2, mapping[ImportField.URL])
    }

    /**
     * The bug this guards against: `login_uri` is claimed as the URL by the exact table and also
     * matches the `login` fragment, so a file with no username column would have filled usernames
     * with URLs.
     */
    @Test
    fun `a column claimed exactly is never re-claimed loosely`() {
        val mapping = ColumnMapper.map(listOf("login_uri", "password"))
        assertEquals(0, mapping[ImportField.URL])
        assertEquals(1, mapping[ImportField.PASSWORD])
        assertNull(mapping[ImportField.USERNAME])
    }

    /** An unknown product still imports as long as its headers are recognisable. */
    @Test
    fun `handles a format nobody has heard of`() {
        val mapping = ColumnMapper.map(
            listOf("Entry Title", "Site URI", "Account Login", "Secret Password", "Free Comment")
        )
        assertEquals(0, mapping[ImportField.LABEL])
        assertEquals(1, mapping[ImportField.URL])
        assertEquals(2, mapping[ImportField.USERNAME])
        assertEquals(3, mapping[ImportField.PASSWORD])
        assertEquals(4, mapping[ImportField.NOTES])
    }

    @Test
    fun `a file with no password column is not usable`() {
        assertFalse(ColumnMapper.isUsable(ColumnMapper.map(listOf("first", "last", "phone"))))
        assertTrue(ColumnMapper.isUsable(ColumnMapper.map(listOf("password"))))
    }

    private fun map(header: String) = ColumnMapper.map(header.split(","))
}
