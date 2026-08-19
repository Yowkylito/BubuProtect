package com.personal.bubuprotect.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.bubuprotect.ui.components.BubuButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuOutlinedButton
import com.personal.bubuprotect.ui.components.ResponsiveContainer
import com.personal.bubuprotect.ui.theme.BubuElevation
import com.personal.bubuprotect.ui.theme.BubuProtectTheme
import com.personal.bubuprotect.ui.theme.BubuSpacing
import com.personal.bubuprotect.ui.theme.bubu
import kotlinx.coroutines.launch

@Immutable
private data class GuidePage(
    val eyebrow: String,
    val title: String,
    val body: String,
    val factLabel: String,
    val fact: String,
    val mood: BubuMood
)

private val guidePages = listOf(
    GuidePage(
        eyebrow = "Your one key",
        title = "Make your passphrase memorable",
        body = "Your master passphrase is the only way into your vault. Bubu cannot see it, send it " +
            "somewhere else, or reset it for you.",
        factLabel = "Memory trick",
        fact = "A strange mental picture is easier to remember than a random list. Try a long, " +
            "unusual sentence you can imagine clearly—and never use it anywhere else.",
        mood = BubuMood.GREETING
    ),
    GuidePage(
        eyebrow = "Why unique matters",
        title = "One leak can open many doors",
        body = "When a website is breached, attackers often try those leaked passwords on other " +
            "apps. Reusing one password turns a single leak into several risks.",
        factLabel = "Security fact",
        fact = "A unique password limits the damage. If one service is breached, your other " +
            "accounts do not share the same key.",
        mood = BubuMood.WORRIED
    ),
    GuidePage(
        eyebrow = "A private safety check",
        title = "Check without sharing your password",
        body = "For saved logins, tap “Check password safety” and confirm it is you. Bubu never " +
            "sends your password, account name, or website.",
        factLabel = "How the trick works",
        fact = "Bubu sends only a tiny piece of a scrambled password fingerprint, receives a mixed " +
            "list of possible matches, and finishes the comparison on your phone. Only the safety " +
            "result is kept in your encrypted vault so Bubu can warn you later—not the fingerprint.",
        mood = BubuMood.GUARDING
    )
)

/**
 * A short, skippable security story used both during first-run setup and from Settings.
 *
 * It teaches one idea per page and gives the user control over pacing. There are no streaks,
 * countdowns, fear prompts, or fake urgency: retention should come from trust and competence.
 */
@Composable
fun SecurityGuideScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    showSkip: Boolean = false,
    doneLabel: String = "Got it",
    onExit: (() -> Unit)? = null,
    reserveTopInset: Boolean = true
) {
    val pagerState = rememberPagerState(pageCount = { guidePages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == guidePages.lastIndex
    val colors = MaterialTheme.colorScheme
    val safeInsets = if (reserveTopInset) {
        WindowInsets.safeDrawing
    } else {
        WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.bubu.champagneContainer.copy(alpha = 0.58f),
                        colors.primaryContainer.copy(alpha = 0.2f),
                        colors.background
                    )
                )
            )
            .windowInsetsPadding(safeInsets)
    ) {
        ResponsiveContainer(
            modifier = Modifier.fillMaxSize(),
            maxContentWidth = 560.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BubuSpacing.sm),
                horizontalArrangement = Arrangement.End
            ) {
                if (showSkip) {
                    TextButton(onClick = onDone) {
                        Text("Skip for now")
                    }
                } else if (onExit != null) {
                    TextButton(onClick = onExit) {
                        Text("Close")
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "Security guide, page ${pagerState.currentPage + 1} of ${guidePages.size}"
                    },
                verticalAlignment = Alignment.Top
            ) { pageIndex ->
                GuidePageContent(
                    page = guidePages[pageIndex],
                    modifier = Modifier.fillMaxSize()
                )
            }

            PageIndicator(
                pageCount = guidePages.size,
                selectedPage = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = BubuSpacing.sm)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BubuSpacing.lg)
                    .padding(bottom = BubuSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(BubuSpacing.sm)
            ) {
                if (pagerState.currentPage > 0) {
                    BubuOutlinedButton(
                        text = "Back",
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                BubuButton(
                    text = if (isLastPage) doneLabel else "Next",
                    onClick = {
                        if (isLastPage) {
                            onDone()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier.weight(if (pagerState.currentPage > 0) 1f else 2f)
                )
            }
        }
    }
}

@Composable
private fun GuidePageContent(
    page: GuidePage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BubuSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BubuMascot(
            mood = page.mood,
            size = 148.dp,
            breathing = false,
            contentDescription = null
        )
        Spacer(Modifier.height(BubuSpacing.sm))
        Text(
            text = page.eyebrow.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(BubuSpacing.xs))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(BubuSpacing.sm))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(BubuSpacing.lg))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = BubuElevation.card,
            border = BorderStroke(
                1.dp,
                MaterialTheme.bubu.champagne.copy(alpha = 0.38f)
            )
        ) {
            Column(
                modifier = Modifier.padding(BubuSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
            ) {
                Text(
                    text = page.factLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = page.fact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(BubuSpacing.md))
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    selectedPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Page ${selectedPage + 1} of $pageCount"
        },
        horizontalArrangement = Arrangement.spacedBy(BubuSpacing.xs)
    ) {
        repeat(pageCount) { index ->
            Box(
                Modifier
                    .size(if (index == selectedPage) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == selectedPage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
    }
}

@Preview(showBackground = true, name = "Security guide")
@Preview(
    showBackground = true,
    name = "Security guide · dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun SecurityGuidePreview() {
    BubuProtectTheme {
        SecurityGuideScreen(onDone = {}, showSkip = true, doneLabel = "Build my vault")
    }
}
