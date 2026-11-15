package com.airtel.monitor.data.model

import androidx.compose.runtime.Immutable
import java.util.Locale

@Immutable
data class NetworkModeOption(
    val code: String,
    val label: String
)

/** Catalog & display decoder derived from `js/advance/networkSet.js:237-294`. */
object NetworkModeCatalog {
    private val baseOptions = listOf(
        NetworkModeOption("1", "2G Only"),
        NetworkModeOption("2", "3G Only"),
        NetworkModeOption("3", "3G/2G"),
        NetworkModeOption("4", "4G Only"),
        NetworkModeOption("40", "4G TDD Only"),
        NetworkModeOption("20", "4G FDD Only"),
        NetworkModeOption("6", "4G/3G"),
        NetworkModeOption("7", "4G/3G/2G"),
        NetworkModeOption("C", "5G NSA Only"),
        NetworkModeOption("10", "5G SA Only"),
        NetworkModeOption("1C", "5G SA+NSA/4G"),
        NetworkModeOption("E", "5G NSA/4G/3G"),
        NetworkModeOption("F", "5G NSA/4G/3G/2G"),
        NetworkModeOption("1E", "5G SA+NSA/4G/3G"),
        NetworkModeOption("1F", "5G SA+NSA/4G/3G/2G")
    )

    private val programList: Map<String, List<String>> = mapOf(
        "3" to listOf("1", "2", "3"),
        "6" to listOf("2", "4", "6"),
        "7" to listOf("1", "2", "3", "4", "7"),
        "C" to listOf("4", "C"),
        "1C" to listOf("4", "C", "10", "1C"),
        "E" to listOf("2", "4", "6", "C", "E"),
        "1E" to listOf("2", "4", "6", "C", "10", "1C", "1E"),
        "F" to listOf("4", "7", "C", "F"),
        "1F" to listOf("4", "7", "C", "10", "1C", "1F")
    )

    fun availableOptions(aeraId: String, rawDisplayMode: String): List<NetworkModeOption> {
        val cleanArea = aeraId.trim()
        val normalizedSupport = normalizeSupportHex(cleanArea, rawDisplayMode)
        val codes: List<String> = when {
            cleanArea == "NG0002" -> listOf("C", "1C", "1E")
            cleanArea == "TZ0355" -> listOf("C", "1C")
            cleanArea == "CN0593" -> if (programList.containsKey(normalizedSupport)) {
                listOf(normalizedSupport)
            } else {
                listOf("1E")
            }
            else -> programList[normalizedSupport] ?: programList.getValue("1C")
        }.toMutableList().apply {
            if (cleanArea == "TH0307" && !contains("E")) add("E")
        }

        val maxHexCode = codes.maxByOrNull { it.toIntOrNull(16) ?: 0 }
        return baseOptions
            .filter { it.code in codes }
            .map { opt ->
                val customName = when {
                    opt.code == maxHexCode -> "Auto"
                    opt.code == "C" && cleanArea == "TH0307" -> "5G NSA Only/4G"
                    else -> opt.label
                }
                opt.copy(label = customName)
            }
    }

    fun labelFor(code: String, options: List<NetworkModeOption>): String {
        val clean = code.trim().uppercase(Locale.US)
        if (clean.isEmpty()) return "--"
        return options.firstOrNull { it.code.equals(clean, ignoreCase = true) }?.label
            ?: baseOptions.firstOrNull { it.code.equals(clean, ignoreCase = true) }?.label
            ?: clean
    }

    private fun normalizeSupportHex(aeraId: String, rawDisplayMode: String): String {
        val upper = rawDisplayMode.trim().uppercase(Locale.US).ifEmpty { "1C" }
        if (upper.length <= 1 || aeraId in setOf("TZ0355", "NG0002", "CN0593")) {
            return upper
        }
        val num = upper.toIntOrNull(16) ?: return upper
        val binary = num.toString(2).padStart(upper.length * 4, '0').toCharArray()
        if (binary.size > 2) {
            binary[1] = '0'
            binary[2] = '0'
        }
        return String(binary).toIntOrNull(2)?.toString(16)?.uppercase(Locale.US) ?: upper
    }
}

