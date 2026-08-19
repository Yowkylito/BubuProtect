package com.personal.bubuprotect.data.local

import android.content.Context

/**
 * Non-sensitive product preferences.
 *
 * Kept separate from VaultKeyStore: whether someone has seen a guide is UX state, not cryptographic
 * enrollment metadata, and resetting it must never affect access to the vault.
 */
class UserPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val hasSeenSecurityGuide: Boolean
        get() = preferences.getBoolean(KEY_SECURITY_GUIDE_SEEN, false)

    fun markSecurityGuideSeen() {
        preferences.edit().putBoolean(KEY_SECURITY_GUIDE_SEEN, true).apply()
    }

    /**
     * Whether Bubu may check passwords against breach data on its own, once per unlocked session.
     *
     * **Off by default, and it has to be.** Everything else in this app is offline; turning it on is
     * the one decision that makes the vault talk to the internet without the user standing there
     * watching. A default of "on" would mean an app whose entire pitch is "nothing leaves this
     * device" quietly making network requests the first time it is opened - true k-anonymity or not,
     * that is a promise broken before it is explained.
     *
     * With it off, the check still exists; it just waits to be asked, from the entry screen or the
     * security report.
     */
    var breachMonitoringEnabled: Boolean
        get() = preferences.getBoolean(KEY_BREACH_MONITORING, false)
        set(value) {
            preferences.edit().putBoolean(KEY_BREACH_MONITORING, value).apply()
        }

    /**
     * Whether every single reveal needs its own fresh biometric check.
     *
     * **Off by default**, because the default already authenticates - opening the vault costs a
     * passphrase or a fingerprint, and revealing a field costs another. What this adds is the
     * removal of every shortcut built on "you just proved it was you a moment ago": the one-prompt-
     * per-editor-session allowance, and copying a field that is already on screen without a second
     * check. Those shortcuts are the right default for a vault someone opens twenty times a day;
     * this is for the person who would rather touch the sensor twenty times.
     *
     * It is a UX preference, not a key policy - nothing about how the vault is encrypted changes
     * with it - which is why it lives here rather than in `VaultKeyStore`. What it does change is
     * what someone holding your unlocked phone can read off the screen, which is precisely the
     * threat the shortcuts trade against.
     *
     * Requires an enrolled strong biometric to mean anything, and the settings switch says so: with
     * no sensor to re-check against there is nothing to honour the promise with, so a reveal is
     * refused outright rather than quietly waved through.
     */
    var strictRevealEnabled: Boolean
        get() = preferences.getBoolean(KEY_STRICT_REVEAL, false)
        set(value) {
            preferences.edit().putBoolean(KEY_STRICT_REVEAL, value).apply()
        }

    /**
     * Device-check findings the user has looked at and accepted.
     *
     * Stores [com.personal.bubuprotect.domain.model.DeviceFinding.fingerprint] values, which are a
     * probe name plus a hash of what it found - never the app names themselves. See that property
     * for why: this file is plain `SharedPreferences`, and a readable list of which apps hold
     * accessibility access on this phone is an inventory of the user's device that a password manager
     * has no reason to keep.
     *
     * The set exists because the alternative is a permanent red dot. Someone who uses a screen reader
     * has a genuine, correct, unfixable finding on this screen forever, and a warning that can never
     * be resolved is a warning they learn to scroll past - taking the resolvable ones with it. The
     * hash makes forgiveness specific: acknowledging the reader they chose does not acknowledge the
     * next service that appears.
     */
    var acknowledgedDeviceRisks: Set<String>
        get() = preferences.getStringSet(KEY_ACKNOWLEDGED_DEVICE_RISKS, emptySet()).orEmpty()
        set(value) {
            // Copied into a new set before writing. SharedPreferences keeps a reference to the set it
            // is handed and returns that same instance on read, so storing a caller's collection lets
            // a later mutation of it change what is "persisted" without an edit.
            preferences.edit()
                .putStringSet(KEY_ACKNOWLEDGED_DEVICE_RISKS, value.toSet())
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "bubu_user"
        const val KEY_SECURITY_GUIDE_SEEN = "security_guide_seen"
        const val KEY_BREACH_MONITORING = "breach_monitoring_enabled"
        const val KEY_STRICT_REVEAL = "strict_reveal_enabled"
        const val KEY_ACKNOWLEDGED_DEVICE_RISKS = "acknowledged_device_risks"
    }
}
