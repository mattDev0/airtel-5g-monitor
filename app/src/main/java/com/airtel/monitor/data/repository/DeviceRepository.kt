package com.airtel.monitor.data.repository

import com.airtel.monitor.core.util.OuiVendorLookup
import com.airtel.monitor.data.local.AliasStore
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.Locale

class DeviceRepository(
    devicesSource: Flow<List<DeviceItem>>,
    private val aliasStore: AliasStore,
    private val ouiLookup: OuiVendorLookup = OuiVendorLookup.EMPTY
) {
    constructor(
        statusRepository: StatusRepository,
        aliasStore: AliasStore,
        ouiLookup: OuiVendorLookup = OuiVendorLookup.EMPTY
    ) : this(
        devicesSource = statusRepository.routerState.map { it?.devices.orEmpty() },
        aliasStore = aliasStore,
        ouiLookup = ouiLookup
    )

    private val aliasVersion = MutableStateFlow(0L)

    val devices: Flow<List<DeviceItem>> = combine(devicesSource, aliasVersion) { rawDevices, _ ->
        rawDevices.map(::enrichDevice)
    }

    fun saveAlias(device: DeviceItem, alias: String) {
        val trimmed = alias.trim()
        aliasStore.saveAlias(device.mac, trimmed)
        if (device.ip.isNotEmpty()) {
            aliasStore.saveAlias(device.ip, trimmed)
        }
        aliasVersion.update { it + 1 }
    }

    fun clearAlias(device: DeviceItem) {
        saveAlias(device, "")
    }

    internal fun enrichDevice(raw: DeviceItem): DeviceItem {
        val oui = ouiLookup.lookup(raw.mac)
        val vendor = oui.vendor.orEmpty()
        val hasCustom = aliasStore.hasAlias(raw.mac) ||
            (raw.ip.isNotEmpty() && aliasStore.hasAlias(raw.ip))
        val resolvedAlias = if (hasCustom) {
            aliasStore.getAlias(raw.mac, aliasStore.getAlias(raw.ip, raw.hostname))
        } else {
            raw.hostname
        }
        val inferredType = refineDeviceType(raw.type, vendor)
        val online = determineOnline(raw)
        return raw.copy(
            alias = resolvedAlias,
            type = inferredType,
            vendor = vendor,
            isPrivateMac = oui.isPrivateMac,
            isOnline = online,
            hasCustomAlias = hasCustom
        )
    }

    companion object {
        internal fun determineOnline(device: DeviceItem): Boolean {
            if (device.isRouter) return true
            if (device.expires.trim() == "0") return false
            val isWlanLease = device.interfaceName.equals("wlan", ignoreCase = true)
            if (isWlanLease &&
                device.rssi.isBlank() &&
                (device.txRateMbps.isBlank() || device.txRateMbps == "-") &&
                (device.rxRateMbps.isBlank() || device.rxRateMbps == "-")
            ) {
                return false
            }
            return true
        }

        internal fun refineDeviceType(current: DeviceType, vendor: String): DeviceType {
            if (current != DeviceType.DEVICE || vendor.isBlank()) return current
            val v = vendor.lowercase(Locale.US)
            return when {
                v in PC_VENDORS -> DeviceType.PC
                v in GAMING_VENDORS -> DeviceType.GAMING
                v in TV_VENDORS -> DeviceType.TV
                v in ROUTER_VENDORS -> DeviceType.ROUTER
                else -> current
            }
        }

        private val PC_VENDORS = setOf(
            "intel", "dell", "hp", "lenovo", "asus", "acer", "microsoft", "vmware", "realtek", "broadcom"
        )
        private val GAMING_VENDORS = setOf("playstation", "nintendo", "valve")
        private val TV_VENDORS = setOf("tcl", "hisense", "roku")
        private val ROUTER_VENDORS = setOf("tp-link", "netgear", "d-link", "ubiquiti", "mikrotik", "cisco", "linksys", "tenda", "mercusys", "zte", "tozed")
    }
}
