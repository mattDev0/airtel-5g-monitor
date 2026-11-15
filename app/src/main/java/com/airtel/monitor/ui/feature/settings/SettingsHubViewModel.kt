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
import com.airtel.monitor.data.model.ApnConfigSnapshot
import com.airtel.monitor.data.model.ApnProfile
import com.airtel.monitor.data.model.DiagnosisMode
import com.airtel.monitor.data.model.DiagnosisState
import com.airtel.monitor.data.model.GuestWifiBandConfig
import com.airtel.monitor.data.model.GuestWifiSnapshot
import com.airtel.monitor.data.model.LanConfig
import com.airtel.monitor.data.model.MobileAdvancedConfig
import com.airtel.monitor.data.model.NetworkModeCatalog
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.model.SimPinState
import com.airtel.monitor.data.model.StaticDhcpLease
import com.airtel.monitor.data.model.WifiAdvancedBandConfig
import com.airtel.monitor.data.model.WifiBandDetails
import com.airtel.monitor.data.model.WifiPower411Config
import com.airtel.monitor.data.model.WifiRadioConfig
import com.airtel.monitor.data.model.WifiScheduleConfig
import com.airtel.monitor.data.model.WpsSnapshot
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ActiveSettingsSheet {
    EDIT_WIFI_24G,
    EDIT_WIFI_5G,
    WIFI_QR,
    GUEST_WIFI,
    WIFI_ADVANCED,
    WPS_SETUP,
    WIFI_SCHEDULE,
    MOBILE_NETWORK_MODE,
    APN_EDITOR,
    SIM_PIN,
    LAN_DHCP,
    STATIC_LEASES
}

