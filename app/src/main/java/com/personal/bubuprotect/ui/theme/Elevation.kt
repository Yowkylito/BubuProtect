package com.personal.bubuprotect.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Restrained depth for quiet luxury. Most hierarchy comes from tone and borders; shadows are saved
 * for surfaces that genuinely float.
 */
@Immutable
object BubuElevation {
    val paper: Dp = 0.dp
    val card: Dp = 1.dp
    val hero: Dp = 3.dp
    val floating: Dp = 7.dp
}
