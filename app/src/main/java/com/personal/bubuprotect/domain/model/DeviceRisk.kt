package com.personal.bubuprotect.domain.model

/**
 * What Bubu found out about the phone the vault is sitting on.
 *
 * ### The honest scope, stated first
 *
 * This is **not** an antivirus, and it deliberately does not pretend to be one. Modern Android will
 * not let an ordinary app do the thing people picture when they say "scan for spyware":
 *
 *  - `getRunningAppProcesses()` has returned only the caller's own process since Android 5, so there
 *    is no process list to walk.
 *  - Nothing here enumerates installed packages. The app does now hold `QUERY_ALL_PACKAGES`, for
 *    [com.personal.bubuprotect.core.shield.AppRiskScanner] - see the argument for it in the manifest -
 *    but this scanner deliberately does not use it. Its probes read capability *grants*, which Android
 *    hands over without any declaration, and widening them to walk the full app list would blur the
 *    line between the two features: this one audits what could reach the vault and names nothing, the
 *    shield accuses specific apps and has to show its evidence.
 *  - Nothing can read another app's memory, files, or traffic without root.
 *
 * So the question is turned around. Instead of "which app here is malware" - unanswerable - it asks
 * **"what on this phone has been handed a capability that could reach my vault, and what state is
 * this device in?"** Every probe below is a grant the user made or a setting they flipped, readable
 * through public API with no restricted permission, and every one of them is a real path a
 * credential thief uses in the wild.
 *
 * That is a narrower claim than "your phone is clean", and it is the only one that would be true.
 * The screen says so out loud rather than burying it.
 */
enum class RiskLevel {
    /** Worth knowing, not worth acting on tonight. A sideloaded build, an emulator. */
    INFO,

    /**
     * A capability that *could* be abused and commonly has a legitimate owner - a smartwatch app
     * reading notifications, a work-profile MDM, a VPN the user turned on themselves.
     */
    WARNING,

    /**
     * A path to the decrypted contents of the vault, live, right now. Root, an attached debugger,
     * wireless ADB, or anything that can read the text of a revealed secret.
     */
    CRITICAL
}

/**
 * One question Bubu knows how to ask about the device.
 *
 * Carries no user-facing copy on purpose - the wording lives beside the screen that shows it, the way
 * [com.personal.bubuprotect.core.security.IntegrityChecker.Finding] does, so the scanner stays a
 * thing that can be reasoned about without reading marketing text.
 */
enum class DeviceProbe {

    /**
     * Accessibility services that can retrieve window content.
     *
     * The most important row on the screen. `FLAG_SECURE` blocks the compositor, so nothing can
     * screenshot or record this app - but an accessibility service is handed the *semantics tree*,
     * not pixels, and there is no flag an app can set to opt out of that. A revealed password is
     * plain text to anything holding this grant. It is also how essentially every Android banking
     * trojan of the last decade has worked.
     */
    SCREEN_READERS,

    /**
     * Apps allowed to read the notification shade.
     *
     * The vault posts nothing, so this is not a direct leak of a stored secret. It leaks the *other
     * half* of an account: one-time codes and sign-in alerts arrive as notifications, and a leaked
     * password plus a readable OTP is a complete takeover.
     */
    NOTIFICATION_READERS,

    /**
     * Active device administrators.
     *
     * Legitimate on a work phone. Also the standard persistence trick for stalkerware, because an
     * active admin cannot be uninstalled until it is deactivated - which is exactly why an app the
     * user does not recognise holding it is worth a look.
     */
    DEVICE_ADMINS,

    /** A superuser binary is present. Every other protection in this app is downstream of this. */
    ROOT_ACCESS,

    /**
     * USB debugging, wireless debugging, or developer options.
     *
     * Wireless debugging is the sharp one: it accepts connections over the network rather than
     * needing a cable in the user's hand, so a phone left with it enabled on shared Wi-Fi is a phone
     * anyone on that network can try to pair with.
     */
    DEBUG_BRIDGE,

    /** A debugger is attached to this process right now and can read the heap. */
    DEBUGGER_ATTACHED,

