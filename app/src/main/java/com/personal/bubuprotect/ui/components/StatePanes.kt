package com.personal.bubuprotect.ui.components

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.core.security.IntegrityChecker
import com.personal.bubuprotect.ui.theme.BubuProtectTheme

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
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(mood = BubuMood.THINKING, size = 140.dp, contentDescription = null)
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(mood = BubuMood.GREETING, size = 170.dp, contentDescription = null)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Nothing in here yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Bubu is ready. Everything you add is encrypted on this device and never leaves it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        BubuButton(text = "Add your first secret", onClick = onAddFirstEntry)
    }
}

/** Shown when a search or filter matched nothing - distinct from an empty vault. */
@Composable
fun NoMatchesPane(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(
            mood = BubuMood.THINKING,
            size = 120.dp,
            showBackdrop = false,
            contentDescription = null
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (query.isBlank()) {
                "Nothing of that kind in here"
            } else {
                "Bubu looked everywhere for “$query”"
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
            .padding(32.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BubuMascot(mood = BubuMood.WORRIED, size = 140.dp, contentDescription = null)
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Security warning",
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "This device may not be able to keep secrets",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
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
