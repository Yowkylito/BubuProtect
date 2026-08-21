package com.personal.bubuprotect.core.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.personal.bubuprotect.data.local.VaultKeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import timber.log.Timber

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
 * The recovery code was well-formed but does not open this vault.
 *
 * Separate from [WrongPassphraseException] so the UI can say the one thing that helps: a code of
 * the right shape that fails is usually a kit for a *different* vault - a previous install, or
 * another phone - rather than a typo, because a typo tends to break the length first.
 */
class WrongRecoveryCodeException : Exception("That recovery code does not open this vault")

/**
 * The recovery screen is sealed because the device's biometric enrollment changed.
 *
 * Raised when the guard key described on [VaultKeyManager.beginRecoveryGuardCheck] is gone or has
 * been invalidated. Not a bug and not data loss: the vault still opens with the master passphrase,
 * and a successful passphrase unlock re-arms the guard.
 *
 * It is a *refusal*, and the refusal is the feature. A stolen phone taken to someone who strips or
 * replaces its fingerprints arrives here, and there is no way past it that does not require the
 * passphrase the thief does not have.
 */
class RecoveryGuardUnavailableException : Exception("Recovery is sealed on this device")

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
 * three times, independently:
 *
 * ```
 *   Keystore KEK  (TEE/StrongBox, per-use biometric)  ─┐
 *                                                      │
 *   PBKDF2-SHA512(master passphrase, salt, 210k)      ─┼─▶ MEK ──HKDF──┬─▶ SQLCipher passphrase
 *                                                      │              └─▶ AES-GCM field key
 *   HKDF-SHA256(recovery code, salt)                  ─┘
 * ```
 *
 * Three wrappers rather than one, because each covers a failure mode the others do not. The
 * Keystore wrapper is the strongest - a non-exportable hardware key beats anything a human will
 * memorise - but it is destroyed by design when a fingerprint is enrolled, and a vault that erases
 * itself when the user adds a finger is a data-loss bug wearing a security hat. The passphrase wrapper is the
 * durable one, and it is what makes the vault resistant to an attacker who has both the device and
 * a way to unlock it.
 *
 * The recovery wrapper covers the failure mode the other two share: a human forgetting something. It
 * is optional, it lives on paper rather than on the device, and it is the only wrapper that survives
 * "I no longer know my passphrase" - which, before it existed, meant permanent and total loss of
 * every secret, backups included, because a backup is sealed with that same passphrase.
 *
 * The MEK is deliberately *not* used directly for anything. Both consumers get an HKDF subkey, so
 * the native SQLCipher key and the JCE field key are independent of one another.
 */