@Immutable
data class MobileAdvancedConfig(
    val networkMode: String = "",
    val displayMode: String = "",
    val roamingEnabled: Boolean = false,
    val lteCaEnabled: Boolean = true,
    val nrCaEnabled: Boolean = true,
    val availableModes: List<NetworkModeOption> = emptyList(),
    val plmnLocked: Boolean = false
)

@Immutable
data class ApnProfile(
    val name: String,
    val apnName: String,
    val selectAuthentication: String = "0",
    val username: String = "",
    val password: String = "",
    val isDefault: Boolean = false,
    val isEditable: Boolean = true
)

@Immutable
data class ApnConfigSnapshot(
    val selectType: String = "IPV4V6",
    val mtu: Int = 1500,
    val natEnabled: Boolean = true,
    val profiles: List<ApnProfile> = emptyList()
)

@Immutable
data class TrafficLimitSettings(
    val limitEnabled: Boolean = false,
    val limitSize: String = "",
    val unitCode: String = "1",
    val usesWordUnit: Boolean = false,
    val startDate: Int = 1,
    val dlMonthMb: Double = 0.0,
    val ulMonthMb: Double = 0.0
) {
    val totalUsedMb: Double get() = dlMonthMb + ulMonthMb
}

@Immutable
data class UssdSessionResult(
    val text: String = "",
    val needReply: Boolean = false,
    val cancelled: Boolean = false
)

@Immutable
data class StaticDhcpLease(
    val ip: String,
    val mac: String
)

@Immutable
data class MacFilterRule(
    val mac: String,
    val remark: String = "",
    val ippro: String = "IPV4",
    val enableRule: Boolean = true,
    val enableLink: Boolean = false
)

@Immutable
data class MacFilterSnapshot(
    val rules: List<MacFilterRule> = emptyList(),
    val acceptAllIpv4: Boolean = true,
    val acceptAllIpv6: Boolean = true
)

@Immutable
data class ParentalSpeedRule(
    val ip: String,
    val maxSpeedKbps: Int,
    val ippro: String = "IPV4",
    val enableRule: Boolean = true
)

@Immutable
data class ParentalUrlRule(
    val mac: String,
    val url: String,
    val enableRule: Boolean = true,
    val enableLink: Boolean = false,
    val ippro: String = "IPV4V6"
)

@Immutable
data class ParentalTimeRule(
    val mac: String,
    val startTime: String,
    val endTime: String,
    val scheduleDays: String = "1,2,3,4,5,6,0",
    val enableRule: Boolean = true
)

@Immutable
data class ParentalControlSnapshot(
    val enabled: Boolean = false,
    val speedRules: List<ParentalSpeedRule> = emptyList(),
    val urlRules: List<ParentalUrlRule> = emptyList(),
    val timeRules: List<ParentalTimeRule> = emptyList()
)

@Immutable
data class GuestWifiBandConfig(
    val wifiOpen: Boolean = false,
    val visibleBroadcast: Boolean = true,
    val ssid: String = "",
    val key: String = "",
    val authenticationType: String = "2",
    val mainWifiOpen: Boolean = true,
    val forbiddenSameSsids: List<String> = emptyList()
)

@Immutable
data class GuestWifiSnapshot(
    val band24g: GuestWifiBandConfig = GuestWifiBandConfig(),
    val band5g: GuestWifiBandConfig = GuestWifiBandConfig()
)

