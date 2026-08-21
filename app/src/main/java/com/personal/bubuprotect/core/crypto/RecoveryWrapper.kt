package com.personal.bubuprotect.core.crypto

/**
 * Seals the vault's root key under a recovery code.
 *
 * Split out of [VaultKeyManager] for the same reason
 * [com.personal.bubuprotect.core.backup.VaultBackupEnvelope] is its own object: the manager needs
 * `SharedPreferences` and the Android Keystore, neither of which exists in a JVM unit test, while
 * this is pure `javax.crypto`. The one piece of this feature that must not have a bug in it is the
 * derive-seal-open round trip, and putting it here is what lets that be tested rather than reasoned
 * about.
 *
 * ### Why HKDF and not a memory-hard KDF
 *
 * A KDF's job against a *passphrase* is to make each guess expensive, because the guess space is
 * small enough to search - that is what the 210,000 PBKDF2 iterations in [PassphraseKdf] buy. A
 * recovery code is 120 uniformly random bits, so there is no guess space to search: at a trillion
 * attempts per second, 2^120 outlasts the age of the universe by an absurd margin. Slowing the
 * derivation would add nothing an attacker has to overcome, and would subtract from the one moment
 * this code path is ever used - someone who has just lost access to everything, typing 24 characters
 * off a sheet of paper.
 *
 * HKDF is the primitive actually designed for this case: extracting a uniform key from input that is
 * already high-entropy. The salt makes the result specific to one vault, so the same code cannot
 * derive the same wrapping key anywhere else - and replacing the salt is what silently retires every
 * previously printed kit.
 */
internal object RecoveryWrapper {

    const val SALT_LENGTH = 32
    private const val KEY_LENGTH = 32
    private const val INFO = "bubu/recovery/v1"

    /**
     * Binds the box to its purpose, so a recovery box cannot be replayed into another slot.
     *
     * Distinct from [VaultKeyManager]'s passphrase AAD deliberately: both boxes hold the same
     * plaintext under different keys, and one shared value would let them be swapped in storage. It
     * still would not decrypt without the right key, but purpose-binding every box is free and
     * removes the question.
     */
    private val AAD = "bubu/mek-wrap/recovery/v1".toByteArray(Charsets.UTF_8)

    fun newSalt(): ByteArray = SecureBytes.randomBytes(SALT_LENGTH)

    /**
     * @param rootKey the MEK. Read, never retained, and not wiped - the caller owns it, and it is
     *   still the live session key at the moment a kit is created.
     */
    fun seal(rootKey: ByteArray, code: RecoveryCode, salt: ByteArray): ByteArray =
        deriveKey(code, salt).use { wrappingKey ->
            AesGcm.seal(wrappingKey.toSecretKey(), rootKey, aad = AAD)
        }

    /**
     * @return the MEK. The caller takes ownership.
     * @throws javax.crypto.AEADBadTagException when the code, the salt or the box is wrong. The tag
     *   *is* the check, so there is no separate verifier that could be attacked on its own.
     */
    fun open(box: ByteArray, code: RecoveryCode, salt: ByteArray): ByteArray =
        deriveKey(code, salt).use { wrappingKey ->
            AesGcm.open(wrappingKey.toSecretKey(), box, aad = AAD)
        }

    private fun deriveKey(code: RecoveryCode, salt: ByteArray): SecureBytes =
        Hkdf.derive(code.secret().use(), salt, INFO, KEY_LENGTH)
}
