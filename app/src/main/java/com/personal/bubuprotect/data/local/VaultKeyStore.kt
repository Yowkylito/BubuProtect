package com.personal.bubuprotect.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.personal.bubuprotect.core.crypto.PassphraseKdf

/**
 * Persistence for the vault's key metadata.
 *
 * Everything written here is either public (salts, iteration counts) or already wrapped under a key
 * this file does not contain - the Keystore KEK or the passphrase-derived key. So plain
 * [SharedPreferences] is the correct home for it. Wrapping it again in `EncryptedSharedPreferences`
 * would only add a third key to manage and a deprecated dependency; it would not raise the bar for
 * an attacker, because the blobs are useless without the KEK or the passphrase either way.
 *
 * Salts are stored, never derived from a device identifier: an attacker with the file has the salt
 * regardless, and a fixed-per-device salt would let precomputation carry across vault resets.
 */
class VaultKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vault_keys", Context.MODE_PRIVATE)

    /** True once a master passphrase has been set. The passphrase wrapper is mandatory. */
    val isEnrolled: Boolean
        get() = prefs.contains(KEY_PASSPHRASE_WRAPPED) && prefs.contains(KEY_HKDF_SALT)

    /** True when a hardware-backed biometric wrapper of the same root key also exists. */
    val hasBiometricWrapper: Boolean
        get() = prefs.contains(KEY_BIOMETRIC_WRAPPED) && prefs.contains(KEY_BIOMETRIC_IV)

    var hkdfSalt: ByteArray
        get() = readBytes(KEY_HKDF_SALT)
        set(value) = writeBytes(KEY_HKDF_SALT, value)

    var passphraseSalt: ByteArray
        get() = readBytes(KEY_PASSPHRASE_SALT)
        set(value) = writeBytes(KEY_PASSPHRASE_SALT, value)

    var passphraseIterations: Int
        get() = prefs.getInt(KEY_PASSPHRASE_ITERATIONS, PassphraseKdf.DEFAULT_ITERATIONS)
        set(value) = prefs.edit().putInt(KEY_PASSPHRASE_ITERATIONS, value).apply()

    /** MEK sealed with the passphrase-derived key, as `iv || ciphertext || tag`. */
    var passphraseWrappedMek: ByteArray
        get() = readBytes(KEY_PASSPHRASE_WRAPPED)
        set(value) = writeBytes(KEY_PASSPHRASE_WRAPPED, value)

    /** MEK sealed by the Keystore KEK. The IV is separate because the Keystore generates it. */
    var biometricWrappedMek: ByteArray
        get() = readBytes(KEY_BIOMETRIC_WRAPPED)
        set(value) = writeBytes(KEY_BIOMETRIC_WRAPPED, value)

    var biometricIv: ByteArray
        get() = readBytes(KEY_BIOMETRIC_IV)
        set(value) = writeBytes(KEY_BIOMETRIC_IV, value)

    fun clearBiometricWrapper() = prefs.edit()
        .remove(KEY_BIOMETRIC_WRAPPED)
        .remove(KEY_BIOMETRIC_IV)
        .apply()

    /** Writes the passphrase wrapper and its parameters atomically, so a crash cannot half-enroll. */
    fun writePassphraseWrapper(salt: ByteArray, iterations: Int, wrapped: ByteArray) {
        prefs.edit()
            .putString(KEY_PASSPHRASE_SALT, encode(salt))
            .putInt(KEY_PASSPHRASE_ITERATIONS, iterations)
            .putString(KEY_PASSPHRASE_WRAPPED, encode(wrapped))
            .apply()
    }

    // --- Failed-attempt throttling -------------------------------------------------------------
    //
    // Both the counter and the deadline are persisted, so force-stopping the app or rebooting the
    // device does not reset the penalty. The deadline uses wall clock because it has to survive a
    // reboot; winding the clock backwards is handled by treating a "now earlier than when the
    // penalty was set" reading as still locked out.

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    fun recordLockout(untilEpochMillis: Long, setAtEpochMillis: Long) {
        prefs.edit()
            .putLong(KEY_LOCKOUT_UNTIL, untilEpochMillis)
            .putLong(KEY_LOCKOUT_SET_AT, setAtEpochMillis)
            .apply()
    }

    val lockoutUntil: Long get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
    val lockoutSetAt: Long get() = prefs.getLong(KEY_LOCKOUT_SET_AT, 0L)

    fun clearLockout() = prefs.edit()
        .remove(KEY_LOCKOUT_UNTIL)
        .remove(KEY_LOCKOUT_SET_AT)
        .putInt(KEY_FAILED_ATTEMPTS, 0)
        .apply()

    /** Nukes all key metadata. The encrypted database becomes permanently unreadable. */
    fun wipe() = prefs.edit().clear().commit()

    private fun readBytes(key: String): ByteArray {
        val raw = prefs.getString(key, null)
            ?: throw IllegalStateException("Vault metadata '$key' is missing")
        return Base64.decode(raw, Base64.NO_WRAP)
    }

    private fun writeBytes(key: String, value: ByteArray) =
        prefs.edit().putString(key, encode(value)).apply()

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private companion object {
        const val KEY_HKDF_SALT = "hkdf_salt"
        const val KEY_PASSPHRASE_SALT = "passphrase_salt"
        const val KEY_PASSPHRASE_ITERATIONS = "passphrase_iterations"
        const val KEY_PASSPHRASE_WRAPPED = "passphrase_wrapped_mek"
        const val KEY_BIOMETRIC_WRAPPED = "biometric_wrapped_mek"
        const val KEY_BIOMETRIC_IV = "biometric_iv"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"
        const val KEY_LOCKOUT_SET_AT = "lockout_set_at"
    }
}
