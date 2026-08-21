package com.personal.bubuprotect.core.shield

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.personal.bubuprotect.core.shield.enforce.ShizukuGateway
import com.personal.bubuprotect.core.shield.network.ShieldVpnService
import com.personal.bubuprotect.core.shield.recorder.ShieldAccessibilityService
import com.personal.bubuprotect.core.shield.recorder.ShieldNotificationListener
import com.personal.bubuprotect.core.shield.recorder.UsageTimelineProbe
import com.personal.bubuprotect.domain.model.RemediationTier

/**
 * Which of the shield's sensors and remediation rungs are actually live right now.
 *
 * ### Why this is one class and not a flag on each component
 *
 * The shield is a ladder the user climbs voluntarily, and the screen has to be honest at every rung
 * about what is switched on, what is off, and what each one would buy. That means one place that knows
 * the whole picture - otherwise the UI ends up asking five different objects and getting five answers
 * that can disagree with each other mid-render.
 *
 * ### Nothing here is required
 *
 * Every capability below is off by default and the shield works with all of them off: it scans
 * capability signals, reports suspects, and offers the system uninstall dialog. Each grant the user adds
 * upgrades a *specific* claim from "possible" to "observed". That is the whole trade, and stating it per
 * capability is what lets the screen ask for one grant at a time in the moment it would help, rather
 * than demanding five up front.
 */
class ShieldCapabilities(
    private val usage: UsageTimelineProbe,
    private val shizuku: ShizukuGateway
) {

    /**
     * @param windowWatcher the accessibility service. Without it nothing can attribute an overlay to
     *   the app that drew it, which is the shield's central claim - so this is the one grant the screen
     *   asks for first.
     * @param notificationTap without it, notification spam cannot be measured or cleared.
     * @param usageTimeline without it, "launched itself" is unavailable, though overlays still convict.
     * @param dnsFilter whether the local tunnel is running. Off is normal; it is the heaviest rung.
     * @param shizukuState what the remediation ladder can reach above ADVISE.
     */
    data class Snapshot(
        val windowWatcher: Boolean,
        val notificationTap: Boolean,
        val usageTimeline: Boolean,
        val dnsFilter: Boolean,
        val shizukuState: ShizukuGateway.Availability
    ) {
        val remediationTiers: Set<RemediationTier>
            get() = buildSet {
                add(RemediationTier.ADVISE)
                if (shizukuState == ShizukuGateway.Availability.Ready) {
                    add(RemediationTier.NEUTER)
                    add(RemediationTier.DISABLE)
                    add(RemediationTier.REMOVE)
                }
            }

        /** Any sensor at all. With none of these, verdicts can never rise above SUSPECT. */
        val canObserveBehaviour: Boolean get() = windowWatcher || notificationTap || dnsFilter

        /** For the progress indicator on the shield screen: how far up the ladder the user has come. */
        val grantedCount: Int
            get() = listOf(windowWatcher, notificationTap, usageTimeline, dnsFilter).count { it }

        companion object {
            const val TOTAL_CAPABILITIES = 4
        }
    }

    fun snapshot(context: Context): Snapshot = Snapshot(
        windowWatcher = isAccessibilityServiceEnabled(context),
        notificationTap = ShieldNotificationListener.isConnected,
        usageTimeline = usage.availability(context) is UsageTimelineProbe.Availability.Granted,
        dnsFilter = ShieldVpnService.running,
        shizukuState = shizuku.availability()
    )

    /**
     * Whether our own accessibility service is in the enabled list.
     *
     * Read from `Settings.Secure` rather than from `AccessibilityManager.getEnabledAccessibilityServiceList`,
     * because that list only reports services matching a requested feedback type and would miss ours
     * depending on how it is queried. The setting is the authoritative record of what the user turned on.
     *
     * Both the flattened-short and flattened-long forms are checked: which one appears in the setting
     * varies by OEM, and matching only one produces a screen that says the service is off while it is
     * demonstrably running.
     */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val component = ComponentName(context, ShieldAccessibilityService::class.java)

        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Exception) {
            null
        } ?: return false

        return enabled.split(':').any { entry ->
            entry.equals(component.flattenToString(), ignoreCase = true) ||
                entry.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }
}
