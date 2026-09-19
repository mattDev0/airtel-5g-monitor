package com.airtel.monitor.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airtel.monitor.data.local.AliasRepository
import com.airtel.monitor.data.local.PreferencesRepository
import com.airtel.monitor.data.model.*
import com.airtel.monitor.data.remote.ZltRouterClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MonitorUiState(
    val routerState: RouterState? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val isPaused: Boolean = false,
    val pollIntervalSeconds: Float = 2.0f,
    val filterMode: DeviceFilter = DeviceFilter.ALL,
    val sortMode: DeviceSort = DeviceSort.SPEED,
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val isRebooting: Boolean = false,
    val statusMessage: String? = null,
    val renameTarget: DeviceItem? = null,
    val showRebootDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val showDnsDialog: Boolean = false,
    val dnsConfig: DnsConfig = DnsConfig(),
    val dnsLoaded: Boolean = false,
    val dnsLoading: Boolean = false,
    val dnsSaving: Boolean = false,
    val dnsMessage: String? = null,
    val wifiRadioConfig: WifiRadioConfig = WifiRadioConfig(),
    val wifiRadioLoading: Boolean = false,
    val wifiRadioSaving: Boolean = false,
    val wifiRadioMessage: String? = null,
    val diagnosisState: DiagnosisState = DiagnosisState(),
    val routerHost: String = "192.168.1.1",
    val username: String = "root"
) {
    val filteredDevices: List<DeviceItem>
        get() {
            val all = routerState?.devices ?: return emptyList()

            // 1. Filter
            val filtered = when (filterMode) {
                DeviceFilter.ALL -> all
                DeviceFilter.WIFI_5G -> all.filter { it.band.contains("5 GHz", ignoreCase = true) }
                DeviceFilter.WIFI_24G -> all.filter { it.band.contains("2.4 GHz", ignoreCase = true) }
                DeviceFilter.LAN -> all.filter {
                    it.band.contains("LAN", ignoreCase = true) || it.band.contains("Ethernet", ignoreCase = true)
                }
            }

            // 2. Search
            val searched = if (searchQuery.isBlank()) {
                filtered
            } else {
                val q = searchQuery.trim().lowercase()
                filtered.filter {
                    it.alias.lowercase().contains(q) ||
                            it.hostname.lowercase().contains(q) ||
                            it.ip.contains(q) ||
                            it.mac.lowercase().contains(q)
                }
            }

            // 3. Sort
            return when (sortMode) {
                DeviceSort.SPEED -> searched.sortedByDescending { it.txRateMbps.toDoubleOrNull() ?: 0.0 }
                DeviceSort.SIGNAL -> searched.sortedByDescending { it.signalPercent }
                DeviceSort.NAME -> searched.sortedBy { it.alias.lowercase() }
                DeviceSort.IP -> searched.sortedWith(Comparator { a, b ->
                    compareIps(a.ip, b.ip)
                })
            }
        }

    private fun compareIps(ipA: String, ipB: String): Int {
        val partsA = ipA.split(".").mapNotNull { it.toIntOrNull() }
        val partsB = ipB.split(".").mapNotNull { it.toIntOrNull() }
        if (partsA.size == 4 && partsB.size == 4) {
            for (i in 0 until 4) {
                val c = partsA[i].compareTo(partsB[i])
                if (c != 0) return c
            }
        }
        return ipA.compareTo(ipB)
    }
}

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = PreferencesRepository(application)
    private val aliasRepository = AliasRepository(application)
    private val routerClient = ZltRouterClient(preferencesRepository, aliasRepository)

    private val _uiState = MutableStateFlow(
        MonitorUiState(
            pollIntervalSeconds = preferencesRepository.pollIntervalSeconds,
            routerHost = preferencesRepository.routerHost,
            username = preferencesRepository.username
        )
    )
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!_uiState.value.isPaused) {
                    performPoll()
                }
                val intervalMs = (_uiState.value.pollIntervalSeconds * 1000L).toLong().coerceAtLeast(500L)
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun performPoll() {
        val result = routerClient.pollMetrics()
        result.onSuccess { state ->
            _uiState.update { current ->
                current.copy(
                    routerState = state,
                    connectionStatus = ConnectionStatus.CONNECTED,
                    isRefreshing = false
                )
            }
            if (_uiState.value.wifiRadioConfig.ssid24g.isEmpty() && !_uiState.value.wifiRadioLoading) {
                loadWifiRadios()
            }
            if (!_uiState.value.dnsLoaded && !_uiState.value.dnsLoading) {
                loadDns()
            }
        }.onFailure {
            _uiState.update { current ->
                current.copy(
                    connectionStatus = ConnectionStatus.RECONNECTING,
                    isRefreshing = false
                )
            }
        }
    }

    fun togglePause() {
        val willBePaused = !_uiState.value.isPaused
        _uiState.update { current ->
            current.copy(
                isPaused = willBePaused,
                statusMessage = if (willBePaused) "Telemetry paused" else "Telemetry resumed"
            )
        }
        if (!willBePaused && pollJob?.isActive == true) {
            viewModelScope.launch(Dispatchers.IO) {
                performPoll()
            }
        }
    }

    fun forceRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isRefreshing = true) }
            performPoll()
        }
    }

    fun setPollInterval(seconds: Float) {
        val clamped = seconds.coerceIn(0.5f, 10.0f)
        preferencesRepository.pollIntervalSeconds = clamped
        _uiState.update { it.copy(pollIntervalSeconds = clamped) }
        if (pollJob?.isActive == true) {
            stopPolling()
            startPolling()
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: DeviceFilter) {
        _uiState.update { it.copy(filterMode = filter) }
    }

    fun setSort(sort: DeviceSort) {
        _uiState.update { it.copy(sortMode = sort) }
    }

    fun openRenameDialog(device: DeviceItem) {
        _uiState.update { it.copy(renameTarget = device) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renameTarget = null) }
    }

    fun saveAlias(device: DeviceItem, alias: String) {
        viewModelScope.launch(Dispatchers.IO) {
            aliasRepository.saveAlias(device.mac, alias)
            if (device.ip.isNotEmpty()) {
                aliasRepository.saveAlias(device.ip, alias)
            }
            dismissRenameDialog()
            performPoll()
            _uiState.update { it.copy(statusMessage = "Device renamed to \"$alias\"") }
        }
    }

    fun openRebootDialog() {
        _uiState.update { it.copy(showRebootDialog = true) }
    }

    fun dismissRebootDialog() {
        _uiState.update { it.copy(showRebootDialog = false) }
    }

    fun confirmReboot() {
        _uiState.update { it.copy(showRebootDialog = false, isRebooting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = routerClient.reboot()
                _uiState.update {
                    it.copy(
                        isRebooting = false,
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        statusMessage = if (ok) {
                            "Reboot command sent. Router will be back in 1–2 minutes."
                        } else {
                            "Reboot command rejected by router."
                        }
                    )
                }
            } catch (e: Exception) {
                // Network drop is expected
                _uiState.update {
                    it.copy(
                        isRebooting = false,
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        statusMessage = "Reboot sent (connection dropped as expected)."
                    )
                }
            }
        }
    }

    fun openSettingsDialog() {
        _uiState.update { it.copy(showSettingsDialog = true) }
    }

    fun dismissSettingsDialog() {
        _uiState.update { it.copy(showSettingsDialog = false) }
    }

    fun saveSettings(host: String, user: String, pass: String, interval: Float) {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        preferencesRepository.routerHost = cleanHost
        preferencesRepository.username = user.trim()
        preferencesRepository.password = pass
        preferencesRepository.pollIntervalSeconds = interval.coerceIn(0.5f, 10.0f)

        routerClient.resetState()

        _uiState.update {
            it.copy(
                showSettingsDialog = false,
                routerHost = cleanHost,
                username = user.trim(),
                pollIntervalSeconds = interval.coerceIn(0.5f, 10.0f),
                statusMessage = "Settings saved. Reconnecting..."
            )
        }
        if (pollJob?.isActive == true) {
            stopPolling()
            startPolling()
        }
    }

    fun openDnsDialog() {
        _uiState.update { it.copy(showDnsDialog = true, dnsLoading = true, dnsMessage = null) }
        loadDns()
    }

    fun dismissDnsDialog() {
        _uiState.update { it.copy(showDnsDialog = false) }
    }

    fun loadDns() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(dnsLoading = true, dnsMessage = null) }
            val (ok, config) = routerClient.getDns()
            _uiState.update {
                it.copy(
                    dnsLoading = false,
                    dnsLoaded = it.dnsLoaded || ok,
                    dnsConfig = if (ok) config else it.dnsConfig,
                    dnsMessage = if (!ok) "Could not retrieve DNS from router" else null
                )
            }
        }
    }

    fun saveDns(primary: String, secondary: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(dnsSaving = true, dnsMessage = null) }
            val result = routerClient.setDns(primary, secondary)
            result.onSuccess { msg ->
                _uiState.update {
                    it.copy(
                        dnsSaving = false,
                        dnsConfig = it.dnsConfig.copy(primary = primary, secondary = secondary),
                        dnsMessage = msg,
                        statusMessage = msg
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        dnsSaving = false,
                        dnsMessage = "Error: ${err.message}"
                    )
                }
                // Show what the router actually has, not what was typed.
                loadDns()
            }
        }
    }

    fun loadWifiRadios() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(wifiRadioLoading = true, wifiRadioMessage = null) }
            val (ok, config) = routerClient.getWifiRadios()
            _uiState.update {
                it.copy(
                    wifiRadioLoading = false,
                    wifiRadioConfig = if (ok) config else it.wifiRadioConfig,
                    wifiRadioMessage = if (!ok) "Could not read Wi-Fi radio status" else null
                )
            }
        }
    }

    fun setWifi24g(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val result = routerClient.setWifi24g(enabled)
            result.onSuccess { msg ->
                _uiState.update {
                    it.copy(
                        wifiRadioSaving = false,
                        wifiRadioConfig = it.wifiRadioConfig.copy(enabled24g = enabled),
                        wifiRadioMessage = msg,
                        statusMessage = msg
                    )
                }
                delay(4000)
                loadWifiRadios()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        wifiRadioSaving = false,
                        wifiRadioMessage = "Error: ${err.message}"
                    )
                }
                loadWifiRadios()
            }
        }
    }

    fun setWifi5g(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val result = routerClient.setWifi5g(enabled)
            result.onSuccess { msg ->
                _uiState.update {
                    it.copy(
                        wifiRadioSaving = false,
                        wifiRadioConfig = it.wifiRadioConfig.copy(enabled5g = enabled),
                        wifiRadioMessage = msg,
                        statusMessage = msg
                    )
                }
                delay(4000)
                loadWifiRadios()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        wifiRadioSaving = false,
                        wifiRadioMessage = "Error: ${err.message}"
                    )
                }
                loadWifiRadios()
            }
        }
    }

    private var diagnosisJob: Job? = null

    fun setDiagnosisMode(mode: DiagnosisMode) {
        if (_uiState.value.diagnosisState.isRunning) return
        _uiState.update {
            it.copy(
                diagnosisState = it.diagnosisState.copy(
                    mode = mode,
                    error = null
                )
            )
        }
    }

    fun setDiagnosisTarget(target: String) {
        _uiState.update {
            it.copy(
                diagnosisState = it.diagnosisState.copy(
                    target = target,
                    error = null
                )
            )
        }
    }

    fun setDiagnosisPingTimes(times: Int) {
        _uiState.update {
            it.copy(
                diagnosisState = it.diagnosisState.copy(
                    pingTimes = times
                )
            )
        }
    }

    fun startDiagnosis() {
        if (diagnosisJob?.isActive == true) return
        val current = _uiState.value.diagnosisState
        val target = current.target.trim()
        if (target.isEmpty()) {
            _uiState.update {
                it.copy(
                    diagnosisState = it.diagnosisState.copy(
                        error = "Target address cannot be blank"
                    )
                )
            }
            return
        }

        diagnosisJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    diagnosisState = it.diagnosisState.copy(
                        isRunning = true,
                        output = "Starting ${current.mode.name.lowercase()} to $target...\n",
                        error = null
                    )
                )
            }

            if (current.mode == DiagnosisMode.PING) {
                val startRes = routerClient.startPing(target, current.pingTimes)
                if (startRes.isFailure) {
                    _uiState.update {
                        it.copy(
                            diagnosisState = it.diagnosisState.copy(
                                isRunning = false,
                                error = startRes.exceptionOrNull()?.message ?: "Failed to start ping"
                            )
                        )
                    }
                    return@launch
                }

                var lastOutput = ""
                var pollCount = 0
                while (isActive && _uiState.value.diagnosisState.isRunning && pollCount < 60) {
                    delay(1000)
                    pollCount++
                    val out = routerClient.getPingOutput()
                    if (out.isNotEmpty() && out != lastOutput) {
                        lastOutput = out
                        _uiState.update {
                            it.copy(
                                diagnosisState = it.diagnosisState.copy(
                                    output = out
                                )
                            )
                        }
                    }
                    if (out.contains("statistic") || out.contains("Network is unreachable") || out.contains("100% packet loss")) {
                        break
                    }
                }
                routerClient.stopPing()
                _uiState.update {
                    it.copy(
                        diagnosisState = it.diagnosisState.copy(
                            isRunning = false
                        )
                    )
                }
            } else {
                val startRes = routerClient.startTrace(target)
                if (startRes.isFailure) {
                    _uiState.update {
                        it.copy(
                            diagnosisState = it.diagnosisState.copy(
                                isRunning = false,
                                error = startRes.exceptionOrNull()?.message ?: "Failed to start traceroute"
                            )
                        )
                    }
                    return@launch
                }

                var lastOutput = ""
                var pollCount = 0
                while (isActive && _uiState.value.diagnosisState.isRunning && pollCount < 90) {
                    delay(1500)
                    pollCount++
                    val out = routerClient.getTraceOutput()
                    if (out.isNotEmpty() && out != lastOutput) {
                        lastOutput = out
                        _uiState.update {
                            it.copy(
                                diagnosisState = it.diagnosisState.copy(
                                    output = out
                                )
                            )
                        }
                    }
                    val running = routerClient.isTraceRunning()
                    if (!running) {
                        break
                    }
                }
                routerClient.stopTrace()
                _uiState.update {
                    it.copy(
                        diagnosisState = it.diagnosisState.copy(
                            isRunning = false
                        )
                    )
                }
            }
        }
    }

    fun stopDiagnosis() {
        val wasRunning = _uiState.value.diagnosisState.isRunning
        _uiState.update {
            it.copy(
                diagnosisState = it.diagnosisState.copy(
                    isRunning = false
                )
            )
        }
        diagnosisJob?.cancel()
        diagnosisJob = null
        if (wasRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                routerClient.stopPing()
                routerClient.stopTrace()
            }
        }
    }

    fun clearDiagnosisOutput() {
        _uiState.update {
            it.copy(
                diagnosisState = it.diagnosisState.copy(
                    output = "",
                    error = null
                )
            )
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    override fun onCleared() {
        pollJob?.cancel()
        diagnosisJob?.cancel()
        super.onCleared()
    }
}
