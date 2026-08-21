package com.personal.bubuprotect.core.shield.recorder

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.personal.bubuprotect.domain.model.ShieldEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Names the app that just drew a window over another one.
 *
 * ### Why this exists at all, given what the device check says about accessibility services
 *
 * [com.personal.bubuprotect.domain.model.DeviceProbe.SCREEN_READERS] reports an active accessibility
 * service as a critical finding, and that finding is right - such a service is handed the semantics
 * tree of every screen, which is the one leak `FLAG_SECURE` cannot close. It applies to this service
 * as much as to any other, and BubuShield lists itself in its own report rather than pretending
 * otherwise.
 *
 * It is here anyway because [getWindows] is the only API on Android that attributes a window to its
 * owner. `PackageManager` can say which apps *hold* `SYSTEM_ALERT_WINDOW`; nothing else can say which
 * one is using it right now. Without this, the shield is a permission scorer.
 *
 * The grant is narrowed as far as the platform allows: `canRetrieveWindowContent="false"` in
 * `res/xml/shield_accessibility.xml` means this service is never handed the text of any screen. It
 * receives window identity, geometry and ownership. There is no path from here to a password field's
 * contents, and the recorder has no field to put one in.
 *
 * ### How an overlay is distinguished from an app switch
 *
 * Both look like a new window on top. The difference is what is *underneath*: switching apps replaces
 * the window stack, while an overlay leaves the previous app's window in place below the new one. So
 * two application windows with different owners, stacked, is the observation - and the one below is
 * recorded as well, because "over Chrome" is the part of the accusation the user can verify.
 *
 * Size is the second filter. Chat heads, screen-recorder buttons and volume sliders are all legitimate
 * overlays, and they are small. Only a window covering most of the display is recorded as
 * [com.personal.bubuprotect.domain.model.RiskSignal.DREW_OVERLAY].
 */
class ShieldAccessibilityService : AccessibilityService(), KoinComponent {

    private val recorder: FlightRecorder by inject()

    /**
     * Last overlay recorded per package, to collapse bursts.
     *
     * A single interstitial produces several window events as it animates in, and recording each one
     * would inflate the overlay count on the evidence card by an order of magnitude - which matters,
     * because that count is the number shown to the user as the reason to uninstall something. An
     * inflated count is a dishonest argument even when the conclusion is right.
     */
    private val lastRecordedAt = mutableMapOf<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The event itself is not read. It is only a signal that the window stack moved; the stack is
        // then examined directly, because an event carries one package and the question needs two.
        val stack = applicationWindows()
        if (stack.size < 2) return

        val (top, beneath) = stack[0] to stack[1]
        if (top.packageName == beneath.packageName) return
        if (top.packageName == packageName) return
        if (isSystemSurface(top.packageName)) return

        val now = System.currentTimeMillis()
        val previous = lastRecordedAt[top.packageName]
        if (previous != null && now - previous < BURST_WINDOW_MILLIS) return
        lastRecordedAt[top.packageName] = now

        recorder.record(
            ShieldEvent.OverlayDrawn(
                packageName = top.packageName,
                at = now,
                overWhat = beneath.packageName.takeUnless(::isSystemSurface),
                fullScreen = top.coversMostOfScreen
            )
        )
    }

    /**
     * Application windows, topmost first.
     *
     * Filtered to [AccessibilityWindowInfo.TYPE_APPLICATION] so the status bar, navigation bar, IME
     * and magnifier - all of which sit above everything by design - cannot be mistaken for an app
     * covering another app.
     *
     * `getWindows()` returns an empty list until the service is bound and
     * `flagRetrieveInteractiveWindows` is in effect, and can throw on some OEM builds during teardown.
     * Both produce an empty list, which reads as "nothing observed" rather than taking the service
     * down - a crashed accessibility service is silently disabled by the system, so a throw here would
     * cost the user the feature with no way to tell.
     */
    private fun applicationWindows(): List<WindowFacts> = try {
        windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull(::factsOf)
            .sortedByDescending(WindowFacts::layer)
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Reduces a window to the three things worth knowing, and drops the reference.
     *
     * The owning package comes from the window's root node. That node is the only place ownership is
     * recorded, and reading `packageName` off it does not read any content - which is what keeps this
     * compatible with `canRetrieveWindowContent="false"`.
     */
    private fun factsOf(window: AccessibilityWindowInfo): WindowFacts? {
        val owner = window.root?.packageName?.toString()?.takeIf(String::isNotBlank) ?: return null

        val bounds = Rect().also(window::getBoundsInScreen)
        val metrics = resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        val windowArea = bounds.width().toLong() * bounds.height().toLong()

        return WindowFacts(
            packageName = owner,
            layer = window.layer,
            coversMostOfScreen = screenArea > 0 &&
                windowArea * 100 >= screenArea * FULL_SCREEN_PERCENT
        )
    }

    /**
     * System UI and the launcher.
     *
     * Excluded from being *accused*, and from being named as the thing that was covered - "an ad was
     * drawn over your launcher" is true of every app opening normally, so it would turn every app
     * launch into an overlay report.
     *
     * Matched by prefix rather than against a fixed list because OEMs ship their own launcher and
     * system UI packages, and a hardcoded list would silently stop working on half of them.
     */
    private fun isSystemSurface(candidate: String): Boolean =
        SYSTEM_SURFACE_HINTS.any { candidate.contains(it, ignoreCase = true) }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        lastRecordedAt.clear()
        super.onDestroy()
    }

    private data class WindowFacts(
        val packageName: String,
        val layer: Int,
        val coversMostOfScreen: Boolean
    )

    private companion object {

        /**
         * Share of the display a window must cover to count as an overlay.
         *
         * 70% rather than a higher figure because interstitial ads routinely leave the status bar and
         * a margin uncovered, and a threshold set at near-full-screen would miss them. Well above
         * anything a floating control occupies.
         */
        const val FULL_SCREEN_PERCENT = 70

        /** One interstitial animating in produces several window events. Collapse them. */
        const val BURST_WINDOW_MILLIS = 2_000L

        val SYSTEM_SURFACE_HINTS = listOf(
            "com.android.systemui",
            "com.android.launcher",
            "launcher",
            "trebuchet",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "android.inputmethod",
            "inputmethod"
        )
    }
}
