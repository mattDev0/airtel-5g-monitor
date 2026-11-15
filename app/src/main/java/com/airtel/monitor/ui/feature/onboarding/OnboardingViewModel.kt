package com.airtel.monitor.ui.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterSettings
import com.airtel.monitor.core.network.WifiGatewayInfo
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.core.network.map
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface OnboardingTestStatus {
    data object Idle : OnboardingTestStatus
    data object Testing : OnboardingTestStatus
    data class Connected(val deviceLabel: String) : OnboardingTestStatus
    data class WrongPassword(val message: String) : OnboardingTestStatus
    data class Unreachable(val message: String) : OnboardingTestStatus
}

data class OnboardingUiState(
    val routerHost: String = "192.168.1.1",
    val username: String = "user",
    val password: String = "",
    val isWifiConnected: Boolean = true,
    val detectedGatewayIp: String? = null,
    val testStatus: OnboardingTestStatus = OnboardingTestStatus.Idle
) {
    val isTesting: Boolean get() = testStatus is OnboardingTestStatus.Testing
    val isVerified: Boolean get() = testStatus is OnboardingTestStatus.Connected
}

class OnboardingViewModel(
    initialHost: String,
    initialUsername: String,
    initialPassword: String,
    gatewayInfo: WifiGatewayInfo,
    private val testProbe: suspend (host: String, username: String, password: String) -> RouterResult<String>,
    private val onApplySettings: (host: String, username: String, password: String, onboardingCompleted: Boolean) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val initialPreFilledHost =
        if (gatewayInfo.isWifiConnected && !gatewayInfo.gatewayIp.isNullOrBlank() &&
            (initialHost.isBlank() || initialHost == "192.168.1.1")
        ) {
            gatewayInfo.gatewayIp
        } else {
            initialHost.ifBlank { "192.168.1.1" }
        }

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            routerHost = initialPreFilledHost,
            username = initialUsername.ifBlank { "user" },
            password = initialPassword,
            isWifiConnected = gatewayInfo.isWifiConnected,
            detectedGatewayIp = gatewayInfo.gatewayIp
        )
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun updateHost(raw: String) {
        _uiState.update { it.copy(routerHost = raw, testStatus = OnboardingTestStatus.Idle) }
    }

    fun updateUsername(raw: String) {
        _uiState.update { it.copy(username = raw, testStatus = OnboardingTestStatus.Idle) }
    }

    fun updatePassword(raw: String) {
        _uiState.update { it.copy(password = raw, testStatus = OnboardingTestStatus.Idle) }
    }

    fun applyDetectedGateway() {
        val gw = _uiState.value.detectedGatewayIp ?: return
        _uiState.update { it.copy(routerHost = gw, testStatus = OnboardingTestStatus.Idle) }
    }

    fun refreshGateway(gatewayInfo: WifiGatewayInfo) {
        _uiState.update { state ->
            val updatedHost = if (gatewayInfo.isWifiConnected && !gatewayInfo.gatewayIp.isNullOrBlank() &&
                (state.routerHost.isBlank() || state.routerHost == "192.168.1.1")
            ) {
                gatewayInfo.gatewayIp
            } else {
                state.routerHost
            }
            state.copy(
                routerHost = updatedHost,
                isWifiConnected = gatewayInfo.isWifiConnected,
                detectedGatewayIp = gatewayInfo.gatewayIp
            )
        }
    }

    fun testConnection() {
        val state = _uiState.value
        val cleanHost = sanitizeHost(state.routerHost)
        val cleanUser = state.username.trim().ifEmpty { "user" }
        if (cleanHost.isEmpty()) {
            _uiState.update {
                it.copy(testStatus = OnboardingTestStatus.Unreachable("Enter the router IP or hostname"))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(testStatus = OnboardingTestStatus.Testing) }
            val result = withContext(ioDispatcher) {
                testProbe(cleanHost, cleanUser, state.password)
            }
            val nextStatus = when (result) {
                is RouterResult.Ok -> OnboardingTestStatus.Connected(
                    result.data.ifBlank { "Airtel ODU" }
                )
                is RouterResult.LimitedAccess -> OnboardingTestStatus.Connected("Airtel ODU")
                is RouterResult.NotLoggedIn -> OnboardingTestStatus.WrongPassword(
                    result.errorMessage().ifBlank { "Incorrect router username or password" }
                )
                is RouterResult.Unreachable,
                is RouterResult.BadResponse -> OnboardingTestStatus.Unreachable(
                    result.errorMessage().ifBlank { "Cannot reach router at $cleanHost" }
                )
            }
            _uiState.update { it.copy(testStatus = nextStatus) }
        }
    }

    fun saveAndContinue(onDone: () -> Unit) {
        val state = _uiState.value
        val cleanHost = sanitizeHost(state.routerHost).ifEmpty { "192.168.1.1" }
        val cleanUser = state.username.trim().ifEmpty { "user" }
        onApplySettings(cleanHost, cleanUser, state.password, true)
        onDone()
    }

    fun skipForNow(onDone: () -> Unit) {
        val state = _uiState.value
        val cleanHost = sanitizeHost(state.routerHost).ifEmpty { "192.168.1.1" }
        val cleanUser = state.username.trim().ifEmpty { "user" }
        onApplySettings(cleanHost, cleanUser, state.password, true)
        onDone()
    }

    private fun sanitizeHost(raw: String): String =
        raw.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")

    companion object {
        fun factory(
            container: AppContainer,
            gatewayInfo: WifiGatewayInfo,
            onCompletedRefresh: () -> Unit
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val prefs = container.preferencesRepository.preferencesFlow.value
                OnboardingViewModel(
                    initialHost = prefs.routerHost,
                    initialUsername = prefs.username,
                    initialPassword = prefs.password,
                    gatewayInfo = gatewayInfo,
                    testProbe = { host, user, pass ->
                        val tempSettings = object : RouterSettings {
                            override val routerHost = host
                            override val username = user
                            override val password = pass
                        }
                        val tempSession = RouterSession(tempSettings, container.transport)
                        when (val loginRes = tempSession.login()) {
                            is RouterResult.Ok -> {
                                tempSession.execute(113, method = "GET")
                                    .map { it.optString("board_type", "Airtel ODU") }
                                    .let { info -> if (info is RouterResult.Ok) info else RouterResult.Ok("Airtel ODU") }
                            }
                            is RouterResult.LimitedAccess -> RouterResult.Ok("Airtel ODU")
                            is RouterResult.NotLoggedIn -> loginRes
                            is RouterResult.Unreachable -> loginRes
                            is RouterResult.BadResponse -> loginRes
                        }
                    },
                    onApplySettings = { host, user, pass, done ->
                        container.preferencesRepository.updateSettings(
                            host = host,
                            user = user,
                            pass = pass,
                            onboardingCompleted = done
                        )
                        container.statusRepository.resetState()
                        onCompletedRefresh()
                    }
                )
            }
        }
    }
}
