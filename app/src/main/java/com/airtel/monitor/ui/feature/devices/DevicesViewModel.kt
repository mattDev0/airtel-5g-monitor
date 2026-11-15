package com.airtel.monitor.ui.feature.devices

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.data.model.DeviceFilter
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DevicePresenceFilter
import com.airtel.monitor.data.model.DeviceSort
import com.airtel.monitor.data.model.MacFilterRule
import com.airtel.monitor.data.model.MacFilterSnapshot
import com.airtel.monitor.data.model.ParentalControlSnapshot
import com.airtel.monitor.data.model.ParentalSpeedRule
import com.airtel.monitor.data.model.ParentalTimeRule
import com.airtel.monitor.data.model.ParentalUrlRule
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.StaticDhcpLease
import com.airtel.monitor.data.repository.DeviceRepository
import com.airtel.monitor.data.repository.LanRepository
import com.airtel.monitor.data.repository.ParentalControlRepository
import com.airtel.monitor.data.repository.SecurityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DevicePolicySheet {
    STATIC_LEASES,
    MAC_FILTER,
    PARENTAL_CONTROL
}

@Immutable
data class DevicesUiState(
    val filteredDevices: List<DeviceItem> = emptyList(),
    val totalCount: Int = 0,
    val onlineCount: Int = 0,
    val offlineCount: Int = 0,
    val wifi5gCount: Int = 0,
    val wifi24gCount: Int = 0,
    val lanCount: Int = 0,
    val presenceFilter: DevicePresenceFilter = DevicePresenceFilter.ALL,
    val filterMode: DeviceFilter = DeviceFilter.ALL,
    val sortMode: DeviceSort = DeviceSort.SPEED,
    val searchQuery: String = "",
    val selectedDevice: DeviceItem? = null,
    val renameTarget: DeviceItem? = null,
    val featureFlags: RouterFeatureFlags = RouterFeatureFlags(),
    val staticLeases: List<StaticDhcpLease> = emptyList(),
    val staticLeasesLoading: Boolean = false,
    val staticLeasesSaving: Boolean = false,
    val macFilter: MacFilterSnapshot = MacFilterSnapshot(),
    val macFilterLoading: Boolean = false,
    val macFilterSaving: Boolean = false,
    val parental: ParentalControlSnapshot = ParentalControlSnapshot(),
    val parentalLoading: Boolean = false,
    val parentalSaving: Boolean = false,
    val activePolicySheet: DevicePolicySheet? = null,
    val prefillDevice: DeviceItem? = null,
    val policyErrorMessage: String? = null
) {
    fun isMacReserved(mac: String): Boolean {
        val norm = Phase71Validators.normalizeMac(mac) ?: return false
        return staticLeases.any { it.mac.equals(norm, ignoreCase = true) }
    }

    fun isMacBlocked(mac: String): Boolean {
        val norm = Phase71Validators.normalizeMac(mac) ?: return false
        return if (macFilter.acceptAllIpv4) {
            macFilter.rules.any {
                it.enableRule && !it.enableLink && it.mac.equals(norm, ignoreCase = true)
            }
        } else {
            macFilter.rules.none {
                it.enableRule && it.enableLink && it.mac.equals(norm, ignoreCase = true)
            }
        }
    }
}

