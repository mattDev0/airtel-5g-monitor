package com.airtel.monitor.data.dto

import com.airtel.monitor.core.network.AliasLookup
import com.airtel.monitor.core.util.BandMask
import com.airtel.monitor.data.model.BandLockConfig
import com.airtel.monitor.data.model.CellLockConfig
import com.airtel.monitor.data.model.CellularMetrics
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DeviceType
import com.airtel.monitor.data.model.DnsConfig
import com.airtel.monitor.data.model.HardwareMetrics
import com.airtel.monitor.data.model.LockState
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
import com.airtel.monitor.data.model.WifiRadioConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class WanByteCounters(
    val rxBytes: Long,
    val txBytes: Long
)

data class NetworkModeSnapshot(
    val mobileDataOn: Boolean?,
    val flightModeOn: Boolean?,
    val networkMode: String = "",
    val mode5g: String = "",
    val roamingEnable: Boolean = false,
    val lteCa: Boolean = true,
    val nrCa: Boolean = true
)

fun parseWanBytes(json: JSONObject): WanByteCounters? {
    val rx = json.optString("rxBytes", "").toLongOrNull() ?: json.optLong("rxBytes", -1)
    val tx = json.optString("txBytes", "").toLongOrNull() ?: json.optLong("txBytes", -1)
    if (rx <= 0 && tx <= 0) return null
    return WanByteCounters(rxBytes = rx, txBytes = tx)
}

fun parseLatestChartRow(statusChart1018: JSONObject): JSONObject {
    val devInfo = statusChart1018.optJSONArray("device_info") ?: return JSONObject()
    if (devInfo.length() == 0) return JSONObject()
    return devInfo.optJSONObject(devInfo.length() - 1) ?: JSONObject()
}

fun parseHardwareMetrics(
    latestChart: JSONObject,
    deviceInfo207: JSONObject = JSONObject()
): HardwareMetrics {
    val cpuFromChart = latestChart.optDouble("cpu_usage", Double.NaN)
    val cpuFrom207 = deviceInfo207.optString("cpu_usage", "").toDoubleOrNull()
        ?: deviceInfo207.optDouble("cpu_usage", Double.NaN)
    val cpu = when {
        !cpuFromChart.isNaN() -> cpuFromChart
        !cpuFrom207.isNaN() -> cpuFrom207
        else -> 0.0
    }
    val temp = latestChart.optString("temperature", "").trim()
        .ifEmpty { deviceInfo207.optString("device_temperature", "").trim() }
        .ifEmpty { "--" }
    val mem = latestChart.optString("memory", "").trim()
        .ifEmpty { deviceInfo207.optString("memory", "").trim() }
        .ifEmpty { "--" }
    return HardwareMetrics(cpuUsage = cpu, temperature = temp, memory = mem)
}

fun parseNetworkMode218(json: JSONObject): NetworkModeSnapshot = NetworkModeSnapshot(
    mobileDataOn = json.optString("dialMode").takeIf { it.isNotEmpty() }?.let { it == "1" },
    flightModeOn = json.optString("flightMode").takeIf { it.isNotEmpty() }?.let { it == "1" },
    networkMode = json.optString("networkMode", ""),
    mode5g = json.optString("mode5g", ""),
    roamingEnable = json.optString("roamingEnable") == "1",
    lteCa = json.optString("lteCA", "1") == "1",
    nrCa = json.optString("nrCA", "1") == "1"
)

fun parseDnsConfig(json: JSONObject): DnsConfig = DnsConfig(
    primary = json.optString("main_dns", ""),
    secondary = json.optString("vice_dns", ""),
    dhcpEnabled = json.optString("dhcpServer") == "1"
)

fun parseBandLock(json: JSONObject): BandLockConfig = BandLockConfig(
    lock4gEnabled = json.optString("band_4g_switch") == "1",
    supported4g = BandMask.decode(json.optString("all_band_4g")).sorted(),
    locked4g = BandMask.decode(json.optString("band_4g_mask")),
    lock5gEnabled = json.optString("band_5g_switch") == "1",
    supported5g = BandMask.decode(json.optString("all_band_5g")).sorted(),
    locked5g = BandMask.decode(json.optString("band_5g_mask"))
)

