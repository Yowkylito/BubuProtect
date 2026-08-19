package com.personal.bubuprotect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.personal.bubuprotect.domain.model.BreachStatus
import com.personal.bubuprotect.domain.model.BreachVerdict
import com.personal.bubuprotect.domain.model.ItemKind
import com.personal.bubuprotect.domain.model.VaultItem
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.PillShape
import com.personal.bubuprotect.ui.theme.bubu

/**
 * The one moment this app raises its voice.
 *
 * ### Why it is allowed to interrupt
 *
 * Everything else in Bubu Protect waits to be asked. This does not, and the reason is that a
 * breached password is the only vault state where *not knowing* is actively costing the user
 * something right now - the credential is already published, and every hour it stays in use is an
 * hour someone else can sign in with it. A badge on a list row the user may not scroll to is not
 * proportionate to that.
 *
 * It earns the interruption by being rare and by being specific: it names the affected entries
 * rather than saying "some of your passwords", and it appears only for verdicts the user has not
 * already dismissed.
 *
 * ### Why "Ignore" is a real, quiet option
 *
 * Not everyone can change a password at the moment they are told to, and a dialog that only offers
 * the virtuous path gets dismissed by rote until it stops being read at all. Ignoring is
 * one tap and does not scold - but it is deliberately the *quieter* of the two buttons, and the
 * dismissal only silences the verdicts as they stand today. A fresh breach on the same entry raises
 * its hand again.
 */
@Composable
fun BreachAlertDialog(
    breached: List<VaultItem>,
    onIgnore: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (breached.isEmpty()) return

    val count = breached.size
    val worst = remember(breached) { breached.maxOf { it.breach.exposureCount } }

    AlertDialog(
        // No onDismissRequest side effect beyond ignoring: a back press or an outside tap is the
        // user saying "not now", which is exactly what Ignore means. Silently vanishing with no
        // record would make the same dialog reappear on the next screen change.
        onDismissRequest = onIgnore,
        modifier = modifier
            .shadow(BubuElevation.hero, MaterialTheme.shapes.extraLarge)
            .border(
                1.dp,
                MaterialTheme.bubu.champagne.copy(alpha = 0.42f),
                MaterialTheme.shapes.extraLarge
            )
            .semantics { liveRegion = LiveRegionMode.Assertive },
        properties = DialogProperties(usePlatformDefaultWidth = true),
        icon = {
            BubuMascot(
                mood = BubuMood.WORRIED,
                size = 104.dp,
                showBackdrop = false,
                contentDescription = null
            )
        },
        title = {
            Text(
                text = if (count == 1) {
                    "Change this password now"
                } else {
                    "Change these $count passwords now"
                },
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BubuSpacing.sm)) {
                Text(
                    text = if (count == 1) {
                        "This password has turned up in a public data leak, so it is already in " +
                            "the lists attackers try first. Change it wherever you have used it, " +
                            "and pick something you use nowhere else."
                    } else {
                        "These passwords have turned up in public data leaks, so they are already " +
                            "in the lists attackers try first. Change each of them wherever you " +
                            "have used them, and pick something you use nowhere else."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.44f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.28f)
                    )
                ) {
                    Column(
                        Modifier.padding(BubuSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
                    ) {
                        // Capped: the point is to make the warning concrete, not to reproduce the
                        // report screen inside a dialog. Past a few names it stops being scannable
                        // and starts being a list the user has to manage in a modal.
                        breached.take(NAMES_SHOWN).forEach { item ->
                            BreachedRow(item)
                        }
                        if (count > NAMES_SHOWN) {
                            Text(
                                text = "and ${count - NAMES_SHOWN} more",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                                    .copy(alpha = 0.78f)
                            )
                        }
                    }
                }

                if (worst > 0L) {
                    Text(
                        text = "The most exposed of these appears ${worst.formatExposure()} in " +
                            "leaked data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            BubuButton(text = "Check them", onClick = onReview)
        },
        dismissButton = {
            BubuOutlinedButton(text = "Ignore", onClick = onIgnore)
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    )
}

@Composable
private fun BreachedRow(item: VaultItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.error, PillShape)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    item.subtitle.takeIf { it.isNotBlank() },
                    item.breach.exposureCount.takeIf { it > 0L }?.formatExposure()
                ).joinToString("  ·  ").ifEmpty { item.kind.title },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val NAMES_SHOWN = 3

@Preview(showBackground = true, name = "Breach alert")
@Preview(
    showBackground = true,
    name = "Breach alert · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun BreachAlertDialogPreview() {
    BubuProtectTheme {
        BreachAlertDialog(
            breached = previewBreached(4),
            onIgnore = {},
            onReview = {}
        )
    }
}

@Preview(showBackground = true, name = "Breach alert · single")
@Composable
private fun BreachAlertDialogSinglePreview() {
    BubuProtectTheme {
        BreachAlertDialog(
            breached = previewBreached(1),
            onIgnore = {},
            onReview = {}
        )
    }
}

private fun previewBreached(count: Int): List<VaultItem> = listOf(
    "Bear mail" to 3_861_493L,
    "Honey shop" to 24_120L,
    "Old forum" to 812L,
    "Home Wi-Fi" to 4L
).take(count).map { (label, exposures) ->
    VaultItem(
        id = label,
        kind = ItemKind.LOGIN,
        label = label,
        subtitle = "bubu@example.com",
        breach = BreachStatus(
            verdict = BreachVerdict.BREACHED,
            exposureCount = exposures,
            checkedAt = 1L
        )
    )
}
