package com.personal.bubuprotect.ui.vm

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.shield.AppRiskScanner
import com.personal.bubuprotect.core.shield.ShieldCapabilities
import com.personal.bubuprotect.core.shield.enforce.RemediationLadder
import com.personal.bubuprotect.core.shield.enforce.ShizukuGateway
import com.personal.bubuprotect.core.shield.intel.SignerBlocklist
import com.personal.bubuprotect.core.shield.network.ShieldVpnService
import com.personal.bubuprotect.core.shield.recorder.FlightRecorder
import com.personal.bubuprotect.core.shield.recorder.UsageTimelineProbe
import com.personal.bubuprotect.data.local.UserPreferences
import com.personal.bubuprotect.domain.model.AppScanReport
import com.personal.bubuprotect.domain.model.AppVerdict
import com.personal.bubuprotect.domain.model.RemediationTier
import com.personal.bubuprotect.domain.model.ShieldEvent
import com.personal.bubuprotect.domain.model.ignoreKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @param evidenceFor per-package detail assembled from the recorder, so the culprit card can show
 *   counts and hostnames without the screen reaching into the recorder itself.
 */
data class ShieldUiState(
    val report: AppScanReport = AppScanReport.Empty,
    val capabilities: ShieldCapabilities.Snapshot? = null,
    val ignored: Set<String> = emptySet(),
    val filtered: Set<String> = emptySet(),
    val evidenceFor: Map<String, ShieldEvidence> = emptyMap(),
    val replay: List<ShieldEvent> = emptyList(),
    val isScanning: Boolean = false,
    val busyPackage: String? = null,
    val notice: String? = null,
    val undo: UndoableAction? = null,
    val blocklistSize: Int = 0
) {
    val culprits: List<AppVerdict> = report.outstanding(ignored)

    val tolerated: List<AppVerdict> = report.convicted.filter { it.ignoreKey in ignored }

    val suspects: List<AppVerdict> = report.suspects

    val tiers: Set<RemediationTier> =
        capabilities?.remediationTiers ?: setOf(RemediationTier.ADVISE)

    /**
     * True when the shield can only ever produce suspects.
     *
     * The screen leads with this rather than with an empty result, because "no culprits found" from a
     * shield with no sensors switched on is not a finding - it is the absence of one, and painting it
     * as good news would be the single most misleading thing this screen could do.
     */
    val isBlind: Boolean = capabilities?.canObserveBehaviour == false
}

/**
 * An action that can be taken back, offered alongside the notice that reports it.
 *
 * ### Why the undo lives on the notice and not on the app's card
 *
 * Because after a successful remediation the card is usually gone. Neutering an app strips its
 * `CAN_DRAW_OVERLAYS` signal, which drops its score below the threshold; disabling or removing it
 * changes what the next scan sees entirely. So the card that held the button cannot be where the undo
 * lives - the notice is the only surface still on screen at the moment the user realises they made a
 * mistake.
 *
 * [RemediationTier.REMOVE] never produces one of these. An uninstall cannot be taken back, and
 * offering an undo for it would be a lie.
 */
data class UndoableAction(
    val packageName: String,
    val label: String,
    val tier: RemediationTier
)

/** What was actually observed about one app, for its evidence card. */
data class ShieldEvidence(
    val overlayCount: Int,
    val adHosts: Set<String>
)

/**
 * Owns one shield pass for the unlocked session.
 *
 * ### Why the report is not persisted
 *
 * Same reasoning as [DeviceCheckViewModel], and it applies harder here. A stored verdict says "this app
 * was drawing ads at 4pm on Tuesday", and the user is asking about now. Worse, the report names apps: a
 * durable file listing which of the user's apps this app considers adware is a profile of their phone,
 * and the manifest's package-visibility comment promises it is not written down. Verdicts live here and
 * die with the session.
 *
 * The two things that do persist are decisions rather than observations - which apps the user chose to
 * tolerate, and which they chose to filter.
 *
 * @param appContext application context. This ViewModel outlives any Activity by design, and every
 *   collaborator that needs a Context is handed this one rather than keeping its own.
 */