@Immutable
data class WifiAdvancedBandConfig(
    val wifiOpen: Boolean = true,
    val clientStaReadOnly: Boolean = false,
    val txPower: String = "100",
    val countryCode: String = "",
    val channel: String = "auto",
    val wifiWorkMode: String = "",
    val bandWidth: String = "0",
    val maxNum: Int = 32,
    val maxNumLimit: Int = 32,
    val connectNum: Int = 0,
    val eliminateNum: Int = 0,
    val wifiWmm: Boolean = true,
    val performance: Boolean = false,
    val dfsSwitch: Boolean = false
)

@Immutable
data class WifiPower411Config(
    val txPower24g: String = "50",
    val txPower5g: String = "50"
)

@Immutable
data class WpsBandStatus(
    val enabled: Boolean = false,
    val wifiOpen: Boolean = true,
    val authType: String = "2",
    val visibleBroadcast: Boolean = true
) {
    val eligibleForWps: Boolean get() = wifiOpen && visibleBroadcast && authType != "4"
}

@Immutable
data class WpsSnapshot(
    val band24g: WpsBandStatus = WpsBandStatus(),
    val band5g: WpsBandStatus = WpsBandStatus()
)

@Immutable
data class WifiScheduleConfig(
    val enabled: Boolean = false,
    val onHour: Int = 8,
    val onMinute: Int = 0,
    val offHour: Int = 23,
    val offMinute: Int = 0
)

@Immutable
data class DmzConfig(
    val enabled: Boolean = false,
    val ip: String = ""
)

@Immutable
data class DdosConfig(
    val enabled: Boolean = false
)

@Immutable
data class UpnpMapping(
    val protocol: String = "",
    val port: String = "",
    val mappingIp: String = "",
    val mappingPort: String = ""
)

@Immutable
data class UpnpStatus(
    val enabled: Boolean = false,
    val mappings: List<UpnpMapping> = emptyList()
)

@Immutable
data class PortForwardRule(
    val port: String,
    val mappingPort: String,
    val mappingIp: String,
    val protocol: String = "TCP",
    val remark: String = "",
    val enableRule: Boolean = true,
    val ifName: String = "DEFAULT"
)

@Immutable
data class IpPortFilterRule(
    val ip: String = "",
    val destIp: String = "",
    val srcPort: String = "",
    val destPort: String = "",
    val protocol: String = "TCP",
    val ippro: String = "IPV4",
    val remark: String = "",
    val enableRule: Boolean = true,
    val enableLink: Boolean = false
)

@Immutable
data class PortFilterRule(
    val port: String,
    val protocol: String = "tcp",
    val ippro: String = "IPV4",
    val remark: String = "",
    val enableRule: Boolean = true,
    val enableLink: Boolean = false
)

@Immutable
data class UrlFilterRule(
    val url: String,
    val ippro: String = "IPV4",
    val remark: String = "",
    val enableRule: Boolean = true,
    val enableLink: Boolean = false
)

@Immutable
data class TrustedIpConfig(
    val enabled: Boolean = false,
    val ips: List<String> = emptyList()
)

@Immutable
data class RouterTimeConfig(
    val systemTime: String = "",
    val timezone: String = "UTC-8",
    val ntpEnabled: Boolean = true,
    val server1: String = "",
    val server2: String = "",
    val server3: String = "",
    val server4: String = ""
)

@Immutable
data class WanLinkDetectConfig(
    val enabled: Boolean = false,
    val isMixMode: Boolean = false,
    val networkMode: String = "",
    val checkTimeSec: Int = 10,
    val checkMode: Int = 0,
    val ip1: String = "",
    val ip2: String = "",
    val ip3: String = "",
    val action: String = "0",
    val rebootWaitMinutes: Int = 3,
    val rescanNetworkSec: Int = 10,
    val ipv4DnsSw: Boolean = false,
    val ipv4Dns1: String = "",
    val ipv4Dns2: String = "",
    val ipv4Dns3: String = "",
    val ipv6DnsSw: Boolean = false,
    val ipv6Dns1: String = "",
    val ipv6Dns2: String = "",
    val ipv6Dns3: String = ""
)