class DevicesViewModel(
    private val deviceRepository: DeviceRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onRefreshRequest: () -> Unit = {},
    private val onStatusMessage: (String) -> Unit = {},
    private val lanRepository: LanRepository? = null,
    private val securityRepository: SecurityRepository? = null,
    private val parentalControlRepository: ParentalControlRepository? = null,
    private val featureFlagsFlow: StateFlow<RouterFeatureFlags> = MutableStateFlow(RouterFeatureFlags()),
    private val ensureFeatureFlagsLoaded: suspend () -> Unit = {}
) : ViewModel() {

    constructor(container: AppContainer) : this(
        deviceRepository = container.deviceRepository,
        ioDispatcher = Dispatchers.IO,
        onRefreshRequest = { container.pollingCoordinator.triggerImmediateRefresh(includeSlow = false) },
        onStatusMessage = container::emitStatusMessage,
        lanRepository = container.lanRepository,
        securityRepository = container.securityRepository,
        parentalControlRepository = container.parentalControlRepository,
        featureFlagsFlow = container.featureFlagRepository.flags,
        ensureFeatureFlagsLoaded = { container.featureFlagRepository.ensureLoaded() }
    )

    private val local = MutableStateFlow(LocalDevicesState())
    private val emptyLeasesFlow = MutableStateFlow<List<StaticDhcpLease>>(emptyList())
    private val emptyMacFlow = MutableStateFlow(MacFilterSnapshot())
    private val emptyParentalFlow = MutableStateFlow(ParentalControlSnapshot())
    private var policiesLoadedOnce = false

    private val policyCombinedFlow = combine(
        lanRepository?.staticLeases ?: emptyLeasesFlow,
        securityRepository?.macFilter ?: emptyMacFlow,
        parentalControlRepository?.snapshot ?: emptyParentalFlow,
        featureFlagsFlow
    ) { leases, mac, parental, flags ->
        PolicyState(leases = leases, macFilter = mac, parental = parental, featureFlags = flags)
    }

    val uiState: StateFlow<DevicesUiState> = combine(
        deviceRepository.devices,
        policyCombinedFlow,
        local
    ) { allDevices, policies, loc ->
        val filtered = filterAndSort(
            all = allDevices,
            presenceFilter = loc.presenceFilter,
            filterMode = loc.filterMode,
            sortMode = loc.sortMode,
            searchQuery = loc.searchQuery
        )
        val onlineCount = allDevices.count { it.isOnline }
        val offlineCount = allDevices.size - onlineCount
        val wifi5gCount = allDevices.count { it.band.contains("5 GHz", ignoreCase = true) }
        val wifi24gCount = allDevices.count { it.band.contains("2.4 GHz", ignoreCase = true) }
        val lanCount = allDevices.count { it.isLanBand() }
        val selected = loc.selectedKey?.let { key ->
            allDevices.firstOrNull { deviceKey(it) == key }
        }
        DevicesUiState(
            filteredDevices = filtered,
            totalCount = allDevices.size,
            onlineCount = onlineCount,
            offlineCount = offlineCount,
            wifi5gCount = wifi5gCount,
            wifi24gCount = wifi24gCount,
            lanCount = lanCount,
            presenceFilter = loc.presenceFilter,
            filterMode = loc.filterMode,
            sortMode = loc.sortMode,
            searchQuery = loc.searchQuery,
            selectedDevice = selected,
            renameTarget = loc.renameTarget,
            featureFlags = policies.featureFlags,
            staticLeases = policies.leases,
            staticLeasesLoading = loc.staticLeasesLoading,
            staticLeasesSaving = loc.staticLeasesSaving,
            macFilter = policies.macFilter,
            macFilterLoading = loc.macFilterLoading,
            macFilterSaving = loc.macFilterSaving,
            parental = policies.parental,
            parentalLoading = loc.parentalLoading,
            parentalSaving = loc.parentalSaving,
            activePolicySheet = loc.activePolicySheet,
            prefillDevice = loc.prefillDevice,
            policyErrorMessage = loc.policyErrorMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DevicesUiState())

    fun ensurePoliciesLoaded() {
        if (policiesLoadedOnce) return
        policiesLoadedOnce = true
        viewModelScope.launch(ioDispatcher) {
            ensureFeatureFlagsLoaded()
        }
        if (lanRepository != null) loadStaticLeases()
        if (securityRepository != null) loadMacFilter()
    }

    fun setPresenceFilter(filter: DevicePresenceFilter) {
        local.update { it.copy(presenceFilter = filter) }
    }

    fun setFilter(filter: DeviceFilter) {
        local.update { it.copy(filterMode = filter) }
    }

    fun setSort(sort: DeviceSort) {
        local.update { it.copy(sortMode = sort) }
    }

    fun setSearchQuery(query: String) {
        local.update { it.copy(searchQuery = query) }
    }

    fun clearFilters() {
        local.update {
            it.copy(
                presenceFilter = DevicePresenceFilter.ALL,
                filterMode = DeviceFilter.ALL,
                searchQuery = ""
            )
        }
    }

    fun selectDevice(device: DeviceItem) {
        local.update { it.copy(selectedKey = deviceKey(device)) }
    }

    fun dismissDetailSheet() {
        local.update { it.copy(selectedKey = null) }
    }

    fun openRenameDialog(device: DeviceItem) {
        local.update { it.copy(renameTarget = device) }
    }

    fun dismissRenameDialog() {
        local.update { it.copy(renameTarget = null) }
    }

    fun openPolicySheet(sheet: DevicePolicySheet, prefillDevice: DeviceItem? = null) {
        local.update {
            it.copy(
                activePolicySheet = sheet,
                prefillDevice = prefillDevice,
                policyErrorMessage = null
            )
        }
        when (sheet) {
            DevicePolicySheet.STATIC_LEASES -> loadStaticLeases()
            DevicePolicySheet.MAC_FILTER -> loadMacFilter()
            DevicePolicySheet.PARENTAL_CONTROL -> loadParentalControl()
        }
    }

    fun dismissPolicySheet() {
        local.update {
            it.copy(
                activePolicySheet = null,
                prefillDevice = null,
                policyErrorMessage = null
            )
        }
    }

    fun loadStaticLeases() {
        val repo = lanRepository ?: return
        viewModelScope.launch {
            local.update { it.copy(staticLeasesLoading = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.loadStaticLeases() }
            local.update {
                it.copy(
                    staticLeasesLoading = false,
                    policyErrorMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
        }
    }

    fun saveStaticLeases(leases: List<StaticDhcpLease>) {
        val repo = lanRepository ?: return
        if (local.value.staticLeasesSaving) return
        viewModelScope.launch {
            local.update { it.copy(staticLeasesSaving = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) {
                repo.saveStaticLeases(
                    leases = leases,
                    syncIpMacBinding = uiState.value.featureFlags.dhcpIpMacBindingSync
                )
            }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(staticLeasesSaving = false, activePolicySheet = null, policyErrorMessage = null) }
                    onStatusMessage("Static DHCP leases updated")
                }
                else -> {
                    local.update { it.copy(staticLeasesSaving = false, policyErrorMessage = res.errorMessage()) }
                }
            }
        }
    }

    fun reserveStaticIpForDevice(device: DeviceItem) {
        val repo = lanRepository ?: return
        val normMac = Phase71Validators.normalizeMac(device.mac)
        if (normMac == null || device.ip.isBlank()) {
            onStatusMessage("Error: device IP or MAC is missing")
            return
        }
        if (local.value.staticLeasesSaving) return
        viewModelScope.launch {
            local.update { it.copy(staticLeasesSaving = true, policyErrorMessage = null) }
            val current = withContext(ioDispatcher) {
                repo.loadStaticLeases().getOrNull() ?: uiState.value.staticLeases
            }
            val updated = current.filterNot {
                it.mac.equals(normMac, ignoreCase = true) || it.ip == device.ip.trim()
            } + StaticDhcpLease(ip = device.ip.trim(), mac = normMac)
            val res = withContext(ioDispatcher) {
                repo.saveStaticLeases(
                    leases = updated,
                    syncIpMacBinding = uiState.value.featureFlags.dhcpIpMacBindingSync
                )
            }
            local.update { it.copy(staticLeasesSaving = false) }
            if (res is RouterResult.Ok) {
                onStatusMessage("Reserved ${device.ip.trim()} for ${device.alias}")
            } else {
                onStatusMessage("Error: ${res.errorMessage()}")
            }
        }
    }

    fun loadMacFilter() {
        val repo = securityRepository ?: return
        viewModelScope.launch {
            local.update { it.copy(macFilterLoading = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.loadMacFilter() }
            local.update {
                it.copy(
                    macFilterLoading = false,
                    policyErrorMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
        }
    }

    fun saveMacFilterRules(rules: List<MacFilterRule>) {
        val repo = securityRepository ?: return
        if (local.value.macFilterSaving) return
        viewModelScope.launch {
            local.update { it.copy(macFilterSaving = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) {
                repo.saveMacFilterRules(rules = rules, acceptAllOverride = uiState.value.macFilter.acceptAllIpv4)
            }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(macFilterSaving = false, activePolicySheet = null, policyErrorMessage = null) }
                    onStatusMessage("MAC filter rules applied")
                }
                else -> {
                    local.update { it.copy(macFilterSaving = false, policyErrorMessage = res.errorMessage()) }
                }
            }
        }
    }

    fun saveMacDefaultPolicy(acceptAll: Boolean) {
        val repo = securityRepository ?: return
        if (local.value.macFilterSaving) return
        viewModelScope.launch {
            local.update { it.copy(macFilterSaving = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.saveDefaultFilterPolicy(acceptAll) }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(macFilterSaving = false, policyErrorMessage = null) }
                    onStatusMessage(
                        if (acceptAll) "MAC default policy set to Blacklist (allow all except listed)"
                        else "MAC default policy set to Whitelist (allow listed only)"
                    )
                }
                else -> {
                    local.update { it.copy(macFilterSaving = false, policyErrorMessage = res.errorMessage()) }
                }
            }
        }
    }

    fun toggleBlockDevice(device: DeviceItem) {
        val repo = securityRepository ?: return
        val normMac = Phase71Validators.normalizeMac(device.mac)
        if (normMac == null) {
            onStatusMessage("Error: invalid device MAC address")
            return
        }
        if (local.value.macFilterSaving) return
        viewModelScope.launch {
            local.update { it.copy(macFilterSaving = true, policyErrorMessage = null) }
            val current = withContext(ioDispatcher) {
                repo.loadMacFilter().getOrNull() ?: uiState.value.macFilter
            }
            val acceptAll = current.acceptAllIpv4
            val wasBlocked = if (acceptAll) {
                current.rules.any { it.enableRule && !it.enableLink && it.mac.equals(normMac, ignoreCase = true) }
            } else {
                current.rules.none { it.enableRule && it.enableLink && it.mac.equals(normMac, ignoreCase = true) }
            }
            val nextRules = if (acceptAll) {
                if (wasBlocked) {
                    current.rules.filterNot { it.mac.equals(normMac, ignoreCase = true) }
                } else {
                    current.rules.filterNot { it.mac.equals(normMac, ignoreCase = true) && it.ippro == "IPV4" } +
                        MacFilterRule(
                            mac = normMac,
                            remark = device.alias.trim().take(32),
                            ippro = "IPV4",
                            enableRule = true,
                            enableLink = false
                        )
                }
            } else {
                if (wasBlocked) {
                    current.rules.filterNot { it.mac.equals(normMac, ignoreCase = true) && it.ippro == "IPV4" } +
                        MacFilterRule(
                            mac = normMac,
                            remark = device.alias.trim().take(32),
                            ippro = "IPV4",
                            enableRule = true,
                            enableLink = true
                        )
                } else {
                    current.rules.filterNot { it.mac.equals(normMac, ignoreCase = true) }
                }
            }
            val res = withContext(ioDispatcher) {
                repo.saveMacFilterRules(rules = nextRules, acceptAllOverride = acceptAll)
            }
            local.update { it.copy(macFilterSaving = false) }
            if (res is RouterResult.Ok) {
                onStatusMessage(
                    if (wasBlocked) "Unblocked ${device.alias}" else "Blocked ${device.alias}"
                )
            } else {
                onStatusMessage("Error: ${res.errorMessage()}")
            }
        }
    }

    fun loadParentalControl() {
        val repo = parentalControlRepository ?: return
        viewModelScope.launch {
            local.update { it.copy(parentalLoading = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.loadAll() }
            local.update {
                it.copy(
                    parentalLoading = false,
                    policyErrorMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
        }
    }

    fun setParentalEnabled(enabled: Boolean) {
        val repo = parentalControlRepository ?: return
        if (local.value.parentalSaving) return
        viewModelScope.launch {
            local.update { it.copy(parentalSaving = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.setEnabled(enabled) }
            local.update {
                it.copy(
                    parentalSaving = false,
                    policyErrorMessage = if (res is RouterResult.Ok) null else res.errorMessage()
                )
            }
            if (res is RouterResult.Ok) {
                onStatusMessage(if (enabled) "Parental controls enabled" else "Parental controls disabled")
            }
        }
    }

    fun saveParentalSpeedRules(rules: List<ParentalSpeedRule>) {
        val repo = parentalControlRepository ?: return
        if (local.value.parentalSaving) return
        viewModelScope.launch {
            local.update { it.copy(parentalSaving = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.saveSpeedRules(rules) }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(parentalSaving = false, policyErrorMessage = null) }
                    onStatusMessage("Speed limit rules saved")
                }
                else -> {
                    local.update { it.copy(parentalSaving = false, policyErrorMessage = res.errorMessage()) }
                }
            }
        }
    }

    fun saveParentalUrlRules(rules: List<ParentalUrlRule>) {
        val repo = parentalControlRepository ?: return
        if (local.value.parentalSaving) return
        viewModelScope.launch {
            local.update { it.copy(parentalSaving = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.saveUrlRules(rules) }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(parentalSaving = false, policyErrorMessage = null) }
                    onStatusMessage("Blocked URL rules saved")
                }
                else -> {
                    local.update { it.copy(parentalSaving = false, policyErrorMessage = res.errorMessage()) }
                }
            }
        }
    }

    fun saveParentalTimeRules(rules: List<ParentalTimeRule>) {
        val repo = parentalControlRepository ?: return
        if (local.value.parentalSaving) return
        viewModelScope.launch {
            local.update { it.copy(parentalSaving = true, policyErrorMessage = null) }
            val res = withContext(ioDispatcher) { repo.saveTimeRules(rules) }
            when (res) {
                is RouterResult.Ok -> {
                    local.update { it.copy(parentalSaving = false, policyErrorMessage = null) }
                    onStatusMessage("Schedule rules saved")
                }
                else -> {
                    local.update { it.copy(parentalSaving = false, policyErrorMessage = res.errorMessage()) }
                }
            }
        }
    }

    fun saveAlias(device: DeviceItem, alias: String) {
        val cleaned = alias.trim()
        viewModelScope.launch(ioDispatcher) {
            deviceRepository.saveAlias(device, cleaned)
            dismissRenameDialog()
            onRefreshRequest()
            onStatusMessage("Device renamed to \"$cleaned\"")
        }
    }

    fun clearAlias(device: DeviceItem) {
        viewModelScope.launch(ioDispatcher) {
            deviceRepository.clearAlias(device)
            onRefreshRequest()
            onStatusMessage("Restored router name for ${device.hostname}")
        }
    }

    fun emitFeedback(message: String) {
        onStatusMessage(message)
    }

    internal fun filterAndSort(
        all: List<DeviceItem>,
        presenceFilter: DevicePresenceFilter,
        filterMode: DeviceFilter,
        sortMode: DeviceSort,
        searchQuery: String
    ): List<DeviceItem> {
        val byPresence = when (presenceFilter) {
            DevicePresenceFilter.ALL -> all
            DevicePresenceFilter.ONLINE -> all.filter { it.isOnline }
            DevicePresenceFilter.OFFLINE -> all.filter { !it.isOnline }
        }

        val byBand = when (filterMode) {
            DeviceFilter.ALL -> byPresence
            DeviceFilter.WIFI_5G -> byPresence.filter { it.band.contains("5 GHz", ignoreCase = true) }
            DeviceFilter.WIFI_24G -> byPresence.filter { it.band.contains("2.4 GHz", ignoreCase = true) }
            DeviceFilter.LAN -> byPresence.filter { it.isLanBand() }
        }

        val searched = if (searchQuery.isBlank()) {
            byBand
        } else {
            val q = searchQuery.trim().lowercase()
            byBand.filter { d ->
                d.alias.lowercase().contains(q) ||
                    d.hostname.lowercase().contains(q) ||
                    d.ip.contains(q) ||
                    d.ipv6.lowercase().contains(q) ||
                    d.mac.lowercase().contains(q) ||
                    d.vendor.lowercase().contains(q) ||
                    d.ssid.lowercase().contains(q) ||
                    (d.isPrivateMac && "private randomized".contains(q))
            }
        }

        return when (sortMode) {
            DeviceSort.SPEED -> searched.sortedWith(
                compareByDescending<DeviceItem> { it.isOnline }
                    .thenByDescending { it.txRateMbps.toDoubleOrNull() ?: it.rxRateMbps.toDoubleOrNull() ?: -1.0 }
                    .thenBy { it.alias.lowercase() }
            )
            DeviceSort.SIGNAL -> searched.sortedWith(
                compareByDescending<DeviceItem> { it.isOnline }
                    .thenByDescending { it.signalPercent }
                    .thenBy { it.alias.lowercase() }
            )
            DeviceSort.NAME -> searched.sortedBy { it.alias.lowercase() }
            DeviceSort.IP -> searched.sortedWith { a, b -> compareIps(a.ip, b.ip) }
        }
    }

    private fun DeviceItem.isLanBand(): Boolean =
        band.contains("LAN", ignoreCase = true) || band.contains("Ethernet", ignoreCase = true)

    private fun deviceKey(device: DeviceItem): String =
        device.mac.ifBlank { device.ip }

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

    private data class PolicyState(
        val leases: List<StaticDhcpLease> = emptyList(),
        val macFilter: MacFilterSnapshot = MacFilterSnapshot(),
        val parental: ParentalControlSnapshot = ParentalControlSnapshot(),
        val featureFlags: RouterFeatureFlags = RouterFeatureFlags()
    )

    private data class LocalDevicesState(
        val presenceFilter: DevicePresenceFilter = DevicePresenceFilter.ALL,
        val filterMode: DeviceFilter = DeviceFilter.ALL,
        val sortMode: DeviceSort = DeviceSort.SPEED,
        val searchQuery: String = "",
        val selectedKey: String? = null,
        val renameTarget: DeviceItem? = null,
        val staticLeasesLoading: Boolean = false,
        val staticLeasesSaving: Boolean = false,
        val macFilterLoading: Boolean = false,
        val macFilterSaving: Boolean = false,
        val parentalLoading: Boolean = false,
        val parentalSaving: Boolean = false,
        val activePolicySheet: DevicePolicySheet? = null,
        val prefillDevice: DeviceItem? = null,
        val policyErrorMessage: String? = null
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DevicesViewModel(container) }
        }
    }
}
