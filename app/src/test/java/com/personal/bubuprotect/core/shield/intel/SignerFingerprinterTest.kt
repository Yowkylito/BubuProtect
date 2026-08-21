package com.personal.bubuprotect.core.shield.intel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure half of [SignerFingerprinter] - digesting and normalising - which is the half a
 * blocklist mismatch would hide in.
 *
 * The `PackageManager` half is deliberately not faked here. A fake would assert that this code calls
 * the methods this code calls, which is worth nothing; the behaviour that actually matters there
 * (which flag an API level accepts, whether an OEM throws) only shows up on a device.
 */
class SignerFingerprinterTest {

    @Test
    fun `digest is uppercase hex with no separators`() {
        val digest = SignerFingerprinter.digestOf(byteArrayOf(1, 2, 3))

        assertEquals(64, digest.length)
        assertEquals(digest.uppercase(), digest)
        assertEquals(emptyList<Char>(), digest.filterNot { it in '0'..'9' || it in 'A'..'F' }.toList())
    }

    @Test
    fun `digest of the empty certificate is the known SHA-256 of no bytes`() {
        // Pinned against the published SHA-256 of the empty input, so a swap to a different digest
        // algorithm fails here rather than silently producing a blocklist that matches nothing.
        assertEquals(
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            SignerFingerprinter.digestOf(ByteArray(0))
        )
    }

    @Test
    fun `different certificates digest differently`() {
        assertNotEquals(
            SignerFingerprinter.digestOf(byteArrayOf(1)),
            SignerFingerprinter.digestOf(byteArrayOf(2))
        )
    }

    @Test
    fun `normalize accepts the shapes public sources publish hashes in`() {
        val expected = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"

        val colonSeparatedLowercase = expected.lowercase().chunked(2).joinToString(":")
        val spaceSeparatedUppercase = expected.chunked(2).joinToString(" ")

        assertEquals(expected, SignerFingerprinter.normalize(colonSeparatedLowercase))
        assertEquals(expected, SignerFingerprinter.normalize(spaceSeparatedUppercase))
        assertEquals(expected, SignerFingerprinter.normalize(expected))
    }

    @Test
    fun `normalize round-trips whatever digestOf produced`() {
        val digest = SignerFingerprinter.digestOf(byteArrayOf(9, 9, 9))

        assertEquals(digest, SignerFingerprinter.normalize(digest))
    }

    @Test
    fun `normalize rejects malformed entries rather than returning something unmatchable`() {
        // Each of these would otherwise load as a blocklist entry that can never hit, which reads as
        // "not blocklisted" for every app it was supposed to catch.
        assertNull(SignerFingerprinter.normalize(""))
        assertNull(SignerFingerprinter.normalize("deadbeef"))
        assertNull(SignerFingerprinter.normalize("Z".repeat(64)))
        assertNull(SignerFingerprinter.normalize("0".repeat(63)))
        assertNull(SignerFingerprinter.normalize("0".repeat(65)))
    }
}
