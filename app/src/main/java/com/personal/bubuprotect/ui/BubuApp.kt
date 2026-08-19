package com.personal.bubuprotect.ui

import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.personal.bubuprotect.session.VaultSession
import com.personal.bubuprotect.core.autofill.AutofillSettings
import com.personal.bubuprotect.core.backup.VaultBackupEnvelope
import com.personal.bubuprotect.core.backup.VaultBackupService
import com.personal.bubuprotect.ui.motion.BubuMotion
import com.personal.bubuprotect.ui.components.BreachAlertDialog
import com.personal.bubuprotect.ui.components.BackupPassphraseDialog
import com.personal.bubuprotect.ui.screens.DeviceCheckRoute
import com.personal.bubuprotect.ui.screens.EntryDetailRoute
import com.personal.bubuprotect.ui.screens.BreachReportScreen
import com.personal.bubuprotect.ui.screens.EntryEditorRoute
import com.personal.bubuprotect.ui.screens.MainHeader
import com.personal.bubuprotect.ui.screens.MainScreen
import com.personal.bubuprotect.ui.screens.SecurityGuideScreen
import com.personal.bubuprotect.ui.screens.UnlockRoute
import com.personal.bubuprotect.ui.screens.VaultRoute
import com.personal.bubuprotect.ui.screens.VaultSettingsSheet
import com.personal.bubuprotect.ui.screens.rememberSessionViewModelStoreOwner
import com.personal.bubuprotect.ui.vm.DeviceCheckViewModel
import com.personal.bubuprotect.ui.vm.EntryDetailViewModel
import com.personal.bubuprotect.ui.vm.VaultViewModel
import com.personal.bubuprotect.ui.vm.rememberBiometricGate
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
        if (unlocked) UnlockedShell() else UnlockRoute()
    }
}

/**
 * The unlocked app: one [MainScreen] around the whole graph.
 *
 * Header and footer stay composed while destinations swap inside the content slot. The vault
 * ViewModel is scoped to this shell - not the Activity - so lock still wipes list state.
 */
