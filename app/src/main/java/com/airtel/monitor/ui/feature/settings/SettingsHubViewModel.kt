package com.airtel.monitor.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.datastore.AppPreferencesSnapshot
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.datastore.ThemePreference
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.DiagnosisMode
import com.airtel.monitor.data.model.DiagnosisState
import com.airtel.monitor.data.model.LanConfig
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.model.SimPinState
import com.airtel.monitor.data.model.WifiBandDetails
import com.airtel.monitor.data.model.WifiRadioConfig
import com.airtel.monitor.data.repository.LanRepository
import com.airtel.monitor.data.repository.MobileRepository
import com.airtel.monitor.data.repository.SimRepository
import com.airtel.monitor.data.repository.SystemRepository
import com.airtel.monitor.data.repository.WifiRepository
import com.airtel.monitor.data.repository.WifiRepository.WifiBand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ActiveSettingsSheet {
    EDIT_WIFI_24G,
    EDIT_WIFI_5G,
    WIFI_QR,
    SIM_PIN,
    LAN_DHCP
}

data class SettingsHubUiState(
    val routerHost: String = "192.168.1.1",
    val username: String = "root",
    val pollIntervalSeconds: Float = 2.0f,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val speedUnitPreference: SpeedUnitPreference = SpeedUnitPreference.MBPS,
    val maskSensitive: Boolean = true,
    val showConnectionDialog: Boolean = false,
    val activeSheet: ActiveSettingsSheet? = null,
    val wifiRadioConfig: WifiRadioConfig = WifiRadioConfig(),
    val wifiBandDetails: WifiBandDetails = WifiBandDetails(),
    val wifiLoaded: Boolean = false,
    val wifiRadioLoading: Boolean = false,
    val wifiRadioSaving: Boolean = false,
    val wifiRadioMessage: String? = null,
    val mobileDataOn: Boolean? = null,
    val flightModeOn: Boolean? = null,
    val networkModeSummary: String = "--",
    val roamingEnabled: Boolean = false,
    val mobileSaving: Boolean = false,
    val mobileMessage: String? = null,
    val simPinState: SimPinState = SimPinState(),
    val simLoaded: Boolean = false,
    val simLoading: Boolean = false,
    val simSaving: Boolean = false,
    val simMessage: String? = null,
    val lanConfig: LanConfig = LanConfig(),
    val lanLoaded: Boolean = false,
    val lanLoading: Boolean = false,
    val lanSaving: Boolean = false,
    val lanMessage: String? = null,
    val diagnosisState: DiagnosisState = DiagnosisState()
)

