package com.airtel.monitor.ui.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.LockSettings
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.model.WifiRadioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val routerState: RouterState? = null,
    val wifiRadios: WifiRadioConfig? = null,
    val smsUnreadCount: Int = 0,
    val speedUnit: SpeedUnitPreference = SpeedUnitPreference.MBPS,
    val networkActionBusy: Boolean = false,
    val showLockEditor: Boolean = false,
    val lockSettings: LockSettings? = null,
    val lockLoading: Boolean = false,
    val lockSaving: Boolean = false,
    val lockMessage: String? = null
)

class HomeViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val local = MutableStateFlow(LocalHomeState())
    private var initialWifiLoaded = false

    private val repoState = combine(
        container.statusRepository.routerState,
        container.signalRepository.lockSettings,
        container.wifiRepository.config,
        container.statusRepository.smsUnreadCount
    ) { routerState, lockSettings, wifiConfig, smsUnread ->
        QuadHomeRepo(routerState, lockSettings, wifiConfig, smsUnread)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        repoState,
        container.preferencesRepository.preferencesFlow,
        local
    ) { repo, prefs, loc ->
        HomeUiState(
            routerState = repo.routerState,
            wifiRadios = repo.wifiConfig,
            smsUnreadCount = repo.smsUnread,
            speedUnit = prefs.speedUnit,
            networkActionBusy = loc.networkActionBusy,
            showLockEditor = loc.showLockEditor,
            lockSettings = repo.lockSettings,
            lockLoading = loc.lockLoading,
            lockSaving = loc.lockSaving,
            lockMessage = loc.lockMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private data class QuadHomeRepo(
        val routerState: RouterState?,
        val lockSettings: LockSettings?,
        val wifiConfig: WifiRadioConfig?,
        val smsUnread: Int
    )

    fun ensureWifiRadiosLoaded() {
        if (initialWifiLoaded) return
        initialWifiLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            container.wifiRepository.loadWifiRadios()
        }
    }

    fun setMobileData(on: Boolean) = runNetworkAction { container.mobileRepository.setMobileData(on) }

    fun setFlightMode(on: Boolean) = runNetworkAction { container.mobileRepository.setFlightMode(on) }

    fun setWifi24g(on: Boolean) = runWifiToggle { container.wifiRepository.setWifi24g(on) }

    fun setWifi5g(on: Boolean) = runWifiToggle { container.wifiRepository.setWifi5g(on) }

    fun rescanNetwork() = runNetworkAction { container.mobileRepository.rescanNetwork() }

    fun emitFeedback(message: String) {
        container.emitStatusMessage(message)
    }

    private fun runWifiToggle(action: suspend () -> RouterResult<String>) {
        if (local.value.networkActionBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(networkActionBusy = true) }
            val result = action()
            container.wifiRepository.loadWifiRadios()
            local.update { it.copy(networkActionBusy = false) }
            val msg = when (result) {
                is RouterResult.Ok -> result.data
                else -> result.errorMessage().ifEmpty { "Router rejected the Wi-Fi change" }
            }
            container.emitStatusMessage(msg)
        }
    }

    private fun runNetworkAction(action: suspend () -> RouterResult<String>) {
        if (local.value.networkActionBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(networkActionBusy = true) }
            val result = action()
            local.update { it.copy(networkActionBusy = false) }
            val msg = when (result) {
                is RouterResult.Ok -> result.data
                else -> result.errorMessage().ifEmpty { "Router rejected the change" }
            }
            container.emitStatusMessage(msg)
            container.statusRepository.pollFast(forceSlow = true)
        }
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

    private data class LocalHomeState(
        val networkActionBusy: Boolean = false,
        val showLockEditor: Boolean = false,
        val lockLoading: Boolean = false,
        val lockSaving: Boolean = false,
        val lockMessage: String? = null
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
