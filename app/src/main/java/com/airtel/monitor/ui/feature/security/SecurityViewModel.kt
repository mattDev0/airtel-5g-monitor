package com.airtel.monitor.ui.feature.security

import com.airtel.monitor.data.model.DdosConfig
import com.airtel.monitor.data.model.DmzConfig
import com.airtel.monitor.data.model.IpPortFilterRule
import com.airtel.monitor.data.model.MacFilterRule
import com.airtel.monitor.data.model.MacFilterSnapshot
import com.airtel.monitor.data.model.PortFilterRule
import com.airtel.monitor.data.model.PortForwardRule
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.TrustedIpConfig
import com.airtel.monitor.data.model.UpnpStatus
import com.airtel.monitor.data.model.UrlFilterRule
import kotlinx.coroutines.flow.flowOf

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
import kotlinx.coroutines.flow.Flow
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
    ),
    val featureFlags: RouterFeatureFlags = RouterFeatureFlags(),
    val dmz: DmzConfig = DmzConfig(),
    val ddos: DdosConfig = DdosConfig(),
    val upnp: UpnpStatus = UpnpStatus(),
    val systemPorts: Set<Int> = emptySet(),
    val portForward: List<PortForwardRule> = emptyList(),
    val ipPortFilter: List<IpPortFilterRule> = emptyList(),
    val portFilter: List<PortFilterRule> = emptyList(),
    val urlFilter: List<UrlFilterRule> = emptyList(),
    val urlDefaultAcceptAll: Boolean = true,
    val macFilter: MacFilterSnapshot = MacFilterSnapshot(),
    val trustedIp: TrustedIpConfig = TrustedIpConfig(),
    val activeSheet: ActiveSecuritySheet? = null,
    val sheetBusy: Boolean = false,
    val sheetError: String? = null
)

enum class ActiveSecuritySheet {
    DMZ,
    PORT_FORWARD,
    IP_PORT_FILTER,
    PORT_FILTER,
    URL_FILTER,
    MAC_FILTER,
    TRUSTED_IP
}

