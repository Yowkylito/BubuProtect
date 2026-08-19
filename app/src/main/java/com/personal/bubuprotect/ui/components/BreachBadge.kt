package com.personal.bubuprotect.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.PillShape

/**
 * The colours and words one [BreachVerdict] is rendered with.
 *
 * Resolved in one place so the list row, the detail card, the report screen and the alert dialog
 * cannot end up disagreeing about what "breached" looks like - which on a security surface is not a
 * cosmetic problem: a user who sees two different reds learns to read neither as urgent.
 */
data class BreachTone(
    val dot: Color,
    val container: Color,
    val content: Color,
    val label: String
)

@Composable
fun BreachVerdict.tone(): BreachTone = when (this) {
    BreachVerdict.BREACHED -> BreachTone(
        dot = MaterialTheme.colorScheme.error,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        label = "Breached"
    )

    BreachVerdict.SAFE -> BreachTone(
        dot = MaterialTheme.colorScheme.tertiary,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurface,
        label = "Secure"
    )

    BreachVerdict.UNCHECKED -> BreachTone(
        dot = Color.Transparent,
        container = MaterialTheme.colorScheme.surfaceContainer,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        label = "Not checked"
    )
}

/**
 * A compact status pill for one secret.
 *
 * ### Why "Not checked" is a visible state
 *
 * The tempting design is two states - red when breached, green otherwise. It is also a lie, and the
 * expensive kind: the vast majority of entries have never been looked up, and painting them green
 * would tell the user their whole vault had been verified when nothing had left the device. A third,
 * deliberately quiet state is the only honest option, and its quietness is what makes the red ones
 * read as exceptional.
 *
 * ### Why a dot rather than an icon
 *
 * A warning triangle on every unchecked row would make the list look like a wall of alarms. The dot
 * carries colour without carrying volume, and the one place this app does raise its voice - the
 * alert dialog - keeps the warning iconography to itself so it still means something.
 */
@Composable
fun BreachBadge(
    status: BreachStatus,
    modifier: Modifier = Modifier,
    showExposureCount: Boolean = false
) {
    val tone = status.verdict.tone()
    val dot by animateColorAsState(tone.dot, tween(BubuMotion.MEDIUM), label = "breachDot")

    val text = when {
        status.verdict == BreachVerdict.BREACHED && showExposureCount ->
            "Breached · ${status.exposureCount.formatExposure()}"
        else -> tone.label
    }

    Surface(
        modifier = modifier.semantics {
            contentDescription = when (status.verdict) {
                BreachVerdict.BREACHED -> "Found in known breach data"
                BreachVerdict.SAFE -> "Not found in known breach data"
                BreachVerdict.UNCHECKED -> "Never checked against breach data"
            }
        },
        shape = PillShape,
        color = tone.container,
        contentColor = tone.content,
        border = BorderStroke(1.dp, tone.content.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = BubuSpacing.xs,
                vertical = BubuSpacing.xxs / 2
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xxs)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .then(
                        if (status.verdict == BreachVerdict.UNCHECKED) {
                            // A ring, not a disc: "we have no reading" rather than "the reading is
                            // grey", which a filled dot in a neutral colour would imply.
                            Modifier.border(1.dp, tone.content.copy(alpha = 0.5f), CircleShape)
                        } else {
                            Modifier.background(dot, CircleShape)
                        }
                    )
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                // The pill announces itself through the Surface's semantics; letting the label read
                // out too would have TalkBack say "Breached, found in known breach data".
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}

/**
 * Exposure counts run from 1 to the tens of millions, and the exact figure is never the point - the
 * point is the order of magnitude. "3.8M times" lands; "3,861,493 times" is a number to read rather
 * than a fact to feel.
 */
fun Long.formatExposure(): String = when {
    this >= 1_000_000L -> "%.1fM times".format(this / 1_000_000.0)
    this >= 1_000L -> "%.0fk times".format(this / 1_000.0)
    this == 1L -> "once"
    else -> "$this times"
}

@Preview(showBackground = true, name = "Breach badges")
@Preview(
    showBackground = true,
    name = "Breach badges · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun BreachBadgePreview() {
    BubuProtectTheme {
        Column(
            Modifier.padding(BubuSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
        ) {
            BreachBadge(BreachStatus.Unchecked)
            BreachBadge(
                BreachStatus(
                    verdict = BreachVerdict.SAFE,
                    checkedAt = 1L
                )
            )
            BreachBadge(
                BreachStatus(
                    verdict = BreachVerdict.BREACHED,
                    exposureCount = 3_861_493L,
                    checkedAt = 1L
                )
            )
            BreachBadge(
                status = BreachStatus(
                    verdict = BreachVerdict.BREACHED,
                    exposureCount = 3_861_493L,
                    checkedAt = 1L
                ),
                showExposureCount = true
            )
        }
    }
}
