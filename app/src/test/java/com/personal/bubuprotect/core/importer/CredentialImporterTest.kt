package com.personal.bubuprotect.core.importer

import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.TOTP_EXTRA_KEY
import com.personal.bubuprotect.domain.model.VaultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialImporterTest {

    @Test
    fun `imports a chrome export`() {
        val preview = CredentialImporter.preview(
            """
            name,url,username,password,note
            GitHub,https://github.com/login,me@example.com,hunter2,work account
            Reddit,https://reddit.com,bubu,s3cret,
            """.trimIndent(),
            existing = emptyList()
        )

        assertEquals(2, preview.importable)
        assertEquals(0, preview.duplicates)
        assertEquals(0, preview.unusable)

        val github = preview.drafts.first()
        assertEquals("GitHub", github.label)
        assertEquals("me@example.com", github.identity)
        assertEquals("hunter2", github.secret)
        assertEquals("https://github.com/login", github.website)
        assertEquals("work account", github.notes)
        assertEquals(ItemKind.LOGIN, github.kind)

        // An empty note column must not become an empty-but-present note.
        assertNull(preview.drafts[1].notes)
    }

    /** Re-importing the same file is the single most likely way a user doubles their vault. */
    @Test
    fun `skips entries already in the vault`() {
        val csv = """
            name,url,username,password
            GitHub,github.com,me@example.com,hunter2
            Reddit,reddit.com,bubu,s3cret
        """.trimIndent()

        val existing = listOf(
            VaultItem(
                id = "1",
                kind = ItemKind.LOGIN,
                label = "GitHub",
                subtitle = "me@example.com",
                website = "github.com"
            )
        )

        val preview = CredentialImporter.preview(csv, existing)
        assertEquals(1, preview.importable)
        assertEquals(1, preview.duplicates)
        assertEquals("Reddit", preview.drafts.single().label)
    }

    @Test
    fun `duplicate detection ignores case and a differing url`() {
        val existing = listOf(
            VaultItem(
                id = "1",
                kind = ItemKind.LOGIN,
                label = "github",
                subtitle = "ME@example.com",
                // Stored bare; the export has the full login URL. The same account either way.
                website = "github.com"
            )
        )
        val preview = CredentialImporter.preview(
            "name,url,username,password\nGitHub,https://github.com/session,me@example.com,x",
            existing
        )
        assertEquals(0, preview.importable)
        assertEquals(1, preview.duplicates)
    }

    @Test
    fun `also dedupes within one file`() {
        val preview = CredentialImporter.preview(
            """
            name,username,password
            GitHub,me,one
            GitHub,me,two
            """.trimIndent(),
            existing = emptyList()
        )
        assertEquals(1, preview.importable)
        assertEquals(1, preview.duplicates)
    }

    /**
     * A seed lands in the 2FA field, so an imported login generates codes straight away - and the
     * user's own notes are left exactly as they wrote them.
     */
    @Test
    fun `puts a 2FA seed in its own field and reports it`() {
        val preview = CredentialImporter.preview(
            """
            name,username,password,totp,extra
            GitHub,me,hunter2,otpauth://totp/GitHub?secret=JBSWY3DPEHPK3PXP,my note
            """.trimIndent(),
            existing = emptyList()
        )

        assertEquals(1, preview.withTotp)
        val draft = preview.drafts.single()
        assertEquals("my note", draft.notes)
        assertEquals(
            "otpauth://totp/GitHub?secret=JBSWY3DPEHPK3PXP",
            draft.extras[TOTP_EXTRA_KEY]
        )
    }

    @Test
    fun `leaves the 2FA field absent when the export had no seed`() {
        val preview = CredentialImporter.preview(
            "name,username,password,totp\nGitHub,me,hunter2,",
            existing = emptyList()
        )
        assertEquals(0, preview.withTotp)
        assertNull(preview.drafts.single().extras[TOTP_EXTRA_KEY])
    }

    @Test
    fun `counts rows with no password rather than importing them`() {
        val preview = CredentialImporter.preview(
            """
            name,username,password
            Good,me,hunter2
            Empty,me,
            """.trimIndent(),
            existing = emptyList()
        )
        assertEquals(1, preview.importable)
        assertEquals(1, preview.unusable)
    }

    @Test
    fun `counts card rows separately and does not import them`() {
        val preview = CredentialImporter.preview(
            """
            name,username,password,card_number
            Login,me,hunter2,
            My Visa,,,4242424242424242
            """.trimIndent(),
            existing = emptyList()
        )
        assertEquals(1, preview.importable)
        assertEquals(1, preview.cards)
        assertEquals(0, preview.unusable)
    }

    @Test
    fun `survives short rows`() {
        val preview = CredentialImporter.preview(
            "name,url,username,password\nOnly,two",
            existing = emptyList()
        )
        assertEquals(0, preview.importable)
        assertEquals(1, preview.unusable)
    }

    /** A bare `url,username,password` export is common and should still produce readable names. */
    @Test
    fun `names an entry from its url when there is no title column`() {
        val preview = CredentialImporter.preview(
            "url,username,password\nhttps://www.accounts.google.com/signin,me,x",
            existing = emptyList()
        )
        assertEquals("accounts.google.com", preview.drafts.single().label)
    }

    @Test
    fun `falls back to the username, then to a placeholder`() {
        val fromUsername = CredentialImporter.preview(
            "username,password\nsolo@example.com,x",
            existing = emptyList()
        )
        assertEquals("solo@example.com", fromUsername.drafts.single().label)

        val placeholder = CredentialImporter.preview(
            "password\nx",
            existing = emptyList()
        )
        assertEquals("Imported entry", placeholder.drafts.single().label)
    }

    @Test
    fun `refuses a file with no password column`() {
        val failure = assertThrows(UnreadableImportException::class.java) {
            CredentialImporter.preview(
                "first,last,phone\nBubu,Bear,555",
                existing = emptyList()
            )
        }
        assertTrue(failure.message.orEmpty().contains("password column"))
    }

    @Test
    fun `refuses a file with only a header`() {
        assertThrows(UnreadableImportException::class.java) {
            CredentialImporter.preview("name,url,username,password", existing = emptyList())
        }
    }

    @Test
    fun `handles quoted fields with commas and newlines end to end`() {
        val preview = CredentialImporter.preview(
            "name,username,password,note\n" +
                "\"Bank, First\",me,\"pa,ss\",\"line one\nline two\"",
            existing = emptyList()
        )
        val draft = preview.drafts.single()
        assertEquals("Bank, First", draft.label)
        assertEquals("pa,ss", draft.secret)
        assertEquals("line one\nline two", draft.notes)
    }
}
