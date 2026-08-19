package com.personal.bubuprotect.core.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.personal.bubuprotect.domain.model.DeviceFinding
import com.personal.bubuprotect.domain.model.DeviceProbe
import com.personal.bubuprotect.domain.model.DeviceScanReport
import com.personal.bubuprotect.domain.model.ProbeResult
import com.personal.bubuprotect.domain.model.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.Collections

/**
 * Asks the platform, ten different ways, what else on this phone could get at the vault.
 *
 * See [com.personal.bubuprotect.domain.model.RiskLevel] for why this is a capability audit rather
 * than a malware scanner, and what Android will and will not let an app find out.
 *
 * ### Every probe fails soft
 *
 * Each one is wrapped so that a manufacturer ROM that throws on a `Settings.Global` read, or a device
 * with no `DevicePolicyManager`, produces [ProbeResult.Unavailable] for that single question instead
 * of taking the whole report down. A security screen that shows nothing because one call threw is
 * worse than one that admits it could not answer one line - the user reads a blank screen as "clean".
 *
 * ### Why it is `suspend`
 *
 * Almost every probe blocks. Root detection stats files, resolving an app label reads resources out
 * of another APK, and [NetworkInterface.getNetworkInterfaces] walks the kernel's interface table.
 * Running that on the main thread would cost frames on a screen the user opened *because* they were
 * worried, so the whole pass moves to [Dispatchers.IO] and the caller gets a finished report.
 *
 * @param integrity reused rather than reimplemented, so root detection has exactly one definition in
 *   this app. It answers three of the probes here and the pre-unlock banner as well; having two sets
 *   of `su` paths that drift apart is how a check quietly stops working. Its fourth finding,
 *   `ACCESSIBILITY_SERVICE_ACTIVE`, is *not* reused: it is a boolean, which is the right shape for a
 *   one-line banner shown before unlock and the wrong shape here, where the whole point is naming
 *   which app holds the grant so the user can decide about it.
 */
