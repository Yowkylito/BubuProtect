package com.personal.bubuprotect.core.otp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The hash a seed was issued for. Almost always [SHA1], and that is not a weakness here. */
enum class OtpAlgorithm(val macName: String, val uriName: String) {
    /**
     * The default, and correct despite SHA-1's reputation.
     *
     * HMAC-SHA1 is unaffected by the collision attacks that retired SHA-1 for signatures: HMAC
     * depends on the hash being a good PRF, not on it being collision-resistant, and no attack on
     * HMAC-SHA1 exists. Refusing it would break compatibility with essentially every issuer on the
     * internet in exchange for nothing.
     */
    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512");

    companion object {
        fun fromUri(name: String?): OtpAlgorithm? =
            entries.firstOrNull { it.uriName.equals(name?.trim(), ignoreCase = true) }
    }
}

/**
 * TOTP, per RFC 6238.
 *
 * Pure, and verified against the specification's own Appendix B test vectors for all three hash
 * algorithms rather than against my reasoning about them. That matters more here than almost anywhere
 * else in this app: a generator that is subtly wrong produces codes that are *rejected*, the user
 * cannot tell whether the fault is the app or the server, and the only visible symptom is being
 * locked out of an account.
 *
 * ### The clock is wall time, and that is the opposite of the vault's choice
 *
 * [com.personal.bubuprotect.session.VaultSession] deliberately measures its auto-lock with
 * `SystemClock.elapsedRealtime()`, because that is monotonic and cannot be moved by changing the
 * device time to dodge the timeout.
 *
 * TOTP needs precisely what that avoids. The server computes its window from real time, so the code
 * has to be derived from the same absolute clock - `System.currentTimeMillis()` - even though the
 * user can change it. A device whose clock is a minute out will produce rejected codes, which is why
 * the UI reports when automatic time is switched off rather than letting that fail mysteriously.
 */
internal object TotpGenerator {

    const val DEFAULT_PERIOD_SECONDS = 30
    const val DEFAULT_DIGITS = 6

    /** RFC 4226 fixes the range; anything outside it is a malformed or hostile issuer. */
    val DIGIT_RANGE = 6..8

    /**
     * A sane band for the step size.
     *
     * 30 seconds is near-universal and 60 appears occasionally. The lower bound stops a crafted URI
     * asking for a one-second period - which would spin the countdown pointlessly - and the upper
     * bound keeps a code from appearing valid for an hour.
     */
    val PERIOD_RANGE = 5..300

    /**
     * @param secret the raw seed bytes.
     * @param timeMillis wall-clock time. Injected rather than read here so the RFC vectors can be
     *   replayed exactly and so the UI can render a code for the window it is counting down.
     * @return the code, zero-padded to [digits].
     */
    fun code(
        secret: ByteArray,
        timeMillis: Long,
        period: Int = DEFAULT_PERIOD_SECONDS,
        digits: Int = DEFAULT_DIGITS,
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1
    ): String {
        require(period in PERIOD_RANGE) { "Unsupported period: $period" }
        require(digits in DIGIT_RANGE) { "Unsupported digit count: $digits" }
        require(secret.isNotEmpty()) { "Empty secret" }

        val counter = counterAt(timeMillis, period)
        val mac = Mac.getInstance(algorithm.macName).apply {
            init(SecretKeySpec(secret, algorithm.macName))
        }
        val hash = mac.doFinal(counter.toBigEndianBytes())

        /*
         * RFC 4226 dynamic truncation.
         *
         * The low nibble of the last byte picks where in the hash to read four bytes from, and the
         * top bit of those is masked off so the result is positive on platforms with signed
         * integers. Both details are load-bearing: taking the first four bytes instead, or forgetting
         * the mask, yields codes that look plausible and are wrong.
         */
        val offset = (hash[hash.size - 1].toInt() and 0x0f)
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)

        val modulus = POWERS_OF_TEN[digits]
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /** How long the current code stays valid. Drives the countdown ring. */
    fun secondsRemaining(timeMillis: Long, period: Int = DEFAULT_PERIOD_SECONDS): Int {
        require(period in PERIOD_RANGE) { "Unsupported period: $period" }
        val seconds = Math.floorDiv(timeMillis, 1000L)
        return (period - Math.floorMod(seconds, period.toLong())).toInt()
    }

    /**
     * `floorDiv`, not `/`.
     *
     * Integer division truncates toward zero, so a negative timestamp - a device with its clock set
     * before 1970, which happens on a dead battery - would land in the wrong window rather than one
     * step earlier. It costs nothing to be right about.
     */
    private fun counterAt(timeMillis: Long, period: Int): Long =
        Math.floorDiv(Math.floorDiv(timeMillis, 1000L), period.toLong())

    private fun Long.toBigEndianBytes(): ByteArray = ByteArray(8) { index ->
        (this ushr (56 - index * 8)).toByte()
    }

    private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
}
