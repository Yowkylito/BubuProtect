package com.personal.bubuprotect.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Chunky, fully-rounded geometry. Bubu and Dudu have no straight lines on them, and a 4dp corner
 * next to a round bear looks like a bug rather than a choice.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Buttons and filter chips. Pill shapes read as "tappable" without needing a border. */
val PillShape = RoundedCornerShape(percent = 50)

/** Large editorial surface used for the one visual anchor on a screen. */
val HeroCardShape = RoundedCornerShape(30.dp)

/**
 * Squircle used for kind badges and small tiles.
 *
 * A circle next to a pill button looks like two different products; 32% corners sit between a
 * circle and a rounded rect so badges, kind cards and icon wells share one silhouette.
 */
val SquircleShape = RoundedCornerShape(percent = 32)
