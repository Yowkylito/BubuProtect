package com.personal.bubuprotect.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import timber.log.Timber
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
 *
 * ### Two instances, two purposes
 *
 * [alias] is a parameter because the app needs two of these keys with identical properties and
 * independent lifetimes. The default wraps the root key for biometric unlock. The second, under
 * [ALIAS_RECOVERY_GUARD], guards the recovery screen - and there the self-destruction on re-enrollment
 * stops being a limitation and becomes the entire point. See
 * [VaultKeyManager.beginRecoveryGuardCheck].
 */
class KeystoreKek(private val alias: String = ALIAS_VAULT) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun exists(): Boolean = runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)

    fun delete() = runCatching { keyStore.deleteEntry(alias) }.getOrElse {
        Timber.tag(TAG).w("Could not delete key %s: %s", alias, it.javaClass.simpleName)
    }

    /**
     * Creates the KEK, replacing any existing one.
     *
     * @throws java.security.InvalidAlgorithmParameterException on devices with no enrolled
     *   biometric - the caller must check enrollment first and stay on the passphrase path.
     */
    fun create() {
        delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            generateStrongBoxOrFallback()
        } else {
            generate(strongBox = false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateStrongBoxOrFallback() {
        try {
            generate(strongBox = true)
        } catch (unavailable: StrongBoxUnavailableException) {
            Timber.tag(TAG).i("StrongBox unavailable, falling back to TEE-backed key")
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(
            alias,
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
        keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Keystore key $alias is missing")

    companion object {
        private const val TAG = "KeystoreKek"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Wraps the root key for biometric unlock. */
        const val ALIAS_VAULT = "bubu.vault.kek.v1"

        /**
         * Guards the recovery screen.
         *
         * Deliberately a *separate* key from [ALIAS_VAULT], not a reuse of it. The vault wrapper is
         * optional - a user who never turns on fingerprint unlock does not have one - and the recovery
         * gate has to work regardless of that choice. Separate aliases also mean disabling biometric
         * unlock cannot silently disarm the recovery gate.
         */
        const val ALIAS_RECOVERY_GUARD = "bubu.recovery.guard.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