class ShieldViewModel(
    private val scanner: AppRiskScanner,
    private val capabilities: ShieldCapabilities,
    private val ladder: RemediationLadder,
    private val shizuku: ShizukuGateway,
    private val recorder: FlightRecorder,
    private val usage: UsageTimelineProbe,
    private val preferences: UserPreferences,
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(
        ShieldUiState(
            ignored = preferences.ignoredShieldApps,
            filtered = preferences.filteredShieldApps
        )
    )
    val state: StateFlow<ShieldUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var blocklist: SignerBlocklist = SignerBlocklist.Empty

    init {
        // Pushed into the VPN's static set on construction as well as on change, so a tunnel that was
        // already running when this session started is filtering the right apps rather than none.
        ShieldVpnService.filteredPackages = preferences.filteredShieldApps
        rescan()
    }

    /**
     * Runs the whole pass: capabilities, usage timeline, blocklist, scan.
     *
     * Called on construction and on every resume, because resume is exactly when the answer is most
     * likely to have changed - the user has just come back from a Settings screen where they granted
     * something, and a screen still showing the old state would read as the grant not having worked.
     *
     * An in-flight pass is cancelled rather than queued: two racing scans resolve in arbitrary order and
     * the later one is always the one worth keeping.
     */
    fun rescan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update {
                it.copy(isScanning = true, capabilities = capabilities.snapshot(appContext))
            }

            if (blocklist.isEmpty) blocklist = SignerBlocklist.load(appContext)

            // Before the scan, so its findings are in the recorder when signals are computed.
            usage.collect(appContext, recorder)

            val report = scanner.scan(appContext, blocklist)

            _state.update { current ->
                current.copy(
                    report = report,
                    capabilities = capabilities.snapshot(appContext),
                    ignored = preferences.ignoredShieldApps,
                    filtered = preferences.filteredShieldApps,
                    evidenceFor = evidenceFor(report),
                    isScanning = false,
                    blocklistSize = blocklist.size
                )
            }
        }
    }

    private fun evidenceFor(report: AppScanReport): Map<String, ShieldEvidence> =
        (report.convicted + report.suspects).associate { verdict ->
            verdict.packageName to ShieldEvidence(
                overlayCount = recorder.overlayCountFor(verdict.packageName),
                adHosts = recorder.adHostsFor(verdict.packageName)
            )
        }

    /**
     * The panic button: "an ad just hit me".
     *
     * Reads the last minute out of the recorder and puts it on screen unfiltered, newest first. No
     * scoring and no ranking - the user is asking what happened, and the honest answer is the log.
     */
    fun replayRecent() {
        _state.update { it.copy(replay = recorder.replay()) }
    }

    fun dismissReplay() = _state.update { it.copy(replay = emptyList()) }

    fun tolerate(verdict: AppVerdict) {
        val updated = preferences.ignoredShieldApps + verdict.ignoreKey
        preferences.ignoredShieldApps = updated
        _state.update { it.copy(ignored = updated) }
    }

    fun untolerate(verdict: AppVerdict) {
        val updated = preferences.ignoredShieldApps - verdict.ignoreKey
        preferences.ignoredShieldApps = updated
        _state.update { it.copy(ignored = updated) }
    }

    /**
     * Turns ad-network blocking on or off for one app.
     *
     * Per app rather than device-wide, because a blanket block breaks free apps whose ads are how they
     * are paid for. Written straight through to the VPN's static set so a running tunnel picks it up on
     * the next query without a restart.
     */
    fun setFiltered(packageName: String, filtered: Boolean) {
        val updated = if (filtered) preferences.filteredShieldApps + packageName
        else preferences.filteredShieldApps - packageName

        preferences.filteredShieldApps = updated
        ShieldVpnService.filteredPackages = updated
        _state.update { it.copy(filtered = updated) }
    }

    fun dismissNotifications(verdict: AppVerdict) {
        val cleared = ladder.dismissNotifications(verdict.packageName)
        _state.update {
            it.copy(
                notice = when {
                    cleared == null -> "Bubu needs notification access to clear those."
                    cleared == 0 -> "${verdict.displayName} has nothing in the shade right now."
                    else -> "Cleared $cleared from ${verdict.displayName}."
                }
            )
        }
    }

    /** Revoke the overlay capability, keep the app. The rung most users actually want. */
    fun neuter(verdict: AppVerdict) = runRung(
        verdict = verdict,
        successPhrase = "can no longer draw over other apps",
        undoTier = RemediationTier.NEUTER
    ) { ladder.neuter(verdict.packageName) }

    fun disable(verdict: AppVerdict) = runRung(
        verdict = verdict,
        successPhrase = "is disabled",
        undoTier = RemediationTier.DISABLE
    ) { ladder.disable(verdict.packageName) }

    /** No undo offered: an uninstall is not reversible, and an Undo button implying it is would lie. */
    fun remove(verdict: AppVerdict) = runRung(
        verdict = verdict,
        successPhrase = "is uninstalled",
        undoTier = null
    ) { ladder.remove(verdict.packageName) }

    /**
     * Puts back whatever [action] took away.
     *
     * Reported through the same notice channel as the original but without a further undo: a reversal
     * offering its own reversal leaves the user toggling a state they have already seen twice.
     */
    fun undo(action: UndoableAction) {
        viewModelScope.launch {
            _state.update { it.copy(busyPackage = action.packageName, undo = null) }

            val outcome = when (action.tier) {
                RemediationTier.NEUTER -> ladder.unneuter(action.packageName)
                RemediationTier.DISABLE -> ladder.enable(action.packageName)
                // Unreachable - neither tier ever produces an UndoableAction. Handled rather than
                // thrown, because crashing on an impossible branch is a poor trade.
                RemediationTier.ADVISE, RemediationTier.REMOVE -> null
            }

            val notice = when (outcome) {
                is ShizukuGateway.Result.Success -> "${action.label} is back to normal."
                null -> "There is nothing to undo for that."
                else -> "Bubu could not undo that. Check ${action.label} in Settings."
            }

            _state.update { it.copy(busyPackage = null, notice = notice) }
            rescan()
        }
    }

    /**
     * Reports that a Settings screen an action pointed at could not be opened.
     *
     * Manufacturer ROMs do remove and rename these activities. Mirrors
     * [DeviceCheckViewModel.reportMissingSettingsScreen] for the same reason it exists there: a button
     * that silently does nothing is worse than one admitting the shortcut is missing and naming where
     * to go by hand.
     */
    fun reportMissingSettingsScreen(where: String) {
        _state.update {
            it.copy(notice = "Bubu could not open that screen. Look for $where in Settings.")
        }
    }

    /** Whether [intent] resolves here, so the UI reports a dead shortcut rather than a silent no-op. */
    fun canOpen(intent: Intent): Boolean = ladder.canOpen(appContext, intent)

    /**
     * Runs one Shizuku rung and reports what happened in the user's terms.
     *
     * Rescans on success rather than mutating the report in place: the action changes facts the scan
     * reads - a removed package is gone from the list, a neutered one has lost a signal - and patching
     * the state by hand would leave the screen asserting something the device no longer agrees with.
     */
    private fun runRung(
        verdict: AppVerdict,
        successPhrase: String,
        undoTier: RemediationTier?,
        action: suspend () -> ShizukuGateway.Result
    ) {
        viewModelScope.launch {
            _state.update { it.copy(busyPackage = verdict.packageName) }

            val outcome = action()
            val notice = when (outcome) {
                is ShizukuGateway.Result.Success -> "${verdict.displayName} $successPhrase."

                is ShizukuGateway.Result.Failed ->
                    "That did not work on this phone (exit ${outcome.exitCode})." +
                        outcome.output.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()

                is ShizukuGateway.Result.NotAvailable -> when (outcome.availability) {
                    ShizukuGateway.Availability.NotInstalled ->
                        "This needs Shizuku, which is not installed."
                    ShizukuGateway.Availability.NotRunning ->
                        "Shizuku is installed but not running. Start it and try again."
                    ShizukuGateway.Availability.PermissionRequired ->
                        "Shizuku has not granted Bubu access yet."
                    ShizukuGateway.Availability.Ready ->
                        "Shizuku became unavailable mid-action."
                }

                is ShizukuGateway.Result.Unsupported -> "Shizuku could not run that: ${outcome.why}"
            }

            _state.update {
                it.copy(
                    busyPackage = null,
                    notice = notice,
                    undo = undoTier
                        ?.takeIf { outcome is ShizukuGateway.Result.Success }
                        ?.let { tier -> UndoableAction(verdict.packageName, verdict.displayName, tier) }
                )
            }
            rescan()
        }
    }

    /**
     * Asks Shizuku for access.
     *
     * Fire-and-forget by design: the answer arrives in Shizuku's own dialog, and the state is re-read on
     * resume - which is the moment the user comes back from having granted it. Wiring a result callback
     * through here would duplicate a path [rescan] already covers.
     */
    fun requestShizukuPermission() {
        shizuku.requestPermission()
    }

    // Intent factories, passed straight through from the ladder.
    //
    // The screen needs a Context to launch these and the ViewModel must not hold an Activity, so the
    // split is: the ViewModel builds the Intent, the composable launches it. That also keeps the
    // knowledge of *which* Settings action fixes *which* grant in one place - the ladder - rather than
    // spread across the UI.

    fun accessibilityIntent() = ladder.accessibilitySettingsIntent()

    fun notificationAccessIntent() = ladder.notificationAccessIntent()

    fun usageAccessIntent() = ladder.usageAccessIntent()

    fun uninstallIntent(packageName: String) = ladder.uninstallIntent(packageName)

    fun appSettingsIntent(packageName: String) = ladder.appSettingsIntent(packageName)

    fun dismissNotice() = _state.update { it.copy(notice = null, undo = null) }

    /** Cleared on lock so a session's observations do not outlive it. */
    override fun onCleared() {
        recorder.clear()
        super.onCleared()
    }
}
