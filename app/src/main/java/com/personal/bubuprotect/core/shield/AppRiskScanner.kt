package com.personal.bubuprotect.core.shield

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.personal.bubuprotect.core.shield.intel.SignerBlocklist
import com.personal.bubuprotect.core.shield.intel.SignerFingerprinter
import com.personal.bubuprotect.core.shield.intel.StaticApkAnalyzer
import com.personal.bubuprotect.core.shield.recorder.FlightRecorder
import com.personal.bubuprotect.domain.model.AppScanReport
import com.personal.bubuprotect.domain.model.AppVerdict
import com.personal.bubuprotect.domain.model.Conviction
import com.personal.bubuprotect.domain.model.RiskSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the case against every installed app, once.
 *
 * ### Two passes, because family resemblance needs a verdict to resemble
 *
 * The first pass scores each app independently: capability signals off the manifest, behavioural
 * signals out of the [FlightRecorder], identity against the [SignerBlocklist]. The second pass looks
 * at the certificates of whatever the first pass convicted and marks every *other* app sharing one as
 * a [RiskSignal.SIGNER_SIBLING].
 *
 * That ordering is the whole reason the shield works without a threat feed. One caught app identifies
 * its whole family locally - and it has to be a second pass, because on the first pass there is
 * nothing to be a sibling of yet.
 *
 * ### Cost, and where it is spent
 *
 * A few hundred packages, each needing a label resolved out of another APK's resources and a
 * permission array walked. That is tens of milliseconds of real work and it is why [scan] is
 * `suspend` and pinned to IO - running it on the main thread would drop frames on a screen the user
 * opened because they were annoyed.
 *
 * The expensive probe, [StaticApkAnalyzer], opens the APK as a zip. It runs only for apps that already
 * carry another signal, so on a clean phone it barely runs at all. That gate is load-bearing: opening
 * three hundred zip files is a visible pause, and it would buy nothing, because an ad SDK on its own
 * never changes a verdict.
 *
 * @param clock injected for the probation window, matching the other scanners in this app.
 */
