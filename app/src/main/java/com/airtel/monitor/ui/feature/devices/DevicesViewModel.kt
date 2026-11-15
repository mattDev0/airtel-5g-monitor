package com.airtel.monitor.ui.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.data.model.DeviceFilter
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DeviceSort
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
    val filterMode: DeviceFilter = DeviceFilter.ALL,
    val sortMode: DeviceSort = DeviceSort.SPEED,
    val searchQuery: String = "",
    val renameTarget: DeviceItem? = null
)

class DevicesViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val local = MutableStateFlow(LocalDevicesState())

    val uiState: StateFlow<DevicesUiState> = combine(
        container.deviceRepository.devices,
        local
    ) { allDevices, loc ->
        val filtered = filterAndSort(allDevices, loc.filterMode, loc.sortMode, loc.searchQuery)
        DevicesUiState(
            filteredDevices = filtered,
            totalCount = allDevices.size,
            filterMode = loc.filterMode,
            sortMode = loc.sortMode,
            searchQuery = loc.searchQuery,
            renameTarget = loc.renameTarget
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DevicesUiState())

    fun setFilter(filter: DeviceFilter) {
        local.update { it.copy(filterMode = filter) }
    }

    fun setSort(sort: DeviceSort) {
        local.update { it.copy(sortMode = sort) }
    }

    fun setSearchQuery(query: String) {
        local.update { it.copy(searchQuery = query) }
    }

    fun openRenameDialog(device: DeviceItem) {
        local.update { it.copy(renameTarget = device) }
    }

    fun dismissRenameDialog() {
        local.update { it.copy(renameTarget = null) }
    }

    fun saveAlias(device: DeviceItem, alias: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.deviceRepository.saveAlias(device, alias)
            dismissRenameDialog()
            container.statusRepository.pollFast(forceSlow = false)
            container.emitStatusMessage("Device renamed to \"$alias\"")
        }
    }

    private fun filterAndSort(
        all: List<DeviceItem>,
        filterMode: DeviceFilter,
        sortMode: DeviceSort,
        searchQuery: String
    ): List<DeviceItem> {
        val filtered = when (filterMode) {
            DeviceFilter.ALL -> all
            DeviceFilter.WIFI_5G -> all.filter { it.band.contains("5 GHz", ignoreCase = true) }
            DeviceFilter.WIFI_24G -> all.filter { it.band.contains("2.4 GHz", ignoreCase = true) }
            DeviceFilter.LAN -> all.filter {
                it.band.contains("LAN", ignoreCase = true) || it.band.contains("Ethernet", ignoreCase = true)
            }
        }

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

        return when (sortMode) {
            DeviceSort.SPEED -> searched.sortedByDescending { it.txRateMbps.toDoubleOrNull() ?: 0.0 }
            DeviceSort.SIGNAL -> searched.sortedByDescending { it.signalPercent }
            DeviceSort.NAME -> searched.sortedBy { it.alias.lowercase() }
            DeviceSort.IP -> searched.sortedWith { a, b -> compareIps(a.ip, b.ip) }
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

    private data class LocalDevicesState(
        val filterMode: DeviceFilter = DeviceFilter.ALL,
        val sortMode: DeviceSort = DeviceSort.SPEED,
        val searchQuery: String = "",
        val renameTarget: DeviceItem? = null
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DevicesViewModel(container) }
        }
    }
}