class DeviceThreatScanner(
    private val integrity: IntegrityChecker,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * @param context application context only. The report outlives any Activity - it is cached in a
     *   ViewModel for the session - so holding one here would leak it.
     */
    suspend fun scan(context: Context): DeviceScanReport = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        val resolver = context.contentResolver
        val integrityFindings = integrity.scan(context)

        val findings = listOf(
            DeviceFinding(DeviceProbe.SCREEN_READERS, screenReaders(context, packages)),
            DeviceFinding(DeviceProbe.NOTIFICATION_READERS, notificationReaders(context, packages)),
            DeviceFinding(DeviceProbe.DEVICE_ADMINS, deviceAdmins(context, packages)),
            DeviceFinding(
                DeviceProbe.ROOT_ACCESS,
                integrityFindings.flagIf(
                    IntegrityChecker.Finding.ROOT_INDICATORS,
                    RiskLevel.CRITICAL
                )
            ),
            DeviceFinding(DeviceProbe.DEBUG_BRIDGE, debugBridge(resolver)),
            DeviceFinding(
                DeviceProbe.DEBUGGER_ATTACHED,
                integrityFindings.flagIf(
                    IntegrityChecker.Finding.DEBUGGER_ATTACHED,
                    RiskLevel.CRITICAL
                )
            ),
            DeviceFinding(DeviceProbe.NETWORK_INTERCEPTION, networkInterception()),
            DeviceFinding(DeviceProbe.SCREEN_LOCK, screenLock(context)),
            DeviceFinding(DeviceProbe.INSTALL_SOURCE, installSource(context, packages)),
            DeviceFinding(
                DeviceProbe.UNTRUSTED_BUILD,
                integrityFindings.flagIf(
                    IntegrityChecker.Finding.UNTRUSTED_BUILD,
                    RiskLevel.INFO
                )
            )
        )

        DeviceScanReport(findings = findings, checkedAt = clock())
    }

    private fun Set<IntegrityChecker.Finding>.flagIf(
        finding: IntegrityChecker.Finding,
        level: RiskLevel
    ): ProbeResult = if (finding in this) ProbeResult.Flagged(level) else ProbeResult.Clear

    // --- Capability probes ------------------------------------------------------------------------

    /**
     * Services that can read what is on screen as text.
     *
     * Filtered on [AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT] rather than on
     * "any enabled service", because a keyboard switcher or a magnifier cannot read a revealed
     * password and listing it would produce a warning with no action behind it. Once a warning
     * contains things the user is supposed to ignore, they ignore all of it.
     *
     * Always [RiskLevel.CRITICAL] when non-empty, even though the usual cause is a screen reader the
     * user installed on purpose. The severity describes the *capability*, not a guess about intent -
     * and the user can silence a service they recognise, which is what the acknowledgement set is
     * for.
     */
    private fun screenReaders(context: Context, packages: PackageManager): ProbeResult = probe {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return@probe ProbeResult.Unavailable("This device has no accessibility manager.")

        val readers = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
            .filter { service ->
                service.capabilities and
                    AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
            }
            .map { service -> service.describe(packages) }
            .distinct()

        if (readers.isEmpty()) {
            ProbeResult.Clear
        } else {
            ProbeResult.Flagged(RiskLevel.CRITICAL, readers)
        }
    }

    /**
     * Apps that can read the notification shade.
     *
     * Split by system flag: a preinstalled listener is the phone's own wearable or car integration,
     * and colouring that the same as an unknown third-party app would cry wolf on most devices. One
     * non-system listener is enough to make the whole row a warning.
     */
    private fun notificationReaders(context: Context, packages: PackageManager): ProbeResult = probe {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .filterNot { it == context.packageName }

        if (enabled.isEmpty()) return@probe ProbeResult.Clear

        val hasThirdParty = enabled.any { !packages.isPreinstalled(it) }
        ProbeResult.Flagged(
            level = if (hasThirdParty) RiskLevel.WARNING else RiskLevel.INFO,
            details = enabled.map { packages.labelOf(it) }.distinct().sorted()
        )
    }

    private fun deviceAdmins(context: Context, packages: PackageManager): ProbeResult = probe {
        val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return@probe ProbeResult.Unavailable("This device has no device-policy manager.")

        val admins: List<ComponentName> = policy.activeAdmins.orEmpty()
        if (admins.isEmpty()) {
            ProbeResult.Clear
        } else {
            ProbeResult.Flagged(
                RiskLevel.WARNING,
                admins.map { packages.labelOf(it.packageName) }.distinct().sorted()
            )
        }
    }

    // --- Device-state probes ----------------------------------------------------------------------

    /**
     * The three debugging switches, as one row with an escalating level.
     *
     * One row rather than three because they live behind the same Settings screen and the same
     * decision - "am I still developing on this phone" - so three separate alarms would read as three
     * problems where there is one. The level tracks the sharpest of them: wireless debugging listens
     * on the network, which is a different kind of exposure from a cable the user can see.
     *
     * `adb_wifi_enabled` has no public constant, so the key is spelled out. It has been the setting's
     * name since it shipped in Android 11; a rename would make this probe silently return false,
     * which is the fail-soft direction rather than a crash.
     */
    private fun debugBridge(resolver: ContentResolver): ProbeResult = probe {
        val wireless = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            resolver.globalFlag(SETTING_ADB_WIFI)
        val usb = resolver.globalFlag(Settings.Global.ADB_ENABLED)
        val developerOptions = resolver.globalFlag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)

        val details = buildList {
            if (wireless) add("Wireless debugging is on")
            if (usb) add("USB debugging is on")
            if (developerOptions && !wireless && !usb) add("Developer options are on")
        }

        when {
            wireless -> ProbeResult.Flagged(RiskLevel.CRITICAL, details)
            usb -> ProbeResult.Flagged(RiskLevel.WARNING, details)
            developerOptions -> ProbeResult.Flagged(RiskLevel.INFO, details)
            else -> ProbeResult.Clear
        }
    }

    /**
     * A VPN tunnel or a proxy in front of this app's only outbound request.
     *
     * ### Why no `ACCESS_NETWORK_STATE`
     *
     * `ConnectivityManager` would answer this more precisely, and it needs a permission. This app's
     * manifest holds three permissions and each one is justified in a comment; adding a fourth so a
     * *advisory* row can be slightly more accurate is the wrong trade, especially when the same
     * question can be asked from inside the process. So:
     *
     *  - VPN is inferred from the interface table. A `tun`/`ppp`/`ipsec`/`wg` device that is up is how
     *    every Android VPN appears, because `VpnService` is implemented with exactly those. It is a
     *    heuristic - the copy on screen says "looks like", not "is".
     *  - The proxy question is asked of [ProxySelector] using the one host this app ever contacts, so
     *    the answer is literally "would my breach lookup go through someone else's server" rather
     *    than a guess about the system's global config.
     */
    private fun networkInterception(): ProbeResult = probe {
        val details = buildList {
            if (hasTunnelInterface()) add("A VPN tunnel is active")
            if (hasProxy()) add("Traffic from this app goes through a proxy")
        }

        if (details.isEmpty()) ProbeResult.Clear else ProbeResult.Flagged(RiskLevel.WARNING, details)
    }

    private fun hasTunnelInterface(): Boolean = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
        Collections.list(interfaces).any { candidate ->
            candidate.isUp && TUNNEL_PREFIXES.any { prefix -> candidate.name.startsWith(prefix) }
        }
    }.getOrDefault(false)

    private fun hasProxy(): Boolean = runCatching {
        ProxySelector.getDefault()
            ?.select(URI("https://$BREACH_LOOKUP_HOST"))
            ?.any { it.type() != Proxy.Type.DIRECT } == true
    }.getOrDefault(false)

    /**
     * Whether the phone itself has a lock.
     *
     * [RiskLevel.WARNING] rather than critical, and the distinction is real: the vault is sealed
     * behind the master passphrase either way, so no lock screen does not hand anybody the secrets.
     * What it does is remove the outer door - and take fingerprint unlock with it, since a biometric
     * needs a device credential to fall back to.
     */
    private fun screenLock(context: Context): ProbeResult = probe {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return@probe ProbeResult.Unavailable("This device has no keyguard.")

        if (keyguard.isDeviceSecure) {
            ProbeResult.Clear
        } else {
            ProbeResult.Flagged(RiskLevel.WARNING, listOf("No PIN, pattern or password is set"))
        }
    }

    /**
     * Where this copy of the app came from.
     *
     * Deliberately only [RiskLevel.INFO]. It cannot distinguish "the owner built this from source"
     * from "somebody installed a repackaged Bubu Protect", and treating a self-built debug install -
     * which reports no installer at all - as a threat would put a permanent red row on the developer's
     * own screen and teach them to ignore the list. What it *is* good for is the case where the user
     * did not install it themselves and had no idea.
     */
    private fun installSource(context: Context, packages: PackageManager): ProbeResult = probe {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(context.packageName)
        }

        when {
            installer == null ->
                ProbeResult.Flagged(RiskLevel.INFO, listOf("Installed directly, not from a store"))

            installer in TRUSTED_INSTALLERS -> ProbeResult.Clear

            else -> ProbeResult.Flagged(
                RiskLevel.INFO,
                listOf("Installed by ${packages.labelOf(installer)}")
            )
        }
    }

    // --- Plumbing --------------------------------------------------------------------------------

    /**
     * Turns any throw into [ProbeResult.Unavailable].
     *
     * The message is fixed text, never the throwable's own: an exception from `PackageManager` can
     * carry a package name, and this string is rendered on screen.
     */
    private fun probe(block: () -> ProbeResult): ProbeResult =
        runCatching(block).getOrElse {
            ProbeResult.Unavailable("This version of Android would not answer.")
        }

    private fun ContentResolver.globalFlag(key: String): Boolean =
        runCatching { Settings.Global.getInt(this, key, 0) == 1 }.getOrDefault(false)

    /** Service label first ("TalkBack"), then the app's, then the bare package as a last resort. */
    private fun AccessibilityServiceInfo.describe(packages: PackageManager): String {
        val fromService = runCatching { resolveInfo?.loadLabel(packages)?.toString() }.getOrNull()
        if (!fromService.isNullOrBlank()) return fromService

        val packageName = id?.substringBefore('/')?.takeIf { it.isNotBlank() }
        return packageName?.let { packages.labelOf(it) } ?: "An accessibility service"
    }

    /**
     * A human-readable name for a package, falling back to the package itself.
     *
     * Resolvable because the manifest declares narrow `<queries>` filters for the three service types
     * this scanner reports on - not `QUERY_ALL_PACKAGES`. When a package is outside those filters the
     * lookup throws and the user sees the raw id, which is still better than an empty row.
     */
    private fun PackageManager.labelOf(packageName: String): String = runCatching {
        getApplicationLabel(getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun PackageManager.isPreinstalled(packageName: String): Boolean = runCatching {
        val flags = getApplicationInfo(packageName, 0).flags
        flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }.getOrDefault(false)

    private companion object {
        /** @see android.provider.Settings.Global (the constant itself is `@hide`). */
        const val SETTING_ADB_WIFI = "adb_wifi_enabled"

        /** Mirrors `PwnedPasswordChecker`'s endpoint - the only host this app ever contacts. */
        const val BREACH_LOOKUP_HOST = "api.pwnedpasswords.com"

        val TUNNEL_PREFIXES = listOf("tun", "ppp", "ipsec", "wg")

        /** Play Store, and the package that installs Play-delivered updates. */
        val TRUSTED_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")
    }
}
