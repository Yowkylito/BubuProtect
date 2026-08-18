package com.personal.bubuprotect.core.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import com.personal.bubuprotect.data.local.VaultKeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The unlocked key set for one session. Closing it wipes every byte it owns. */
class VaultKeys(
    private val mek: SecureBytes,
    val databasePassphrase: SecureBytes,
    val fieldKey: SecretKeySpec
) : AutoCloseable {

    /** The root secret, needed only to add or re-wrap another unlock method. */
    internal fun rootKey(): SecureBytes = mek

    override fun close() {
        mek.destroy()
        databasePassphrase.destroy()
    }
}

class WrongPassphraseException : Exception("Master passphrase is incorrect")

/**
 * Raised when the hardware wrapper died because biometrics were re-enrolled (or the device was
 * factory-reset-protected). Not data loss: the passphrase wrapper still opens the vault, and a
 * fresh biometric wrapper is written on the next successful passphrase unlock.
 */
class BiometricKeyInvalidatedException : Exception("Biometric unlock key was invalidated")

/**
 * Envelope encryption for the vault.
 *
 * One 256-bit root key (the MEK) is generated once and never stored in the clear. It is wrapped
 * twice, independently:
 *
 * ```
 *   Keystore KEK  (TEE/StrongBox, per-use biometric)  ─┐
 *                                                      ├─▶  MEK  ──HKDF──┬─▶ SQLCipher passphrase
 *   PBKDF2-SHA512(master passphrase, salt, 210k)      ─┘                 └─▶ AES-GCM field key
 * ```
 *
 * Two wrappers rather than one because each covers the other's failure mode. The Keystore wrapper
 * is the stronger of the two - a non-exportable hardware key beats anything a human will memorise -
 * but it is destroyed by design when a fingerprint is enrolled, and a vault that erases itself when
 * the user adds a finger is a data-loss bug wearing a security hat. The passphrase wrapper is the
 * durable one, and it is what makes the vault resistant to an attacker who has both the device and
 * a way to unlock it.
 *
 * The MEK is deliberately *not* used directly for anything. Both consumers get an HKDF subkey, so
 * the native SQLCipher key and the JCE field key are independent of one another.
 */