fun parseCellLock(json: JSONObject): CellLockConfig {
    fun list(key: String) = json.optString(key).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun state(flag: String) = when (json.optString(flag)) {
        "1" -> LockState.LOCKED
        "-1" -> LockState.FAILED
        else -> LockState.UNLOCKED
    }

    val lteFreqs = list("lte_lock_freq")
    val ltePcis = json.optString("lte_lock_pci").split(',')
    val nrFreqs = list("nr_lock_freq")
    val nrPcis = json.optString("nr_lock_pci").split(',')
    val nrBands = json.optString("nr_lock_cell_band").split(',')

    return CellLockConfig(
        lteEnabled = json.optString("lte_lock_sw") == "1",
        lteCells = lteFreqs.mapIndexed { i, f -> LteLockCell(f, ltePcis.getOrElse(i) { "" }.trim()) },
        lteState = state("lock_4g_flag"),
        nrEnabled = json.optString("nr_lock_sw") == "1",
        nrCells = nrFreqs.mapIndexed { i, f ->
            NrLockCell(nrBands.getOrElse(i) { "" }.trim(), f, nrPcis.getOrElse(i) { "" }.trim())
        },
        nrState = state("lock_5g_flag"),
        current4g = json.optString("FREQ").takeIf { it.isNotBlank() }?.let { LteLockCell(it, json.optString("PCI")) },
        current5g = json.optString("FREQ_5G").takeIf { it.isNotBlank() }
            ?.let { NrLockCell(json.optString("current_band_5g"), it, json.optString("PCI_5G")) }
    )
}

fun parseWifiRadioConfig(res24: JSONObject, res5: JSONObject): WifiRadioConfig = WifiRadioConfig(
    enabled24g = res24.optString("wifiOpen", "1") == "1",
    ssid24g = decodeBase64Utf8(res24.optString("ssid", "")),
    channel24g = res24.optString("wifi24Channel", ""),
    enabled5g = res5.optString("wifiOpen", "1") == "1",
    ssid5g = decodeBase64Utf8(res5.optString("ssid", "")),
    channel5g = res5.optString("wifi5Channel", "")
)

/**
 * Maps router `signal_lvl` (0..5 scale used by `js/main.js` StatusUtil.formatSingalLevel)
 * onto a 0..5 bar count, preferring cmd 205 / 394 over older status fields.
 */
fun parseSignalBarsLevel(
    net205: JSONObject = JSONObject(),
    statusBar394: JSONObject = JSONObject(),
    dash: JSONObject = JSONObject(),
    sysStatus: JSONObject = JSONObject(),
    networkType: String = ""
): Int {
    val rawLvl = listOf(net205, statusBar394, dash, sysStatus).firstNotNullOfOrNull { src ->
        val raw = src.optString("signal_lvl", "").trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) null else raw.toIntOrNull()
    }
    val registered = networkType.isNotEmpty() && networkType != "-"
    return when {
        rawLvl == null || rawLvl < 0 || rawLvl > 5 -> if (registered) 1 else 0
        rawLvl == 0 -> if (registered) 1 else 0
        else -> rawLvl.coerceIn(0, 5)
    }
}

