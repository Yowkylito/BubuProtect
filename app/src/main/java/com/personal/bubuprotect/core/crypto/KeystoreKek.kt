package com.personal.bubuprotect.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The hardware-backed key-encryption key. It never leaves the TEE (or StrongBox, when the device
 * has one): this class can only ask the Keystore to run a cipher on its behalf, so the KEK cannot
 * be read out of the process, out of a heap dump, or off a stolen flash chip.
 *
 * Three properties do the real work here:
 *
 *  - **per-use authentication** (validity duration 0/-1) - the KEK is unusable unless a
 *    [javax.crypto.Cipher] bound to it was carried through a successful biometric prompt. Holding
 *    an unlocked device is not enough.
 *  - **invalidate on biometric enrollment** - enrolling a new fingerprint destroys the key. An
 *    attacker who can force a device unlock still cannot add their own finger and walk in.
 *  - **unlocked device required** - no use while the lockscreen is up, which kills the
 *    "decrypt in the background" angle.
 *
 * Because the key is destroyed on re-enrollment, this wrapper is only ever the *convenience* path.
 * [PassphraseKdf] provides the recovery path that survives it.
 */
class KeystoreKek {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun exists(): Boolean = runCatching { keyStore.containsAlias(ALIAS) }.getOrDefault(false)

    fun delete() = runCatching { keyStore.deleteEntry(ALIAS) }.getOrElse {
        Log.w(TAG, "Could not delete KEK: ${it.javaClass.simpleName}")
    }

    /**
     * Creates the KEK, replacing any existing one.
     *
     * @throws java.security.InvalidAlgorithmParameterException on devices with no enrolled
     *   biometric - the caller must check enrollment first and stay on the passphrase path.
     */
    fun create() {
        delete()
        try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (unavailable: StrongBoxUnavailableException) {
            Log.i(TAG, "StrongBox unavailable, falling back to TEE-backed key")
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // 0s validity = authenticate for every single use, strong biometrics only.
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(true)
                    setIsStrongBoxBacked(strongBox)
                }
            }
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    /**
     * A cipher ready to wrap the root key. Must be carried through [BiometricPrompt] before
     * `doFinal` - the Keystore refuses the operation otherwise.
     */
    fun encryptCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, requireKey())
    }

    /** @throws KeyPermanentlyInvalidatedException if biometrics were re-enrolled. */
    fun decryptCipher(iv: ByteArray): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(TAG_BITS, iv))
    }

    private fun requireKey(): SecretKey =
        keyStore.getKey(ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Vault KEK is missing from the Android Keystore")

    companion object {
        private const val TAG = "KeystoreKek"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "bubu.vault.kek.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
