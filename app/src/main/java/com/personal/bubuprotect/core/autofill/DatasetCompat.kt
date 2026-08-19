package com.personal.bubuprotect.core.autofill

import android.content.IntentSender
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi

/**
 * The two ways the autofill framework accepts a dataset, behind one call.
 *
 * API 33 replaced the `RemoteViews`-shaped constructors and setters with [Presentations] and
 * [Field]. The old ones still function - they are wrappers over the new ones - but they are
 * deprecated, and a security-relevant file has no business sitting on an API the platform has
 * already announced it is finished with. `minSdk` here is 26, so both paths have to exist.
 *
 * The split is confined to this file rather than repeated at each call site. There are three of
 * them, all on the path that puts a password into another app's text field, and three hand-written
 * version checks is three places for one of them to be written the wrong way round.
 */
internal object DatasetCompat {

    fun datasetBuilder(presentation: RemoteViews): Dataset.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dataset.Builder(presentations(presentation))
        } else {
            @Suppress("DEPRECATION")
            Dataset.Builder(presentation)
        }

    /**
     * @param value null for a dataset that authenticates - the framework wants to know which fields
     *   would be filled, and nothing more until the authentication returns a real dataset.
     */
    fun Dataset.Builder.putValue(id: AutofillId, value: AutofillValue?): Dataset.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setField(id, value?.let { Field.Builder().setValue(it).build() })
        } else {
            @Suppress("DEPRECATION")
            setValue(id, value)
        }

    fun FillResponse.Builder.putAuthentication(
        ids: Array<AutofillId>,
        sender: IntentSender,
        presentation: RemoteViews
    ): FillResponse.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setAuthentication(ids, sender, presentations(presentation))
        } else {
            @Suppress("DEPRECATION")
            setAuthentication(ids, sender, presentation)
        }

    /**
     * Only the menu presentation is set.
     *
     * [Presentations] can also carry an inline presentation, which is what draws a suggestion inside
     * the keyboard's strip, and a dialog presentation. Neither is filled in here: an inline
     * suggestion is rendered by the *IME*, so opting into it would put entry labels inside a
     * third-party keyboard's process. That is a trade worth making deliberately and with a setting
     * behind it, not as a side effect of migrating off a deprecated constructor.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun presentations(presentation: RemoteViews): Presentations =
        Presentations.Builder().setMenuPresentation(presentation).build()
}
