package com.personal.bubuprotect.core.security

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * Window-level protections, applied once for the whole activity rather than toggled per screen.
 *
 * The previous build set `FLAG_SECURE` only while a password was on screen and cleared it on
 * dispose. That leaves two holes: the recents-screen snapshot is taken as the app goes to the
 * background, which can race the clear, and any screen that shows a *label* ("Bank Account") still
 * leaks the shape of the vault to a screen recorder. A password manager has no window worth
 * screenshotting, so the flag stays on for the process lifetime.
 */
object SecureWindow {

    /**
     * Turns on every screen-capture and overlay defence the platform offers.
     *
     * **`FLAG_SECURE`** is the big one. It makes the window unrecordable at the compositor, so it
     * covers the system screenshot, `MediaProjection` screen recording and casting, the recents
     * thumbnail, the accessibility `takeScreenshot` API, and mirroring to any display not marked
     * secure. Nothing in userspace can opt out of it, which is why it beats trying to detect
     * recorders.
     *
     * **`setHideOverlayWindows`** (API 31+) hides other apps' overlay windows while we are in front.
     * That is the tap-jacking defence: without it an overlay can sit over "Reveal" and harvest the
     * tap while the user believes they are dismissing a notification.
     *
     * **`filterTouchesWhenObscured`** covers API 26-30, where `setHideOverlayWindows` does not
     * exist. Rather than hiding the overlay it discards any touch that arrived through one, which
     * gets the same security outcome by a different route - a tap-jacked press simply does nothing.
     * It is set on the decor view, so the whole hierarchy below it inherits the check.
     */
    fun harden(activity: Activity) {
        val decor = activity.window.decorView

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.window.setHideOverlayWindows(true)
        } else {
            decor.filterTouchesWhenObscured = true
        }

        optOutOfTextHarvesting(decor)
    }

    /**
     * Closes the two text channels `FLAG_SECURE` does **not** cover.
     *
     * `FLAG_SECURE` protects *pixels*. It says nothing about the framework handing this app's text
     * to other system services as structured data, and two of those run by default:
     *
     *  - **Autofill.** The platform walks the view tree and ships an `AssistStructure` - field
     *    values included - to whichever autofill service is configured. That service is a
     *    third-party app. A password manager feeding every secret it displays to a *different*
     *    password manager is the exact inversion of the threat model, and it happens with no
     *    prompt and no visible sign.
     *  - **Content Capture.** Android streams on-screen text to the system content-capture service
     *    for features like Smart Actions and screenshot text detection. Same data, different pipe.
     *
     * Both are opted out at the decor view with `EXCLUDE_DESCENDANTS`, so the whole hierarchy is
     * covered and no individual field can be forgotten. The cost is that this app cannot be
     * autofilled *into* - which for a vault is the correct trade, not a regression.
     */
    private fun optOutOfTextHarvesting(decor: View) {
        decor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
    }
}
