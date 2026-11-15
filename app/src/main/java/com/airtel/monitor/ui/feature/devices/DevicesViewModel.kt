package com.airtel.monitor.ui.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.data.model.DeviceFilter
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DevicePresenceFilter
import com.airtel.monitor.data.model.DeviceSort
import com.airtel.monitor.data.repository.DeviceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val renameTarget: DeviceItem? = null
)

class DevicesViewModel(
    private val deviceRepository: DeviceRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onRefreshRequest: () -> Unit = {},
    private val onStatusMessage: (String) -> Unit = {}
) : ViewModel() {

    constructor(container: AppContainer) : this(
        deviceRepository = container.deviceRepository,
        ioDispatcher = Dispatchers.IO,
        onRefreshRequest = { container.pollingCoordinator.triggerImmediateRefresh(includeSlow = false) },
        onStatusMessage = container::emitStatusMessage
    )

    private val local = MutableStateFlow(LocalDevicesState())

    val uiState: StateFlow<DevicesUiState> = combine(
        deviceRepository.devices,
        local
    ) { allDevices, loc ->
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
            renameTarget = loc.renameTarget
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DevicesUiState())

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

    private data class LocalDevicesState(
        val presenceFilter: DevicePresenceFilter = DevicePresenceFilter.ALL,
        val filterMode: DeviceFilter = DeviceFilter.ALL,
        val sortMode: DeviceSort = DeviceSort.SPEED,
        val searchQuery: String = "",
        val selectedKey: String? = null,
        val renameTarget: DeviceItem? = null
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DevicesViewModel(container) }
        }
    }
}
