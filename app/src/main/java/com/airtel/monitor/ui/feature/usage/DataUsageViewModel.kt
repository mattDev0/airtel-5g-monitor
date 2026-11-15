package com.airtel.monitor.ui.feature.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.local.DailyUsageStore
import com.airtel.monitor.data.model.DailyUsageEntry
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.model.TrafficLimitSettings
import com.airtel.monitor.data.repository.MobileRepository
import com.airtel.monitor.domain.MonthlyUsageSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataUsageUiState(
    val monthlyUsage: MonthlyUsageSummary = MonthlyUsageSummary(),
    val dailyHistory: List<DailyUsageEntry> = emptyList(),
    val limitSettings: TrafficLimitSettings? = null,
    val quotaEditable: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val showQuotaSheet: Boolean = false,
    val showCalibrateSheet: Boolean = false,
    val statusMessage: String? = null
)

class DataUsageViewModel internal constructor(
    routerStateFlow: Flow<RouterState?>,
    private val dailyUsageStore: DailyUsageStore,
    private val triggerRefresh: (Boolean) -> Unit,
    private val mobileRepository: MobileRepository? = null,
    featureFlagsFlow: Flow<RouterFeatureFlags> = flowOf(RouterFeatureFlags()),
    private val emitStatusMessage: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor(container: AppContainer) : this(
        routerStateFlow = container.statusRepository.routerState,
        dailyUsageStore = container.dailyUsageStore,
        triggerRefresh = { includeSlow -> container.pollingCoordinator.triggerImmediateRefresh(includeSlow) },
        mobileRepository = container.mobileRepository,
        featureFlagsFlow = container.featureFlagRepository.flags,
        emitStatusMessage = container::emitStatusMessage
    )

    private val local = MutableStateFlow(LocalUsageState())
    private var initialLoaded = false

    val uiState: StateFlow<DataUsageUiState> = combine(
        routerStateFlow,
        dailyUsageStore.history,
        mobileRepository?.trafficSettings ?: flowOf(null),
        featureFlagsFlow,
        local
    ) { routerState, history, trafficCfg, flags, loc ->
        DataUsageUiState(
            monthlyUsage = routerState?.monthlyUsage ?: MonthlyUsageSummary(),
            dailyHistory = history,
            limitSettings = trafficCfg,
            quotaEditable = !flags.isLoaded || flags.trafficLimitAvailable,
            isLoading = loc.isLoading,
            isSaving = loc.isSaving,
            showQuotaSheet = loc.showQuotaSheet,
            showCalibrateSheet = loc.showCalibrateSheet,
            statusMessage = loc.statusMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DataUsageUiState())

    fun ensureLoaded() {
        if (initialLoaded) return
        initialLoaded = true
        loadTrafficLimit()
    }

    fun loadTrafficLimit() {
        val repo = mobileRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isLoading = true) }
            val res = repo.loadTrafficLimit()
            local.update {
                it.copy(
                    isLoading = false,
                    statusMessage = if (res is RouterResult.Ok) it.statusMessage else "Error: ${res.errorMessage()}"
                )
            }
        }
    }

    fun refresh() {
        triggerRefresh(true)
        loadTrafficLimit()
    }

    fun clearLocalHistory() {
        dailyUsageStore.clearHistory()
    }

    fun openQuotaSheet() {
        local.update { it.copy(showQuotaSheet = true, statusMessage = null) }
        if (mobileRepository?.trafficSettings?.value == null) {
            loadTrafficLimit()
        }
    }

    fun openCalibrateSheet() {
        local.update { it.copy(showCalibrateSheet = true, statusMessage = null) }
        if (mobileRepository?.trafficSettings?.value == null) {
            loadTrafficLimit()
        }
    }

    fun dismissSheets() {
        local.update { it.copy(showQuotaSheet = false, showCalibrateSheet = false) }
    }

    fun saveTrafficLimit(
        limitEnabled: Boolean,
        limitSize: String,
        unitCode: String,
        startDate: Int
    ) {
        val repo = mobileRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isSaving = true, statusMessage = null) }
            when (val res = repo.saveTrafficLimit(limitEnabled, limitSize, unitCode, startDate)) {
                is RouterResult.Ok -> {
                    val msg = "Monthly quota settings saved"
                    local.update { it.copy(isSaving = false, showQuotaSheet = false, statusMessage = msg) }
                    emitStatusMessage(msg)
                    triggerRefresh(true)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isSaving = false, statusMessage = "Error: $err") }
                }
            }
        }
    }

    fun resetTrafficStatistics(targetUsedMb: Double) {
        val repo = mobileRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isSaving = true, statusMessage = null) }
            when (val res = repo.resetTrafficStatistics(targetUsedMb)) {
                is RouterResult.Ok -> {
                    val msg = if (targetUsedMb == 0.0) {
                        "Monthly usage counter reset"
                    } else {
                        "Monthly usage counter calibrated"
                    }
                    local.update { it.copy(isSaving = false, showCalibrateSheet = false, statusMessage = msg) }
                    emitStatusMessage(msg)
                    triggerRefresh(true)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isSaving = false, statusMessage = "Error: $err") }
                }
            }
        }
    }

    private data class LocalUsageState(
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val showQuotaSheet: Boolean = false,
        val showCalibrateSheet: Boolean = false,
        val statusMessage: String? = null
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DataUsageViewModel(container) }
        }
    }
}