class SettingsHubViewModel internal constructor(
    preferencesFlow: Flow<AppPreferencesSnapshot>,
    private val storedPasswordProvider: () -> String,
    private val updatePreferences: (
        host: String?,
        user: String?,
        pass: String?,
        interval: Float?,
        themeMode: ThemePreference?,
        speedUnit: SpeedUnitPreference?,
        maskSensitive: Boolean?
    ) -> Unit,
    private val resetStatusState: () -> Unit,
    routerStateFlow: Flow<RouterState?>,
    private val wifiRepository: WifiRepository,
    private val lanRepository: LanRepository,
    private val simRepository: SimRepository,
    private val mobileRepository: MobileRepository,
    private val systemRepository: SystemRepository,
    private val emitStatusMessage: (String) -> Unit,
    private val onIntervalChanged: () -> Unit,
    private val triggerRefresh: (Boolean) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor(container: AppContainer) : this(
        preferencesFlow = container.preferencesRepository.preferencesFlow,
        storedPasswordProvider = { container.preferencesRepository.password },
        updatePreferences = { host, user, pass, interval, theme, speedUnit, mask ->
            container.preferencesRepository.updateSettings(
                host = host,
                user = user,
                pass = pass,
                interval = interval,
                themeMode = theme,
                speedUnit = speedUnit,
                maskSensitive = mask
            )
        },
        resetStatusState = { container.statusRepository.resetState() },
        routerStateFlow = container.statusRepository.routerState,
        wifiRepository = container.wifiRepository,
        lanRepository = container.lanRepository,
        simRepository = container.simRepository,
        mobileRepository = container.mobileRepository,
        systemRepository = container.systemRepository,
        emitStatusMessage = container::emitStatusMessage,
        onIntervalChanged = { container.pollingCoordinator.onIntervalChanged() },
        triggerRefresh = { includeSlow -> container.pollingCoordinator.triggerImmediateRefresh(includeSlow) }
    )

    private val local = MutableStateFlow(LocalSettingsState())
    private var diagnosisJob: Job? = null
    private var initialLoadTriggered = false

    private val routerAndWifiFlow = combine(
        routerStateFlow,
        wifiRepository.config,
        wifiRepository.bandDetails
    ) { router, wifiCfg, wifiDetails ->
        Triple(router, wifiCfg, wifiDetails)
    }

    private val repoConfigsFlow = combine(
        lanRepository.lanConfig,
        simRepository.state,
        local
    ) { lan, sim, loc ->
        Triple(lan, sim, loc)
    }

    val uiState: StateFlow<SettingsHubUiState> = combine(
        preferencesFlow,
        routerAndWifiFlow,
        repoConfigsFlow
    ) { prefs, (router, wifiCfg, wifiDetails), (lan, sim, loc) ->
        val effectiveRouter = router ?: RouterState()
        val mobileData = effectiveRouter.networkModeInfo.mobileDataOn ?: effectiveRouter.cellLock.mobileDataOn
        val flightMode = effectiveRouter.networkModeInfo.flightModeOn ?: effectiveRouter.cellLock.flightModeOn
        val modeSummary = buildNetworkModeSummary(effectiveRouter)

        SettingsHubUiState(
            routerHost = prefs.routerHost,
            username = prefs.username,
            pollIntervalSeconds = prefs.pollIntervalSeconds,
            themePreference = prefs.themeMode,
            speedUnitPreference = prefs.speedUnit,
            maskSensitive = prefs.maskSensitive,
            showConnectionDialog = loc.showConnectionDialog,
            activeSheet = loc.activeSheet,
            wifiRadioConfig = wifiCfg ?: WifiRadioConfig(),
            wifiBandDetails = wifiDetails ?: WifiBandDetails(),
            wifiLoaded = wifiDetails != null || loc.wifiLoaded,
            wifiRadioLoading = loc.wifiRadioLoading,
            wifiRadioSaving = loc.wifiRadioSaving,
            wifiRadioMessage = loc.wifiRadioMessage,
            mobileDataOn = mobileData,
            flightModeOn = flightMode,
            networkModeSummary = modeSummary,
            roamingEnabled = effectiveRouter.networkModeInfo.roamingEnable,
            mobileSaving = loc.mobileSaving,
            mobileMessage = loc.mobileMessage,
            simPinState = sim ?: SimPinState(),
            simLoaded = sim != null || loc.simLoaded,
            simLoading = loc.simLoading,
            simSaving = loc.simSaving,
            simMessage = loc.simMessage,
            lanConfig = lan ?: LanConfig(),
            lanLoaded = lan != null || loc.lanLoaded,
            lanLoading = loc.lanLoading,
            lanSaving = loc.lanSaving,
            lanMessage = loc.lanMessage,
            diagnosisState = loc.diagnosisState
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsHubUiState())

    /** Settings load on open rather than running background timers for static config pages. */
    fun ensureLoadedOnOpen() {
        if (initialLoadTriggered) return
        initialLoadTriggered = true
        refreshAllSettings()
    }

    fun refreshAllSettings() {
        loadWifiRadios()
        loadDns()
        loadSimStatus()
    }

    fun openConnectionDialog() {
        local.update { it.copy(showConnectionDialog = true) }
    }

    fun dismissConnectionDialog() {
        local.update { it.copy(showConnectionDialog = false) }
    }

    fun openSheet(sheet: ActiveSettingsSheet) {
        local.update {
            it.copy(
                activeSheet = sheet,
                wifiRadioMessage = if (sheet in setOf(ActiveSettingsSheet.EDIT_WIFI_24G, ActiveSettingsSheet.EDIT_WIFI_5G)) null else it.wifiRadioMessage,
                simMessage = if (sheet == ActiveSettingsSheet.SIM_PIN) null else it.simMessage,
                lanMessage = if (sheet == ActiveSettingsSheet.LAN_DHCP) null else it.lanMessage
            )
        }
        when (sheet) {
            ActiveSettingsSheet.EDIT_WIFI_24G,
            ActiveSettingsSheet.EDIT_WIFI_5G,
            ActiveSettingsSheet.WIFI_QR -> if (!uiState.value.wifiLoaded) loadWifiRadios()
            ActiveSettingsSheet.SIM_PIN -> loadSimStatus()
            ActiveSettingsSheet.LAN_DHCP -> if (!uiState.value.lanLoaded) loadDns()
        }
    }

    fun dismissSheet() {
        local.update { it.copy(activeSheet = null) }
    }

    fun emitFeedback(message: String) {
        emitStatusMessage(message)
    }

    fun storedPassword(): String = storedPasswordProvider()

    fun saveConnectionSettings(host: String, user: String, pass: String, interval: Float) {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        updatePreferences(
            cleanHost,
            user.trim(),
            pass,
            interval.coerceIn(0.5f, 10.0f),
            null,
            null,
            null
        )
        resetStatusState()
        local.update { it.copy(showConnectionDialog = false) }
        emitStatusMessage("Settings saved. Reconnecting...")
        onIntervalChanged()
        triggerRefresh(true)
    }

    fun setThemePreference(theme: ThemePreference) {
        updatePreferences(null, null, null, null, theme, null, null)
    }

    fun setSpeedUnitPreference(unit: SpeedUnitPreference) {
        updatePreferences(null, null, null, null, null, unit, null)
    }

    fun setMaskSensitive(mask: Boolean) {
        updatePreferences(null, null, null, null, null, null, mask)
    }

    fun loadWifiRadios() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioLoading = true, wifiRadioMessage = null) }
            val res = wifiRepository.loadWifiBandDetails()
            local.update {
                it.copy(
                    wifiRadioLoading = false,
                    wifiLoaded = it.wifiLoaded || res is RouterResult.Ok,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else "Could not read Wi-Fi configuration"
                )
            }
        }
    }

    fun setWifi24g(enabled: Boolean) = setWifiBand(WifiBand.BAND_24G, enabled)

    fun setWifi5g(enabled: Boolean) = setWifiBand(WifiBand.BAND_5G, enabled)

    private fun setWifiBand(band: WifiBand, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = if (band == WifiBand.BAND_24G) {
                wifiRepository.setWifi24g(enabled)
            } else {
                wifiRepository.setWifi5g(enabled)
            }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = res.data) }
                    emitStatusMessage(res.data)
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

    fun saveWifiBandConfig(
        band: WifiBand,
        ssid: String,
        key: String,
        authenticationType: String,
        visibleBroadcast: Boolean,
        wifiSames: Boolean?,
        applySameToBothBands: Boolean
    ) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = wifiRepository.saveWifiBandConfig(
                band = band,
                ssid = ssid,
                key = key,
                authenticationType = authenticationType,
                visibleBroadcast = visibleBroadcast,
                wifiSames = wifiSames,
                applySameToBothBands = applySameToBothBands
            )
            when (res) {
                is RouterResult.Ok -> {
                    val targetMsg = if (applySameToBothBands) {
                        "Updated 2.4 GHz & 5 GHz Wi-Fi"
                    } else {
                        "Updated ${band.label} Wi-Fi"
                    }
                    local.update {
                        it.copy(
                            wifiRadioSaving = false,
                            wifiLoaded = true,
                            activeSheet = null,
                            wifiRadioMessage = targetMsg
                        )
                    }
                    emitStatusMessage(targetMsg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = "Error: $err") }
                }
            }
        }
    }

    fun setMobileData(on: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            val res = mobileRepository.setMobileData(on)
            handleMobileResult(res)
            triggerRefresh(false)
        }
    }

    fun setFlightMode(on: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            val res = mobileRepository.setFlightMode(on)
            handleMobileResult(res)
            triggerRefresh(false)
        }
    }

    fun rescanNetwork() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            val res = mobileRepository.rescanNetwork()
            handleMobileResult(res)
            triggerRefresh(false)
        }
    }

    private fun handleMobileResult(res: RouterResult<String>) {
        when (res) {
            is RouterResult.Ok -> {
                local.update { it.copy(mobileSaving = false, mobileMessage = res.data) }
                emitStatusMessage(res.data)
            }
            else -> {
                val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                local.update { it.copy(mobileSaving = false, mobileMessage = "Error: $err") }
            }
        }
    }

    fun loadSimStatus() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(simLoading = true) }
            val res = simRepository.loadSimStatus()
            local.update {
                it.copy(
                    simLoading = false,
                    simLoaded = it.simLoaded || res is RouterResult.Ok,
                    simMessage = if (res is RouterResult.Ok) it.simMessage else "Could not read SIM PIN status"
                )
            }
        }
    }

    fun setPinLockEnabled(enable: Boolean, pin: String) {
        val label = if (enable) "SIM PIN lock enabled" else "SIM PIN lock disabled"
        executeSimAction(label) { simRepository.setPinLockEnabled(enable, pin) }
    }

    fun unlockPin(pin: String) {
        executeSimAction("SIM PIN unlocked") { simRepository.unlockPin(pin) }
    }

    fun unlockPuk(puk: String, newPin: String) {
        executeSimAction("SIM unlocked with PUK and new PIN set") { simRepository.unlockPuk(puk, newPin) }
    }

    fun changePin(oldPin: String, newPin: String) {
        executeSimAction("SIM PIN updated") { simRepository.changePin(oldPin, newPin) }
    }

    private fun executeSimAction(
        successMessage: String = "SIM PIN setting updated",
        action: suspend () -> RouterResult<SimPinState>
    ) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(simSaving = true, simMessage = null) }
            when (val res = action()) {
                is RouterResult.Ok -> {
                    local.update {
                        it.copy(
                            simSaving = false,
                            simLoaded = true,
                            simMessage = successMessage
                        )
                    }
                    emitStatusMessage(successMessage)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(simSaving = false, simMessage = err) }
                }
            }
        }
    }

    fun loadDns() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(lanLoading = true, lanMessage = null) }
            val res = lanRepository.loadLanConfig()
            local.update {
                it.copy(
                    lanLoading = false,
                    lanLoaded = it.lanLoaded || res is RouterResult.Ok,
                    lanMessage = if (res is RouterResult.Ok) null else "Could not retrieve LAN / DNS settings"
                )
            }
        }
    }

    fun saveDns(primary: String, secondary: String) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(lanSaving = true, lanMessage = null) }
            val res = lanRepository.saveDns(primary, secondary)
            when (res) {
                is RouterResult.Ok -> {
                    lanRepository.loadLanConfig()
                    val msg = "DNS updated and confirmed by router"
                    local.update { it.copy(lanSaving = false, lanLoaded = true, lanMessage = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(lanSaving = false, lanMessage = "Error: $err") }
                    loadDns()
                }
            }
        }
    }

    fun saveLanDhcpSettings(
        lanIp: String,
        netMask: String,
        dhcpEnabled: Boolean,
        ipBegin: String,
        ipEnd: String,
        expireTime: String,
        primaryDns: String,
        secondaryDns: String,
        updateHostIfChanged: Boolean
    ) {
        val currentHost = uiState.value.routerHost
        val cleanNewIp = lanIp.trim()
        val hostChanged = cleanNewIp != currentHost

        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(lanSaving = true, lanMessage = null) }
            val res = lanRepository.saveLanDhcpSettings(
                lanIp = cleanNewIp,
                netMask = netMask,
                dhcpEnabled = dhcpEnabled,
                ipBegin = ipBegin,
                ipEnd = ipEnd,
                expireTime = expireTime,
                primaryDns = primaryDns,
                secondaryDns = secondaryDns
            )
            when (res) {
                is RouterResult.Ok -> {
                    if (hostChanged && updateHostIfChanged) {
                        updatePreferences(cleanNewIp, null, null, null, null, null, null)
                        resetStatusState()
                        triggerRefresh(true)
                    }
                    val msg = if (hostChanged && updateHostIfChanged) {
                        "LAN saved and router host updated to $cleanNewIp"
                    } else {
                        "LAN & DHCP settings updated"
                    }
                    local.update {
                        it.copy(
                            lanSaving = false,
                            lanLoaded = true,
                            activeSheet = null,
                            lanMessage = msg
                        )
                    }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    if (hostChanged && updateHostIfChanged && res is RouterResult.Unreachable) {
                        updatePreferences(cleanNewIp, null, null, null, null, null, null)
                        resetStatusState()
                        triggerRefresh(true)
                    }
                    local.update { it.copy(lanSaving = false, lanMessage = "Error: $err") }
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

        diagnosisJob = viewModelScope.launch(ioDispatcher) {
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
                val startRes = systemRepository.startPing(target, current.pingTimes)
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
                    val out = systemRepository.getPingOutput()
                    if (out.isNotEmpty() && out != lastOutput) {
                        lastOutput = out
                        local.update { it.copy(diagnosisState = it.diagnosisState.copy(output = out)) }
                    }
                    if (out.contains("statistic") || out.contains("Network is unreachable") || out.contains("100% packet loss")) {
                        break
                    }
                }
                systemRepository.stopPing()
                local.update { it.copy(diagnosisState = it.diagnosisState.copy(isRunning = false)) }
            } else {
                val startRes = systemRepository.startTrace(target)
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
                    val out = systemRepository.getTraceOutput()
                    if (out.isNotEmpty() && out != lastOutput) {
                        lastOutput = out
                        local.update { it.copy(diagnosisState = it.diagnosisState.copy(output = out)) }
                    }
                    if (!systemRepository.isTraceRunning()) break
                }
                systemRepository.stopTrace()
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
            viewModelScope.launch(ioDispatcher) {
                systemRepository.stopPing()
                systemRepository.stopTrace()
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

    private fun buildNetworkModeSummary(router: RouterState): String {
        val mode = router.cellular.networkType.ifBlank { router.networkModeInfo.networkMode }.ifBlank { "--" }
        val nrMode = when (router.networkModeInfo.mode5g) {
            "0" -> "SA + NSA"
            "1" -> "SA"
            "2" -> "NSA"
            else -> ""
        }
        val ca = buildList {
            if (router.networkModeInfo.lteCa) add("LTE CA")
            if (router.networkModeInfo.nrCa) add("NR CA")
        }.joinToString(" · ")
        return listOf(mode, nrMode, ca).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "--" }
    }

    private data class LocalSettingsState(
        val showConnectionDialog: Boolean = false,
        val activeSheet: ActiveSettingsSheet? = null,
        val wifiLoaded: Boolean = false,
        val wifiRadioLoading: Boolean = false,
        val wifiRadioSaving: Boolean = false,
        val wifiRadioMessage: String? = null,
        val mobileSaving: Boolean = false,
        val mobileMessage: String? = null,
        val simLoaded: Boolean = false,
        val simLoading: Boolean = false,
        val simSaving: Boolean = false,
        val simMessage: String? = null,
        val lanLoaded: Boolean = false,
        val lanLoading: Boolean = false,
        val lanSaving: Boolean = false,
        val lanMessage: String? = null,
        val diagnosisState: DiagnosisState = DiagnosisState()
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsHubViewModel(container) }
        }
    }
}
