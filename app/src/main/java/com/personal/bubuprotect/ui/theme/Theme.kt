package com.personal.bubuprotect.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Dynamic colour is deliberately not used. Material You would pull the scheme from the user's
 * wallpaper, which on a screen full of text fields and reveal buttons means contrast that changes
 * out from under us - and the warm Bubu & Dudu palette is the app's identity, not a placeholder.
 */
private val LightScheme = lightColorScheme(
    primary = Olive,
    onPrimary = CreamLight,
    primaryContainer = BubuBlush,
    onPrimaryContainer = Cocoa,
    secondary = BubuTan,
    onSecondary = Color.White,
    secondaryContainer = CreamDeep,
    onSecondaryContainer = Cocoa,
    tertiary = LeafGreen,
    onTertiary = Color.White,
    background = CreamLight,
    onBackground = Cocoa,
    surface = CreamLight,
    onSurface = Cocoa,
    surfaceVariant = CreamDeep,
    onSurfaceVariant = Taupe,
    surfaceContainer = CreamDeep,
    surfaceContainerHigh = Color(0xFFE2DBC9),
    outline = Taupe,
    outlineVariant = Sage,
    error = ErrorRust,
    onError = Color.White,
    errorContainer = Color(0xFFF6DCD5),
    onErrorContainer = Color(0xFF6E2418),
    scrim = Cocoa
)

private val DarkScheme = darkColorScheme(
    primary = Sage,
    onPrimary = NightOlive,
    primaryContainer = Color(0xFF54382E),
    onPrimaryContainer = BubuBlush,
    secondary = BubuTan,
    onSecondary = NightOlive,
    secondaryContainer = NightSurfaceHigh,
    onSecondaryContainer = CreamLight,
    tertiary = Color(0xFF9FBE92),
    onTertiary = NightOlive,
    background = NightOlive,
    onBackground = CreamLight,
    surface = NightSurface,
    onSurface = CreamLight,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = Sage,
    surfaceContainer = NightSurfaceHigh,
    surfaceContainerHigh = Color(0xFF4B4D42),
    outline = Sage,
    outlineVariant = Olive,
    error = ErrorSand,
    onError = NightOlive,
    errorContainer = Color(0xFF5B2419),
    onErrorContainer = ErrorSand,
    scrim = Color.Black
)

/**
 * Accents Material's scheme has nowhere to put.
 *
 * An item kind's colour is not "primary" or "tertiary" - there are five of them and they are peers.
 * Hanging them off a [staticCompositionLocalOf] keeps them theme-aware without abusing the M3 roles,
 * and `static` rather than `compositionLocalOf` because the value changes once (on a theme switch)
 * and never during normal use: a static local skips the read-tracking that a changing local needs.
 *
 * Every [AccentPair] is a container plus the ink that goes *on* it, chosen together so no call site
 * has to guess whether the accent is light enough to write on.
 */
@Immutable
data class AccentPair(val container: Color, val content: Color)

@Immutable
data class BubuColors(
    val login: AccentPair,
    val card: AccentPair,
    val note: AccentPair,
    val identity: AccentPair,
    val wifi: AccentPair,
    /** Password-strength ramp. Also used for the lockout countdown. */
    val weak: Color,
    val fair: Color,
    val strong: Color,
    /** The soft disc the mascots sit on. */
    val backdrop: Color
)

private val LightBubuColors = BubuColors(
    login = AccentPair(BubuBlush, Color(0xFF8A5240)),
    card = AccentPair(Color(0xFFD3E0CB), Color(0xFF3F5C34)),
    note = AccentPair(Color(0xFFF0DFB8), Color(0xFF7A5410)),
    identity = AccentPair(Color(0xFFE2D3E4), Color(0xFF5A4260)),
    wifi = AccentPair(Color(0xFFCFDDE6), Color(0xFF35546A)),
    weak = ErrorRust,
    fair = HoneyGold,
    strong = LeafGreen,
    backdrop = Color(0xFFE7E0CF)
)

private val DarkBubuColors = BubuColors(
    login = AccentPair(Color(0xFF54382E), BubuBlush),
    card = AccentPair(Color(0xFF31402A), Color(0xFFCADFC0)),
    note = AccentPair(Color(0xFF4A3A18), Color(0xFFF0DBA8)),
    identity = AccentPair(Color(0xFF443349), Color(0xFFE3CFE7)),
    wifi = AccentPair(Color(0xFF2C3F4C), Color(0xFFC8DCE8)),
    weak = ErrorSand,
    fair = Color(0xFFE0BC6B),
    strong = Color(0xFF9FBE92),
    backdrop = Color(0xFF3D3F35)
)

private val LocalBubuColors = staticCompositionLocalOf { LightBubuColors }

/** `MaterialTheme.bubu` - the extended palette, read the same way as the M3 scheme. */
val MaterialTheme.bubu: BubuColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBubuColors.current

@Composable
fun BubuProtectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalBubuColors provides if (darkTheme) DarkBubuColors else LightBubuColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
