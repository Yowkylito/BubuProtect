package com.personal.bubuprotect.core.otp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Base32Test {

    /** RFC 4648 test vectors, so the decoder is checked against the spec rather than itself. */
    @Test
    fun `matches the RFC 4648 vectors`() {
        assertArrayEquals("f".toByteArray(), Base32.decode("MY======"))
        assertArrayEquals("fo".toByteArray(), Base32.decode("MZXQ===="))
        assertArrayEquals("foo".toByteArray(), Base32.decode("MZXW6==="))
        assertArrayEquals("foob".toByteArray(), Base32.decode("MZXW6YQ="))
        assertArrayEquals("fooba".toByteArray(), Base32.decode("MZXW6YTB"))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
    }

    /** The well-known example seed, as printed by issuers in groups of four. */
    @Test
    fun `tolerates how secrets are actually presented`() {
        val expected = Base32.decode("JBSWY3DPEHPK3PXP")
        assertNotNull(expected)
        assertArrayEquals(expected, Base32.decode("jbswy3dpehpk3pxp"))
        assertArrayEquals(expected, Base32.decode("JBSW Y3DP EHPK 3PXP"))
        assertArrayEquals(expected, Base32.decode("JBSW-Y3DP-EHPK-3PXP"))
        assertArrayEquals(expected, Base32.decode("  JBSWY3DPEHPK3PXP  "))
    }

    /**
     * The opposite choice from the recovery code, and deliberately so. `0`, `1`, `8` and `9` are not
     * in this alphabet, and folding them would produce a *different secret* that generates wrong
     * codes forever - whereas a recovery code is transcribed by hand and needs forgiving.
     */
    @Test
    fun `rejects characters outside the alphabet instead of folding them`() {
        assertNull(Base32.decode("JBSWY3DPEHPK3PX0"))
        assertNull(Base32.decode("JBSWY3DPEHPK3PX1"))
        assertNull(Base32.decode("JBSWY3DPEHPK3PX9"))
        assertNull(Base32.decode("hello!"))
    }

    @Test
    fun `rejects empty and oversized input`() {
        assertNull(Base32.decode(""))
        assertNull(Base32.decode("   "))
        assertNull(Base32.decode("A".repeat(600)))
    }

    @Test
    fun `normalises to the canonical form`() {
        assertEquals("JBSWY3DP", Base32.normalize("jbsw y3dp"))
        assertEquals("MZXW6", Base32.normalize("MZXW6==="))
        assertNull(Base32.normalize(""))
    }

    @Test
    fun `validity agrees with the decoder`() {
        assertTrue(Base32.isValid("JBSWY3DPEHPK3PXP"))
        assertFalse(Base32.isValid("JBSWY3DPEHPK3PX0"))
    }
}

class OtpAuthUriTest {

    @Test
    fun `reads a typical issuer URI`() {
        val secret = OtpAuthUri.parse(
            "otpauth://totp/GitHub:me@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        )
        assertNotNull(secret)
        secret!!
        assertEquals("JBSWY3DPEHPK3PXP", secret.base32)
        assertEquals("GitHub", secret.issuer)
        assertEquals("me@example.com", secret.account)
        assertEquals(6, secret.digits)
        assertEquals(30, secret.periodSeconds)
        assertEquals(OtpAlgorithm.SHA1, secret.algorithm)
    }

    @Test
    fun `honours non-default parameters`() {
        val secret = OtpAuthUri.parse(
            "otpauth://totp/Bank?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=8&period=60"
        )
        assertNotNull(secret)
        assertEquals(8, secret!!.digits)
        assertEquals(60, secret.periodSeconds)
        assertEquals(OtpAlgorithm.SHA256, secret.algorithm)
    }

    @Test
    fun `decodes a percent-encoded label`() {
        val secret = OtpAuthUri.parse(
            "otpauth://totp/ACME%20Co%3Ajohn.doe%40email.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co"
        )
        assertNotNull(secret)
        assertEquals("ACME Co", secret!!.issuer)
        assertEquals("john.doe@email.com", secret.account)
    }

    /** A bare secret is what someone types when the site hides its QR behind "can't scan?". */
    @Test
    fun `accepts a bare secret with the defaults`() {
        val secret = OtpAuthUri.parse("jbsw y3dp ehpk 3pxp")
        assertNotNull(secret)
        assertEquals("JBSWY3DPEHPK3PXP", secret!!.base32)
        assertEquals(6, secret.digits)
        assertNull(secret.issuer)
    }

    // --- Refusals -------------------------------------------------------------------------------

    /**
     * These all come from a QR code, which is input the user did not write. Refusing beats clamping:
     * a seed whose parameters we had to alter would generate codes the server rejects, so failing at
     * enrollment is far kinder than failing at every login afterwards.
     */
    @Test
    fun `refuses out-of-range parameters rather than clamping them`() {
        assertNull(OtpAuthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=2"))
        assertNull(OtpAuthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=100"))
        assertNull(OtpAuthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&period=0"))
        assertNull(OtpAuthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&period=99999"))
        assertNull(OtpAuthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&digits=abc"))
    }

    @Test
    fun `refuses an unknown algorithm instead of defaulting to SHA1`() {
        assertNull(OtpAuthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP&algorithm=SHA3-512"))
    }

    @Test
    fun `refuses counter-based HOTP`() {
        assertNull(OtpAuthUri.parse("otpauth://hotp/a?secret=JBSWY3DPEHPK3PXP&counter=1"))
    }

    @Test
    fun `refuses a URI with no usable secret`() {
        assertNull(OtpAuthUri.parse("otpauth://totp/a?issuer=GitHub"))
        assertNull(OtpAuthUri.parse("otpauth://totp/a?secret=not-base32!"))
        assertNull(OtpAuthUri.parse(""))
        assertNull(OtpAuthUri.parse("https://example.com"))
    }

    // --- Round trip -----------------------------------------------------------------------------

    /** The stored form has to survive being read back, or parameters silently revert to defaults. */
    @Test
    fun `round trips through the stored URI`() {
        val original = OtpSecret(
            base32 = "JBSWY3DPEHPK3PXP",
            digits = 8,
            periodSeconds = 60,
            algorithm = OtpAlgorithm.SHA512,
            issuer = "ACME Co",
            account = "john.doe@email.com"
        )

        val reparsed = OtpAuthUri.parse(original.toUri())
        assertEquals(original, reparsed)
    }

    @Test
    fun `round trips a bare secret`() {
        val original = OtpSecret(base32 = "JBSWY3DPEHPK3PXP")
        assertEquals(original, OtpAuthUri.parse(original.toUri()))
    }

    @Test
    fun `generates a code from a parsed secret`() {
        val secret = OtpAuthUri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP")!!
        val bytes = secret.secretBytes()
        assertNotNull(bytes)
        val code = TotpGenerator.code(bytes!!, 59_000L, secret.periodSeconds, secret.digits, secret.algorithm)
        assertEquals(6, code.length)
    }

    @Test
    fun `names itself for display`() {
        assertEquals(
            "GitHub (me@example.com)",
            OtpSecret("JBSWY3DPEHPK3PXP", issuer = "GitHub", account = "me@example.com").displayName
        )
        assertEquals("GitHub", OtpSecret("JBSWY3DPEHPK3PXP", issuer = "GitHub").displayName)
        assertEquals("One-time code", OtpSecret("JBSWY3DPEHPK3PXP").displayName)
    }
}
