package com.personal.bubuprotect.core.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RFC 6238 Appendix B, verbatim.
 *
 * These are the specification's own vectors, which makes them the acceptance criteria for the whole
 * feature rather than a check on my arithmetic. It matters more here than in most places: a generator
 * that is subtly wrong produces codes the *server* rejects, the user cannot tell whether the fault is
 * this app or the site, and the visible symptom is being locked out of an account.
 *
 * The seeds are the ASCII strings the RFC specifies, repeated to the length each hash needs.
 */
class TotpGeneratorTest {

    private val sha1Seed = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    private val sha256Seed = "12345678901234567890123456789012".toByteArray(Charsets.US_ASCII)
    private val sha512Seed =
        "1234567890123456789012345678901234567890123456789012345678901234".toByteArray(Charsets.US_ASCII)

    /** Seconds -> expected 8-digit code, from the RFC's table. */
    private val sha1Vectors = mapOf(
        59L to "94287082",
        1111111109L to "07081804",
        1111111111L to "14050471",
        1234567890L to "89005924",
        2000000000L to "69279037",
        20000000000L to "65353130"
    )

    private val sha256Vectors = mapOf(
        59L to "46119246",
        1111111109L to "68084774",
        1111111111L to "67062674",
        1234567890L to "91819424",
        2000000000L to "90698825",
        20000000000L to "77737706"
    )

    private val sha512Vectors = mapOf(
        59L to "90693936",
        1111111109L to "25091201",
        1111111111L to "99943326",
        1234567890L to "93441116",
        2000000000L to "38618901",
        20000000000L to "47863826"
    )

    @Test
    fun `matches the RFC 6238 vectors for HMAC-SHA1`() = check(sha1Seed, OtpAlgorithm.SHA1, sha1Vectors)

    @Test
    fun `matches the RFC 6238 vectors for HMAC-SHA256`() =
        check(sha256Seed, OtpAlgorithm.SHA256, sha256Vectors)

    @Test
    fun `matches the RFC 6238 vectors for HMAC-SHA512`() =
        check(sha512Seed, OtpAlgorithm.SHA512, sha512Vectors)

    /** Six digits is what issuers actually use; it is the same value truncated further. */
    @Test
    fun `produces six digits by taking the low end of the same number`() {
        val eight = TotpGenerator.code(sha1Seed, 59_000L, digits = 8, algorithm = OtpAlgorithm.SHA1)
        val six = TotpGenerator.code(sha1Seed, 59_000L, digits = 6, algorithm = OtpAlgorithm.SHA1)
        assertEquals("94287082", eight)
        assertEquals(eight.takeLast(6), six)
    }

    @Test
    fun `pads a short code to the full width`() {
        // Whatever window produces a small number must still render as six characters; a five-digit
        // code is silently rejected by every server.
        val codes = (0L until 400L).map {
            TotpGenerator.code(sha1Seed, it * 30_000L, digits = 6)
        }
        assertEquals(emptyList<String>(), codes.filter { it.length != 6 })
    }

    @Test
    fun `the code is stable across one period and changes at the boundary`() {
        val insideStart = TotpGenerator.code(sha1Seed, 60_000L)
        val insideEnd = TotpGenerator.code(sha1Seed, 89_999L)
        val nextWindow = TotpGenerator.code(sha1Seed, 90_000L)

        assertEquals(insideStart, insideEnd)
        assert(insideStart != nextWindow)
    }

    @Test
    fun `counts down to the end of the window`() {
        assertEquals(30, TotpGenerator.secondsRemaining(60_000L))
        assertEquals(29, TotpGenerator.secondsRemaining(61_000L))
        assertEquals(1, TotpGenerator.secondsRemaining(89_000L))
        assertEquals(30, TotpGenerator.secondsRemaining(90_000L))
    }

    /** A phone with a dead battery can report a pre-1970 clock; that must not skip a window. */
    @Test
    fun `handles a negative timestamp without truncating toward zero`() {
        assertEquals(
            TotpGenerator.code(sha1Seed, -30_000L),
            TotpGenerator.code(sha1Seed, -1L)
        )
        assert(TotpGenerator.secondsRemaining(-1L) in 1..30)
    }

    @Test
    fun `refuses parameters outside the specification`() {
        assertThrows(IllegalArgumentException::class.java) {
            TotpGenerator.code(sha1Seed, 0L, digits = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TotpGenerator.code(sha1Seed, 0L, digits = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TotpGenerator.code(sha1Seed, 0L, period = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TotpGenerator.code(ByteArray(0), 0L)
        }
    }

    private fun check(seed: ByteArray, algorithm: OtpAlgorithm, vectors: Map<Long, String>) {
        vectors.forEach { (seconds, expected) ->
            assertEquals(
                "$algorithm at T=$seconds",
                expected,
                TotpGenerator.code(
                    secret = seed,
                    timeMillis = seconds * 1000L,
                    period = 30,
                    digits = 8,
                    algorithm = algorithm
                )
            )
        }
    }
}
