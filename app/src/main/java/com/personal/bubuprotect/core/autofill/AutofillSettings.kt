package com.personal.bubuprotect.core.autofill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager

/**
 * Whether this app is the device's autofill provider, and how to ask to become one.
 *
 * There is no in-app switch for this and there should not be. Only one autofill service can be
 * active at a time, so turning this one on turns another one off - a decision that belongs to the
 * system's own picker, where the user can see what they are replacing. A toggle in this app's
 * settings would have to either lie about what it does or silently disable whatever they were using
 * before.
 *
 * So the settings row reports state and hands off. What the app owns is telling the truth about why
 * it is worth doing.
 */
object AutofillSettings {

    /**
     * Whether the platform offers autofill at all.
     *
     * False on devices where the feature is absent or has been stripped out - it is optional, and
     * some builds ship without the service that backs it. The settings row says so rather than
     * offering a button that opens nothing.
     */
    fun isSupported(context: Context): Boolean =
        manager(context)?.isAutofillSupported == true

    fun isEnabled(context: Context): Boolean =
        manager(context)?.hasEnabledAutofillServices() == true

    /**
     * The system picker, scoped to this package.
     *
     * The `package:` URI is what makes the dialog say "use Bubu Protect?" rather than dropping the
     * user into a list. Without it the intent still resolves, but they arrive somewhere generic and
     * have to find the app themselves.
     */
    fun requestEnableIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .setData(Uri.parse("package:${context.packageName}"))

    private fun manager(context: Context): AutofillManager? =
        try {
            context.getSystemService(AutofillManager::class.java)
        } catch (missing: Exception) {
            // Absent on devices without the feature, and `getSystemService` is documented to return
            // null there - but some OEM builds throw instead. Either way the answer is "no autofill".
            null
        }
}
