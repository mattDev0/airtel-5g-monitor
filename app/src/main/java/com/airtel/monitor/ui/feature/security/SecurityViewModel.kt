package com.airtel.monitor.ui.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.AlgSettings
import com.airtel.monitor.data.model.FirewallLevel
import com.airtel.monitor.data.repository.SecurityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SecurityUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val firewallLoaded: Boolean = false,
    val firewallRestricted: Boolean = false,
    val firewallEnabled: Boolean = true,
    val firewallLevel: FirewallLevel = FirewallLevel.LOW,
    val algLoaded: Boolean = false,
    val algRestricted: Boolean = false,
    val alg: AlgSettings = AlgSettings(
        l2tp = true,
        ipsec = true,
        h323 = true,
        rtsp = true,
        sip = true,
        ftp = true,
        pptp = true
    )
)

class SecurityViewModel(
    private val securityRepository: SecurityRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStatusMessage: (String) -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState(isLoading = true))
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val (fwRes, algRes) = withContext(ioDispatcher) {
                securityRepository.loadFirewall() to securityRepository.loadAlgSettings()
            }
            val combinedError = when {
                fwRes is RouterResult.NotLoggedIn -> fwRes.errorMessage()
                fwRes is RouterResult.Unreachable -> fwRes.errorMessage()
                algRes is RouterResult.NotLoggedIn -> algRes.errorMessage()
                algRes is RouterResult.Unreachable -> algRes.errorMessage()
                fwRes is RouterResult.BadResponse && algRes is RouterResult.BadResponse -> fwRes.errorMessage()
                else -> null
            }
            _uiState.update { state ->
                val fwOk = (fwRes as? RouterResult.Ok)?.data
                val algOk = (algRes as? RouterResult.Ok)?.data
                state.copy(
                    isLoading = false,
                    errorMessage = combinedError,
                    firewallLoaded = fwOk != null || state.firewallLoaded,
                    firewallRestricted = fwRes is RouterResult.LimitedAccess,
                    firewallEnabled = fwOk?.enabled ?: state.firewallEnabled,
                    firewallLevel = fwOk?.level ?: state.firewallLevel,
                    algLoaded = algOk != null || state.algLoaded,
                    algRestricted = algRes is RouterResult.LimitedAccess,
                    alg = algOk ?: state.alg
                )
            }
        }
    }

    fun saveFirewall(enabled: Boolean, level: FirewallLevel) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val res = withContext(ioDispatcher) {
                securityRepository.saveFirewall(enabled = enabled, level = level)
            }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            firewallLoaded = true,
                            firewallEnabled = res.data.enabled,
                            firewallLevel = res.data.level
                        )
                    }
                    onStatusMessage(
                        if (res.data.enabled) "Firewall set to ${res.data.level.label}"
                        else "Firewall disabled"
                    )
                }
                else -> {
                    _uiState.update { it.copy(isSaving = false, errorMessage = res.errorMessage()) }
                    onStatusMessage(res.errorMessage())
                }
            }
        }
    }

    fun saveAlg(updated: AlgSettings) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val res = withContext(ioDispatcher) {
                securityRepository.saveAlgSettings(updated)
            }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            algLoaded = true,
                            alg = res.data
                        )
                    }
                    onStatusMessage("ALG settings saved")
                }
                else -> {
                    _uiState.update { it.copy(isSaving = false, errorMessage = res.errorMessage()) }
                    onStatusMessage(res.errorMessage())
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SecurityViewModel(
                    securityRepository = container.securityRepository,
                    onStatusMessage = container::emitStatusMessage
                )
            }
        }
    }
}
