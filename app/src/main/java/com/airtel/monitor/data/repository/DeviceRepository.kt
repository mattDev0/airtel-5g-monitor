package com.airtel.monitor.data.repository

import com.airtel.monitor.data.local.AliasRepository
import com.airtel.monitor.data.model.DeviceItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DeviceRepository(
    statusRepository: StatusRepository,
    private val aliasRepository: AliasRepository
) {
    val devices: Flow<List<DeviceItem>> = statusRepository.routerState.map { it?.devices.orEmpty() }

    fun saveAlias(device: DeviceItem, alias: String) {
        aliasRepository.saveAlias(device.mac, alias)
        if (device.ip.isNotEmpty()) {
            aliasRepository.saveAlias(device.ip, alias)
        }
    }
}
