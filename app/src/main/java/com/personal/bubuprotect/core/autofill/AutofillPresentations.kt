package com.personal.bubuprotect.core.autofill

import android.content.Context
import android.widget.RemoteViews
import com.personal.bubuprotect.R
import com.personal.bubuprotect.domain.model.ItemKind

/**
 * The rows the system draws in its autofill dropdown.
 *
 * `RemoteViews` rather than Compose, because these are inflated and drawn by the system UI process
 * and only the classic widget set survives that trip. Everything the row needs is baked in here -
 * there is no theme to inherit on the other side.
 *
 * ### What a row is allowed to say
 *
 * The title is the entry's label and the subtitle is its username. Neither is a secret, but both are
 * shown *on top of another app's window*, so the rule is that a row never contains anything the user
 * would not be willing to have on screen while someone is looking over their shoulder. No password,
 * no partial password, no card digits.
 */
internal object AutofillPresentations {

    fun row(context: Context, title: String, subtitle: String, kind: ItemKind): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_row).apply {
            setTextViewText(R.id.autofill_row_title, title)
            setTextViewText(R.id.autofill_row_subtitle, subtitle)
            setImageViewResource(R.id.autofill_row_icon, kind.autofillIcon)
        }

    /**
     * Mirrors `ItemKind.iconRes`, which cannot be reused here: that one is a Compose-facing
     * extension in the ui layer, and this file has to stay loadable from a service with no
     * composition. The two lists are small and both derive from the same drawables.
     */
    private val ItemKind.autofillIcon: Int
        get() = when (this) {
            ItemKind.LOGIN -> R.drawable.ic_kind_login
            ItemKind.CARD -> R.drawable.ic_kind_card
            ItemKind.NOTE -> R.drawable.ic_kind_note
            ItemKind.IDENTITY -> R.drawable.ic_kind_identity
            ItemKind.WIFI -> R.drawable.ic_kind_wifi
        }
}
