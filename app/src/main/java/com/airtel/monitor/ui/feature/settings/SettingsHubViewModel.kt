package com.airtel.monitor.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.datastore.AppPreferencesSnapshot
import com.airtel.monitor.core.datastore.ThemePreference
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.DiagnosisMode
import com.airtel.monitor.data.model.DiagnosisState
import com.airtel.monitor.data.model.DnsConfig
import com.airtel.monitor.data.model.WifiRadioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SettingsHubUiState(
    val routerHost: String = "192.168.1.1",
    val username: String = "root",
    val pollIntervalSeconds: Float = 2.0f,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val showConnectionDialog: Boolean = false,
    val wifiRadioConfig: WifiRadioConfig = WifiRadioConfig(),
    val wifiRadioLoading: Boolean = false,
    val wifiRadioSaving: Boolean = false,
    val wifiRadioMessage: String? = null,
    val dnsConfig: DnsConfig = DnsConfig(),
    val dnsLoaded: Boolean = false,
    val dnsLoading: Boolean = false,
    val dnsSaving: Boolean = false,
    val dnsMessage: String? = null,
    val diagnosisState: DiagnosisState = DiagnosisState()
)

class SettingsHubViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val local = MutableStateFlow(LocalSettingsState())
    private var diagnosisJob: Job? = null
    private var initialLoadTriggered = false

    val uiState: StateFlow<SettingsHubUiState> = combine(
        container.preferencesRepository.preferencesFlow,
        container.wifiRepository.config,
        container.lanRepository.dnsConfig,
        local
    ) { prefs: AppPreferencesSnapshot, wifi: WifiRadioConfig?, dns: DnsConfig?, loc: LocalSettingsState ->
        SettingsHubUiState(
            routerHost = prefs.routerHost,
            username = prefs.username,
            pollIntervalSeconds = prefs.pollIntervalSeconds,
            themePreference = prefs.themeMode,
            showConnectionDialog = loc.showConnectionDialog,
            wifiRadioConfig = wifi ?: WifiRadioConfig(),
            wifiRadioLoading = loc.wifiRadioLoading,
            wifiRadioSaving = loc.wifiRadioSaving,
            wifiRadioMessage = loc.wifiRadioMessage,
            dnsConfig = dns ?: DnsConfig(),
            dnsLoaded = dns != null || loc.dnsLoaded,
            dnsLoading = loc.dnsLoading,
            dnsSaving = loc.dnsSaving,
            dnsMessage = loc.dnsMessage,
            diagnosisState = loc.diagnosisState
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsHubUiState())

    /** Settings screens load on open, not on a background timer. */
    fun ensureLoadedOnOpen() {
        if (initialLoadTriggered) return
        initialLoadTriggered = true
        loadWifiRadios()
        loadDns()
    }

    fun openConnectionDialog() {
        local.update { it.copy(showConnectionDialog = true) }
    }

    fun dismissConnectionDialog() {
        local.update { it.copy(showConnectionDialog = false) }
    }

    fun storedPassword(): String = container.preferencesRepository.password

    fun saveConnectionSettings(host: String, user: String, pass: String, interval: Float) {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        container.preferencesRepository.updateSettings(
            host = cleanHost,
            user = user.trim(),
            pass = pass,
            interval = interval.coerceIn(0.5f, 10.0f)
        )
        container.statusRepository.resetState()
        local.update { it.copy(showConnectionDialog = false) }
        container.emitStatusMessage("Settings saved. Reconnecting...")
        container.pollingCoordinator.onIntervalChanged()
        container.pollingCoordinator.triggerImmediateRefresh(includeSlow = true)
    }

    fun setThemePreference(theme: ThemePreference) {
        container.preferencesRepository.updateSettings(themeMode = theme)
    }

    fun loadWifiRadios() {
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(wifiRadioLoading = true, wifiRadioMessage = null) }
            val res = container.wifiRepository.loadWifiRadios()
            local.update {
                it.copy(
                    wifiRadioLoading = false,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else "Could not read Wi-Fi radio status"
                )
            }
        }
    }

    fun setWifi24g(enabled: Boolean) = setWifiBand(is24g = true, enabled = enabled)

    fun setWifi5g(enabled: Boolean) = setWifiBand(is24g = false, enabled = enabled)

    private fun setWifiBand(is24g: Boolean, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = if (is24g) {
                container.wifiRepository.setWifi24g(enabled)
            } else {
                container.wifiRepository.setWifi5g(enabled)
            }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = res.data) }
                    container.emitStatusMessage(res.data)
                    delay(4000)
                    loadWifiRadios()
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = "Error: $err") }
                    loadWifiRadios()
                }
            }
        }
    }

    fun loadDns() {
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(dnsLoading = true, dnsMessage = null) }
            val res = container.lanRepository.loadDns()
            local.update {
                it.copy(
                    dnsLoading = false,
                    dnsLoaded = it.dnsLoaded || res is RouterResult.Ok,
                    dnsMessage = if (res is RouterResult.Ok) null else "Could not retrieve DNS from router"
                )
            }
        }
    }

    fun saveDns(primary: String, secondary: String) {
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(dnsSaving = true, dnsMessage = null) }
            val res = container.lanRepository.saveDns(primary, secondary)
            when (res) {
                is RouterResult.Ok -> {
                    val msg = "DNS updated and confirmed by router"
                    local.update { it.copy(dnsSaving = false, dnsLoaded = true, dnsMessage = msg) }
                    container.emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(dnsSaving = false, dnsMessage = "Error: $err") }
                    loadDns()
                }
            }
        }
    }

    fun setDiagnosisMode(mode: DiagnosisMode) {
        if (local.value.diagnosisState.isRunning) return
        local.update { it.copy(diagnosisState = it.diagnosisState.copy(mode = mode, error = null)) }
    }

    fun setDiagnosisTarget(target: String) {
        local.update { it.copy(diagnosisState = it.diagnosisState.copy(target = target, error = null)) }
    }

    fun setDiagnosisPingTimes(times: Int) {
        local.update { it.copy(diagnosisState = it.diagnosisState.copy(pingTimes = times)) }
    }

    fun startDiagnosis() {
        if (diagnosisJob?.isActive == true) return
        val current = local.value.diagnosisState
        val target = current.target.trim()
        if (target.isEmpty()) {
            local.update { it.copy(diagnosisState = it.diagnosisState.copy(error = "Target address cannot be blank")) }
            return
        }

        diagnosisJob = viewModelScope.launch(Dispatchers.IO) {
            local.update {
                it.copy(
                    diagnosisState = it.diagnosisState.copy(
                        isRunning = true,
                        output = "Starting ${current.mode.name.lowercase()} to $target...\n",
                        error = null
                    )
                )
            }

            if (current.mode == DiagnosisMode.PING) {
                val startRes = container.systemRepository.startPing(target, current.pingTimes)
                if (startRes !is RouterResult.Ok) {
                    local.update {
                        it.copy(
                            diagnosisState = it.diagnosisState.copy(
                                isRunning = false,
                                error = startRes.errorMessage().ifEmpty { "Failed to start ping" }
                            )
                        )
                    }
                    return@launch
                }

                var lastOutput = ""
                var pollCount = 0
                while (isActive && local.value.diagnosisState.isRunning && pollCount < 60) {
                    delay(1000)
                    pollCount++
                    val out = container.systemRepository.getPingOutput()
                    if (out.isNotEmpty() && out != lastOutput) {
                        lastOutput = out
                        local.update { it.copy(diagnosisState = it.diagnosisState.copy(output = out)) }
                    }
                    if (out.contains("statistic") || out.contains("Network is unreachable") || out.contains("100% packet loss")) {
                        break
                    }
                }
                container.systemRepository.stopPing()
                local.update { it.copy(diagnosisState = it.diagnosisState.copy(isRunning = false)) }
            } else {
                val startRes = container.systemRepository.startTrace(target)
                if (startRes !is RouterResult.Ok) {
                    local.update {
                        it.copy(
                            diagnosisState = it.diagnosisState.copy(
                                isRunning = false,
                                error = startRes.errorMessage().ifEmpty { "Failed to start traceroute" }
                            )
                        )
                    }
                    return@launch
                }

                var lastOutput = ""
                var pollCount = 0
                while (isActive && local.value.diagnosisState.isRunning && pollCount < 90) {
                    delay(1500)
                    pollCount++
                    val out = container.systemRepository.getTraceOutput()
                    if (out.isNotEmpty() && out != lastOutput) {
                        lastOutput = out
                        local.update { it.copy(diagnosisState = it.diagnosisState.copy(output = out)) }
                    }
                    if (!container.systemRepository.isTraceRunning()) break
                }
                container.systemRepository.stopTrace()
                local.update { it.copy(diagnosisState = it.diagnosisState.copy(isRunning = false)) }
            }
        }
    }

    fun stopDiagnosis() {
        val wasRunning = local.value.diagnosisState.isRunning
        local.update { it.copy(diagnosisState = it.diagnosisState.copy(isRunning = false)) }
        diagnosisJob?.cancel()
        diagnosisJob = null
        if (wasRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                container.systemRepository.stopPing()
                container.systemRepository.stopTrace()
            }
        }
    }

    fun clearDiagnosisOutput() {
        local.update { it.copy(diagnosisState = it.diagnosisState.copy(output = "", error = null)) }
    }

    override fun onCleared() {
        diagnosisJob?.cancel()
        super.onCleared()
    }

    private data class LocalSettingsState(
        val showConnectionDialog: Boolean = false,
        val wifiRadioLoading: Boolean = false,
        val wifiRadioSaving: Boolean = false,
        val wifiRadioMessage: String? = null,
        val dnsLoaded: Boolean = false,
        val dnsLoading: Boolean = false,
        val dnsSaving: Boolean = false,
        val dnsMessage: String? = null,
        val diagnosisState: DiagnosisState = DiagnosisState()
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsHubViewModel(container) }
        }
    }
}
