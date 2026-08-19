package com.personal.bubuprotect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.core.security.IntegrityChecker
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu

/**
 * The busy state.
 *
 * A thinking mascot instead of a bare spinner. The loads this covers are key derivation and a
 * SQLCipher open - deliberately slow operations, sometimes a second or more - and a spinner for that
 * long reads as "stuck" while a character reads as "working".
 */
@Composable
fun LoadingPane(modifier: Modifier = Modifier, label: String = "Loading") {
    Column(
        modifier = modifier
            .fillMaxSize()
            .luxuryStateBackdrop()
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(mood = BubuMood.THINKING, size = 140.dp, contentDescription = null)
        Spacer(Modifier.height(BubuSpacing.md))
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(BubuSpacing.xxs))
        Text(
            "Your vault stays right here on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The empty vault.
 *
 * Worded and drawn as a beginning, not a failure. This is the first thing a new user sees after
 * setting a passphrase, and an apologetic grey "No items" would make a vault they just created feel
 * broken.
 */
@Composable
fun EmptyVaultPane(
    onAddFirstEntry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .luxuryStateBackdrop()
            .padding(BubuSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(mood = BubuMood.EYES_COVERED, size = 170.dp, contentDescription = null)
        Spacer(Modifier.height(BubuSpacing.screen))
        Text(
            text = "Nothing in here yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(BubuSpacing.xs))
        Text(
            text = "Bubu and Dudu are ready. Everything you add is encrypted on this device and never leaves it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(BubuSpacing.screen))
        BubuButton(text = "Add your first secret", onClick = onAddFirstEntry)
    }
}

/** Shown when a search or filter matched nothing - distinct from an empty vault. */
@Composable
fun NoMatchesPane(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .luxuryStateBackdrop()
            .padding(BubuSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(
            mood = BubuMood.THINKING,
            size = 120.dp,
            showBackdrop = false,
            contentDescription = null
        )
        Spacer(Modifier.height(BubuSpacing.sm))
        Text(
            text = if (query.isBlank()) {
                "Nothing of that kind in here"
            } else {
                "Bubu and Dudu looked everywhere for “$query”"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ErrorPane(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .luxuryStateBackdrop()
            .padding(BubuSpacing.xl)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(mood = BubuMood.WORRIED, size = 140.dp, contentDescription = null)
        Spacer(Modifier.height(BubuSpacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(BubuSpacing.md))
            BubuOutlinedButton(text = "Try again", onClick = onRetry)
        }
    }
}

@Composable
private fun Modifier.luxuryStateBackdrop(): Modifier = background(
    brush = Brush.radialGradient(
        colors = listOf(
            MaterialTheme.bubu.champagneContainer.copy(alpha = 0.38f),
            Color.Transparent
        ),
        radius = 760f
    )
)

/**
 * Advisory banner for [IntegrityChecker] findings.
 *
 * Worded as information, not an alarm, and never blocks the unlock button - the checks are
 * heuristics and a false positive must not stand between the user and their own passwords.
 */
@Composable
fun SecurityWarningBanner(
    findings: Set<IntegrityChecker.Finding>,
    modifier: Modifier = Modifier
) {
    if (findings.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = BubuElevation.card,
        border = BorderStroke(
            1.dp,
            MaterialTheme.bubu.cardBorder.copy(alpha = 0.55f)
        )
    ) {
        Row(modifier = Modifier.padding(BubuSpacing.md), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Security warning",
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(BubuSpacing.sm))
            Column {
                Text(
                    text = "This device may not be able to keep secrets",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(BubuSpacing.xxs))
                findings.forEach { finding ->
                    Text(text = "• ${finding.explain()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun IntegrityChecker.Finding.explain(): String = when (this) {
    IntegrityChecker.Finding.ROOT_INDICATORS ->
        "Root access was detected. Other software here may be able to read decrypted passwords."
    IntegrityChecker.Finding.DEBUGGER_ATTACHED ->
        "A debugger is attached and can read this app's memory."
    IntegrityChecker.Finding.UNTRUSTED_BUILD ->
        "This looks like an emulator or engineering build, where hardware key storage may be simulated."
    IntegrityChecker.Finding.ACCESSIBILITY_SERVICE_ACTIVE ->
        "An accessibility service is running. Screen capture is blocked, but a service like that " +
            "can still read text you reveal. Normal if you use a screen reader."
}

@Preview(showBackground = true)
@Preview(showBackground = true, name = "Loading · dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoadingPanePreview() {
    BubuProtectTheme { LoadingPane(label = "Opening the vault") }
}

@Preview(showBackground = true)
@Composable
private fun EmptyVaultPanePreview() {
    BubuProtectTheme { EmptyVaultPane(onAddFirstEntry = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ErrorPanePreview() {
    BubuProtectTheme { ErrorPane(message = "Could not read the vault.", onRetry = {}) }
}

@Preview(showBackground = true)
@Composable
private fun SecurityWarningBannerPreview() {
    BubuProtectTheme {
        SecurityWarningBanner(
            findings = setOf(
                IntegrityChecker.Finding.ROOT_INDICATORS,
                IntegrityChecker.Finding.DEBUGGER_ATTACHED
            ),
            modifier = Modifier.padding(16.dp)
        )
    }
}
