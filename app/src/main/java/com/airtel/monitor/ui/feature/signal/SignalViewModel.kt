package com.airtel.monitor.ui.feature.signal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.LockSettings
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
import com.airtel.monitor.data.model.RouterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignalUiState(
    val routerState: RouterState? = null,
    val lockSettings: LockSettings? = null,
    val showLockEditor: Boolean = false,
    val lockLoading: Boolean = false,
    val lockSaving: Boolean = false,
    val lockMessage: String? = null
)

class SignalViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val local = MutableStateFlow(LocalSignalState())

    val uiState: StateFlow<SignalUiState> = combine(
        container.statusRepository.routerState,
        container.signalRepository.lockSettings,
        local
    ) { routerState, lockSettings, loc ->
        SignalUiState(
            routerState = routerState,
            lockSettings = lockSettings,
            showLockEditor = loc.showLockEditor,
            lockLoading = loc.lockLoading,
            lockSaving = loc.lockSaving,
            lockMessage = loc.lockMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SignalUiState())

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

    private data class LocalSignalState(
        val showLockEditor: Boolean = false,
        val lockLoading: Boolean = false,
        val lockSaving: Boolean = false,
        val lockMessage: String? = null
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SignalViewModel(container) }
        }
    }
}
