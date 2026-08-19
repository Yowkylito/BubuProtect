package com.personal.bubuprotect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.motion.squish
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.theme.bubu
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The app's primary button.
 *
 * Wraps M3's [Button] rather than replacing it, so it keeps the ripple, the focus ring, the disabled
 * colours and the minimum touch target that come with it - and adds two things M3 has no opinion on:
 * a squash on press, and a busy state that swaps the label for a spinner *in place* rather than
 * replacing the button with a spinner, which would move everything below it.
 */
@Composable
fun BubuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isBusy: Boolean = false,
    busyText: String = "Working"
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 52.dp)
            .squish(interactionSource),
        enabled = enabled && !isBusy,
        shape = PillShape,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        AnimatedContent(
            targetState = isBusy,
            transitionSpec = {
                (fadeIn(tween(BubuMotion.FAST)) + scaleIn(initialScale = 0.8f)) togetherWith
                        (fadeOut(tween(BubuMotion.FAST)) + scaleOut(targetScale = 0.8f))
            },
            label = "buttonBusy"
        ) { busy ->
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(BubuSpacing.xs))
                    Text(busyText, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The quieter sibling. Same squash, outlined instead of filled. */
@Composable
fun BubuOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.bubu.cardBorder
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 52.dp)
            .squish(interactionSource),
        enabled = enabled,
        shape = PillShape,
        interactionSource = interactionSource,
        border = BorderStroke(
            1.dp,
            borderColor.copy(alpha = if (enabled) 0.76f else 0.36f)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(BubuSpacing.xs))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * An icon button that squashes.
 *
 * [contentDescription] is required, not nullable: this control has no visible label, so omitting it
 * leaves a screen reader announcing "button" and nothing else. Making it non-null moves that from a
 * review comment to a compile error.
 */
@Composable
fun BubuIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val interactionSource = remember { MutableInteractionSource() }
    val iconModifier = Modifier
        .size(22.dp)
        .squish(interactionSource, pressedScale = 0.82f)
    // 48dp is the accessibility floor, and IconButton's own default; stated so a caller passing
    // a smaller size modifier gets clamped rather than silently shipping a 32dp target.
    val buttonModifier = modifier.size(48.dp)

    if (tonal) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = buttonModifier.border(
                width = 1.dp,
                color = MaterialTheme.bubu.cardBorder.copy(alpha = if (enabled) 0.68f else 0.3f),
                shape = CircleShape
            ),
            enabled = enabled,
            interactionSource = interactionSource,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Transparent,
                contentColor = tint
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = iconModifier
            )
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            interactionSource = interactionSource
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.38f),
                modifier = iconModifier
            )
        }
    }
}

/** A labelled split between two equal paths - used on unlock when fingerprint and passphrase coexist. */
@Composable
fun BubuOrDivider(
    modifier: Modifier = Modifier,
    text: String = "or"
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = BubuSpacing.sm)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * The kind filter above the vault list.
 *
 * A [LazyRow] rather than a wrapping `FlowRow`: five chips fit on a phone today, but the whole point
 * of [ItemKind] is that a sixth and seventh are cheap to add, and a lazy row absorbs that without the
 * filter bar quietly growing to two lines and pushing the list down.
 */
@Composable
fun KindFilterRow(
    selected: ItemKind?,
    onSelect: (ItemKind?) -> Unit,
    counts: Map<ItemKind, Int>,
    modifier: Modifier = Modifier
) {
    val kinds = remember { ItemKind.entries.toList() }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xs),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BubuSpacing.screen)
    ) {
        item(key = "all") {
            BubuFilterChip(
                label = "Everything",
                isSelected = selected == null,
                onClick = { onSelect(null) }
            )
        }
        items(kinds, key = { it.storageKey }) { kind ->
            val count = counts[kind] ?: 0
            BubuFilterChip(
                label = if (count > 0) "${kind.title} $count" else kind.title,
                isSelected = selected == kind,
                enabled = count > 0 || selected == kind,
                accentContainer = kind.accent().container,
                accentContent = kind.accent().content,
                onClick = { onSelect(if (selected == kind) null else kind) }
            )
        }
    }
}

@Composable
private fun BubuFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentContainer: Color = MaterialTheme.bubu.champagneContainer,
    accentContent: Color = MaterialTheme.colorScheme.onSurface
) {
    val interactionSource = remember { MutableInteractionSource() }
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = 40.dp)
            .squish(interactionSource, pressedScale = 0.93f),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = accentContainer,
            selectedLabelColor = accentContent,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = isSelected,
            borderColor = MaterialTheme.bubu.cardBorder.copy(alpha = 0.68f),
            selectedBorderColor = accentContent.copy(alpha = 0.22f),
            disabledBorderColor = MaterialTheme.bubu.cardBorder.copy(alpha = 0.28f)
        ),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
    )
}

// --- Password strength ---------------------------------------------------------------------------

enum class SecretStrength(val label: String) {
    EMPTY(""),
    WEAK("Bubu is worried about this one"),
    FAIR("Not bad"),
    STRONG("Dudu approves")
}

/**
 * A rough entropy estimate: `length x log2(character pool)`.
 *
 * Honest about what it is. This measures *shape*, not guessability - "Password1!" scores the same as
 * ten random characters from the same pool, and a real strength meter would need a dictionary and a
 * zxcvbn-style pattern matcher. Shipping one of those means bundling a wordlist into an offline app
 * for a number that is advisory either way, so this stays a shape check and the UI never blocks a
 * save on it.
 */
