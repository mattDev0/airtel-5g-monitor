package com.airtel.monitor.ui.feature.signal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.PollingMode
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.LockSettings
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.domain.AlignmentSnapshot
import com.airtel.monitor.domain.NeighborCell
import com.airtel.monitor.domain.SignalHistoryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignalUiState(
    val routerState: RouterState? = null,
    val lockSettings: LockSettings? = null,
    val signalHistory: List<SignalHistoryPoint> = emptyList(),
    val neighborCells: List<NeighborCell> = emptyList(),
    val neighborsLoading: Boolean = false,
    val rescanBusy: Boolean = false,
    val showLockEditor: Boolean = false,
    val lockLoading: Boolean = false,
    val lockSaving: Boolean = false,
    val lockMessage: String? = null,
    val alignmentSnapshot: AlignmentSnapshot? = null,
    val alignmentSoundEnabled: Boolean = true,
    val bestSinr: Float? = null,
    val bestRsrp: Float? = null,
    val alignmentSinrHistory: List<Float> = emptyList(),
    val alignmentRsrpHistory: List<Float> = emptyList()
)

class SignalViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val local = MutableStateFlow(LocalSignalState())
    private var initialNeighborsLoaded = false
    private var alignmentActive = false

    val uiState: StateFlow<SignalUiState> = combine(
        container.statusRepository.routerState,
        container.signalRepository.lockSettings,
        container.statusRepository.signalHistory,
        container.signalRepository.neighborCells,
        container.statusRepository.alignmentSnapshot,
        local
    ) { values ->
        val routerState = values[0] as? RouterState
        val lockSettings = values[1] as? LockSettings
        @Suppress("UNCHECKED_CAST")
        val history = values[2] as List<SignalHistoryPoint>
        @Suppress("UNCHECKED_CAST")
        val neighbors = values[3] as List<NeighborCell>
        val alignment = values[4] as? AlignmentSnapshot
        val loc = values[5] as LocalSignalState

        SignalUiState(
            routerState = routerState,
            lockSettings = lockSettings,
            signalHistory = history,
            neighborCells = neighbors,
            neighborsLoading = loc.neighborsLoading,
            rescanBusy = loc.rescanBusy,
            showLockEditor = loc.showLockEditor,
            lockLoading = loc.lockLoading,
            lockSaving = loc.lockSaving,
            lockMessage = loc.lockMessage,
            alignmentSnapshot = alignment,
            alignmentSoundEnabled = loc.alignmentSoundEnabled,
            bestSinr = loc.bestSinr,
            bestRsrp = loc.bestRsrp,
            alignmentSinrHistory = loc.alignmentSinrHistory,
            alignmentRsrpHistory = loc.alignmentRsrpHistory
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SignalUiState())

    init {
        viewModelScope.launch {
            container.statusRepository.alignmentSnapshot
                .filterNotNull()
                .collect { snap ->
                    if (!alignmentActive) return@collect
                    recordAlignmentSample(snap)
                }
        }
    }

    fun ensureNeighborsLoaded() {
        if (initialNeighborsLoaded) return
        initialNeighborsLoaded = true
        loadNeighborCells()
    }

    fun loadNeighborCells() {
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(neighborsLoading = true) }
            container.signalRepository.loadNeighborCells()
            local.update { it.copy(neighborsLoading = false) }
        }
    }

    fun rescanNetwork() {
        if (local.value.rescanBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(rescanBusy = true) }
            val result = container.mobileRepository.rescanNetwork()
            local.update { it.copy(rescanBusy = false) }
            val msg = when (result) {
                is RouterResult.Ok -> result.data
                else -> result.errorMessage().ifEmpty { "Router rejected network re-search" }
            }
            container.emitStatusMessage(msg)
            container.statusRepository.pollFast(forceSlow = true)
        }
    }

    fun enterAlignmentMode() {
        alignmentActive = true
        val current = container.statusRepository.alignmentSnapshot.value
        local.update {
            it.copy(
                bestSinr = current?.primarySinr,
                bestRsrp = current?.primaryRsrp,
                alignmentSinrHistory = listOfNotNull(current?.primarySinr),
                alignmentRsrpHistory = listOfNotNull(current?.primaryRsrp)
            )
        }
        container.pollingCoordinator.setMode(PollingMode.ALIGNMENT)
    }

    fun exitAlignmentMode() {
        if (!alignmentActive) return
        alignmentActive = false
        container.pollingCoordinator.setMode(PollingMode.NORMAL)
    }

    fun toggleAlignmentSound() {
        local.update { it.copy(alignmentSoundEnabled = !it.alignmentSoundEnabled) }
    }

    fun resetAlignmentBest() {
        val current = container.statusRepository.alignmentSnapshot.value
        local.update {
            it.copy(
                bestSinr = current?.primarySinr,
                bestRsrp = current?.primaryRsrp
            )
        }
    }

    private fun recordAlignmentSample(snap: AlignmentSnapshot) {
        val curSinr = snap.primarySinr
        val curRsrp = snap.primaryRsrp
        local.update { prev ->
            val newBestSinr = when {
                curSinr == null -> prev.bestSinr
                prev.bestSinr == null -> curSinr
                else -> maxOf(prev.bestSinr, curSinr)
            }
            val newBestRsrp = when {
                curRsrp == null -> prev.bestRsrp
                prev.bestRsrp == null -> curRsrp
                else -> maxOf(prev.bestRsrp, curRsrp)
            }
            val sinrHist = if (curSinr != null) (prev.alignmentSinrHistory + curSinr).takeLast(30) else prev.alignmentSinrHistory
            val rsrpHist = if (curRsrp != null) (prev.alignmentRsrpHistory + curRsrp).takeLast(30) else prev.alignmentRsrpHistory
            prev.copy(
                bestSinr = newBestSinr,
                bestRsrp = newBestRsrp,
                alignmentSinrHistory = sinrHist,
                alignmentRsrpHistory = rsrpHist
            )
        }
    }

    fun emitFeedback(message: String) {
        container.emitStatusMessage(message)
    }

    fun openLockEditor() {
        local.update { it.copy(showLockEditor = true, lockMessage = null) }
        loadLocks()
    }

    fun dismissLockEditor() {
        local.update { it.copy(showLockEditor = false, lockMessage = null) }
    }

    fun loadLocks() {
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(lockLoading = true) }
            val res = container.signalRepository.loadLockSettings()
            local.update {
                it.copy(
                    lockLoading = false,
                    lockMessage = if (res is RouterResult.Ok) it.lockMessage else "Could not read lock settings from the router"
                )
            }
        }
    }

    fun saveBandLock(lock4g: Boolean, bands4g: Set<Int>, lock5g: Boolean, bands5g: Set<Int>) =
        runLockSave("Band lock saved and confirmed by router") {
            container.signalRepository.saveBandLock(lock4g, bands4g, lock5g, bands5g)
        }

    fun saveLteCellLock(enabled: Boolean, cells: List<LteLockCell>) =
        runLockSave("${if (enabled && cells.isNotEmpty()) "4G cell lock" else "4G cell unlock"} saved and confirmed by router") {
            container.signalRepository.saveLteCellLock(enabled, cells)
        }

    fun saveNrCellLock(enabled: Boolean, cells: List<NrLockCell>) =
        runLockSave("${if (enabled && cells.isNotEmpty()) "5G cell lock" else "5G cell unlock"} saved and confirmed by router") {
            container.signalRepository.saveNrCellLock(enabled, cells)
        }

    private fun runLockSave(successMessage: String, save: suspend () -> RouterResult<*>) {
        if (local.value.lockSaving) return
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(lockSaving = true, lockMessage = null) }
            val res = save()
            container.signalRepository.loadLockSettings()
            local.update {
                it.copy(
                    lockSaving = false,
                    lockMessage = if (res is RouterResult.Ok) successMessage else res.errorMessage().ifEmpty { "Save failed" }
                )
            }
            container.statusRepository.pollFast(forceSlow = true)
        }
    }

    override fun onCleared() {
        exitAlignmentMode()
        super.onCleared()
    }

    private data class LocalSignalState(
        val neighborsLoading: Boolean = false,
        val rescanBusy: Boolean = false,
        val showLockEditor: Boolean = false,
        val lockLoading: Boolean = false,
        val lockSaving: Boolean = false,
        val lockMessage: String? = null,
        val alignmentSoundEnabled: Boolean = true,
        val bestSinr: Float? = null,
        val bestRsrp: Float? = null,
        val alignmentSinrHistory: List<Float> = emptyList(),
        val alignmentRsrpHistory: List<Float> = emptyList()
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SignalViewModel(container) }
        }
    }
}
