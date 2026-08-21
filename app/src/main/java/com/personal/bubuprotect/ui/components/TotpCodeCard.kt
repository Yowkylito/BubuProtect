package com.personal.bubuprotect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.R
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.SecretTextStyle
import com.personal.bubuprotect.ui.theme.bubu
import com.personal.bubuprotect.ui.vm.TotpDisplay

/**
 * The two-factor code for an entry.
 *
 * ### What is on screen, and what never is
 *
 * The code, and only the code. The *seed* generates every future code, so it is worth strictly more
 * than any one of them and is never rendered here - the editor is the only screen that shows it, and
 * only behind the same masking a password gets.
 *
 * ### Why it starts hidden
 *
 * A code that appeared as soon as the entry opened would make the biometric prompt pointless, and the
 * prompt is the entire reason it is defensible to keep a seed next to its password. Putting both
 * factors behind one passphrase is what that arrangement gives up; making the code cost a separate
 * authentication is what buys most of it back. Someone holding an unlocked phone has the password and
 * still cannot produce this.
 */
@Composable
fun TotpCodeCard(
    display: TotpDisplay?,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = BubuElevation.card,
        border = BorderStroke(1.dp, MaterialTheme.bubu.cardBorder.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = BubuSpacing.md,
                vertical = BubuSpacing.sm
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Two-factor code",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(BubuSpacing.xxs))

                AnimatedContent(
                    targetState = display,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
                    label = "totpCode"
                ) { current ->
                    if (current == null) {
                        Text(
                            text = "Hidden",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = current.grouped,
                            style = SecretTextStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            // Spoken as separate digits rather than as one large number, which is
                            // what a screen reader would otherwise do with "123456".
                            modifier = Modifier.semantics {
                                contentDescription = current.code.toList().joinToString(" ")
                            }
                        )
                    }
                }
            }

            if (display != null) {
                Spacer(Modifier.width(BubuSpacing.xs))
                CountdownRing(
                    secondsRemaining = display.secondsRemaining,
                    totalSeconds = display.periodSeconds,
                    // The ring already reads as a timer next to a code; announcing "18" adds nothing
                    // a TalkBack user can act on.
                    modifier = Modifier.clearAndSetSemantics { }
                )
                Spacer(Modifier.width(BubuSpacing.xs))
                BubuIconButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_copy),
                    contentDescription = "Copy the two-factor code",
                    onClick = onCopy
                )
                BubuIconButton(
                    icon = ImageVector.vectorResource(R.drawable.icon_hide_password),
                    contentDescription = "Hide the two-factor code",
                    onClick = onHide
                )
            } else {
                Spacer(Modifier.width(BubuSpacing.xs))
                BubuOutlinedButton(
                    text = "Show",
                    onClick = onShow,
                    leadingIcon = ImageVector.vectorResource(R.drawable.ic_fingerprint),
                    modifier = Modifier.width(132.dp)
                )
            }
        }
    }
}

// --- Previews ----------------------------------------------------------------------------------

@Preview(name = "Hidden", showBackground = true)
@Composable
private fun TotpCodeCardHiddenPreview() {
    BubuProtectTheme {
        Column(Modifier.padding(BubuSpacing.md), verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)) {
            TotpCodeCard(display = null, onShow = {}, onHide = {}, onCopy = {})
        }
    }
}

@Preview(name = "Showing", showBackground = true)
@Composable
private fun TotpCodeCardShowingPreview() {
    BubuProtectTheme {
        Column(Modifier.padding(BubuSpacing.md), verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)) {
            TotpCodeCard(
                display = TotpDisplay(code = "418052", secondsRemaining = 18, periodSeconds = 30),
                onShow = {}, onHide = {}, onCopy = {}
            )
        }
    }
}

@Preview(name = "About to roll over", showBackground = true)
@Composable
private fun TotpCodeCardExpiringPreview() {
    BubuProtectTheme {
        Column(Modifier.padding(BubuSpacing.md)) {
            TotpCodeCard(
                display = TotpDisplay(code = "007319", secondsRemaining = 2, periodSeconds = 30),
                onShow = {}, onHide = {}, onCopy = {}
            )
        }
    }
}
