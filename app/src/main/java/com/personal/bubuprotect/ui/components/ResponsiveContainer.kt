package com.personal.bubuprotect.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.window.core.layout.WindowSizeClass

/**
 * Centres content and caps its width once the window is wide enough to make full-bleed layouts
 * unreadable - a tablet, a foldable opened flat, or a resized window on a desktop-mode display.
 *
 * The breakpoint comes from [WindowSizeClass]'s own named constant rather than a dp literal picked
 * to match one device, and the decision is based on the window rather than the screen, so it stays
 * correct in split-screen and in freeform windows where the app owns only part of the display.
 */
@Composable
fun ResponsiveContainer(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = DEFAULT_MAX_CONTENT_WIDTH,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    val isWide = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = if (isWide) {
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
            } else {
                Modifier.fillMaxSize()
            },
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

private val DEFAULT_MAX_CONTENT_WIDTH = 560.dp
