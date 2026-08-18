package com.personal.bubuprotect.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * One motion vocabulary for the whole app.
 *
 * ### Why every helper here ends in `graphicsLayer { }`
 *
 * A client app's budget is per frame - 16ms at 60fps, 8ms at 120fps - and the cheapest way to blow it
 * is to animate through composition. `val s by animateFloatAsState(...)` read in a composable body
 * recomposes that composable on **every frame** of the animation, then relayouts, then redraws.
 *
 * The lambda form of `graphicsLayer` takes a block that runs in the *draw* phase. Reading
 * `scale.value` inside that block means the animation skips recomposition and layout entirely and
 * only ever invalidates the draw pass, which is why `scaleX`/`translationX`/`alpha` are the only
 * things animated continuously in this app. Mechanically: one phase per frame instead of three, and
 * no allocation in the recomposition scope.
 *
 * The same rule is why nothing here animates a *size*. `animateContentSize` and animated padding
 * relayout their subtree every frame; on a scrolling vault list that is the whole budget for one
 * decorative effect. Where something must appear to grow, it scales.
 *
 * ### Springs over tweens
 *
 * Springs are interruptible: retargeting mid-flight continues from the current velocity instead of
 * snapping. That matters most on the press/release path, where a fast double tap under a `tween`
 * produces a visible jump.
 */
object BubuMotion {

    // Durations. Named for intent so call sites do not hardcode a number they cannot justify.
    const val FAST = 160
    const val MEDIUM = 300
    const val SLOW = 520

    /** Material's emphasised curve: leaves quickly, settles slowly. Good for anything entering. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Overshoots and settles. This is the "jolly" one - mascots, the FAB, save confirmation. */
    val Bouncy: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessLow)

    /** A hint of overshoot. Default for anything appearing on screen. */
    val Playful: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

    /** No overshoot. For press feedback, where a bounce would feel like a mis-tap. */
    val Snappy: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)

    // --- Navigation ----------------------------------------------------------------------------
    //
    // Direction carries meaning. Going deeper slides forward; coming back slides back; the editor
    // rises from the bottom because it is a task laid on top of the vault rather than a place inside
    // it. The offsets are fractions of the container, not fixed dp, so the gesture reads the same on
    // a phone and on a tablet.

    fun forwardEnter(): EnterTransition =
        slideInHorizontally(tween(MEDIUM, easing = Emphasized)) { full -> full / 5 } +
            fadeIn(tween(MEDIUM, easing = Emphasized))

    fun forwardExit(): ExitTransition =
        slideOutHorizontally(tween(MEDIUM, easing = EmphasizedAccelerate)) { full -> -full / 10 } +
            fadeOut(tween(FAST))

    fun backEnter(): EnterTransition =
        slideInHorizontally(tween(MEDIUM, easing = Emphasized)) { full -> -full / 10 } +
            fadeIn(tween(MEDIUM, easing = Emphasized))

    fun backExit(): ExitTransition =
        slideOutHorizontally(tween(MEDIUM, easing = EmphasizedAccelerate)) { full -> full / 5 } +
            fadeOut(tween(FAST))

    /** The editor: a sheet of paper sliding up onto the desk. */
    fun sheetEnter(): EnterTransition =
        slideInVertically(tween(MEDIUM, easing = Emphasized)) { full -> full / 4 } +
            scaleIn(tween(MEDIUM, easing = Emphasized), initialScale = 0.94f) +
            fadeIn(tween(MEDIUM))

    fun sheetExit(): ExitTransition =
        slideOutVertically(tween(MEDIUM, easing = EmphasizedAccelerate)) { full -> full / 4 } +
            scaleOut(tween(MEDIUM), targetScale = 0.94f) +
            fadeOut(tween(FAST))

    /**
     * Unlock -> vault. A fade-through rather than a slide: the two screens are not neighbours in a
     * hierarchy, one replaces the other, and sliding would imply you can swipe back to the lock
     * screen - which you deliberately cannot.
     */
    fun revealEnter(): EnterTransition =
        fadeIn(tween(SLOW, delayMillis = 90, easing = Emphasized)) +
            scaleIn(tween(SLOW, delayMillis = 90, easing = Emphasized), initialScale = 0.92f)

    fun revealExit(): ExitTransition =
        fadeOut(tween(FAST)) + scaleOut(tween(MEDIUM), targetScale = 1.04f)
}

// --- Modifiers -----------------------------------------------------------------------------------

/**
 * Squash-and-stretch press feedback.
 *
 * Drives scale from the interaction source rather than from a click callback, so it tracks a finger
 * that presses and slides off - the button un-squishes on the way out instead of firing and popping.
 */
@Composable
fun Modifier.squish(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.94f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = BubuMotion.Snappy,
        label = "squish"
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * A slow idle scale, as if the thing is breathing. Reserved for mascots.
 *
 * `rememberInfiniteTransition` is the one place this app runs an animation with no end, so it is kept
 * to a draw-phase scale on a single element. [enabled] exists so a caller can stop it when the mascot
 * is off screen or the vault is locked - an infinite animation keeps the choreographer waking up
 * every frame forever otherwise, which is a battery cost with nothing on screen to justify it.
 */
@Composable
fun Modifier.breathe(
    enabled: Boolean = true,
    amount: Float = 0.03f,
    periodMillis: Int = 2600
): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "breathe")
    val scale = transition.animateFloat(
        initialValue = 1f - amount,
        targetValue = 1f + amount,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = BubuMotion.Emphasized),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheScale"
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * A horizontal head-shake, fired by changing [trigger].
 *
 * Used for a rejected passphrase. It is deliberately short and damped: the point is to say "no"
 * without making someone who simply mistyped feel told off, and a long shake on a wrong password
 * also tells anyone watching over your shoulder that you got it wrong.
 */
@Composable
fun Modifier.wobble(trigger: Any?, intensity: Float = 14f): Modifier {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        offset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 380
                0f at 0
                -intensity at 60
                intensity at 130
                -intensity * 0.6f at 210
                intensity * 0.35f at 290
                0f at 380
            }
        )
    }
    return graphicsLayer { translationX = offset.value }
}

/**
 * Staggered entrance for list items.
 *
 * [index] is capped before it becomes a delay: without the cap the 40th row waits over a second to
 * appear, which stops reading as choreography and starts reading as the app being broken.
 */
@Composable
fun Modifier.enterStaggered(index: Int, maxStaggered: Int = 8): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = BubuMotion.MEDIUM,
                delayMillis = index.coerceAtMost(maxStaggered) * 45,
                easing = BubuMotion.Emphasized
            )
        )
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 40f
    }
}
