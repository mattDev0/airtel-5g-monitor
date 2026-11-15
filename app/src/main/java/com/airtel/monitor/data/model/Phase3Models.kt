package com.airtel.monitor.data.model

/** Full writable configuration for one Wi-Fi radio band read from `cmd 2` or `cmd 211` (`subcmd: 0`). */
data class WifiBandDetail(
    val enabled: Boolean = false,
    val ssid: String = "",
    val key: String = "",
    val visibleBroadcast: Boolean = true,
    val authenticationType: String = "2",
    val channel: String = "Auto",
    val hasWifiSames: Boolean = false,
    val wifiSames: Boolean = false
) {
    val isOpenNetwork: Boolean get() = authenticationType == "0"
    val securityLabel: String
        get() = when (authenticationType) {
            "0" -> "Open"
            "1" -> "WPA-PSK"
            "2" -> "WPA2-PSK"
            "3" -> "WPA/WPA2-PSK"
            "4" -> "WPA3-SAE"
            else -> "WPA2-PSK"
        }
}

/** Combined 2.4 GHz and 5 GHz Wi-Fi settings snapshot. */
data class WifiBandDetails(
    val band24g: WifiBandDetail = WifiBandDetail(),
    val band5g: WifiBandDetail = WifiBandDetail()
) {
    val hasWifiSames: Boolean get() = band24g.hasWifiSames || band5g.hasWifiSames
    val wifiSames: Boolean get() = band24g.wifiSames || band5g.wifiSames
}

/** Full LAN & DHCP snapshot parsed from `cmd 3` (`NETWORK_CONFIG`). */
data class LanConfig(
    val lanIp: String = "192.168.1.1",
    val netMask: String = "255.255.255.0",
    val dhcpEnabled: Boolean = true,
    val ipBegin: String = "192.168.1.100",
    val ipEnd: String = "192.168.1.200",
    val expireTime: String = "24h",
    val primaryDns: String = "",
    val secondaryDns: String = "",
    val mtu: String = "",
    val domain: String = ""
) {
    fun toDnsConfig(): DnsConfig = DnsConfig(
        primary = primaryDns,
        secondary = secondaryDns,
        dhcpEnabled = dhcpEnabled
    )
}

/** SIM lock / PIN / PUK telemetry read from `cmd 113` and `cmd 134` (`QUERY_SIM_STATUS`). */
data class SimPinState(
    val simInserted: Boolean = true,
    val cardType: String = "",
    val pinLockEnabled: Boolean = false,
    val pinRequired: Boolean = false,
    val pukRequired: Boolean = false,
    val pinAttemptsLeft: Int = 3,
    val pukAttemptsLeft: Int = 10
)
