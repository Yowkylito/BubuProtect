package com.personal.bubuprotect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.R
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.SecretTextStyle
import com.personal.bubuprotect.ui.theme.bubu

/**
 * One field of an open entry.
 *
 * ### The mask is a fixed width
 *
 * [MASK] is always the same length regardless of the value behind it. Rendering one dot per
 * character would publish the password's length to anyone glancing at the screen - and length is
 * most of what an attacker needs to narrow a guess. It costs nothing to withhold.
 *
 * ### Reveal is a request, not a toggle
 *
 * [onReveal] does not flip a local boolean; it asks the ViewModel, which re-authenticates and starts
 * a countdown. This composable stays stateless so that the "who is allowed to see this" decision
 * lives in exactly one place, and so a screenshot of the composition tree can never contain a secret
 * the user did not ask for.
 */
@Composable
fun VaultFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isSecret: Boolean = false,
    isRevealed: Boolean = false,
    isMultiline: Boolean = false,
    secondsRemaining: Int? = null,
    totalSeconds: Int = 20,
    onReveal: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null
) {
    val shown = !isSecret || isRevealed

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = BubuElevation.card,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
    ) {
        Column(
            Modifier.padding(
                start = BubuSpacing.md,
                top = BubuSpacing.sm,
                end = BubuSpacing.xs,
                bottom = BubuSpacing.md
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                // The countdown only exists while something is on screen that should not be.
                AnimatedVisibility(
                    visible = isRevealed && secondsRemaining != null,
                    enter = scaleIn(BubuMotion.Playful) + fadeIn(),
                    exit = scaleOut(tween(BubuMotion.FAST)) + fadeOut()
                ) {
                    CountdownRing(
                        secondsRemaining = secondsRemaining ?: 0,
                        totalSeconds = totalSeconds
                    )
                }

                if (isSecret && onReveal != null && onHide != null) {
                    BubuIconButton(
                        icon = ImageVector.vectorResource(
                            if (isRevealed) R.drawable.icon_show_password else R.drawable.icon_hide_password
                        ),
                        contentDescription = if (isRevealed) "Hide $label" else "Reveal $label",
                        onClick = { if (isRevealed) onHide() else onReveal() },
                        tonal = true,
                        tint = MaterialTheme.bubu.champagne
                    )
                }

                if (onCopy != null) {
                    BubuIconButton(
                        icon = ImageVector.vectorResource(R.drawable.ic_copy),
                        contentDescription = "Copy $label",
                        onClick = onCopy,
                        tonal = true
                    )
                }
            }

            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    // The real value rises into place as the mask drops away - a small "unveiling"
                    // rather than a hard swap, so the eye tracks that something changed.
                    (slideInVertically(tween(BubuMotion.MEDIUM, easing = BubuMotion.Emphasized)) { it / 3 } +
                        fadeIn(tween(BubuMotion.MEDIUM))) togetherWith
                        (slideOutVertically(tween(BubuMotion.FAST)) { -it / 3 } +
                            fadeOut(tween(BubuMotion.FAST)))
                },
                label = "fieldReveal"
            ) { visible ->
                Text(
                    text = if (visible) value else MASK,
                    style = if (isSecret) SecretTextStyle else MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (isMultiline) Int.MAX_VALUE else 3,
                    modifier = Modifier.padding(end = BubuSpacing.sm)
                )
            }
        }
    }
}

/** Fixed length on purpose - see the class docs. */
private const val MASK = "••••••••••"

@Preview(showBackground = true)
@Composable
private fun VaultFieldRowPreview() {
    BubuProtectTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VaultFieldRow(
                label = "Username",
                value = "bubu@example.com",
                onCopy = {}
            )
            VaultFieldRow(
                label = "Password",
                value = "Zq7-mK2p!vT9",
                isSecret = true,
                onReveal = {},
                onHide = {},
                onCopy = {}
            )
            VaultFieldRow(
                label = "Password",
                value = "Zq7-mK2p!vT9",
                isSecret = true,
                isRevealed = true,
                secondsRemaining = 13,
                onReveal = {},
                onHide = {},
                onCopy = {}
            )
        }
    }
}
