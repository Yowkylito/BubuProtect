package com.personal.bubuprotect.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM sealed boxes, laid out as `iv || ciphertext || tag`.
 *
 * GCM rather than CBC because it authenticates: a flipped bit in the database file fails the tag
 * check instead of silently decrypting to garbage that the UI would happily present as a password.
 *
 * Every [seal] draws a fresh random IV. A 96-bit random IV under a single key stays comfortably
 * inside the GCM birthday bound for any realistic number of entries in a personal vault, and the
 * alternative - a counter - would need durable state we would have to keep in sync with the DB.
 */
object AesGcm {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    /**
     * @param aad additional authenticated data. Not encrypted, but the tag covers it, so
     *   decryption fails unless the caller supplies the identical value. Used to bind a
     *   ciphertext to the row and column it belongs to.
     */
    fun seal(key: SecretKeySpec, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = SecureBytes.randomBytes(IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            aad?.let(::updateAAD)
        }
        val sealed = cipher.doFinal(plaintext)
        return iv + sealed
    }

    /** @throws javax.crypto.AEADBadTagException if the key, the AAD, or the bytes are wrong. */
    fun open(key: SecretKeySpec, box: ByteArray, aad: ByteArray? = null): ByteArray {
        require(box.size > IV_LENGTH) { "Sealed box is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, box, 0, IV_LENGTH))
            aad?.let(::updateAAD)
        }
        return cipher.doFinal(box, IV_LENGTH, box.size - IV_LENGTH)
    }
}