class SecurityViewModel(
    private val securityRepository: SecurityRepository,
    private val featureFlagsFlow: Flow<RouterFeatureFlags> = flowOf(RouterFeatureFlags()),
    private val ensureFlagsLoaded: suspend () -> RouterFeatureFlags = { RouterFeatureFlags() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStatusMessage: (String) -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState(isLoading = true))
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            featureFlagsFlow.collect { flags ->
                _uiState.update { it.copy(featureFlags = flags) }
            }
        }
        viewModelScope.launch {
            securityRepository.macFilter.collect { snap ->
                _uiState.update { it.copy(macFilter = snap) }
            }
        }
        refresh()
    }

    fun openSheet(sheet: ActiveSecuritySheet) {
        _uiState.update { it.copy(activeSheet = sheet, sheetError = null) }
        refreshSheet(sheet)
    }

    fun dismissSheet() {
        _uiState.update { it.copy(activeSheet = null, sheetError = null) }
    }

    fun refreshSheet(sheet: ActiveSecuritySheet) {
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res: RouterResult<*> = withContext(ioDispatcher) {
                when (sheet) {
                    ActiveSecuritySheet.DMZ -> securityRepository.loadDmz()
                    ActiveSecuritySheet.PORT_FORWARD -> {
                        securityRepository.loadSystemPorts()
                        securityRepository.loadPortForward()
                    }
                    ActiveSecuritySheet.IP_PORT_FILTER -> {
                        securityRepository.loadMacFilter()
                        securityRepository.loadIpPortFilter()
                    }
                    ActiveSecuritySheet.PORT_FILTER -> {
                        securityRepository.loadMacFilter()
                        securityRepository.loadPortFilter()
                    }
                    ActiveSecuritySheet.URL_FILTER -> securityRepository.loadUrlFilter()
                    ActiveSecuritySheet.MAC_FILTER -> securityRepository.loadMacFilter()
                    ActiveSecuritySheet.TRUSTED_IP -> securityRepository.loadTrustedIps()
                }
            }
            _uiState.update { state ->
                state.copy(
                    sheetBusy = false,
                    sheetError = if (res is RouterResult.Ok) null else res.errorMessage(),
                    dmz = if (sheet == ActiveSecuritySheet.DMZ && res is RouterResult.Ok) res.data as DmzConfig else state.dmz,
                    systemPorts = securityRepository.systemPorts.value,
                    portForward = if (sheet == ActiveSecuritySheet.PORT_FORWARD && res is RouterResult.Ok) securityRepository.portForward.value else state.portForward,
                    ipPortFilter = if (sheet == ActiveSecuritySheet.IP_PORT_FILTER && res is RouterResult.Ok) securityRepository.ipPortFilter.value else state.ipPortFilter,
                    portFilter = if (sheet == ActiveSecuritySheet.PORT_FILTER && res is RouterResult.Ok) securityRepository.portFilter.value else state.portFilter,
                    urlFilter = if (sheet == ActiveSecuritySheet.URL_FILTER && res is RouterResult.Ok) securityRepository.urlFilter.value else state.urlFilter,
                    urlDefaultAcceptAll = securityRepository.urlDefaultAcceptAll.value,
                    macFilter = securityRepository.macFilter.value,
                    trustedIp = if (sheet == ActiveSecuritySheet.TRUSTED_IP && res is RouterResult.Ok) res.data as TrustedIpConfig else state.trustedIp
                )
            }
        }
    }

    fun loadAdvancedSummaries() {
        viewModelScope.launch {
            val flags = withContext(ioDispatcher) { ensureFlagsLoaded() }
            withContext(ioDispatcher) {
                if (flags.dmzAvailable) securityRepository.loadDmz()
                if (flags.ddosAvailable) securityRepository.loadDdos()
                if (flags.upnpAvailable) securityRepository.loadUpnp()
                if (flags.portForwardAvailable) securityRepository.loadPortForward()
                if (flags.trustedIpAvailable) securityRepository.loadTrustedIps()
            }
            _uiState.update { state ->
                state.copy(
                    featureFlags = flags,
                    dmz = securityRepository.dmz.value,
                    ddos = securityRepository.ddos.value,
                    upnp = securityRepository.upnp.value,
                    portForward = securityRepository.portForward.value,
                    trustedIp = securityRepository.trustedIp.value
                )
            }
        }
    }

    fun saveDdos(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val res = withContext(ioDispatcher) { securityRepository.saveDdos(enabled) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update { it.copy(isSaving = false, ddos = res.data) }
                    onStatusMessage(if (enabled) "DDoS protection enabled" else "DDoS protection disabled")
                }
                else -> {
                    _uiState.update { it.copy(isSaving = false) }
                    onStatusMessage(res.errorMessage())
                }
            }
        }
    }

    fun saveUpnp(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val res = withContext(ioDispatcher) { securityRepository.saveUpnp(enabled) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update { it.copy(isSaving = false, upnp = res.data) }
                    onStatusMessage(if (enabled) "UPnP enabled" else "UPnP disabled")
                }
                else -> {
                    _uiState.update { it.copy(isSaving = false) }
                    onStatusMessage(res.errorMessage())
                }
            }
        }
    }

    fun saveDmz(enabled: Boolean, ip: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) { securityRepository.saveDmz(enabled, ip) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update { it.copy(sheetBusy = false, dmz = res.data, activeSheet = null) }
                    onStatusMessage("DMZ configuration saved")
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
    }

    fun findSystemPortConflict(rule: PortForwardRule): Int? =
        securityRepository.findSystemPortConflict(rule)

    fun savePortForwardRules(rules: List<PortForwardRule>) {
        executeSheetSave("Port forwarding rules applied") {
            securityRepository.savePortForwardRules(rules)
        }
    }

    fun saveIpPortFilterRules(rules: List<IpPortFilterRule>) {
        executeSheetSave("IP / port filter rules applied") {
            securityRepository.saveIpPortFilterRules(rules)
        }
    }

    fun savePortFilterRules(rules: List<PortFilterRule>) {
        executeSheetSave("Port filter rules applied") {
            securityRepository.savePortFilterRules(rules)
        }
    }

    fun saveUrlFilterRules(rules: List<UrlFilterRule>) {
        executeSheetSave("URL filter rules applied") {
            securityRepository.saveUrlFilterRules(rules)
        }
    }

    fun saveUrlDefaultPolicy(acceptAll: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) { securityRepository.saveUrlDefaultPolicy(acceptAll) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update {
                        it.copy(
                            sheetBusy = false,
                            urlDefaultAcceptAll = res.data,
                            urlFilter = securityRepository.urlFilter.value
                        )
                    }
                    onStatusMessage("URL default policy updated")
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
    }

    fun saveMacFilterRules(rules: List<MacFilterRule>) {
        executeSheetSave("MAC filter rules applied") {
            securityRepository.saveMacFilterRules(rules)
        }
    }

    fun saveDefaultFilterPolicy(acceptAll: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) { securityRepository.saveDefaultFilterPolicy(acceptAll) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update { it.copy(sheetBusy = false, macFilter = res.data) }
                    onStatusMessage("Default firewall policy updated")
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
    }

    fun saveTrustedIps(config: TrustedIpConfig) {
        executeSheetSave("Trusted management IPs updated") {
            securityRepository.saveTrustedIps(config)
        }
    }

    private fun executeSheetSave(
        successMsg: String,
        block: suspend () -> RouterResult<*>
    ) {
        if (_uiState.value.sheetBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) { block() }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update { state ->
                        state.copy(
                            sheetBusy = false,
                            activeSheet = null,
                            dmz = securityRepository.dmz.value,
                            portForward = securityRepository.portForward.value,
                            ipPortFilter = securityRepository.ipPortFilter.value,
                            portFilter = securityRepository.portFilter.value,
                            urlFilter = securityRepository.urlFilter.value,
                            urlDefaultAcceptAll = securityRepository.urlDefaultAcceptAll.value,
                            macFilter = securityRepository.macFilter.value,
                            trustedIp = securityRepository.trustedIp.value
                        )
                    }
                    onStatusMessage(successMsg)
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
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
            loadAdvancedSummaries()
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
                    featureFlagsFlow = container.featureFlagRepository.flags,
                    ensureFlagsLoaded = {
                        (container.featureFlagRepository.ensureLoaded() as? RouterResult.Ok)?.data
                            ?: container.featureFlagRepository.flags.value
                    },
                    onStatusMessage = container::emitStatusMessage
                )
            }
        }
    }
}