data class SettingsHubUiState(
    val routerHost: String = "192.168.1.1",
    val username: String = "root",
    val pollIntervalSeconds: Float = 2.0f,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val speedUnitPreference: SpeedUnitPreference = SpeedUnitPreference.MBPS,
    val maskSensitive: Boolean = true,
    val backgroundMonitorEnabled: Boolean = false,
    val notifyQuotaWarning: Boolean = true,
    val notifyNewSms: Boolean = true,
    val notifyNewDevice: Boolean = false,
    val notifyUnreachable: Boolean = true,
    val showConnectionDialog: Boolean = false,
    val activeSheet: ActiveSettingsSheet? = null,
    val featureFlags: RouterFeatureFlags = RouterFeatureFlags(),
    val wifiRadioConfig: WifiRadioConfig = WifiRadioConfig(),
    val wifiBandDetails: WifiBandDetails = WifiBandDetails(),
    val guestWifi: GuestWifiSnapshot = GuestWifiSnapshot(),
    val wifiAdv24g: WifiAdvancedBandConfig? = null,
    val wifiAdv5g: WifiAdvancedBandConfig? = null,
    val wifiPower411: WifiPower411Config? = null,
    val wpsSnapshot: WpsSnapshot = WpsSnapshot(),
    val wpsRandomPin: String? = null,
    val wpsRandomSecondsLeft: Int = 0,
    val wpsPairedSuccess: Boolean = false,
    val wifiSchedule: WifiScheduleConfig = WifiScheduleConfig(),
    val wifiLoaded: Boolean = false,
    val wifiRadioLoading: Boolean = false,
    val wifiRadioSaving: Boolean = false,
    val wifiRadioMessage: String? = null,
    val mobileDataOn: Boolean? = null,
    val flightModeOn: Boolean? = null,
    val networkModeSummary: String = "--",
    val networkModeLabel: String = "--",
    val roamingEnabled: Boolean = false,
    val lteCaEnabled: Boolean = true,
    val nrCaEnabled: Boolean = true,
    val mobileAdvanced: MobileAdvancedConfig = MobileAdvancedConfig(),
    val mobileAdvancedLoading: Boolean = false,
    val apnConfig: ApnConfigSnapshot = ApnConfigSnapshot(),
    val apnLoading: Boolean = false,
    val mobileSaving: Boolean = false,
    val mobileMessage: String? = null,
    val simPinState: SimPinState = SimPinState(),
    val simLoaded: Boolean = false,
    val simLoading: Boolean = false,
    val simSaving: Boolean = false,
    val simMessage: String? = null,
    val lanConfig: LanConfig = LanConfig(),
    val staticLeases: List<StaticDhcpLease> = emptyList(),
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updateNotificationPrefs: (
        bgMonitor: Boolean?,
        quota: Boolean?,
        sms: Boolean?,
        device: Boolean?,
        unreachable: Boolean?
    ) -> Unit = { _, _, _, _, _ -> },
    private val featureFlagsFlow: Flow<RouterFeatureFlags> = flowOf(RouterFeatureFlags()),
    private val ensureFlagsLoaded: () -> RouterFeatureFlags = { RouterFeatureFlags() }
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
        triggerRefresh = { includeSlow -> container.pollingCoordinator.triggerImmediateRefresh(includeSlow) },
        updateNotificationPrefs = { bgMonitor, quota, sms, device, unreachable ->
            container.preferencesRepository.updateSettings(
                backgroundMonitorEnabled = bgMonitor,
                notifyQuotaWarning = quota,
                notifyNewSms = sms,
                notifyNewDevice = device,
                notifyUnreachable = unreachable
            )
        },
        featureFlagsFlow = container.featureFlagRepository.flags,
        ensureFlagsLoaded = {
            container.featureFlagRepository.ensureLoaded().getOrNull()
                ?: container.featureFlagRepository.flags.value
        }
    )

    private val local = MutableStateFlow(LocalSettingsState())
    private var diagnosisJob: Job? = null
    private var wpsPollJob: Job? = null
    private var initialLoadTriggered = false

    private data class WifiCoreTuple(
        val router: RouterState?,
        val cfg: WifiRadioConfig?,
        val details: WifiBandDetails?,
        val guest: GuestWifiSnapshot
    )

    private data class WifiAdvTuple(
        val adv24: WifiAdvancedBandConfig?,
        val adv5: WifiAdvancedBandConfig?,
        val pwr411: WifiPower411Config?,
        val wps: WpsSnapshot,
        val schedule: WifiScheduleConfig?
    )

    private val wifiCoreFlow = combine(
        routerStateFlow,
        wifiRepository.config,
        wifiRepository.bandDetails,
        wifiRepository.guestWifi
    ) { router, wifiCfg, wifiDetails, guest ->
        WifiCoreTuple(router, wifiCfg, wifiDetails, guest)
    }

    private val wifiAdvFlow = combine(
        wifiRepository.adv24g,
        wifiRepository.adv5g,
        wifiRepository.power411,
        wifiRepository.wps,
        wifiRepository.schedule
    ) { adv24, adv5, pwr411, wps, sched ->
        WifiAdvTuple(adv24, adv5, pwr411, wps, sched)
    }

    private val routerAndWifiFlow = combine(wifiCoreFlow, wifiAdvFlow) { core, adv ->
        core to adv
    }

    private val mobileRepoFlow = combine(
        mobileRepository.advancedConfig,
        mobileRepository.apnConfig,
        featureFlagsFlow
    ) { mobAdv, apn, flags ->
        Triple(mobAdv, apn, flags)
    }

    private data class RepoConfigsTuple(
        val lan: LanConfig?,
        val staticLeases: List<StaticDhcpLease>,
        val sim: SimPinState?,
        val loc: LocalSettingsState
    )

    private val repoConfigsFlow = combine(
        lanRepository.lanConfig,
        lanRepository.staticLeases,
        simRepository.state,
        local
    ) { lan, leases, sim, loc ->
        RepoConfigsTuple(lan, leases, sim, loc)
    }

    val uiState: StateFlow<SettingsHubUiState> = combine(
        preferencesFlow,
        routerAndWifiFlow,
        mobileRepoFlow,
        repoConfigsFlow
    ) { prefs, wifiPair, (mobAdv, apn, flags), repoTuple ->
        val (wifiCore, wifiAdv) = wifiPair
        val (router, wifiCfg, wifiDetails, guestWifi) = wifiCore
        val (adv24, adv5, pwr411, wps, sched) = wifiAdv
        val (lan, staticLeases, sim, loc) = repoTuple
        val effectiveRouter = router ?: RouterState()
        val mobileData = effectiveRouter.networkModeInfo.mobileDataOn ?: effectiveRouter.cellLock.mobileDataOn
        val flightMode = effectiveRouter.networkModeInfo.flightModeOn ?: effectiveRouter.cellLock.flightModeOn
        val modeSummary = buildNetworkModeSummary(effectiveRouter)
        val effectiveAdv = mobAdv ?: MobileAdvancedConfig(
            networkMode = effectiveRouter.networkModeInfo.networkMode,
            roamingEnabled = effectiveRouter.networkModeInfo.roamingEnable,
            lteCaEnabled = effectiveRouter.networkModeInfo.lteCa,
            nrCaEnabled = effectiveRouter.networkModeInfo.nrCa,
            availableModes = NetworkModeCatalog.availableOptions(flags.aeraId, ""),
            plmnLocked = flags.lockPlmnFlag
        )
        val modeLabel = NetworkModeCatalog.labelFor(effectiveAdv.networkMode, effectiveAdv.availableModes)

        SettingsHubUiState(
            routerHost = prefs.routerHost,
            username = prefs.username,
            pollIntervalSeconds = prefs.pollIntervalSeconds,
            themePreference = prefs.themeMode,
            speedUnitPreference = prefs.speedUnit,
            maskSensitive = prefs.maskSensitive,
            backgroundMonitorEnabled = prefs.backgroundMonitorEnabled,
            notifyQuotaWarning = prefs.notifyQuotaWarning,
            notifyNewSms = prefs.notifyNewSms,
            notifyNewDevice = prefs.notifyNewDevice,
            notifyUnreachable = prefs.notifyUnreachable,
            showConnectionDialog = loc.showConnectionDialog,
            activeSheet = loc.activeSheet,
            featureFlags = flags,
            wifiRadioConfig = wifiCfg ?: WifiRadioConfig(),
            wifiBandDetails = wifiDetails ?: WifiBandDetails(),
            guestWifi = guestWifi,
            wifiAdv24g = adv24,
            wifiAdv5g = adv5,
            wifiPower411 = pwr411,
            wpsSnapshot = wps,
            wpsRandomPin = loc.wpsRandomPin,
            wpsRandomSecondsLeft = loc.wpsRandomSecondsLeft,
            wpsPairedSuccess = loc.wpsPairedSuccess,
            wifiSchedule = sched ?: WifiScheduleConfig(),
            wifiLoaded = wifiDetails != null || loc.wifiLoaded,
            wifiRadioLoading = loc.wifiRadioLoading,
            wifiRadioSaving = loc.wifiRadioSaving,
            wifiRadioMessage = loc.wifiRadioMessage,
            mobileDataOn = mobileData,
            flightModeOn = flightMode,
            networkModeSummary = modeSummary,
            networkModeLabel = modeLabel,
            roamingEnabled = mobAdv?.roamingEnabled ?: effectiveRouter.networkModeInfo.roamingEnable,
            lteCaEnabled = mobAdv?.lteCaEnabled ?: effectiveRouter.networkModeInfo.lteCa,
            nrCaEnabled = mobAdv?.nrCaEnabled ?: effectiveRouter.networkModeInfo.nrCa,
            mobileAdvanced = effectiveAdv,
            mobileAdvancedLoading = loc.mobileAdvancedLoading,
            apnConfig = apn ?: ApnConfigSnapshot(),
            apnLoading = loc.apnLoading,
            mobileSaving = loc.mobileSaving,
            mobileMessage = loc.mobileMessage,
            simPinState = sim ?: SimPinState(),
            simLoaded = sim != null || loc.simLoaded,
            simLoading = loc.simLoading,
            simSaving = loc.simSaving,
            simMessage = loc.simMessage,
            lanConfig = lan ?: LanConfig(),
            staticLeases = staticLeases,
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
        loadMobileAdvanced()
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
                wifiRadioMessage = if (sheet in setOf(
                        ActiveSettingsSheet.EDIT_WIFI_24G,
                        ActiveSettingsSheet.EDIT_WIFI_5G,
                        ActiveSettingsSheet.GUEST_WIFI,
                        ActiveSettingsSheet.WIFI_ADVANCED,
                        ActiveSettingsSheet.WPS_SETUP,
                        ActiveSettingsSheet.WIFI_SCHEDULE
                    )
                ) null else it.wifiRadioMessage,
                mobileMessage = if (sheet in setOf(ActiveSettingsSheet.MOBILE_NETWORK_MODE, ActiveSettingsSheet.APN_EDITOR)) null else it.mobileMessage,
                simMessage = if (sheet == ActiveSettingsSheet.SIM_PIN) null else it.simMessage,
                lanMessage = if (sheet in setOf(ActiveSettingsSheet.LAN_DHCP, ActiveSettingsSheet.STATIC_LEASES)) null else it.lanMessage
            )
        }
        when (sheet) {
            ActiveSettingsSheet.EDIT_WIFI_24G,
            ActiveSettingsSheet.EDIT_WIFI_5G,
            ActiveSettingsSheet.WIFI_QR -> if (!uiState.value.wifiLoaded) loadWifiRadios()
            ActiveSettingsSheet.GUEST_WIFI -> loadGuestWifi()
            ActiveSettingsSheet.WIFI_ADVANCED -> loadWifiAdvanced()
            ActiveSettingsSheet.WPS_SETUP -> loadWpsStatus()
            ActiveSettingsSheet.WIFI_SCHEDULE -> loadWifiSchedule()
            ActiveSettingsSheet.MOBILE_NETWORK_MODE -> loadMobileAdvanced()
            ActiveSettingsSheet.APN_EDITOR -> loadApnConfig()
            ActiveSettingsSheet.SIM_PIN -> loadSimStatus()
            ActiveSettingsSheet.LAN_DHCP -> if (!uiState.value.lanLoaded) loadDns()
            ActiveSettingsSheet.STATIC_LEASES -> loadStaticLeases()
        }
    }

    fun dismissSheet() {
        stopRandomWpsPin()
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

    fun setBackgroundMonitoring(enabled: Boolean) {
        updateNotificationPrefs(enabled, null, null, null, null)
    }

    fun setNotifyQuotaWarning(enabled: Boolean) {
        updateNotificationPrefs(null, enabled, null, null, null)
    }

    fun setNotifyNewSms(enabled: Boolean) {
        updateNotificationPrefs(null, null, enabled, null, null)
    }

    fun setNotifyNewDevice(enabled: Boolean) {
        updateNotificationPrefs(null, null, null, enabled, null)
    }

    fun setNotifyUnreachable(enabled: Boolean) {
        updateNotificationPrefs(null, null, null, null, enabled)
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

    fun loadGuestWifi() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioLoading = true, wifiRadioMessage = null) }
            val flags = ensureFlagsLoaded()
            val res = wifiRepository.loadGuestWifi(
                load24g = flags.guestWifi24gAvailable,
                load5g = flags.guestWifi5gAvailable
            )
            local.update {
                it.copy(
                    wifiRadioLoading = false,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
        }
    }

    fun saveGuestWifi(band: WifiBand, config: GuestWifiBandConfig) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = wifiRepository.saveGuestWifi(band, config)
            when (res) {
                is RouterResult.Ok -> {
                    val msg = "Updated ${band.label} Guest Wi-Fi"
                    local.update { it.copy(wifiRadioSaving = false, activeSheet = null, wifiRadioMessage = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = "Error: $err") }
                }
            }
        }
    }

    fun loadWifiAdvanced() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioLoading = true, wifiRadioMessage = null) }
            val flags = ensureFlagsLoaded()
            val r24 = wifiRepository.loadWifiAdvanced(WifiBand.BAND_24G)
            val r5 = wifiRepository.loadWifiAdvanced(WifiBand.BAND_5G)
            if (flags.wifiPowerCmd411Available) {
                wifiRepository.loadWifiPower411()
            }
            val err = when {
                r24 is RouterResult.Ok || r5 is RouterResult.Ok -> null
                r24 !is RouterResult.Ok -> r24.errorMessage()
                else -> r5.errorMessage()
            }
            local.update { it.copy(wifiRadioLoading = false, wifiRadioMessage = err) }
        }
    }

    fun saveWifiAdvanced(band: WifiBand, config: WifiAdvancedBandConfig) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = wifiRepository.saveWifiAdvanced(band, config)
            when (res) {
                is RouterResult.Ok -> {
                    val msg = "Updated ${band.label} advanced radio settings"
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = "Error: $err") }
                }
            }
        }
    }

    fun saveWifiPower411(txPower: String) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = wifiRepository.saveWifiPower411(txPower)
            when (res) {
                is RouterResult.Ok -> {
                    val msg = "Updated Wi-Fi TX coverage ($txPower%)"
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = "Error: $err") }
                }
            }
        }
    }

    fun loadWpsStatus() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioLoading = true, wifiRadioMessage = null) }
            val res = wifiRepository.loadWpsStatus()
            local.update {
                it.copy(
                    wifiRadioLoading = false,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
        }
    }

    fun setWpsEnabled(band: WifiBand, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = wifiRepository.setWpsEnabled(band, enabled)
            local.update {
                it.copy(
                    wifiRadioSaving = false,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
            if (res is RouterResult.Ok) {
                emitStatusMessage("WPS ${if (enabled) "enabled" else "disabled"} on ${band.label}")
            }
        }
    }

    fun startWpsPushButton(band: WifiBand) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null, wpsPairedSuccess = false) }
            val res = wifiRepository.startWpsPushButton(band)
            local.update {
                it.copy(
                    wifiRadioSaving = false,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
            if (res is RouterResult.Ok) {
                emitStatusMessage("WPS push-button window started on ${band.label}")
            }
        }
    }

    fun startWpsPin(band: WifiBand, pin: String) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null, wpsPairedSuccess = false) }
            val res = wifiRepository.startWpsPin(band, pin)
            local.update {
                it.copy(
                    wifiRadioSaving = false,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
            if (res is RouterResult.Ok) {
                emitStatusMessage("WPS enrollee PIN pairing started on ${band.label}")
            }
        }
    }

    fun openRandomWpsPin(band: WifiBand) {
        wpsPollJob?.cancel()
        wpsPollJob = viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null, wpsPairedSuccess = false) }
            val res = wifiRepository.openRandomWpsPin(band)
            if (res !is RouterResult.Ok) {
                local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = res.errorMessage()) }
                return@launch
            }
            val pin = res.data
            local.update {
                it.copy(
                    wifiRadioSaving = false,
                    wpsRandomPin = pin,
                    wpsRandomSecondsLeft = 120
                )
            }
            var left = 120
            while (isActive && left > 0) {
                if (left % 3 == 0 && left < 120) {
                    val matched = wifiRepository.pollRandomWpsSuccess().getOrNull() == true
                    if (matched) {
                        local.update {
                            it.copy(
                                wpsRandomPin = null,
                                wpsRandomSecondsLeft = 0,
                                wpsPairedSuccess = true
                            )
                        }
                        emitStatusMessage("WPS pairing completed")
                        return@launch
                    }
                }
                delay(1000L)
                left--
                local.update { it.copy(wpsRandomSecondsLeft = left) }
            }
            local.update { it.copy(wpsRandomPin = null, wpsRandomSecondsLeft = 0) }
        }
    }

    fun stopRandomWpsPin() {
        wpsPollJob?.cancel()
        wpsPollJob = null
        local.update { it.copy(wpsRandomPin = null, wpsRandomSecondsLeft = 0) }
    }

    fun loadWifiSchedule() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioLoading = true, wifiRadioMessage = null) }
            val res = wifiRepository.loadWifiSchedule()
            local.update {
                it.copy(
                    wifiRadioLoading = false,
                    wifiRadioMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
        }
    }

    fun saveWifiSchedule(config: WifiScheduleConfig) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(wifiRadioSaving = true, wifiRadioMessage = null) }
            val res = wifiRepository.saveWifiSchedule(config)
            when (res) {
                is RouterResult.Ok -> {
                    val msg = if (config.enabled) "Wi-Fi schedule saved" else "Wi-Fi schedule disabled"
                    local.update { it.copy(wifiRadioSaving = false, activeSheet = null, wifiRadioMessage = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    local.update { it.copy(wifiRadioSaving = false, wifiRadioMessage = "Error: ${res.errorMessage()}") }
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

    fun loadMobileAdvanced() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileAdvancedLoading = true) }
            val flags = ensureFlagsLoaded()
            val res = mobileRepository.loadMobileAdvanced(flags)
            local.update {
                it.copy(
                    mobileAdvancedLoading = false,
                    mobileMessage = if (res is RouterResult.Ok) it.mobileMessage else "Error: ${res.errorMessage()}"
                )
            }
        }
    }

    fun setNetworkMode(modeCode: String) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            val flags = ensureFlagsLoaded()
            when (val res = mobileRepository.setNetworkMode(modeCode, flags)) {
                is RouterResult.Ok -> {
                    val label = NetworkModeCatalog.labelFor(res.data.networkMode, res.data.availableModes)
                    val msg = "Network mode set to $label"
                    local.update { it.copy(mobileSaving = false, activeSheet = null, mobileMessage = msg) }
                    emitStatusMessage(msg)
                    triggerRefresh(true)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(mobileSaving = false, mobileMessage = "Error: $err") }
                }
            }
        }
    }

    fun setDataRoaming(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            val state = if (enabled) "enabled" else "disabled"
            val res = mobileRepository.setDataRoaming(enabled).let { r ->
                when (r) {
                    is RouterResult.Ok -> RouterResult.Ok("Data roaming $state")
                    is RouterResult.NotLoggedIn -> r
                    is RouterResult.LimitedAccess -> r
                    is RouterResult.Unreachable -> r
                    is RouterResult.BadResponse -> r
                }
            }
            handleMobileResult(res)
            triggerRefresh(true)
        }
    }

    fun setCarrierAggregation(lteCa: Boolean? = null, nrCa: Boolean? = null) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            val res = mobileRepository.setCarrierAggregation(lteCa = lteCa, nrCa = nrCa).let { r ->
                when (r) {
                    is RouterResult.Ok -> RouterResult.Ok("Carrier aggregation updated")
                    is RouterResult.NotLoggedIn -> r
                    is RouterResult.LimitedAccess -> r
                    is RouterResult.Unreachable -> r
                    is RouterResult.BadResponse -> r
                }
            }
            handleMobileResult(res)
            triggerRefresh(true)
        }
    }

    fun loadApnConfig() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(apnLoading = true) }
            val res = mobileRepository.loadApnConfig()
            local.update {
                it.copy(
                    apnLoading = false,
                    mobileMessage = if (res is RouterResult.Ok) it.mobileMessage else "Error: ${res.errorMessage()}"
                )
            }
        }
    }

    fun saveApnBasic(selectType: String, mtu: Int, natEnabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            when (val res = mobileRepository.saveApnBasic(selectType, mtu, natEnabled)) {
                is RouterResult.Ok -> {
                    val msg = "APN protocol and MTU saved"
                    local.update { it.copy(mobileSaving = false, mobileMessage = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(mobileSaving = false, mobileMessage = "Error: $err") }
                }
            }
        }
    }

    fun saveApnProfiles(profiles: List<ApnProfile>) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            when (val res = mobileRepository.saveApnProfiles(profiles)) {
                is RouterResult.Ok -> {
                    val msg = "APN profiles saved"
                    local.update { it.copy(mobileSaving = false, activeSheet = null, mobileMessage = msg) }
                    emitStatusMessage(msg)
                    triggerRefresh(true)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(mobileSaving = false, mobileMessage = "Error: $err") }
                }
            }
        }
    }

    fun resetApnProfiles() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(mobileSaving = true, mobileMessage = null) }
            when (val res = mobileRepository.resetApnProfiles()) {
                is RouterResult.Ok -> {
                    val msg = "Default APN profiles restored"
                    local.update { it.copy(mobileSaving = false, activeSheet = null, mobileMessage = msg) }
                    emitStatusMessage(msg)
                    triggerRefresh(true)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(mobileSaving = false, mobileMessage = "Error: $err") }
                }
            }
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

    fun loadStaticLeases() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(lanLoading = true, lanMessage = null) }
            val res = lanRepository.loadStaticLeases()
            local.update {
                it.copy(
                    lanLoading = false,
                    lanMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
        }
    }

    fun saveStaticLeases(leases: List<StaticDhcpLease>) {
        if (local.value.lanSaving) return
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(lanSaving = true, lanMessage = null) }
            val res = lanRepository.saveStaticLeases(
                leases = leases,
                syncIpMacBinding = uiState.value.featureFlags.dhcpIpMacBindingSync
            )
            when (res) {
                is RouterResult.Ok -> {
                    val msg = "Static DHCP leases updated"
                    local.update { it.copy(lanSaving = false, activeSheet = null, lanMessage = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    local.update { it.copy(lanSaving = false, lanMessage = "Error: ${res.errorMessage()}") }
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
        wpsPollJob?.cancel()
    }

    private fun buildNetworkModeSummary(router: RouterState): String {
        val mode = router.cellular.networkType.ifBlank { router.networkModeInfo.networkMode }.ifBlank { "--" }
        val is5gActive = mode.contains("5G", ignoreCase = true)
        val nrMode = if (is5gActive) {
            when (router.networkModeInfo.mode5g) {
                "0" -> "5G mode: SA + NSA"
                "1" -> "5G mode: SA"
                "2" -> "5G mode: NSA"
                else -> ""
            }
        } else {
            ""
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
        val wpsRandomPin: String? = null,
        val wpsRandomSecondsLeft: Int = 0,
        val wpsPairedSuccess: Boolean = false,
        val mobileAdvancedLoading: Boolean = false,
        val apnLoading: Boolean = false,
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