class VaultKeyManager(
    private val keyStore: VaultKeyStore,
    private val kek: KeystoreKek
) {

    val isEnrolled: Boolean get() = keyStore.isEnrolled

    val isBiometricUnlockEnabled: Boolean
        get() = keyStore.hasBiometricWrapper && kek.exists()

    /**
     * First run: mints the root key and seals it under [passphrase].
     *
     * Blocking on the KDF - call from a background dispatcher.
     */
    fun enroll(passphrase: CharArray): VaultKeys {
        val mek = SecureBytes.random(KEY_LENGTH)
        val hkdfSalt = SecureBytes.randomBytes(HKDF_SALT_LENGTH)
        val passphraseSalt = PassphraseKdf.newSalt()
        val iterations = PassphraseKdf.DEFAULT_ITERATIONS

        PassphraseKdf.deriveKey(passphrase, passphraseSalt, iterations).use { wrappingKey ->
            val wrapped = AesGcm.seal(wrappingKey.toSecretKey(), mek.use(), aad = PASSPHRASE_AAD)
            keyStore.hkdfSalt = hkdfSalt
            keyStore.writePassphraseWrapper(passphraseSalt, iterations, wrapped)
        }
        return deriveSessionKeys(mek, hkdfSalt)
    }

    /** The recovery path, and the fallback whenever the hardware wrapper is unavailable. */
    fun unlockWithPassphrase(passphrase: CharArray): VaultKeys {
        check(isEnrolled) { "Vault has not been set up yet" }
        val salt = keyStore.passphraseSalt
        val iterations = keyStore.passphraseIterations

        val mek = PassphraseKdf.deriveKey(passphrase, salt, iterations).use { wrappingKey ->
            try {
                SecureBytes.adopt(
                    AesGcm.open(
                        wrappingKey.toSecretKey(),
                        keyStore.passphraseWrappedMek,
                        aad = PASSPHRASE_AAD
                    )
                )
            } catch (badTag: AEADBadTagException) {
                // The GCM tag is the passphrase check - no separate verifier to leak.
                throw WrongPassphraseException()
            }
        }
        return deriveSessionKeys(mek, keyStore.hkdfSalt)
    }

    /**
     * A cipher that must be handed to `BiometricPrompt` inside a `CryptoObject`. The Keystore only
     * permits `doFinal` on it after the prompt reports success.
     *
     * @throws BiometricKeyInvalidatedException if biometric enrollment changed.
     */
    fun beginBiometricUnlock(): Cipher {
        check(isBiometricUnlockEnabled) { "Biometric unlock is not set up" }
        return try {
            kek.decryptCipher(keyStore.biometricIv)
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            discardBiometricWrapper()
            throw BiometricKeyInvalidatedException()
        }
    }

    /** Finishes the unlock with the authenticated cipher returned by the prompt. */
    fun finishBiometricUnlock(authenticatedCipher: Cipher): VaultKeys {
        val mek = SecureBytes.adopt(authenticatedCipher.doFinal(keyStore.biometricWrappedMek))
        return deriveSessionKeys(mek, keyStore.hkdfSalt)
    }

    /** Step one of turning on biometric unlock; also needs to go through the prompt. */
    fun beginBiometricEnable(): Cipher {
        if (!kek.exists()) kek.create()
        return kek.encryptCipher()
    }

    /** Step two: seals the already-unlocked root key under the hardware KEK. */
    fun finishBiometricEnable(authenticatedCipher: Cipher, keys: VaultKeys) {
        val wrapped = authenticatedCipher.doFinal(keys.rootKey().use())
        keyStore.biometricIv = authenticatedCipher.iv
        keyStore.biometricWrappedMek = wrapped
    }

    fun disableBiometricUnlock() = discardBiometricWrapper()

    /**
     * Re-wraps the existing root key under a new passphrase. The database is untouched, because
     * the DB passphrase is derived from the root key, not from what the user types.
     */
    fun changePassphrase(keys: VaultKeys, newPassphrase: CharArray) {
        val salt = PassphraseKdf.newSalt()
        val iterations = PassphraseKdf.DEFAULT_ITERATIONS
        PassphraseKdf.deriveKey(newPassphrase, salt, iterations).use { wrappingKey ->
            val wrapped = AesGcm.seal(wrappingKey.toSecretKey(), keys.rootKey().use(), aad = PASSPHRASE_AAD)
            keyStore.writePassphraseWrapper(salt, iterations, wrapped)
        }
    }

    private fun deriveSessionKeys(mek: SecureBytes, hkdfSalt: ByteArray): VaultKeys {
        val dbPassphrase = Hkdf.derive(mek.use(), hkdfSalt, INFO_DATABASE, KEY_LENGTH)
        val fieldKey = Hkdf.derive(mek.use(), hkdfSalt, INFO_FIELD, KEY_LENGTH)
        return try {
            VaultKeys(mek, dbPassphrase, fieldKey.toSecretKey())
        } finally {
            // SecretKeySpec cloned the bytes; ours are no longer needed.
            fieldKey.destroy()
        }
    }

    private fun discardBiometricWrapper() {
        keyStore.clearBiometricWrapper()
        kek.delete()
        Log.i(TAG, "Biometric wrapper discarded; passphrase unlock remains available")
    }

    private companion object {
        const val TAG = "VaultKeyManager"
        const val KEY_LENGTH = 32
        const val HKDF_SALT_LENGTH = 32
        const val INFO_DATABASE = "bubu/sqlcipher/v1"
        const val INFO_FIELD = "bubu/field/v1"

        /** Binds the passphrase wrapper to its purpose so a blob cannot be replayed elsewhere. */
        val PASSPHRASE_AAD = "bubu/mek-wrap/passphrase/v1".toByteArray(Charsets.UTF_8)
    }
}
