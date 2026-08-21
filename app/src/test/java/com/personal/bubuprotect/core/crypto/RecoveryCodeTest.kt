package com.personal.bubuprotect.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery code is the last thing standing between a forgotten passphrase and permanent loss of
 * every secret in the vault, and it is transcribed by hand off paper. So the encoding has to be an
 * exact round trip, and it has to forgive the mistakes people actually make when copying 24
 * characters.
 */
class RecoveryCodeTest {

    @Test
    fun `generates the documented shape`() {
        RecoveryCode.generate().use { code ->
            val formatted = code.formatted()
            // BP1 + six groups of four, separated by dashes.
            assertTrue(formatted, Regex("^BP1(-[0-9A-HJKMNP-TV-Z]{4}){6}$").matches(formatted))
            assertEquals(24, RecoveryCode.CODE_LENGTH)
        }
    }

    @Test
    fun `round trips exactly`() {
        RecoveryCode.generate().use { original ->
            val parsed = RecoveryCode.parse(original.formatted())
            assertTrue(parsed != null)
            parsed!!.use {
                assertArrayEquals(original.secret().use(), it.secret().use())
            }
        }
    }

    @Test
    fun `two codes differ`() {
        RecoveryCode.generate().use { first ->
            RecoveryCode.generate().use { second ->
                assertNotEquals(first.formatted(), second.formatted())
            }
        }
    }

    /** Every one of these is a way someone might realistically type the same code back. */
    @Test
    fun `accepts the code however it is typed`() {
        RecoveryCode.generate().use { original ->
            val canonical = original.formatted()
            val payload = canonical.removePrefix("BP1-")

            val variants = listOf(
                canonical,
                canonical.lowercase(),
                canonical.replace("-", ""),
                canonical.replace("-", " "),
                payload,
                payload.replace("-", ""),
                "  $canonical  ",
                canonical.replace("-", "\n")
            )

            variants.forEach { variant ->
                val parsed = RecoveryCode.parse(variant)
                assertTrue("failed to parse: $variant", parsed != null)
                parsed!!.use {
                    assertArrayEquals(variant, original.secret().use(), it.secret().use())
                }
            }
        }
    }

    /**
     * The whole reason for Crockford's alphabet. Someone reading a printed `0` as `O`, or a `1` as
     * `I` or `l`, must still get into their vault.
     */
    @Test
    fun `folds the confusable characters`() {
        // A canonical code built from characters that exercise both foldings.
        val canonical = "BP1-0000-0000-0000-0000-0000-1111"
        val mistyped = "bp1-OOOO-oooo-0000-OOOO-0000-IlIl"

        val a = RecoveryCode.parse(canonical)
        val b = RecoveryCode.parse(mistyped)
        assertTrue(a != null && b != null)
        a!!.use { first -> b!!.use { second ->
            assertArrayEquals(first.secret().use(), second.secret().use())
        } }
    }

    /**
     * `B`, `P` and `1` are all payload characters, so roughly one code in 32,768 has a payload that
     * itself begins with `BP1`. Those codes have to work whether or not the user types the prefix -
     * an unconditional prefix strip would silently eat three real characters and the kit would just
     * refuse to open the vault.
     */
    @Test
    fun `handles a payload that begins with the prefix`() {
        val payload = "BP1" + "0".repeat(RecoveryCode.CODE_LENGTH - 3)
        val withPrefix = "BP1-" + payload.chunked(4).joinToString("-")

        val bare = RecoveryCode.parse(payload)
        val prefixed = RecoveryCode.parse(withPrefix)

        assertTrue("bare payload did not parse", bare != null)
        assertTrue("prefixed form did not parse", prefixed != null)
        bare!!.use { a -> prefixed!!.use { b ->
            assertArrayEquals(a.secret().use(), b.secret().use())
        } }
    }

    @Test
    fun `rejects the wrong length`() {
        assertNull(RecoveryCode.parse(""))
        assertNull(RecoveryCode.parse("BP1-0000"))
        assertNull(RecoveryCode.parse("BP1-0000-0000-0000-0000-0000-000"))
        assertNull(RecoveryCode.parse("BP1-0000-0000-0000-0000-0000-00000"))
    }

    /**
     * `U` is not in the alphabet, so it is stripped rather than folded - which shortens the payload
     * and the length check catches it. That is the intended outcome: a code containing `U` was never
     * produced by this app.
     */
    @Test
    fun `rejects characters outside the alphabet`() {
        assertNull(RecoveryCode.parse("BP1-UUUU-0000-0000-0000-0000-0000"))
        assertNull(RecoveryCode.parse("BP1-!!!!-0000-0000-0000-0000-0000"))
    }

    @Test
    fun `well-formed check matches the parser`() {
        RecoveryCode.generate().use { code ->
            assertTrue(RecoveryCode.looksWellFormed(code.formatted()))
        }
        assertFalse(RecoveryCode.looksWellFormed("BP1-0000"))
        assertFalse(RecoveryCode.looksWellFormed(""))
    }

    @Test
    fun `closing wipes the material`() {
        val code = RecoveryCode.generate()
        val material = code.secret()
        code.close()
        assertTrue(material.isDestroyed)
    }

    /**
     * 120 bits, no padding. A padded encoding would have trailing bits that decode from more than one
     * string, so two different typings of "the same" code could both parse and only one would work -
     * an ambiguity a recovery path cannot afford.
     */
    @Test
    fun `the encoding is an exact fit`() {
        assertEquals(15, RecoveryCode.MATERIAL_BYTES)
        assertEquals(0, RecoveryCode.MATERIAL_BYTES * 8 % 5)
    }
}
