package com.personal.bubuprotect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.personal.bubuprotect.session.VaultSession
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.screens.EntryDetailRoute
import com.personal.bubuprotect.ui.screens.EntryEditorRoute
import com.personal.bubuprotect.ui.screens.UnlockRoute
import com.personal.bubuprotect.ui.screens.VaultRoute
import com.personal.bubuprotect.ui.vm.EntryDetailViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * The root.
 *
 * Locked and unlocked are one [AnimatedContent], driven by [VaultSession.isUnlocked] rather than by
 * navigation. Two consequences, both deliberate:
 *
 *  - The unlocked graph is *composed out of existence* on lock. Every ViewModel under it is cleared,
 *    every decrypted string it held becomes garbage, and there is no back stack entry left that
 *    could restore a vault screen without keys.
 *  - The session is the single source of truth. The UI cannot end up showing an open vault because
 *    it navigated somewhere, only because the keys are actually in memory.
 */
@Composable
fun BubuApp(modifier: Modifier = Modifier) {
    val session: VaultSession = koinInject()
    val isUnlocked by session.isUnlocked.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = isUnlocked,
        transitionSpec = { BubuMotion.revealEnter() togetherWith BubuMotion.revealExit() },
        label = "vaultGate",
        modifier = modifier.fillMaxSize()
    ) { unlocked ->
        if (unlocked) VaultNavHost() else UnlockRoute()
    }
}

/**
 * The unlocked graph.
 *
 * Transitions are set once on the [NavHost] and overridden per destination only where the meaning
 * differs: detail is *deeper* so it slides forward, the editor is a *task on top* so it rises. Doing
 * it here rather than inside each screen keeps a screen from having an opinion about how it is
 * arrived at, which is what makes them previewable in isolation.
 */
@Composable
private fun VaultNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.Vault,
        modifier = modifier.fillMaxSize(),
        enterTransition = { BubuMotion.forwardEnter() },
        exitTransition = { BubuMotion.forwardExit() },
        popEnterTransition = { BubuMotion.backEnter() },
        popExitTransition = { BubuMotion.backExit() }
    ) {
        composable<Routes.Vault> {
            VaultRoute(
                onOpenEntry = { entryId -> navController.navigate(Routes.Detail(entryId)) },
                onAddEntry = { navController.navigate(Routes.Editor()) }
            )
        }

        composable<Routes.Detail> { backStackEntry ->
            val route = backStackEntry.toRoute<Routes.Detail>()
            val viewModel: EntryDetailViewModel = koinViewModel { parametersOf(route.entryId) }

            // Coming back from the editor, the entry on screen is stale. The editor sets this flag
            // on the destination it returns to; reloading only when it is set avoids re-decrypting
            // on every resume, which would also cancel a reveal countdown the user is mid-way
            // through reading.
            val savedFlag by backStackEntry.savedStateHandle
                .getStateFlow(ENTRY_SAVED, false)
                .collectAsStateWithLifecycle()

            LaunchedEffect(savedFlag) {
                if (savedFlag) {
                    viewModel.load()
                    backStackEntry.savedStateHandle[ENTRY_SAVED] = false
                }
            }

            EntryDetailRoute(
                entryId = route.entryId,
                onBack = { navController.popBackStack() },
                onEdit = { entryId -> navController.navigate(Routes.Editor(entryId)) },
                viewModel = viewModel
            )
        }

        composable<Routes.Editor>(
            enterTransition = { BubuMotion.sheetEnter() },
            exitTransition = { BubuMotion.forwardExit() },
            popExitTransition = { BubuMotion.sheetExit() }
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Routes.Editor>()
            EntryEditorRoute(
                entryId = route.entryId,
                onDiscard = { navController.popBackStack() },
                onSaved = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(ENTRY_SAVED, true)
                    navController.popBackStack()
                }
            )
        }
    }
}

/** Result key for "the editor wrote something", read by whichever destination it returns to. */
private const val ENTRY_SAVED = "bubu.entrySaved"