class VaultKeyManager(
    private val keyStore: VaultKeyStore,
    private val kek: KeystoreKek,
    /**
     * A second Keystore key with the same properties as [kek], guarding the recovery screen.
     *
     * Separate on purpose - see [KeystoreKek.ALIAS_RECOVERY_GUARD].
     */
    private val recoveryGuard: KeystoreKek = KeystoreKek(KeystoreKek.ALIAS_RECOVERY_GUARD)
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

    // --- Recovery kit --------------------------------------------------------------------------

    val hasRecoveryKit: Boolean get() = keyStore.hasRecoveryWrapper

    val recoveryKitCreatedAt: Long get() = keyStore.recoveryCreatedAt

    /**
     * Mints a recovery code and seals the root key under it.
     *
     * Requires an already-unlocked [keys], which is the point: creating a kit is not a way *into* the
     * vault, it is something done from inside one. Minting a credential from a locked vault would be
     * issuing access without ever having authenticated.
     *
     * Any previously printed kit stops working here, because a fresh salt and a fresh box replace the
     * old pair - see [VaultKeyStore.writeRecoveryWrapper].
     *
     * @return the code. **The caller owns it and must close it.** It is the only copy that will ever
     *   exist; the wrapper it just wrote cannot give it back.
     */
    fun createRecoveryKit(keys: VaultKeys, now: Long = System.currentTimeMillis()): RecoveryCode {
        val code = RecoveryCode.generate()
        try {
            /*
             * The guard is armed first, and its failure aborts the whole thing.
             *
             * On a device with no enrolled biometric this throws, and that is the correct outcome
             * rather than an inconvenience: without a guard the printed code would be the only thing
             * standing between an unlocked phone and the vault. Handing the user a kit that cannot be
             * protected - and not saying so - would be the worst of the three options.
             */
            recoveryGuard.create()

            val salt = RecoveryWrapper.newSalt()
            val wrapped = RecoveryWrapper.seal(keys.rootKey().use(), code, salt)
            keyStore.writeRecoveryWrapper(salt, wrapped, now)
            return code
        } catch (failure: Throwable) {
            // A guard with no wrapper behind it would make the settings row lie about a kit existing.
            recoveryGuard.delete()
            // Handing back a code whose wrapper was never written is worse than failing outright:
            // the user would write it on paper, file it away, and trust it.
            code.close()
            throw failure
        }
    }

    /**
     * The way back in when the passphrase is gone.
     *
     * @throws WrongRecoveryCodeException if the code does not open this vault.
     *
     * The derivation, and why it is HKDF rather than something memory-hard, is documented on
     * [RecoveryWrapper].
     */
    fun unlockWithRecoveryCode(code: RecoveryCode): VaultKeys {
        check(isEnrolled) { "Vault has not been set up yet" }
        check(keyStore.hasRecoveryWrapper) { "No recovery kit has been created" }

        val mek = try {
            SecureBytes.adopt(
                RecoveryWrapper.open(keyStore.recoveryWrappedMek, code, keyStore.recoverySalt)
            )
        } catch (badTag: AEADBadTagException) {
            // As on the passphrase path, the GCM tag *is* the check - there is no separate verifier
            // that could be attacked on its own.
            throw WrongRecoveryCodeException()
        }
        return deriveSessionKeys(mek, keyStore.hkdfSalt)
    }

    /**
     * Whether the recovery screen can be opened at all on this device, right now.
     *
     * Cheap: it asks the Keystore to initialise a cipher, which is where an invalidated key reveals
     * itself, and does not prompt for anything.
     */
    val isRecoveryGuardIntact: Boolean
        get() = keyStore.hasRecoveryWrapper &&
            recoveryGuard.exists() &&
            runCatching { recoveryGuard.encryptCipher() }.isSuccess

    /**
     * Step one of opening the recovery screen: a cipher that only a live biometric can use.
     *
     * ### Why this is a Keystore key and not a `BiometricManager` check
     *
     * Asking `BiometricManager.canAuthenticate()` answers "is *a* fingerprint enrolled", which is the
     * wrong question. A stolen phone taken to someone who removes the owner's fingerprint and enrols
     * their own passes that check perfectly, and their finger then satisfies the prompt.
     *
     * This key is built with `setInvalidatedByBiometricEnrollment(true)`, so the Android Keystore
     * destroys it the instant the enrolled set changes - added, removed, replaced. The check is
     * therefore not "does a biometric exist" but "is this the same enrollment that was present when
     * the kit was made", and that is a question a technician cannot answer yes to.
     *
     * ### Why there is no fall-through
     *
     * A missing or invalidated guard *refuses*. Earlier this allowed the action when no biometric was
     * available, to avoid turning a forgotten passphrase into permanent loss - and that was the wrong
     * trade. It handed a thief the softer path simply by making the device look sensor-less, which is
     * exactly what stripping biometrics achieves. Protecting the vault's contents beats preserving the
     * recovery feature on a device that cannot protect it.
     *
     * ### Why this does not strand the real owner
     *
     * Re-enrolling a fingerprint invalidates this key *and* the biometric unlock wrapper, so the owner
     * lands on the master passphrase - which they still know, because the recovery path is for when
     * they do not. A successful passphrase unlock re-arms the guard via [rearmRecoveryGuard]. The
     * thief cannot reach that path, because re-arming requires getting into the vault first.
     *
     * @throws RecoveryGuardUnavailableException when the guard is gone. The caller must not proceed.
     */
    fun beginRecoveryGuardCheck(): Cipher {
        check(keyStore.hasRecoveryWrapper) { "No recovery kit has been created" }
        if (!recoveryGuard.exists()) throw RecoveryGuardUnavailableException()
        return try {
            recoveryGuard.encryptCipher()
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            Timber.tag(TAG).w("Recovery guard invalidated; biometric enrollment changed")
            recoveryGuard.delete()
            throw RecoveryGuardUnavailableException()
        }
    }

    /**
     * Step two: proves the Keystore actually released the key.
     *
     * The `doFinal` is the whole point and must not be skipped. A `BiometricPrompt` reporting success
     * says the *framework* was satisfied; only completing an operation on an auth-bound key proves the
     * *Keystore* was. Treating the callback alone as proof is the classic way a biometric gate turns
     * out to be decorative.
     *
     * The ciphertext is discarded - nothing is being protected here, the successful operation *is* the
     * result.
     */
    fun finishRecoveryGuardCheck(authenticatedCipher: Cipher): Boolean = try {
        authenticatedCipher.doFinal(GUARD_PROBE).isNotEmpty()
    } catch (failure: Exception) {
        Timber.tag(TAG).w(failure, "Recovery guard cipher was not usable after the prompt")
        false
    }

    /**
     * Re-arms the guard after a successful unlock.
     *
     * Called on every unlock rather than only when something looks wrong, because it is idempotent and
     * cheap when intact. Silent by design: a user who re-enrolled a fingerprint should not be told
     * anything, because nothing about their vault changed.
     *
     * Reachable only from inside an unlocked vault, which is what makes it safe. An attacker who
     * invalidated the guard by changing biometrics cannot get here without the passphrase - and if
     * they had that, the recovery screen would be irrelevant to them.
     */
    fun rearmRecoveryGuard() {
        if (!keyStore.hasRecoveryWrapper) return
        if (isRecoveryGuardIntact) return
        runCatching { recoveryGuard.create() }
            .onSuccess { Timber.tag(TAG).i("Recovery guard re-armed") }
            .onFailure {
                // No enrolled biometric to bind to. The kit stays unusable until there is one, which
                // is the honest state rather than a silently weakened gate.
                Timber.tag(TAG).w("Could not re-arm the recovery guard: %s", it.javaClass.simpleName)
            }
    }

    /**
     * Forgets the kit, making every printed copy inert.
     *
     * Offered because a recovery code is a complete credential on a piece of paper, and paper gets
     * lost, photographed and thrown out. Someone who cannot account for their kit needs to be able to
     * kill it, not only replace it.
     */
    fun discardRecoveryKit() {
        keyStore.clearRecoveryWrapper()
        recoveryGuard.delete()
        Timber.tag(TAG).i("Recovery wrapper discarded; printed kits no longer open this vault")
    }

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
        Timber.tag(TAG).i("Biometric wrapper discarded; passphrase unlock remains available")
    }

    private companion object {
        const val TAG = "VaultKeyManager"
        const val KEY_LENGTH = 32
        const val HKDF_SALT_LENGTH = 32
        const val INFO_DATABASE = "bubu/sqlcipher/v1"

        /** Arbitrary bytes. Only the fact that the Keystore encrypted them matters. */
        val GUARD_PROBE = "bubu/recovery/guard".toByteArray(Charsets.UTF_8)
        const val INFO_FIELD = "bubu/field/v1"

        /** Binds the passphrase wrapper to its purpose so a blob cannot be replayed elsewhere. */
        val PASSPHRASE_AAD = "bubu/mek-wrap/passphrase/v1".toByteArray(Charsets.UTF_8)
    }
}
