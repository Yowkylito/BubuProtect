package com.personal.bubuprotect.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is one a naive comma-split gets wrong, and getting it wrong does not throw - it
 * shifts every later column, so an entry ends up with half a note as its password. For a one-shot
 * migration nobody re-checks, quietly wrong is the worst outcome available.
 */
class CsvReaderTest {

    @Test
    fun `reads a plain file`() {
        val rows = CsvReader.parse("name,url,username,password\nGitHub,github.com,me,hunter2")
        assertEquals(2, rows.size)
        assertEquals(listOf("name", "url", "username", "password"), rows[0])
        assertEquals(listOf("GitHub", "github.com", "me", "hunter2"), rows[1])
    }

    @Test
    fun `keeps commas inside quoted fields`() {
        val rows = CsvReader.parse("""a,b,c
"one,two",three,"four,five"""")
        assertEquals(listOf("one,two", "three", "four,five"), rows[1])
    }

    /** A password of `he said ""hi""` is a real password, and it must survive intact. */
    @Test
    fun `unescapes doubled quotes`() {
        val rows = CsvReader.parse("password\n\"he said \"\"hi\"\"\"")
        assertEquals(listOf("""he said "hi""""), rows[1])
    }

    @Test
    fun `keeps newlines inside quoted fields`() {
        val rows = CsvReader.parse("name,notes\nBank,\"line one\nline two\"")
        assertEquals(2, rows.size)
        assertEquals("line one\nline two", rows[1][1])
    }

    @Test
    fun `handles CRLF without producing empty rows`() {
        val rows = CsvReader.parse("a,b\r\n1,2\r\n3,4\r\n")
        assertEquals(3, rows.size)
        assertEquals(listOf("3", "4"), rows[2])
    }

    /** Excel and several exporters prepend one. Left in, it corrupts the first header name. */
    @Test
    fun `strips a byte order mark`() {
        val rows = CsvReader.parse("﻿name,password\nGitHub,hunter2")
        assertEquals("name", rows[0][0])
    }

    @Test
    fun `keeps empty fields in place`() {
        val rows = CsvReader.parse("a,b,c\n1,,3")
        assertEquals(listOf("1", "", "3"), rows[1])
    }

    @Test
    fun `drops blank lines but not blank fields`() {
        val rows = CsvReader.parse("a,b\n\n1,2\n\n")
        assertEquals(2, rows.size)
    }

    @Test
    fun `treats a mid-field quote as a literal`() {
        // Some tools emit unquoted fields containing quotes. Better a stray character than a
        // swallowed field boundary.
        val rows = CsvReader.parse("a\n5\" nail")
        assertEquals("""5" nail""", rows[1][0])
    }

    @Test
    fun `returns nothing for an empty file`() {
        assertTrue(CsvReader.parse("").isEmpty())
    }

    @Test
    fun `does not pad short rows`() {
        val rows = CsvReader.parse("a,b,c\n1,2")
        assertEquals(2, rows[1].size)
    }
}
