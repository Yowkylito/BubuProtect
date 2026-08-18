package com.personal.bubuprotect.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Bubu & Dudu palette.
 *
 * Warm, papery and low-contrast-by-default, because this app is looked at in bed and on a bus, and a
 * vault that glows white is a vault that gets read over a shoulder. Every accent is desaturated
 * enough to sit next to the cream base without vibrating, and each one is paired with a foreground
 * that clears 4.5:1 on the surface it is used on.
 */

// --- Paper -------------------------------------------------------------------------------------

/** The base cream. Everything else is tuned against this. */
val CreamLight = Color(0xFFF3EFE4)

/** One step down from [CreamLight]; used for cards so they read as raised without a hard shadow. */
val CreamDeep = Color(0xFFE9E3D4)

val Olive = Color(0xFF5A5C4E)
val Sage = Color(0xFFB5B8A9)

// --- Ink ---------------------------------------------------------------------------------------

val Cocoa = Color(0xFF4B4237)
val Taupe = Color(0xFF8B8175)

// --- The characters ----------------------------------------------------------------------------

/** Bubu, the brown bear. The app's primary accent. */
val BubuTan = Color(0xFFCB9B85)
val BubuBlush = Color(0xFFE9C4B4)

/** Dudu, the panda. Used where something needs to feel serious rather than sweet. */
val DuduInk = Color(0xFF3A3A3C)
val DuduSnow = Color(0xFFFAF7F2)

// --- Signals -----------------------------------------------------------------------------------

val LeafGreen = Color(0xFF6F8F62)
val HoneyGold = Color(0xFFC79233)
val SkyDenim = Color(0xFF6E8CA0)
val PlumSoft = Color(0xFF8B6F8E)

val ErrorRust = Color(0xFF9E3B2C)
val ErrorSand = Color(0xFFE8A598)

// --- Night -------------------------------------------------------------------------------------

val NightOlive = Color(0xFF2A2B25)
val NightSurface = Color(0xFF35362E)
val NightSurfaceHigh = Color(0xFF41433A)
