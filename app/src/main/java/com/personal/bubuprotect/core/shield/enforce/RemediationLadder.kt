package com.personal.bubuprotect.core.shield.enforce

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.personal.bubuprotect.core.shield.recorder.ShieldNotificationListener
import com.personal.bubuprotect.domain.model.RemediationTier

/**
 * Does something about a convicted app.
 *
 * ### The rungs, and why the order is what it is
 *
 * The obvious product is a single "remove" button. It is the wrong one. Most people hit by ad-spam
 * installed the offending app on purpose and it may still do the thing they wanted; what they want is
 * for the ads to stop. So the ladder starts at the least destructive action that actually solves the
 * problem and only climbs on request:
 *
 *  1. [dismissNotifications] - clears what is in the shade now. No grant beyond notification access,
 *     nothing removed, immediate.
 *  2. [neuter] - revokes the overlay capability through `appops`. The app stays installed and working
 *     and can no longer draw over anything. This is the rung no other tool offers.
 *  3. [disable] - `pm disable-user`. Gone from the launcher, unable to run, restorable in one tap.
 *  4. [remove] - `pm uninstall --user 0`. Works on preinstalled apps that have no uninstall button.
 *
 * Everything above the first rung needs [ShizukuGateway]. Without it, [uninstallIntent] hands the job
 * to the system dialog, which is the honest fallback: this app asks, the user confirms, Android does it.
 *
 * ### Nothing here runs without an explicit tap
 *
 * There is no auto-remediation, and that is a decision rather than an omission. A false positive that
 * silently disables an app the user needed is a failure they cannot diagnose - the app is simply gone
 * and nothing said why. Every method below is called from a button next to the evidence that justified
 * it.
 */
class RemediationLadder(private val shizuku: ShizukuGateway) {

    /** Which rungs are reachable right now, for the UI to enable and disable buttons honestly. */
    fun availableTiers(): Set<RemediationTier> = buildSet {
        add(RemediationTier.ADVISE)
        if (shizuku.availability() == ShizukuGateway.Availability.Ready) {
            add(RemediationTier.NEUTER)
            add(RemediationTier.DISABLE)
            add(RemediationTier.REMOVE)
        }
    }

    /**
     * Revokes the ability to draw over other apps, leaving the app installed.
     *
     * `appops set <pkg> SYSTEM_ALERT_WINDOW ignore` rather than `deny`: `ignore` makes the platform
     * silently refuse the operation, while `deny` surfaces as a permission error the app can detect and
     * complain about - or crash on. The user wants their app to keep working without ads, not to keep
     * an app that now shows an error where the ad used to be.
     */
    suspend fun neuter(packageName: String): ShizukuGateway.Result =
        shizuku.exec("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "ignore")

    /** Puts the overlay capability back. Paired with [neuter] so the action is reversible. */
    suspend fun unneuter(packageName: String): ShizukuGateway.Result =
        shizuku.exec("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow")

    /**
     * Disables the app for the current user.
     *
     * `disable-user` rather than `disable`: the latter needs a privilege shell UID does not have on
     * every build, and fails inconsistently across OEMs. `--user 0` is explicit because the command
     * defaults differently depending on how the shell was entered.
     */
    suspend fun disable(packageName: String): ShizukuGateway.Result =
        shizuku.exec("pm", "disable-user", "--user", "0", packageName)

    suspend fun enable(packageName: String): ShizukuGateway.Result =
        shizuku.exec("pm", "enable", packageName)

    /**
     * Uninstalls for the current user.
     *
     * `--user 0` rather than a full removal, and the distinction matters for preinstalled apps: this
     * leaves the system image untouched, so the app returns after a factory reset and nothing about the
     * OS partition has been modified. It is the strongest removal that cannot brick anything.
     */
    suspend fun remove(packageName: String): ShizukuGateway.Result =
        shizuku.exec("pm", "uninstall", "--user", "0", packageName)

    /** Clears everything the app currently has in the shade. @return count, or null if not connected. */
    fun dismissNotifications(packageName: String): Int? =
        ShieldNotificationListener.dismissAll(packageName)

    /**
     * The `ADVISE` fallback: ask Android to uninstall, and let it ask the user.
     *
     * Always available, needs only `REQUEST_DELETE_PACKAGES`, and keeps the system confirmation dialog
     * in the loop - which is why this app never needs to be trusted with silent removal.
     */
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, "package:$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The app's own settings page, where the user can revoke grants by hand.
     *
     * App details rather than `ACTION_MANAGE_OVERLAY_PERMISSION`. That action exists, but on most
     * builds it opens the *list* of apps or only works for the caller's own package, so aiming at it
     * produces a screen the user then has to search - while app details lands on the right app every
     * time and has the permission controls one tap away.
     */
    fun appSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Where the user grants notification access, for the tier-1 prompt. */
    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Where the user grants the window watcher, for the tier-1 prompt. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Where the user grants usage access. */
    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether [intent] can actually be opened on this phone.
     *
     * OEM ROMs do remove and rename these Settings activities, and a button that silently does nothing
     * is worse than one that is absent - the same reason
     * [com.personal.bubuprotect.ui.vm.DeviceCheckViewModel.reportMissingSettingsScreen] exists.
     */
    fun canOpen(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    private fun String.toUri(): Uri = Uri.parse(this)
}