fun parseCellularMetrics(
    sysStatus: JSONObject,
    dash: JSONObject,
    net205: JSONObject = JSONObject(),
    latestChart: JSONObject = JSONObject(),
    routerInfo133: JSONObject = JSONObject(),
    statusBar394: JSONObject = JSONObject()
): CellularMetrics {
    fun pick(vararg values: String): String =
        values.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals("null", ignoreCase = true) && it != "-" } ?: "-"

    val netType = pick(
        net205.optString("network_type_str"),
        statusBar394.optString("network_type_str"),
        sysStatus.optString("network_type_str"),
        dash.optString("network_type_str"),
        routerInfo133.optString("network_type_str")
    )
    return CellularMetrics(
        networkType = netType,
        signalLvl = parseSignalBarsLevel(
            net205 = net205,
            statusBar394 = statusBar394,
            dash = dash,
            sysStatus = sysStatus,
            networkType = netType
        ),
        rsrp5g = pick(net205.optString("RSRP_5G"), routerInfo133.optString("RSRP_5G"), latestChart.optString("rsrp_5g"), dash.optString("RSRP_5G")),
        sinr5g = pick(net205.optString("SINR_5G"), routerInfo133.optString("SINR_5G"), latestChart.optString("sinr_5g")),
        rsrp4g = pick(net205.optString("RSRP"), routerInfo133.optString("RSRP"), latestChart.optString("rsrp_4g"), dash.optString("RSRP")),
        sinr4g = pick(net205.optString("SINR"), routerInfo133.optString("SINR"), latestChart.optString("sinr_4g")),
        operator = pick(net205.optString("network_operator"), dash.optString("network_operator"), "Airtel"),
        boardType = sysStatus.optString("board_type", "").ifEmpty { "ZLT X17M" },
        iduType = sysStatus.optString("idu_dev_type", "").ifEmpty { "ZLT W304VA PRO" },
        nrCqi = pick(net205.optString("CQI_5G"), latestChart.optString("nr_cqi")),
        lteCqi = pick(net205.optString("CQI"), routerInfo133.optString("CQI"), latestChart.optString("lte_cqi")),
        nrQamDl = pick(net205.optString("max_dl_qam_5g"), latestChart.optString("nr_qam_dl")),
        rsrq5g = pick(net205.optString("RSRQ_5G"), routerInfo133.optString("RSRQ_5G")),
        rssi5g = pick(net205.optString("RSSI_5G"), routerInfo133.optString("RSSI_5G")),
        rsrq4g = pick(net205.optString("RSRQ"), routerInfo133.optString("RSRQ")),
        rssi4g = pick(net205.optString("RSSI"), routerInfo133.optString("RSSI"))
    )
}

fun parseDevices(
    dhcp: JSONObject,
    dash: JSONObject,
    wifi5g: JSONObject,
    wifi24g: JSONObject,
    aliasLookup: AliasLookup
): List<DeviceItem> {
    val dhcpArray = dhcp.optJSONArray("dhcp_list_info")
        ?: dash.optJSONArray("dhcp_list_info")
        ?: JSONArray()

    val wifiMap = mutableMapOf<String, JSONObject>()

    val w5g = wifi5g.optJSONArray("wlan5g_wifi_info") ?: JSONArray()
    for (i in 0 until w5g.length()) {
        val item = w5g.optJSONObject(i) ?: continue
        val copy = JSONObject(item.toString()).put("band", "5 GHz (Wi-Fi 6/ac)")
        val mac = copy.optString("mac", "").lowercase().trim()
        val ip = copy.optString("ip", "").trim()
        if (mac.isNotEmpty()) wifiMap[mac] = copy
        if (ip.isNotEmpty()) wifiMap[ip] = copy
    }

    val w24g = wifi24g.optJSONArray("wlan24g_wifi_info") ?: JSONArray()
    for (i in 0 until w24g.length()) {
        val item = w24g.optJSONObject(i) ?: continue
        val copy = JSONObject(item.toString()).put("band", "2.4 GHz (Wi-Fi)")
        val mac = copy.optString("mac", "").lowercase().trim()
        val ip = copy.optString("ip", "").trim()
        if (mac.isNotEmpty()) wifiMap[mac] = copy
        if (ip.isNotEmpty()) wifiMap[ip] = copy
    }

    val devices = mutableListOf<DeviceItem>()
    for (i in 0 until dhcpArray.length()) {
        val d = dhcpArray.optJSONObject(i) ?: continue
        val mac = d.optString("mac", "").lowercase().trim()
        val ip = d.optString("ip", "").trim()
        val rawHost = d.optString("hostname", "").trim()
        val hostname = if (rawHost.isEmpty() || rawHost.equals("null", ignoreCase = true)) {
            "Unknown Device"
        } else {
            rawHost
        }

        val wInfo = wifiMap[mac] ?: wifiMap[ip]
        val band = wInfo?.optString("band")
            ?: if (d.optString("interface") != "wlan") "Ethernet (LAN)" else "Wi-Fi"
        val rssi = wInfo?.optString("rssi", "") ?: ""
        val txRate = wInfo?.optString("txrate", "") ?: ""
        val rxRate = wInfo?.optString("rxrate", "") ?: ""
        val ssid = wInfo?.optString("ssid", "") ?: ""

        var signalPct = 0
        if (rssi.isNotEmpty()) {
            val rVal = rssi.toDoubleOrNull()
            if (rVal != null) {
                signalPct = max(5, min(100, ((rVal + 100) * 1.42).toInt()))
            }
        } else if (band.contains("LAN") || band.contains("Ethernet")) {
            signalPct = 100
        }

        val customAlias = aliasLookup.getAlias(mac, aliasLookup.getAlias(ip, hostname))
        val devType = inferDeviceType("$hostname $customAlias")
        val isRouter = ip in listOf("192.168.1.1", "192.168.1.2") || hostname.lowercase().contains("zlt")

        devices.add(
            DeviceItem(
                mac = mac,
                ip = ip,
                hostname = hostname,
                alias = customAlias,
                type = devType,
                band = band,
                ssid = ssid,
                rssi = rssi,
                signalPercent = signalPct,
                txRateMbps = txRate.ifEmpty { "-" },
                rxRateMbps = rxRate.ifEmpty { "-" },
                interfaceName = d.optString("interface", "wlan"),
                expires = d.optString("expires", ""),
                ipv6 = d.optString("ipv6", ""),
                isRouter = isRouter
            )
        )
    }
    return devices
}

