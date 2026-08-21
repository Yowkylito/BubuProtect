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

    /**
     * Apps the user has told BubuShield to leave alone.
     *
     * Keyed by [com.personal.bubuprotect.domain.model.ignoreKey], which folds in the signal set - so
     * "leave this one alone" applies to the app *as it behaved when they said it*. If a tolerated app
     * later starts drawing overlays, the key changes and the row returns without the user having to
     * remember to look. Same self-invalidating property as [acknowledgedDeviceRisks], for the same
     * reason.
     */
    var ignoredShieldApps: Set<String>
        get() = preferences.getStringSet(KEY_IGNORED_SHIELD_APPS, emptySet()).orEmpty()
        set(value) {
            preferences.edit().putStringSet(KEY_IGNORED_SHIELD_APPS, value.toSet()).apply()
        }

    /**
     * Apps whose ad-network lookups the local filter answers NXDOMAIN rather than merely counting.
     *
     * Package names, unhashed, and deliberately so - unlike the ignore set this is not a record of what
     * the user was warned about, it is an active configuration the DNS filter has to read on every
     * query. A hash would have to be reversed to be useful, and blocking is already visible to the user
     * as a row on the screen.
     */
    var filteredShieldApps: Set<String>
        get() = preferences.getStringSet(KEY_FILTERED_SHIELD_APPS, emptySet()).orEmpty()
        set(value) {
            preferences.edit().putStringSet(KEY_FILTERED_SHIELD_APPS, value.toSet()).apply()
        }

    /**
     * Set when a vault is created or restored, cleared once the recovery-kit screen has been offered.
     *
     * A one-shot handoff rather than a check for "does a kit exist", because those are different
     * questions. Someone who deliberately chose "Not now" should not be walked to the same screen on
     * every unlock - that is how a warning becomes wallpaper. The persistent nudge is the tinted
     * settings row; this is the single, well-timed offer at the one moment the vault is empty and the
     * user has nothing to lose by spending thirty seconds on it.
     *
     * Persisted rather than held in memory because setup ends by opening the vault, and screen-off
     * locks immediately - so an in-memory flag would routinely be dropped before it was ever read.
     */
    var recoveryKitPromptPending: Boolean
        get() = preferences.getBoolean(KEY_RECOVERY_PROMPT_PENDING, false)
        set(value) {
            preferences.edit().putBoolean(KEY_RECOVERY_PROMPT_PENDING, value).apply()
        }

    /**
     * Set after a recovery, cleared once the user has been told.
     *
     * Recovery ends with a new master passphrase, and every backup file already on disk is sealed
     * with the *old* one - the one that was just forgotten. Those files are now unopenable, and
     * nothing about them looks different.
     *
     * That is a trap this app would otherwise have built itself: before recovery existed, a forgotten
     * passphrase meant no access at all, so dead backups were moot. Now the user is back inside a
     * working vault and would reasonably assume their backups came back with it. They did not, and
     * they will find out on the day they need one.
     */
    var backupRefreshNeeded: Boolean
        get() = preferences.getBoolean(KEY_BACKUP_REFRESH_NEEDED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_BACKUP_REFRESH_NEEDED, value).apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "bubu_user"
        const val KEY_BACKUP_REFRESH_NEEDED = "backup_refresh_needed"
        const val KEY_RECOVERY_PROMPT_PENDING = "recovery_kit_prompt_pending"
        const val KEY_SECURITY_GUIDE_SEEN = "security_guide_seen"
        const val KEY_BREACH_MONITORING = "breach_monitoring_enabled"
        const val KEY_STRICT_REVEAL = "strict_reveal_enabled"
        const val KEY_ACKNOWLEDGED_DEVICE_RISKS = "acknowledged_device_risks"
        const val KEY_IGNORED_SHIELD_APPS = "ignored_shield_apps"
        const val KEY_FILTERED_SHIELD_APPS = "filtered_shield_apps"
    }
}