@Composable
private fun UnlockedShell(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val sessionOwner = rememberSessionViewModelStoreOwner()
    val vaultViewModel: VaultViewModel = koinViewModel(viewModelStoreOwner = sessionOwner)
    /*
     * Session-scoped, and hosted here rather than inside the device-check destination.
     *
     * Two things read one report that way - the badge on the settings row and the screen itself - so
     * they cannot disagree about how many findings there are, and the first scan has already run by
     * the time the user taps through rather than starting when they arrive. It dies with the shell on
     * lock, which is correct: a device finding is only a statement about right now.
     */
    val deviceCheckViewModel: DeviceCheckViewModel = koinViewModel(viewModelStoreOwner = sessionOwner)
    val backupService: VaultBackupService = koinInject()
    val vaultState by vaultViewModel.state.collectAsStateWithLifecycle()
    val deviceState by deviceCheckViewModel.state.collectAsStateWithLifecycle()
    val gate = rememberBiometricGate()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    /*
     * Whether this app is the device's autofill provider.
     *
     * Re-read on every resume rather than once, because the only way to change it is to leave for
     * the system picker and come back. Read once at composition, the settings row would still say
     * "Turn on" immediately after the user had turned it on - which reads as the button not having
     * worked, and invites them to do it again.
     */
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var autofillProbe by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) autofillProbe++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val autofillSupported = remember(autofillProbe) { AutofillSettings.isSupported(context) }
    val autofillEnabled = remember(autofillProbe) { AutofillSettings.isEnabled(context) }

    /*
     * Backup export, in two steps.
     *
     * The picker runs first and the passphrase is asked for second, deliberately. The Storage Access
     * Framework hands back a Uri and nothing else, so the app never names a directory, holds no
     * storage permission, and cannot write a vault anywhere the user did not point at. Asking for
     * the passphrase before the picker would also mean holding it across a whole activity round trip
     * for no reason.
     */
    var exportDestination by remember { mutableStateOf<Uri?>(null) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VaultBackupEnvelope.MIME_TYPE)
    ) { uri -> exportDestination = uri }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onVault = backStackEntry?.destination?.hasRoute<Routes.Vault>() == true

    MainScreen(
        modifier = modifier,
        topBar = {
            // Status-bar inset stays even when the brand header hides, so detail/editor app bars
            // (which use zero window insets) never slide under the clock.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                        )
                    )
            ) {
                AnimatedVisibility(
                    visible = onVault,
                    enter = fadeIn(tween(BubuMotion.FAST)) + expandVertically(tween(BubuMotion.FAST)),
                    exit = fadeOut(tween(BubuMotion.FAST)) + shrinkVertically(tween(BubuMotion.FAST))
                ) {
                    MainHeader(
                        entryCount = vaultState.totalCount,
                        onLock = vaultViewModel::lock,
                        onOpenSettings = { settingsOpen = true }
                    )
                }
            }
        }
    ) { innerPadding ->
        VaultNavHost(
            navController = navController,
            vaultViewModel = vaultViewModel,
            deviceCheckViewModel = deviceCheckViewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    if (settingsOpen) {
        VaultSettingsSheet(
            biometricUnlockEnabled = vaultState.biometricUnlockEnabled,
            canOfferBiometricUnlock = vaultState.canOfferBiometricUnlock,
            entryCount = vaultState.totalCount,
            onToggleBiometricUnlock = { enable ->
                if (enable) vaultViewModel.enableBiometricUnlock(gate) else {
                    vaultViewModel.disableBiometricUnlock()
                }
            },
            strictRevealEnabled = vaultState.strictRevealEnabled,
            onToggleStrictReveal = vaultViewModel::setStrictReveal,
            autofillSupported = autofillSupported,
            autofillEnabled = autofillEnabled,
            onOpenAutofillSettings = {
                settingsOpen = false
                // Failure is survivable and unremarkable: some builds ship without the settings
                // screen this resolves to, and the row's own copy already says whether the feature
                // exists. A crash here would be a crash for opening a settings sheet.
                runCatching {
                    context.startActivity(AutofillSettings.requestEnableIntent(context))
                }
            },
            onOpenSecurityGuide = {
                settingsOpen = false
                navController.navigate(Routes.SecurityGuide)
            },
            breachMonitoringEnabled = vaultState.breachMonitoringEnabled,
            breachedCount = vaultState.breached.size,
            onToggleBreachMonitoring = vaultViewModel::setBreachMonitoring,
            onOpenBreachReport = {
                settingsOpen = false
                navController.navigate(Routes.BreachReport)
            },
            deviceRiskCount = deviceState.outstanding.size,
            hasCriticalDeviceRisk = deviceState.criticalOutstanding > 0,
            onOpenDeviceCheck = {
                settingsOpen = false
                navController.navigate(Routes.DeviceCheck)
            },
            onExportBackup = {
                settingsOpen = false
                exportPicker.launch(backupService.suggestedFileName(todayStamp()))
            },
            onLock = {
                settingsOpen = false
                vaultViewModel.lock()
            },
            onDismiss = { settingsOpen = false }
        )
    }

    /**
     * Hosted at the shell rather than inside a screen.
     *
     * A breached password is true wherever the user happens to be standing, and a dialog owned by
     * the vault list would only appear if they were looking at the vault list - so a scan that
     * finished while they were reading an entry would say nothing until they navigated back.
     *
     * The settings sheet is the one exception: a modal bottom sheet and an alert dialog stacked on
     * top of each other is a scrim over a scrim, and the sheet has its own visible breach row.
     */
    exportDestination?.let { destination ->
        BackupPassphraseDialog(
            title = "Save a backup",
            body = "Bubu will seal every secret in the vault into one file, locked with your " +
                "master passphrase. The file is useless to anyone who does not know it.",
            confirmLabel = "Save it",
            footnote = "Keep it somewhere you trust. If you forget this passphrase the file " +
                "cannot be opened by anyone, including Bubu.",
            isBusy = vaultState.isBackupRunning,
            onConfirm = { passphrase -> vaultViewModel.exportBackup(destination, passphrase) },
            onDismiss = { exportDestination = null }
        )
    }

    /*
     * Closes the dialog once the write finishes, so the spinner stays up for the whole KDF and write
     * rather than flashing away the instant the button is tapped.
     *
     * Keyed on the running -> not-running *transition*, not on the flag itself: the flag is already
     * false when the dialog first appears, so reacting to the value alone would dismiss it before the
     * user could type anything.
     */
    var exportWasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(vaultState.isBackupRunning) {
        if (exportWasRunning && !vaultState.isBackupRunning) exportDestination = null
        exportWasRunning = vaultState.isBackupRunning
    }

    if (vaultState.breachAlertVisible && !settingsOpen) {
        BreachAlertDialog(
            breached = vaultState.breached,
            onIgnore = vaultViewModel::ignoreBreachAlert,
            onReview = {
                vaultViewModel.dismissBreachAlert()
                // Nothing to pop first: the report is a place the user can back out of to wherever
                // they were, which is friendlier than being teleported to the vault root.
                navController.navigate(Routes.BreachReport)
            }
        )
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
    vaultViewModel: VaultViewModel,
    deviceCheckViewModel: DeviceCheckViewModel,
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
                onAddEntry = { navController.navigate(Routes.Editor()) },
                viewModel = vaultViewModel
            )
        }

        /*
         * Reached only from Settings, so it is always a re-read.
         *
         * First-run onboarding is not here - it lives in [UnlockRoute], gated on UnlockStage.SETUP.
         * It used to be auto-navigated to from the unlocked shell, which meant it could only ever
         * appear *after* the user was already inside their vault, and re-fired on every lock/unlock
         * cycle because the shell is torn down and rebuilt each time - so any session that ended
         * before the guide was dismissed brought it straight back.
         *
         * No preference is written here: by the time anyone can reach Settings they are enrolled, so
         * there is nothing left for a "have they seen it" flag to gate.
         */
        composable<Routes.SecurityGuide> {
            val closeGuide = {
                navController.popBackStack()
                Unit
            }
            BackHandler(onBack = closeGuide)
            SecurityGuideScreen(
                onDone = closeGuide,
                doneLabel = "Got it",
                onExit = closeGuide,
                reserveTopInset = false
            )
        }

        composable<Routes.BreachReport> {
            val state by vaultViewModel.state.collectAsStateWithLifecycle()
            val gate = rememberBiometricGate()
            BreachReportScreen(
                // The unfiltered list: this screen has its own idea of what to show, and inheriting
                // the vault's search box would let a stray query hide a breached entry from a
                // report whose entire job is to list them.
                items = state.allItems,
                scanState = state.scan,
                onBack = { navController.popBackStack() },
                onOpenEntry = { item -> navController.navigate(Routes.Detail(item.id)) },
                onScan = { vaultViewModel.runBreachScan(gate, force = false) }
            )
        }

        /*
         * The ViewModel comes from the shell rather than being resolved here, so the report behind the
         * settings badge and the report on this screen are the same object. Resolving a fresh one per
         * visit would start a new scan on every arrival and let the badge say "2" while the screen
         * showed three.
         */
        composable<Routes.DeviceCheck> {
            DeviceCheckRoute(
                onBack = { navController.popBackStack() },
                viewModel = deviceCheckViewModel
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
                onOpenBreachReport = { navController.navigate(Routes.BreachReport) },
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

/**
 * `2026-08-19`, for the pre-filled backup filename.
 *
 * ISO order so successive exports sort chronologically in a file browser, and nothing else in the
 * name - not the entry count, not the device. A backup sitting in a shared folder should not
 * advertise what it is a backup of.
 */
private fun todayStamp(): String =
    java.time.LocalDate.now().toString()
