package com.personal.bubuprotect.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.personal.bubuprotect.ui.components.BubuIconButton
import com.personal.bubuprotect.ui.components.BubuMascot
import com.personal.bubuprotect.ui.components.BubuMood
import com.personal.bubuprotect.ui.components.BubuTopBar
import com.personal.bubuprotect.ui.theme.BubuSpacing

/**
 * Pinned app chrome that draws *behind* the system bars and only insets the controls.
 *
 * Padding the whole scaffold by the navigation bar lifts the background and makes the app look
 * letterboxed. The cream (or night) surface stays full-bleed; [MainHeader] pads its row under the
 * status bar, and lists / FABs pad their own bottoms above the gesture pill.
 *
 * Used once around the unlocked graph so the header does not rebuild on every destination.
 */
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        content = content
    )
}

/**
 * The vault-list header: brand, vault count, lock, settings.
 *
 * System-bar insets are applied by the shell, not here, so this row can hide on detail/editor
 * without leaving a second status-bar gap - and so those screens' own app bars sit cleanly under
 * the same reserved top inset.
 */
@Composable
fun MainHeader(
    entryCount: Int,
    onLock: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    BubuTopBar(
        title = "Bubu Protect",
        subtitle = when (entryCount) {
            0 -> "A quiet little vault, ready when you are"
            1 -> "Guarding 1 secret on this device"
            else -> "Guarding $entryCount secrets on this device"
        },
        leading = {
            BubuMascot(
                mood = BubuMood.GUARDING,
                size = 44.dp,
                breathing = false,
                showBackdrop = false,
                contentDescription = null
            )
        },
        actions = {
            BubuIconButton(
                icon = Icons.Filled.Lock,
                contentDescription = "Lock the vault now",
                onClick = onLock,
                tonal = true
            )
            Spacer(Modifier.width(BubuSpacing.xxs))
            BubuIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings,
                tonal = true
            )
        },
        modifier = modifier
    )
}

/**
 * ViewModel store for the unlocked session.
 *
 * Cleared when the unlocked graph leaves composition (lock), so vault list state cannot survive
 * into the lock screen. Must not be the Activity store: that would outlive [VaultSession].
 */
@Composable
fun rememberSessionViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(Unit) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}
