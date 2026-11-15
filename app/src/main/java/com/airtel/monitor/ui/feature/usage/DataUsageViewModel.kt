package com.airtel.monitor.ui.feature.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.data.local.DailyUsageStore
import com.airtel.monitor.data.model.DailyUsageEntry
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.domain.MonthlyUsageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DataUsageUiState(
    val monthlyUsage: MonthlyUsageSummary = MonthlyUsageSummary(),
    val dailyHistory: List<DailyUsageEntry> = emptyList()
)

class DataUsageViewModel internal constructor(
    routerStateFlow: Flow<RouterState?>,
    private val dailyUsageStore: DailyUsageStore,
    private val triggerRefresh: (Boolean) -> Unit
) : ViewModel() {

    constructor(container: AppContainer) : this(
        routerStateFlow = container.statusRepository.routerState,
        dailyUsageStore = container.dailyUsageStore,
        triggerRefresh = { includeSlow -> container.pollingCoordinator.triggerImmediateRefresh(includeSlow) }
    )

    val uiState: StateFlow<DataUsageUiState> = combine(
        routerStateFlow,
        dailyUsageStore.history
    ) { routerState, history ->
        DataUsageUiState(
            monthlyUsage = routerState?.monthlyUsage ?: MonthlyUsageSummary(),
            dailyHistory = history
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DataUsageUiState())

    fun refresh() {
        triggerRefresh(true)
    }

    fun clearLocalHistory() {
        dailyUsageStore.clearHistory()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DataUsageViewModel(container) }
        }
    }
}