fun parseSmsUnreadCount(statusBar394: JSONObject): Int {
    val raw = statusBar394.optString("sms_unread", "").trim()
    return raw.toIntOrNull() ?: statusBar394.optInt("sms_unread", 0)
}

fun inferDeviceType(name: String): DeviceType {
    val h = name.lowercase()
    val phoneKeywords = listOf("pixel", "iphone", "galaxy", "android", "redmi", "xiaomi", "oppo", "vivo", "realme", "oneplus", "huawei", "honor", "tecno", "infinix")
    if (phoneKeywords.any { h.contains(it) }) return DeviceType.PHONE

    val tabKeywords = listOf("ipad", "tab", "tablet")
    if (tabKeywords.any { h.contains(it) }) return DeviceType.TABLET

    val pcKeywords = listOf("pc", "laptop", "desktop", "macbook", "thinkpad", "dell", "hp", "lenovo", "asus", "acer", "msi", "surface")
    if (pcKeywords.any { h.contains(it) }) return DeviceType.PC

    val tvKeywords = listOf("tv", "appletv", "roku", "firetv", "bravia", "chromecast", "webos", "tizen", "smarttv")
    if (tvKeywords.any { h.contains(it) }) return DeviceType.TV

    val gameKeywords = listOf("playstation", "ps4", "ps5", "xbox", "nintendo", "switch")
    if (gameKeywords.any { h.contains(it) }) return DeviceType.GAMING

    val routerKeywords = listOf("zlt", "router", "ap", "repeater", "extender", "w304")
    if (routerKeywords.any { h.contains(it) }) return DeviceType.ROUTER

    return DeviceType.DEVICE
}

/** Pure Kotlin RFC 4648 Base64 decoder so DTO parsing works identically on Android API 24+ and JVM tests. */
fun decodeBase64Utf8(raw: String): String {
    val clean = raw.filterNot { it.isWhitespace() }
    if (clean.isEmpty()) return ""
    return try {
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return raw
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        String(out.toByteArray(), Charsets.UTF_8)
    } catch (_: Exception) {
        raw
    }
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Pure Kotlin RFC 4648 Base64 encoder for API 24+ and JVM unit tests. */
fun encodeBase64Utf8(raw: String): String {
    val bytes = raw.toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty()) return ""
    val out = StringBuilder(((bytes.size + 2) / 3) * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val hasB1 = i + 1 < bytes.size
        val hasB2 = i + 2 < bytes.size
        val b1 = if (hasB1) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (hasB2) bytes[i + 2].toInt() and 0xFF else 0

        out.append(BASE64_ALPHABET[b0 ushr 2])
        out.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
        out.append(if (hasB1) BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
        out.append(if (hasB2) BASE64_ALPHABET[b2 and 0x3F] else '=')
        i += 3
    }
    return out.toString()
}
