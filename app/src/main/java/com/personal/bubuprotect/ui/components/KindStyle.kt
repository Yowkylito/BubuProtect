package com.personal.bubuprotect.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.R
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.ui.theme.AccentPair
import com.personal.bubuprotect.ui.theme.bubu

/**
 * How each [ItemKind] looks.
 *
 * Kept out of the domain module on purpose: a drawable id and a `Color` are presentation, and the
 * data layer must stay usable without a Compose runtime. The mapping lives here, in one place, so
 * the list row, the filter chip, the editor picker and the detail header cannot disagree about what
 * a card looks like.
 */
@get:DrawableRes
val ItemKind.iconRes: Int
    get() = when (this) {
        ItemKind.LOGIN -> R.drawable.ic_kind_login
        ItemKind.CARD -> R.drawable.ic_kind_card
        ItemKind.NOTE -> R.drawable.ic_kind_note
        ItemKind.IDENTITY -> R.drawable.ic_kind_identity
        ItemKind.WIFI -> R.drawable.ic_kind_wifi
    }

@Composable
@ReadOnlyComposable
fun ItemKind.accent(): AccentPair = with(MaterialTheme.bubu) {
    when (this@accent) {
        ItemKind.LOGIN -> login
        ItemKind.CARD -> card
        ItemKind.NOTE -> note
        ItemKind.IDENTITY -> identity
        ItemKind.WIFI -> wifi
    }
}

/**
 * The kind's icon in its accent disc.
 *
 * Decorative by default: every place this appears, the kind is already named in adjacent text, and a
 * TalkBack user does not need "Login icon, Login".
 */
@Composable
fun KindBadge(
    kind: ItemKind,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDescription: String? = null
) {
    val accent = kind.accent()
    Box(
        modifier = modifier
            .size(size)
            .background(accent.container, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(kind.iconRes),
            contentDescription = contentDescription,
            tint = accent.content,
            modifier = Modifier.size(size * 0.52f)
        )
    }
}
