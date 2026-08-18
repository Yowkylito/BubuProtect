package com.personal.bubuprotect.core.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.view.accessibility.AccessibilityManager
import java.io.File

/**
 * Environment signals worth telling the user about before they type a master passphrase.
 *
 * Scope check, because this kind of code is routinely oversold: none of these are a security
 * boundary. Anything with root can defeat every check below, and a determined attacker patches them
 * out. What they are good for is the honest-mistake case - the vault has been restored onto a rooted
 * daily driver, or a debugger is attached - where the user would want to know that the process
 * memory holding their decrypted passwords is readable by other software on the device.
 *
 * So the result is advisory and shown as a banner. It never blocks unlock: a false positive that
 * locks someone out of their own passwords is a worse failure than the warning is a win.
 */
class IntegrityChecker {

    enum class Finding {
        /** A superuser binary is present - other processes may be able to read our memory. */
        ROOT_INDICATORS,

        /** A debugger is attached right now, and can read decrypted secrets straight out of the heap. */
        DEBUGGER_ATTACHED,

        /** Emulator or engineering build; hardware-backed key storage may be simulated, not real. */
        UNTRUSTED_BUILD,

        /**
         * An accessibility service is running.
         *
         * This is the one leak `FLAG_SECURE` cannot touch. Screen capture is blocked at the
         * compositor, but an accessibility service reads the *semantics tree*, not pixels - so it
         * can be handed the text of a revealed secret regardless. Android offers no way for an app
         * to opt out; granting the permission is the user's decision and it is absolute.
         *
         * Reported rather than acted on, because the overwhelmingly common cause is a legitimate
         * screen reader. The user is told what the capability implies and left to judge it.
         */
        ACCESSIBILITY_SERVICE_ACTIVE
    }

    /**
     * @param context application context only. A scan holding an Activity would outlive it via the
     *   ViewModel that caches the result.
     */
    fun scan(context: Context): Set<Finding> = buildSet {
        if (hasRootIndicators()) add(Finding.ROOT_INDICATORS)
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) add(Finding.DEBUGGER_ATTACHED)
        if (isUntrustedBuild()) add(Finding.UNTRUSTED_BUILD)
        if (hasActiveAccessibilityService(context)) add(Finding.ACCESSIBILITY_SERVICE_ACTIVE)
    }

    /**
     * Asks for services that can retrieve window content specifically, rather than for any enabled
     * service. A soft-keyboard switcher or a magnifier cannot read text and would only produce a
     * warning the user cannot act on.
     */
    private fun hasActiveAccessibilityService(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { service ->
                service.capabilities and
                    AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
            } == true
    }.getOrDefault(false)

    private fun hasRootIndicators(): Boolean =
        Build.TAGS?.contains("test-keys") == true || SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }

    private fun isUntrustedBuild(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("unknown") ||
            Build.FINGERPRINT.contains(":eng/") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.PRODUCT == "sdk" ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    private companion object {
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/system/bin/magisk",
            "/data/adb/magisk"
        )
    }
}
