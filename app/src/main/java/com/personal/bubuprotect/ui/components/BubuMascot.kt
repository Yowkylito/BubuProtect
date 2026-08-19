package com.personal.bubuprotect.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import com.personal.bubuprotect.R
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.breathe
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.bubu
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip

/**
 * The single GIF-capable [ImageLoader] for the process.
 *
 * `staticCompositionLocalOf` with no default that builds one: every `ImageLoader` owns its own
 * memory cache and bitmap pool, so the old habit of calling `createImageLoader(context)` inside each
 * composable meant a fresh multi-megabyte cache per screen, none of which ever shared a decoded
 * frame. One instance, provided at the root, decodes each mascot once for the whole app.
 */
val LocalBubuImageLoader: ProvidableCompositionLocal<ImageLoader?> =
    staticCompositionLocalOf { null }

@Composable
fun rememberBubuImageLoader(): ImageLoader {
    val provided = LocalBubuImageLoader.current
    val context = LocalContext.current
    // Previews and tests render without the root provider; fall back rather than crash the preview.
    return provided ?: remember(context) { createImageLoader(context) }
}

/**
 * What Bubu and Dudu are doing right now.
 *
 * The mascot is the app's status indicator, not decoration. A spinner tells you the app is busy; a
 * worried bear tells you *the thing you just tried did not work* without a red banner shouting it at
 * whoever is sitting next to you. Each mood therefore owns an accessibility description, because a
 * screen reader user has to get the same signal the animation carries.
 */
@Immutable
enum class BubuMood(@param:DrawableRes val art: Int, val description: String) {
    /** First run, and the empty vault. */
    GREETING(R.drawable.dont_forget_state, "Bubu waving hello"),

    /** The lock screen. Standing watch over the vault. */
    GUARDING(R.drawable.protect, "Bubu standing guard over the vault"),

    /** Something is deliberately concealed - a masked secret, a locked vault. */
    HIDING(R.drawable.hide, "Bubu hiding a secret"),

    /** A wrong passphrase, a failed integrity check, an error. */
    WORRIED(R.drawable.breach_state, "Bubu looking worried"),

    /** A save landed, or the vault just opened. */
    CELEBRATING(R.drawable.success_gif, "Bubu celebrating"),

    /** About to delete something. */
    SULKING(R.drawable.delete, "Bubu looking sad"),

    /** Working. Key derivation, database open. */
    THINKING(R.drawable.loading3, "Bubu thinking"),

    /**
     * Something on the device is worth a second look, but nothing is on fire.
     *
     * Distinct from [WORRIED] on purpose. The device check finds a legitimate screen reader far more
     * often than it finds an attacker, and leading that result with the same bear that means "your
     * passphrase was wrong" would turn every normal phone into an emergency.
     */
    SUSPICIOUS(R.drawable.suspicious, "Bubu peering at something suspicious"),
    EYES_COVERED(R.drawable.nothing_state, "Bubu covering eyes")
}

/**
 * The animated mascot.
 *
 * Two layers of motion, on purpose:
 *  - a slow [breathe] scale, so an idle screen is never completely still, and
 *  - a pop-swap through [AnimatedContent] whenever [mood] changes, so a state change is *felt*.
 *
 * Both are draw-phase transforms on one element. The GIF itself is the expensive part - it decodes a
 * frame at a time on Coil's dispatcher - which is why the app never puts two mascots on screen at
 * once, and why [breathing] can be switched off for a mascot that is scrolled away or purely
 * decorative.
 *
 * @param contentDescription pass null when adjacent text already states what the mood means; the
 *   mascot then goes silent for TalkBack instead of repeating it.
 */
@Composable
fun BubuMascot(
    mood: BubuMood,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    breathing: Boolean = true,
    showBackdrop: Boolean = true,
    contentDescription: String? = mood.description
) {
    val imageLoader = rememberBubuImageLoader()
    val backdrop = MaterialTheme.bubu.backdrop

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription == null) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showBackdrop) {
            Box(
                Modifier
                    .size(size)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceContainerLowest,
                                backdrop
                            )
                        ),
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.bubu.champagne.copy(alpha = 0.38f),
                        shape = CircleShape
                    )
            )
        }

        AnimatedContent(
            targetState = mood,
            transitionSpec = {
                // The new mood pops in slightly large and settles; the old one shrinks away. Reads
                // as one character changing expression rather than two images cross-fading.
                (scaleIn(BubuMotion.Playful, initialScale = 0.7f) +
                    fadeIn(tween(BubuMotion.FAST))) togetherWith
                    (scaleOut(tween(BubuMotion.FAST), targetScale = 0.85f) +
                        fadeOut(tween(BubuMotion.FAST)))
            },
            label = "mascotMood"
        ) { current ->
            if (LocalInspectionMode.current) {
                // Coil does not decode in the preview renderer; a plain disc keeps layout honest.
                Box(
                    Modifier
                        .size(size * 1.72f)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                )
            } else {
                Image(
                    // Every mascot asset is a GIF. painterResource only supports static vector and
                    // bitmap resources and crashes when focus used to switch this branch "off".
                    // Coil's GIF decoder is therefore the single safe path for every mood.
                    painter = rememberAsyncImagePainter(current.art, imageLoader),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(size)
                        .breathe(enabled = breathing)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BubuMascotPreview() {
    BubuProtectTheme {
        BubuMascot(mood = BubuMood.GUARDING)
    }
}
