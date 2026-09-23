package com.airtel.monitor.data.model

enum class DeviceType {
    PHONE,
    TABLET,
    PC,
    TV,
    GAMING,
    ROUTER,
    DEVICE
}

enum class DeviceFilter {
    ALL,
    WIFI_5G,
    WIFI_24G,
    LAN
}

enum class DeviceSort {
    SPEED,
    SIGNAL,
    NAME,
    IP
}

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    DISCONNECTED
}

data class WanMetrics(
    val downloadKbps: Double = 0.0,
    val uploadKbps: Double = 0.0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val dlFlowMb: String = "-",
    val ulFlowMb: String = "-",
    val totalFlowMb: String = "-",
    val peakDlMbps: Double = 0.0,
    val peakUlMbps: Double = 0.0
)

data class CellularMetrics(
    val networkType: String = "-",
    val signalLvl: Int = 0,
    val rsrp5g: String = "-",
    val sinr5g: String = "-",
    val rsrp4g: String = "-",
    val sinr4g: String = "-",
    val operator: String = "-",
    val boardType: String = "-",
    val iduType: String = "-",
    val nrCqi: String = "-",
    val lteCqi: String = "-",
    val nrQamDl: String = "-"
)

data class CellLockInfo(
    val servingPci4g: String = "-",
    val servingFreq4g: String = "-",
    val servingPci5g: String = "-",
    val servingFreq5g: String = "-",
    val currentBands4g: String = "-",
    val currentBand5g: String = "-",
    val lteLockEnabled: Boolean = false,
    val lteLockPci: String = "-",
    val lteLockFreq: String = "-",
    val nrLockEnabled: Boolean = false,
    val nrLockPci: String = "-",
    val nrLockFreq: String = "-",
    val bandLock4gEnabled: Boolean = false,
    val bandLock5gEnabled: Boolean = false
)

data class HardwareMetrics(
    val cpuUsage: Double = 0.0,
    val temperature: String = "--",
    val memory: String = "--"
)

data class DeviceItem(
    val mac: String,
    val ip: String,
    val hostname: String,
    val alias: String,
    val type: DeviceType,
    val band: String,
    val ssid: String = "",
    val rssi: String = "",
    val signalPercent: Int = 0,
    val txRateMbps: String = "-",
    val rxRateMbps: String = "-",
    val interfaceName: String = "wlan",
    val expires: String = "",
    val ipv6: String = "",
    val isRouter: Boolean = false
)

data class BandwidthPoint(
    val timeFormatted: String,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val rxKbps: Double,
    val txKbps: Double
)

data class DnsConfig(
    val primary: String = "",
    val secondary: String = "",
    val dhcpEnabled: Boolean = true
)

data class WifiRadioConfig(
    val enabled24g: Boolean = true,
    val ssid24g: String = "",
    val channel24g: String = "",
    val enabled5g: Boolean = true,
    val ssid5g: String = "",
    val channel5g: String = ""
)

enum class DiagnosisMode {
    PING,
    TRACEROUTE
}

data class DiagnosisState(
    val mode: DiagnosisMode = DiagnosisMode.PING,
    val target: String = "8.8.8.8",
    val pingTimes: Int = 4,
    val isRunning: Boolean = false,
    val output: String = "",
    val error: String? = null
)

data class RouterState(
    val timestamp: Long = System.currentTimeMillis(),
    val timeFormatted: String = "",
    val wan: WanMetrics = WanMetrics(),
    val cellular: CellularMetrics = CellularMetrics(),
    val cellLock: CellLockInfo = CellLockInfo(),
    val hardware: HardwareMetrics = HardwareMetrics(),
    val devices: List<DeviceItem> = emptyList(),
    val history: List<BandwidthPoint> = emptyList()
)

/** cmd 161: which LTE/NR bands the modem may use. Band numbers, e.g. 3 = B3, 78 = n78. */
data class BandLockConfig(
    val lock4gEnabled: Boolean = false,
    val supported4g: List<Int> = emptyList(),
    val locked4g: Set<Int> = emptySet(),
    val lock5gEnabled: Boolean = false,
    val supported5g: List<Int> = emptyList(),
    val locked5g: Set<Int> = emptySet()
)

data class LteLockCell(val earfcn: String, val pci: String)

data class NrLockCell(val band: String, val arfcn: String, val pci: String)

enum class LockState { UNLOCKED, LOCKED, FAILED }

/** cmd 160: local physical cell locking, plus the cell the modem is on right now. */
data class CellLockConfig(
    val lteEnabled: Boolean = false,
    val lteCells: List<LteLockCell> = emptyList(),
    val lteState: LockState = LockState.UNLOCKED,
    val nrEnabled: Boolean = false,
    val nrCells: List<NrLockCell> = emptyList(),
    val nrState: LockState = LockState.UNLOCKED,
    val current4g: LteLockCell? = null,
    val current5g: NrLockCell? = null
)

data class LockSettings(
    val bands: BandLockConfig = BandLockConfig(),
    val cells: CellLockConfig = CellLockConfig()
)
