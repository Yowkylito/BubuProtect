package com.personal.bubuprotect.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Chunky, fully-rounded geometry. Bubu and Dudu have no straight lines on them, and a 4dp corner
 * next to a round bear looks like a bug rather than a choice.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** Buttons and filter chips. Pill shapes read as "tappable" without needing a border. */
val PillShape = RoundedCornerShape(percent = 50)