fun estimateStrength(secret: String): SecretStrength {
    if (secret.isEmpty()) return SecretStrength.EMPTY
    var pool = 0
    if (secret.any { it.isLowerCase() }) pool += 26
    if (secret.any { it.isUpperCase() }) pool += 26
    if (secret.any { it.isDigit() }) pool += 10
    if (secret.any { !it.isLetterOrDigit() }) pool += 32
    val distinct = secret.toSet().size
    // Repetition penalty: "aaaaaaaaaaaa" is long but carries one character of information.
    val effectiveLength = secret.length * (distinct.toDouble() / secret.length).coerceIn(0.35, 1.0)
    val bits = if (pool <= 1) 0.0 else effectiveLength * (ln(pool.toDouble()) / ln(2.0))
    return when {
        bits < 45 -> SecretStrength.WEAK
        bits < 72 -> SecretStrength.FAIR
        else -> SecretStrength.STRONG
    }
}

/**
 * Animated strength bar.
 *
 * Both the fill fraction and the colour are animated *values* read inside the [Canvas] draw lambda,
 * so the whole thing is a per-frame redraw of one 6dp strip - no recomposition, no relayout, and no
 * effect on the text field being typed into above it.
 */
@Composable
fun SecretStrengthMeter(
    secret: String,
    modifier: Modifier = Modifier
) {
    val strength = remember(secret) { estimateStrength(secret) }
    val palette = MaterialTheme.bubu
    val target = when (strength) {
        SecretStrength.EMPTY -> 0f
        SecretStrength.WEAK -> 0.33f
        SecretStrength.FAIR -> 0.66f
        SecretStrength.STRONG -> 1f
    }
    val progress = animateFloatAsState(target, BubuMotion.Playful, label = "strength")
    val color by animateColorAsState(
        targetValue = when (strength) {
            SecretStrength.EMPTY -> MaterialTheme.colorScheme.outlineVariant
            SecretStrength.WEAK -> palette.weak
            SecretStrength.FAIR -> palette.fair
            SecretStrength.STRONG -> palette.strong
        },
        animationSpec = tween(BubuMotion.MEDIUM),
        label = "strengthColor"
    )
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // One announcement for the pair, so TalkBack says the verdict instead of describing a bar.
            .semantics {
                contentDescription = if (strength == SecretStrength.EMPTY) {
                    "No password entered"
                } else {
                    "Password strength: ${strength.name.lowercase()}"
                }
            }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(BubuSpacing.xs / 2)
        ) {
            drawLine(
                color = track,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                strokeWidth = size.height,
                cap = StrokeCap.Round
            )
            if (progress.value > 0f) {
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                    end = androidx.compose.ui.geometry.Offset(
                        size.width * progress.value,
                        size.height / 2
                    ),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round
                )
            }
        }
        AnimatedContent(
            targetState = strength,
            transitionSpec = {
                fadeIn(tween(BubuMotion.FAST)) togetherWith fadeOut(tween(BubuMotion.FAST))
            },
            label = "strengthLabel"
        ) { value ->
            Text(
                text = value.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = BubuSpacing.xs)
                    .clearAndSetSemantics { }
            )
        }
    }
}

/** A countdown ring, used for the auto-hide timer on a revealed secret. */
@Composable
fun CountdownRing(
    secondsRemaining: Int,
    totalSeconds: Int,
    modifier: Modifier = Modifier
) {
    val fraction = animateFloatAsState(
        targetValue = (secondsRemaining.toFloat() / totalSeconds).coerceIn(0f, 1f),
        animationSpec = tween(1000, easing = androidx.compose.animation.core.LinearEasing),
        label = "countdown"
    )
    val ring = MaterialTheme.bubu.champagne
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .size(34.dp)
            .semantics { contentDescription = "Hides in $secondsRemaining seconds" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(34.dp)) {
            val stroke = 3.dp.toPx()
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke,
                    cap = StrokeCap.Round
                ),
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            )
            drawArc(
                color = ring,
                startAngle = -90f,
                sweepAngle = 360f * fraction.value,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke,
                    cap = StrokeCap.Round
                ),
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            )
        }
        Text(
            text = secondsRemaining.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Preview(showBackground = true, name = "Controls")
@Preview(
    showBackground = true,
    name = "Controls · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun BubuControlsPreview() {
    BubuProtectTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BubuButton(text = "Open the vault", onClick = {}, modifier = Modifier.fillMaxWidth())
            BubuButton(
                text = "Open the vault",
                onClick = {},
                isBusy = true,
                busyText = "Unlocking",
                modifier = Modifier.fillMaxWidth()
            )
            BubuOutlinedButton(
                text = "Use passphrase",
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            )
            BubuOutlinedButton(
                text = "Delete this secret",
                onClick = {},
                contentColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
                borderColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            SecretStrengthMeter(secret = "correct-horse-battery-staple-9!")
            SecretStrengthMeter(secret = "hunter2")
            KindFilterRow(
                selected = ItemKind.CARD,
                onSelect = {},
                counts = ItemKind.entries.associateWith { 2 }
            )
            CountdownRing(secondsRemaining = 13, totalSeconds = 20)
        }
    }
}

/** Formats a "last updated" stamp without pulling in a date library. */
fun relativeAge(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (updatedAt <= 0L) return ""
    val minutes = ((now - updatedAt) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${(minutes / 60.0).roundToInt()}h ago"
        minutes < 60 * 24 * 30 -> "${(minutes / (60.0 * 24)).roundToInt()}d ago"
        else -> "${(minutes / (60.0 * 24 * 30)).roundToInt()}mo ago"
    }
}
