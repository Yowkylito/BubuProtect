package com.personal.bubuprotect.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869), used to split the one root secret into purpose-bound subkeys.
 *
 * Why derive instead of reusing the root key everywhere: the SQLCipher passphrase is handed to a
 * native library and the field key is used by the JCE. Deriving them independently means a
 * weakness or a leak in one consumer does not hand the attacker the other key, and the `info`
 * label lets us rotate one subkey's scheme later without touching the other.
 */
object Hkdf {

    private const val HMAC = "HmacSHA256"
    private const val HASH_LENGTH = 32

    fun derive(ikm: ByteArray, salt: ByteArray, info: String, length: Int = HASH_LENGTH): SecureBytes {
        require(length in 1..(255 * HASH_LENGTH)) { "Unsupported output length: $length" }
        val prk = extract(ikm, salt)
        return try {
            SecureBytes.adopt(expand(prk, info.toByteArray(Charsets.UTF_8), length))
        } finally {
            prk.wipe()
        }
    }

    private fun extract(ikm: ByteArray, salt: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(salt, HMAC))
            doFinal(ikm)
        }

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
        val output = ByteArray(length)
        var previousBlock = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.reset()
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())
            previousBlock = mac.doFinal()
            val take = minOf(previousBlock.size, length - written)
            previousBlock.copyInto(output, written, 0, take)
            written += take
            counter++
        }
        previousBlock.wipe()
        return output
    }
}