class AppRiskScanner(
    private val fingerprinter: SignerFingerprinter,
    private val analyzer: StaticApkAnalyzer,
    private val recorder: FlightRecorder,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun scan(
        context: Context,
        blocklist: SignerBlocklist
    ): AppScanReport = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        val installed = try {
            @Suppress("DEPRECATION")
            packages.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) {
            // A TransactionTooLargeException here is real on phones with very many packages. There is
            // no smaller query that answers the question, so the report says it could not run rather
            // than showing an empty list that reads as "nothing found".
            return@withContext AppScanReport.unavailable(
                "Android would not hand over the full app list on this phone",
                clock()
            )
        }

        val admins = activeAdmins(context)
        val accessibilityDeclarers = declarersOf(packages, ACCESSIBILITY_ACTION)
        val notificationDeclarers = declarersOf(packages, NOTIFICATION_LISTENER_ACTION)

        val first = installed.mapNotNull { info ->
            verdictFor(
                context = context,
                packages = packages,
                info = info,
                blocklist = blocklist,
                admins = admins,
                accessibilityDeclarers = accessibilityDeclarers,
                notificationDeclarers = notificationDeclarers
            )
        }

        AppScanReport(verdicts = withSiblings(first), checkedAt = clock())
    }

    /**
     * @return null for this app itself, and for apps that produced no signal at all - the report holds
     *   a count of what was examined rather than a row per package, so several hundred empty verdicts
     *   would be several hundred objects nobody reads.
     */
    private fun verdictFor(
        context: Context,
        packages: PackageManager,
        info: PackageInfo,
        blocklist: SignerBlocklist,
        admins: Set<String>,
        accessibilityDeclarers: Set<String>,
        notificationDeclarers: Set<String>
    ): AppVerdict? {
        val pkg = info.packageName
        val app = info.applicationInfo ?: return null

        if (fingerprinter.isSelf(packages, pkg)) {
            // Reported rather than skipped: BubuShield holds accessibility and notification access,
            // which is exactly what it flags in others, and a scanner that hides its own row has
            // asked the user to trust something it will not show them.
            return AppVerdict(
                packageName = pkg,
                label = labelOf(packages, app),
                signerSha256 = fingerprinter.fingerprint(packages, pkg),
                signals = emptySet(),
                firstInstalledAt = info.firstInstallTime,
                isSelf = true
            )
        }

        val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val requested = info.requestedPermissions?.toSet().orEmpty()
        val fingerprints = fingerprinter.fingerprints(packages, pkg)

        val signals = buildSet {
            if (blocklist.matches(fingerprints)) add(RiskSignal.KNOWN_BAD_SIGNER)

            addAll(recorder.signalsFor(pkg))

            if (PERMISSION_OVERLAY in requested) add(RiskSignal.CAN_DRAW_OVERLAYS)
            if (pkg in accessibilityDeclarers) add(RiskSignal.DECLARES_ACCESSIBILITY)
            if (pkg in notificationDeclarers) add(RiskSignal.DECLARES_NOTIFICATION_LISTENER)
            if (pkg in admins) add(RiskSignal.ACTIVE_DEVICE_ADMIN)

            if (PERMISSION_BOOT in requested && PERMISSION_FOREGROUND_SERVICE in requested) {
                add(RiskSignal.BOOT_PERSISTENCE)
            }

            if (app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                add(RiskSignal.DEBUGGABLE_BUILD)
            }

            // System apps are exempt from the provenance and launcher-icon signals, not out of
            // deference but because they are meaningless there: a preinstalled app has no installer
            // and most have no launcher entry by design. Applying them would flag a hundred platform
            // components and bury the one row that mattered.
            if (!isSystem) {
                when (installerOf(packages, pkg)) {
                    null -> add(RiskSignal.UNKNOWN_INSTALLER)
                    !in TRUSTED_INSTALLERS -> add(RiskSignal.SIDELOADED)
                    else -> Unit
                }

                if (packages.getLaunchIntentForPackage(pkg) == null) {
                    add(RiskSignal.NO_LAUNCHER_ICON)
                }
            }

            if (clock() - info.firstInstallTime < PROBATION_MILLIS) add(RiskSignal.ON_PROBATION)

            // Gated last and deliberately: this opens the APK, and it can never change a verdict on
            // its own. Running it for an app with no other signal would be pure cost.
            if (isNotEmpty() && !isSystem && analyzer.analyze(app)) {
                add(RiskSignal.AD_SDK_PRESENT)
            }
        }

        if (signals.isEmpty()) return null

        return AppVerdict(
            packageName = pkg,
            label = labelOf(packages, app),
            signerSha256 = fingerprints.firstOrNull(),
            signals = signals,
            firstInstalledAt = info.firstInstallTime
        )
    }

    /**
     * Marks apps sharing a certificate with something already convicted.
     *
     * Grouping is on the *current* signer only. Pairing on a retired key would group apps whose
     * ownership diverged when the key was sold or rotated, and the claim being made here - "same
     * developer as the one you just caught" - has to be true today to be worth acting on.
     */
    private fun withSiblings(verdicts: List<AppVerdict>): List<AppVerdict> {
        val convictedSigners = verdicts
            .filter { !it.isSelf && it.conviction == Conviction.CONVICTED }
            .mapNotNullTo(mutableSetOf(), AppVerdict::signerSha256)

        if (convictedSigners.isEmpty()) return verdicts

        return verdicts.map { verdict ->
            val shares = verdict.signerSha256 in convictedSigners
            val alreadyConvicted = verdict.conviction == Conviction.CONVICTED

            if (!shares || alreadyConvicted || verdict.isSelf) verdict
            else verdict.copy(signals = verdict.signals + RiskSignal.SIGNER_SIBLING)
        }
    }

    /** Packages declaring a service for [action], resolved once rather than per app. */
    private fun declarersOf(packages: PackageManager, action: String): Set<String> = try {
        @Suppress("DEPRECATION")
        packages.queryIntentServices(android.content.Intent(action), 0)
            .mapNotNullTo(mutableSetOf()) { it.serviceInfo?.packageName }
    } catch (_: Exception) {
        emptySet()
    }

    private fun activeAdmins(context: Context): Set<String> = try {
        context.getSystemService(DevicePolicyManager::class.java)
            ?.activeAdmins
            ?.mapTo(mutableSetOf()) { it.packageName }
            .orEmpty()
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * Who installed [pkg], or null when nothing could be attributed.
     *
     * Null is a distinct answer from "an installer we do not recognise": no attribution at all means
     * adb, a file manager, or a dropper that did not identify itself, which is a stronger signal than
     * a store nobody has heard of.
     */
    private fun installerOf(packages: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = packages.getInstallSourceInfo(pkg)
            source.installingPackageName ?: source.initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(pkg)
        }?.takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }

    private fun labelOf(packages: PackageManager, app: ApplicationInfo): String? = try {
        packages.getApplicationLabel(app).toString().takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }

    private companion object {

        const val PERMISSION_OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
        const val PERMISSION_BOOT = "android.permission.RECEIVE_BOOT_COMPLETED"
        const val PERMISSION_FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"

        const val ACCESSIBILITY_ACTION = "android.accessibilityservice.AccessibilityService"
        const val NOTIFICATION_LISTENER_ACTION =
            "android.service.notification.NotificationListenerService"

        /**
         * Installers that make an app "from a store" rather than sideloaded.
         *
         * Includes the major alternative stores as well as Play, because this app is itself
         * distributed outside Play and flagging every F-Droid install as suspicious would be both
         * wrong and hypocritical. `com.android.packageinstaller` and its successor are the system
         * installer - what a user sees when they open an APK deliberately - so those count as a real
         * attribution and score as [RiskSignal.SIDELOADED] rather than as unknown.
         */
        val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",
            "com.google.android.feedback",
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.aurora.store",
            "com.amazon.venezia",
            "com.sec.android.app.samsungapps",
            "com.huawei.appmarket",
            "com.xiaomi.mipicks",
            "com.oppo.market",
            "com.vivo.appstore",
            "com.heytap.market"
        )

        /** How long a newly installed app is treated as not yet having a behavioural record. */
        const val PROBATION_MILLIS = 24 * 60 * 60 * 1000L
    }
}
