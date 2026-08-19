package com.personal.bubuprotect.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bubuprotect.core.security.DeviceThreatScanner
import com.personal.bubuprotect.data.local.UserPreferences
import com.personal.bubuprotect.domain.model.DeviceFinding
import com.personal.bubuprotect.domain.model.DeviceScanReport
import com.personal.bubuprotect.domain.model.RiskLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceCheckUiState(
    val report: DeviceScanReport = DeviceScanReport.Empty,
    val acknowledged: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val notice: String? = null
) {
    /** Flagged and not yet waved away. This is what the badge and the hero count are about. */
    val outstanding: List<DeviceFinding> = report.outstanding(acknowledged)

    /**
     * Findings the user has accepted, kept visible rather than deleted.
     *
     * An acknowledged risk is still a risk - the screen reader can still read a revealed password.
     * Hiding it entirely would make the screen lie by omission the moment the user pressed "this one
     * is mine", so it moves to a quiet section instead of disappearing.
     */
    val accepted: List<DeviceFinding> = report.flagged.filter { it.fingerprint in acknowledged }

    val worstOutstanding: RiskLevel? = outstanding.firstOrNull()?.level

    val criticalOutstanding: Int = outstanding.count { it.level == RiskLevel.CRITICAL }
}

/**
 * Holds one device-check pass for the unlocked session.
 *
 * ### Why the report is not persisted
 *
 * The breach verdicts are stored, because re-deriving them costs a network round trip per password. A
 * device finding is the opposite: re-deriving it costs a few milliseconds of local calls, and a stored
 * one is *actively misleading* - "no screen reader, as of Tuesday" says nothing about now, and now is
 * the only moment the user is asking about. So the scan is cheap, live, and thrown away on lock. The
 * only thing that outlives the session is the acknowledgement set, which is a decision rather than an
 * observation.
 *
 * ### Scoped to the unlocked shell
 *
 * Same owner as [VaultViewModel], so the settings sheet's badge and the device-check screen read one
 * report and cannot disagree with each other, and locking the vault clears it.
 *
 * @param appContext application context. A ViewModel outliving an Activity is the ordinary case, not
 *   an edge case, and the scanner is handed this rather than keeping one of its own.
 */
class DeviceCheckViewModel(
    private val scanner: DeviceThreatScanner,
    private val preferences: UserPreferences,
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(
        DeviceCheckUiState(acknowledged = preferences.acknowledgedDeviceRisks)
    )
    val state: StateFlow<DeviceCheckUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        rescan()
    }

    /**
     * Runs the probes again.
     *
     * Called on construction and every time the screen comes back to the foreground, because that is
     * exactly when the answer is most likely to have changed: the user has just been sent to a Settings
     * screen to turn something off, and a report that still showed the old finding would make them
     * think it had not worked.
     *
     * A scan already in flight is cancelled rather than queued. Two passes racing would resolve in an
     * arbitrary order and the later one is always the one worth keeping.
     */
    fun rescan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(isScanning = true) }
            val report = scanner.scan(appContext)
            _state.update {
                it.copy(
                    report = report,
                    // Re-read rather than trusted from the last snapshot: the set is written straight
                    // to preferences, and this keeps one source of truth for it.
                    acknowledged = preferences.acknowledgedDeviceRisks,
                    isScanning = false
                )
            }
        }
    }

    /**
     * "I know about this one."
     *
     * Silences [finding] and only [finding]. The key includes a hash of what was found, so the moment
     * a different app turns up under the same probe the fingerprint changes and the warning comes back
     * without the user having to remember to look.
     */
    fun acknowledge(finding: DeviceFinding) {
        val updated = preferences.acknowledgedDeviceRisks + finding.fingerprint
        preferences.acknowledgedDeviceRisks = updated
        _state.update { it.copy(acknowledged = updated) }
    }

    fun unacknowledge(finding: DeviceFinding) {
        val updated = preferences.acknowledgedDeviceRisks - finding.fingerprint
        preferences.acknowledgedDeviceRisks = updated
        _state.update { it.copy(acknowledged = updated) }
    }

    /**
     * Reports that Bubu could not open the Settings screen a finding pointed at.
     *
     * Manufacturer ROMs do remove and rename these activities, and a "Fix this" button that silently
     * does nothing is worse than one that admits the shortcut is missing and names where to go.
     */
    fun reportMissingSettingsScreen(where: String) {
        _state.update {
            it.copy(notice = "Bubu could not open that screen. Look for $where in Settings.")
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
}