    /**
     * A VPN tunnel or an HTTP proxy standing between this app and the network.
     *
     * Scoped honestly: the vault never leaves the device, so this cannot expose a stored secret. What
     * it touches is the one request this app makes - the breach lookup - and a proxy that sees those
     * requests learns five-character hash prefixes. Much smaller than a password, not nothing.
     */
    NETWORK_INTERCEPTION,

    /** No PIN, pattern or password on the lock screen. */
    SCREEN_LOCK,

    /** Where this copy of Bubu Protect came from. */
    INSTALL_SOURCE,

    /** Emulator or engineering build - hardware-backed key storage may be simulated. */
    UNTRUSTED_BUILD
}

/** What one probe came back with. */
sealed interface ProbeResult {

    /** Asked, answered, nothing there. */
    data object Clear : ProbeResult

    /**
     * @param details what specifically was found - app names for the capability probes, setting names
     *   for the device-state ones. A package name is only used when no label could be resolved,
     *   because "com.a.b.c has accessibility access" is not actionable advice.
     */
    data class Flagged(
        val level: RiskLevel,
        val details: List<String> = emptyList()
    ) : ProbeResult

    /**
     * The platform declined to answer, or this Android version cannot.
     *
     * A third state rather than folding into [Clear], for the same reason
     * [BreachVerdict.UNCHECKED] exists: "nothing found" and "could not look" are different claims,
     * and a screen that paints them the same colour tells the user their device was verified when it
     * was not.
     */
    data class Unavailable(val why: String) : ProbeResult
}

data class DeviceFinding(
    val probe: DeviceProbe,
    val result: ProbeResult
) {
    val level: RiskLevel? get() = (result as? ProbeResult.Flagged)?.level

    val details: List<String>
        get() = (result as? ProbeResult.Flagged)?.details ?: emptyList()

    val isFlagged: Boolean get() = result is ProbeResult.Flagged

    /**
     * Identity of *this exact finding*, for the acknowledgement set.
     *
     * ### Why it is a hash and not the list itself
     *
     * Acknowledgement has to be self-invalidating. "I know, that one is mine" must silence the
     * screen reader the user chose and must **not** silence a second one appearing next week - the
     * same property [BreachStatus] gets by comparing `acknowledgedAt` against `secretUpdatedAt`.
     * Keying on the details means any change to the set of offenders mints a new key and the warning
     * returns by itself.
     *
     * They are hashed rather than stored verbatim because the acknowledgement set lives in plain
     * `SharedPreferences`, and a readable list of which apps hold accessibility access is a small
     * inventory of the user's device that this app has no reason to write down. `String.hashCode` is
     * specified by the language, so the key is stable across processes and upgrades; it is a change
     * detector rather than a security boundary, and a collision costs at worst one warning staying
     * quiet.
     */
    val fingerprint: String
        get() = "${probe.name}:${details.sorted().joinToString("|").hashCode()}"
}

/**
 * The result of one pass over every probe.
 *
 * Holds *all* findings, clear ones included, rather than only the bad news. That is what lets the
 * screen show a denominator - "10 checks, 9 clear" - so a clean result reads as something that was
 * looked for rather than an empty list that might mean nothing ran.
 */
data class DeviceScanReport(
    val findings: List<DeviceFinding>,
    /** Wall clock of the scan, or 0 for [Empty]. */
    val checkedAt: Long
) {
    /** Worst first, so the screen's first card is the one that matters most. */
    val flagged: List<DeviceFinding> =
        findings.filter { it.isFlagged }.sortedByDescending { it.level?.ordinal ?: -1 }

    val clear: List<DeviceFinding> get() = findings.filter { it.result is ProbeResult.Clear }

    val unavailable: List<DeviceFinding>
        get() = findings.filter { it.result is ProbeResult.Unavailable }

    val worst: RiskLevel? get() = flagged.firstOrNull()?.level

    val criticalCount: Int get() = flagged.count { it.level == RiskLevel.CRITICAL }

    val hasRun: Boolean get() = checkedAt > 0L

    /**
     * Everything the user has not already waved away.
     *
     * @param acknowledged [DeviceFinding.fingerprint] values the user has accepted.
     */
    fun outstanding(acknowledged: Set<String>): List<DeviceFinding> =
        flagged.filterNot { it.fingerprint in acknowledged }

    companion object {
        val Empty = DeviceScanReport(findings = emptyList(), checkedAt = 0L)
    }
}
