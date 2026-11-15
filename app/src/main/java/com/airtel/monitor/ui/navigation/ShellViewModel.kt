package com.airtel.monitor.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.datastore.ThemePreference
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShellUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val isPaused: Boolean = false,
    val pollIntervalSeconds: Float = 2.0f,
    val routerHost: String = "192.168.1.1",
    val smsUnreadCount: Int = 0,
    val isAuthError: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val showRebootConfirm: Boolean = false,
    val isRebooting: Boolean = false
)

class ShellViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val localState = MutableStateFlow(LocalShellState())

    val statusMessages: SharedFlow<String> = container.statusMessages

    val uiState: StateFlow<ShellUiState> = combine(
        container.statusRepository.connectionStatus,
        container.pollingCoordinator.isPaused,
        container.preferencesRepository.preferencesFlow,
        container.statusRepository.smsUnreadCount,
        container.statusRepository.lastError,
        localState
    ) { values ->
        val status = values[0] as ConnectionStatus
        val paused = values[1] as Boolean
        val prefs = values[2] as com.airtel.monitor.core.datastore.AppPreferencesSnapshot
        val smsUnread = values[3] as Int
        val lastErr = values[4] as? RouterResult<*>
        val local = values[5] as LocalShellState
        ShellUiState(
            connectionStatus = status,
            isPaused = paused,
            pollIntervalSeconds = prefs.pollIntervalSeconds,
            routerHost = prefs.routerHost,
            smsUnreadCount = smsUnread,
            isAuthError = lastErr is RouterResult.NotLoggedIn,
            themePreference = prefs.themeMode,
            showRebootConfirm = local.showRebootConfirm,
            isRebooting = local.isRebooting
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShellUiState())

    fun togglePause() {
        val paused = container.pollingCoordinator.togglePause()
        container.emitStatusMessage(if (paused) "Telemetry paused" else "Telemetry resumed")
        if (!paused) {
            container.pollingCoordinator.triggerImmediateRefresh(includeSlow = false)
        }
    }

    fun forceRefresh() {
        container.pollingCoordinator.triggerImmediateRefresh(includeSlow = true)
    }

    fun setPollInterval(seconds: Float) {
        val clamped = seconds.coerceIn(0.5f, 10.0f)
        container.preferencesRepository.pollIntervalSeconds = clamped
        container.pollingCoordinator.onIntervalChanged()
    }

    fun openRebootConfirm() {
        localState.update { it.copy(showRebootConfirm = true) }
    }

    fun dismissRebootConfirm() {
        localState.update { it.copy(showRebootConfirm = false) }
    }

    fun confirmReboot() {
        localState.update { it.copy(showRebootConfirm = false, isRebooting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val res = container.systemRepository.reboot()
            container.statusRepository.markDisconnected()
            localState.update { it.copy(isRebooting = false) }
            val msg = when (res) {
                is RouterResult.Ok -> "Reboot command sent. Router will be back in 1–2 minutes."
                is RouterResult.Unreachable -> "Reboot sent (connection dropped as expected)."
                else -> res.errorMessage().ifEmpty { "Reboot command rejected by router." }
            }
            container.emitStatusMessage(msg)
        }
    }

    private data class LocalShellState(
        val showRebootConfirm: Boolean = false,
        val isRebooting: Boolean = false
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShellViewModel(container) }
        }
    }
}
