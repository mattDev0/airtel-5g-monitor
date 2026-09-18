package com.airtel.monitor.data.remote

import com.airtel.monitor.data.model.*
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Connection settings the client reads on every request; implemented by PreferencesRepository. */
interface RouterSettings {
    val routerHost: String
    val username: String
    val password: String
}

/** Device-name overrides; implemented by AliasRepository. */
fun interface AliasLookup {
    fun getAlias(identifier: String, fallback: String): String
}

/** Posts one JSON request to the router CGI and returns the raw reply body. */
fun interface RouterTransport {
    fun post(url: String, body: String, timeoutSeconds: Long): String
}

class OkHttpRouterTransport : RouterTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        // Off: OkHttp would silently resend a POST (reboot, DNS, Wi-Fi) after a
        // connection failure, and the router may already have acted on it.
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMediaType = "application/json; charset=UTF-8".toMediaType()

    override fun post(url: String, body: String, timeoutSeconds: Long): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ZLT-Monitor/2.0")
            .build()
        val call = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
            .newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                return JSONObject().put("error", "HTTP ${response.code}").toString()
            }
            return response.body?.string() ?: "{}"
        }
    }
}

class ZltRouterClient(
    private val preferencesRepository: RouterSettings,
    private val aliasRepository: AliasLookup,
    private val transport: RouterTransport = OkHttpRouterTransport(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var sessionId: String? = null
    private val lock = Any()

    // WAN throughput calculation state
    private var lastPollTimeMs: Long = 0
    private var prevWanRxBytes: Long? = null
    private var prevWanTxBytes: Long? = null
    private var prevWanTimeMs: Long = 0

    // 60-second rolling history
    private val history = Collections.synchronizedList(mutableListOf<BandwidthPoint>())
    private val maxHistory = 60

    // Slow-polled data cache (heavy cmd 1018 ~210KB, cmd 160, cmd 161)
    private var lastSlowPollTimeMs: Long = 0
    private val slowIntervalMs = 20_000L
    private var cachedLatestChart: JSONObject = JSONObject()
    private var cachedCell160: JSONObject = JSONObject()
    private var cachedBand161: JSONObject = JSONObject()

    var peakDlMbps: Double = 0.0
        private set
    var peakUlMbps: Double = 0.0
        private set

    private val url: String
        get() = "http://${preferencesRepository.routerHost}/cgi-bin/http.cgi"

    fun resetState() {
        synchronized(lock) {
            sessionId = null
            lastPollTimeMs = 0
            prevWanRxBytes = null
            prevWanTxBytes = null
            prevWanTimeMs = 0
            lastSlowPollTimeMs = 0
            history.clear()
            peakDlMbps = 0.0
            peakUlMbps = 0.0
        }
    }

    private fun sendCmd(payload: JSONObject, timeoutSeconds: Long = 4): JSONObject {
        val reply = transport.post(url, payload.toString(), timeoutSeconds)
        return try {
            JSONObject(reply)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun login(): Boolean {
        synchronized(lock) {
            try {
                // 1. Fetch challenge token via GET_NEXT_LOGIN_TIME (cmd 232)
                val tPayload = JSONObject().apply {
                    put("cmd", 232)
                    put("method", "GET")
                    put("sessionId", "")
                }
                val tResp = sendCmd(tPayload)
                val token = tResp.optString("token", "")
                if (token.isEmpty()) return false

                // 2. Compute SHA256(token + password)
                val pwdHash = sha256(token + preferencesRepository.password)
                val newSessionId = UUID.randomUUID().toString().replace("-", "") +
                        UUID.randomUUID().toString().replace("-", "")

                // 3. Post authentication (cmd 100)
                val lPayload = JSONObject().apply {
                    put("cmd", 100)
                    put("method", "POST")
                    put("sessionId", newSessionId)
                    put("username", preferencesRepository.username)
                    put("passwd", pwdHash)
                    put("isAutoUpgrade", "0")
                    put("subcmd", 0)
                }
                val lResp = sendCmd(lPayload)

                val success = lResp.optBoolean("success") ||
                        lResp.optString("message") == "success" ||
                        lResp.has("sessionId")

                if (success) {
                    val sid = lResp.optString("sessionId")
                    sessionId = if (sid.isNotEmpty()) sid else newSessionId
                    return true
                }
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }
    }

    fun query(
        cmdId: Int,
        method: String = "GET",
        extra: Map<String, Any> = emptyMap(),
        timeoutSeconds: Long = 4
    ): JSONObject {
        synchronized(lock) {
            if (sessionId == null) {
                if (!login()) return JSONObject()
            }
            val payload = JSONObject().apply {
                put("cmd", cmdId)
                put("method", method)
                put("sessionId", sessionId ?: "")
                extra.forEach { (k, v) -> put(k, v) }
            }
            val isWrite = method == "POST"
            try {
                var res = try {
                    sendCmd(payload, timeoutSeconds)
                } catch (e: java.io.IOException) {
                    // A read is safe to repeat once (e.g. a stale pooled connection).
                    // A write is not: the router may have acted on it before the failure.
                    if (isWrite) throw e
                    sendCmd(payload, timeoutSeconds)
                }
                val msg = res.optString("message", "")
                val sessionExpired = msg in listOf("NO_AUTH", "LOGIN_TIMEOUT")
                // Writes are only repeated when the router refused them for an expired
                // session, i.e. they were never applied. Reads keep the broader retry.
                if (sessionExpired || (!isWrite && !res.optBoolean("success", true))) {
                    if (login()) {
                        payload.put("sessionId", sessionId ?: "")
                        if (payload.has("token")) {
                            // The token belonged to the old session.
                            payload.put("token", query(233).optString("token", ""))
                        }
                        res = sendCmd(payload, timeoutSeconds)
                    }
                }
                return res
            } catch (e: Exception) {
                return JSONObject()
            }
        }
    }

    private fun readWanBytes(): Pair<Long, Long>? {
        val res = query(18)
        val rx = res.optString("rxBytes", "").toLongOrNull() ?: res.optLong("rxBytes", -1)
        val tx = res.optString("txBytes", "").toLongOrNull() ?: res.optLong("txBytes", -1)
        if (rx <= 0 && tx <= 0) return null
        return Pair(rx, tx)
    }

    private fun computeWanSpeed(nowMs: Long): Pair<Double, Double> {
        val counters = readWanBytes() ?: return Pair(0.0, 0.0)
        val (rx, tx) = counters
        val prevRx = prevWanRxBytes
        val prevTx = prevWanTxBytes
        val prevTime = prevWanTimeMs

        prevWanRxBytes = rx
        prevWanTxBytes = tx
        prevWanTimeMs = nowMs

        if (prevRx == null || prevTx == null || prevTime <= 0) {
            return Pair(0.0, 0.0)
        }

        val dt = (nowMs - prevTime) / 1000.0
        if (dt <= 0.05) return Pair(0.0, 0.0)

        val dRx = rx - prevRx
        val dTx = tx - prevTx

        // Router reset or counter overflow
        if (dRx < 0 || dTx < 0) {
            return Pair(0.0, 0.0)
        }

        val rxKbps = (dRx / dt) / 1024.0
        val txKbps = (dTx / dt) / 1024.0
        return Pair(roundTo2(rxKbps), roundTo2(txKbps))
    }

    private fun refreshSlowData(nowMs: Long, force: Boolean = false) {
        if (!force && (nowMs - lastSlowPollTimeMs) < slowIntervalMs) {
            return
        }
        lastSlowPollTimeMs = nowMs

        // cmd 1018: hardware stats & RF metrics (~210KB history blob)
        val statusChart = query(1018)
        val devInfo = statusChart.optJSONArray("device_info")
        if (devInfo != null && devInfo.length() > 0) {
            cachedLatestChart = devInfo.getJSONObject(devInfo.length() - 1)
        }

        // cmd 160: cellular cell lock status
        val cell = query(160)
        if (cell.optBoolean("success", true) && cell.length() > 1) {
            cachedCell160 = cell
        }

        // cmd 161: band lock switches
        val band = query(161)
        if (band.optBoolean("success", true) && band.length() > 1) {
            cachedBand161 = band
        }
    }

    fun pollMetrics(): Result<RouterState> {
        val nowMs = clock()

        // 1. Ensure authenticated session
        synchronized(lock) {
            if (sessionId == null) {
                if (!login()) {
                    return Result.failure(java.io.IOException("Cannot authenticate with router at ${preferencesRepository.routerHost}"))
                }
            }
        }

        // 2. Query primary status endpoints
        var dash = query(401)
        var sysStatus = query(113)

        // If core queries return empty / error, session might have expired or connection dropped
        if (dash.length() == 0 && sysStatus.length() == 0) {
            synchronized(lock) {
                sessionId = null
                if (!login()) {
                    return Result.failure(java.io.IOException("Router unreachable or session lost"))
                }
            }
            dash = query(401)
            sysStatus = query(113)
            if (dash.length() == 0 && sysStatus.length() == 0) {
                return Result.failure(java.io.IOException("Router did not return valid status response"))
            }
        }

        // Verify that we received real authenticated response payload
        val hasValidResponse = (dash.length() > 0 && !dash.has("error")) ||
                (sysStatus.length() > 0 && !sysStatus.has("error"))
        if (!hasValidResponse) {
            return Result.failure(java.io.IOException("Router returned error or empty response"))
        }

        val dt = if (lastPollTimeMs > 0) (nowMs - lastPollTimeMs) / 1000.0 else 1.0
        val dhcp = query(223)
        val wifi5g = query(225)
        val wifi24g = query(224)

        // 3. Slow queries cached every 20s (force on first poll)
        refreshSlowData(nowMs, force = (lastSlowPollTimeMs == 0L))
        val latestChart = cachedLatestChart

        // 4. DHCP device list
        val dhcpArray = dhcp.optJSONArray("dhcp_list_info")
            ?: dash.optJSONArray("dhcp_list_info")
            ?: JSONArray()

        // 5. Wi-Fi mapping
        val wifiMap = mutableMapOf<String, JSONObject>()

        val w5g = wifi5g.optJSONArray("wlan5g_wifi_info") ?: JSONArray()
        for (i in 0 until w5g.length()) {
            val item = w5g.optJSONObject(i) ?: continue
            val mac = item.optString("mac", "").lowercase().trim()
            val ip = item.optString("ip", "").trim()
            item.put("band", "5 GHz (Wi-Fi 6/ac)")
            if (mac.isNotEmpty()) wifiMap[mac] = item
            if (ip.isNotEmpty()) wifiMap[ip] = item
        }

        val w24g = wifi24g.optJSONArray("wlan24g_wifi_info") ?: JSONArray()
        for (i in 0 until w24g.length()) {
            val item = w24g.optJSONObject(i) ?: continue
            val mac = item.optString("mac", "").lowercase().trim()
            val ip = item.optString("ip", "").trim()
            item.put("band", "2.4 GHz (Wi-Fi)")
            if (mac.isNotEmpty()) wifiMap[mac] = item
            if (ip.isNotEmpty()) wifiMap[ip] = item
        }

        // 6. WAN Speeds
        val (rxKbps, txKbps) = computeWanSpeed(nowMs)
        val dlMbps = roundTo2((rxKbps * 8.0) / 1024.0)
        val ulMbps = roundTo2((txKbps * 8.0) / 1024.0)

        if (dlMbps > peakDlMbps) peakDlMbps = dlMbps
        if (ulMbps > peakUlMbps) peakUlMbps = ulMbps

        // 7. Parse Devices
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
            val band = wInfo?.optString("band") ?: if (d.optString("interface") != "wlan") "Ethernet (LAN)" else "Wi-Fi"
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

            val customAlias = aliasRepository.getAlias(mac, aliasRepository.getAlias(ip, hostname))
            val devType = inferDeviceType("$hostname $customAlias", mac, band)

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
                    txRateMbps = if (txRate.isNotEmpty()) txRate else "-",
                    rxRateMbps = if (rxRate.isNotEmpty()) rxRate else "-",
                    interfaceName = d.optString("interface", "wlan"),
                    expires = d.optString("expires", ""),
                    ipv6 = d.optString("ipv6", ""),
                    isRouter = isRouter
                )
            )
        }

        // 8. Cellular Metrics
        val netType = sysStatus.optString("network_type_str", dash.optString("network_type_str", "")).trim()
        val cellular = CellularMetrics(
            networkType = if (netType.isNotEmpty() && netType != "null") netType else "-",
            signalLvl = sysStatus.optInt("signal_lvl", dash.optInt("signal_lvl", 0)),
            rsrp5g = latestChart.optString("rsrp_5g", dash.optString("RSRP_5G", "-")).ifEmpty { "-" },
            sinr5g = latestChart.optString("sinr_5g", "-").ifEmpty { "-" },
            rsrp4g = latestChart.optString("rsrp_4g", dash.optString("RSRP", "-")).ifEmpty { "-" },
            sinr4g = latestChart.optString("sinr_4g", "-").ifEmpty { "-" },
            operator = dash.optString("network_operator", "").ifEmpty { "Airtel" },
            boardType = sysStatus.optString("board_type", "").ifEmpty { "ZLT X17M" },
            iduType = sysStatus.optString("idu_dev_type", "").ifEmpty { "ZLT W304VA PRO" },
            nrCqi = latestChart.optString("nr_cqi", "-").ifEmpty { "-" },
            lteCqi = latestChart.optString("lte_cqi", "-").ifEmpty { "-" },
            nrQamDl = latestChart.optString("nr_qam_dl", "-").ifEmpty { "-" }
        )

        // 9. Cell Lock Info
        val cell160 = cachedCell160
        val band161 = cachedBand161

        fun tf(key: String, json: JSONObject): Boolean {
            val v = json.optString(key, "")
            return v in listOf("1", "true", "True")
        }

        val cellLock = CellLockInfo(
            servingPci4g = cell160.optString("PCI", "-").ifEmpty { "-" },
            servingFreq4g = cell160.optString("FREQ", "-").ifEmpty { "-" },
            servingPci5g = cell160.optString("PCI_5G", "-").ifEmpty { "-" },
            servingFreq5g = cell160.optString("FREQ_5G", "-").ifEmpty { "-" },
            currentBands4g = cell160.optString("current_band", "-").ifEmpty { "-" },
            currentBand5g = cell160.optString("current_band_5g", "-").ifEmpty { "-" },
            lteLockEnabled = tf("lte_lock_sw", cell160),
            lteLockPci = cell160.optString("lte_lock_pci", "-").ifEmpty { "-" },
            lteLockFreq = cell160.optString("lte_lock_freq", "-").ifEmpty { "-" },
            nrLockEnabled = tf("nr_lock_sw", cell160),
            nrLockPci = cell160.optString("nr_lock_pci", "-").ifEmpty { "-" },
            nrLockFreq = cell160.optString("nr_lock_freq", "-").ifEmpty { "-" },
            bandLock4gEnabled = tf("band_4g_switch", band161),
            bandLock5gEnabled = tf("band_5g_switch", band161)
        )

        // 10. Hardware
        val hardware = HardwareMetrics(
            cpuUsage = latestChart.optDouble("cpu_usage", 0.0),
            temperature = latestChart.optString("temperature", "--"),
            memory = latestChart.optString("memory", "--")
        )

        // 11. History Update
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMs))
        history.add(
            BandwidthPoint(
                timeFormatted = timeFmt,
                downloadMbps = dlMbps,
                uploadMbps = ulMbps,
                rxKbps = rxKbps,
                txKbps = txKbps
            )
        )
        while (history.size > maxHistory) {
            history.removeAt(0)
        }

        lastPollTimeMs = nowMs

        val wan = WanMetrics(
            downloadKbps = rxKbps,
            uploadKbps = txKbps,
            downloadMbps = dlMbps,
            uploadMbps = ulMbps,
            dlFlowMb = dash.optString("flow_dl", "-"),
            ulFlowMb = dash.optString("flow_ul", "-"),
            totalFlowMb = dash.optString("mon_total_flow", "-"),
            peakDlMbps = peakDlMbps,
            peakUlMbps = peakUlMbps
        )

        val state = RouterState(
            timestamp = nowMs,
            timeFormatted = timeFmt,
            wan = wan,
            cellular = cellular,
            cellLock = cellLock,
            hardware = hardware,
            devices = devices,
            history = history.toList()
        )

        return Result.success(state)
    }

    fun reboot(): Boolean {
        val res = query(6, method = "POST", extra = mapOf("rebootType" to 1))
        return res.optBoolean("success", false) || res.optString("message") == "success"
    }

    // ---- LAN DNS Configuration ----
    private val lanSaveKeys = listOf(
        "lanIp", "netMask", "dhcpServer", "main_dns", "vice_dns",
        "ipBegin", "ipEnd", "expireTime", "ipv6_mode",
        "ipv6_startIp", "ipv6_endIp", "ipv6_main_dns", "ipv6_vice_dns"
    )

    private fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        for (p in parts) {
            val n = p.toIntOrNull() ?: return false
            if (n !in 0..255 || (p.length > 1 && p.startsWith("0"))) return false
        }
        return true
    }

    fun getDns(): Pair<Boolean, DnsConfig> {
        val res = query(3)
        if (!res.optBoolean("success", false)) {
            return Pair(false, DnsConfig())
        }
        return Pair(
            true,
            DnsConfig(
                primary = res.optString("main_dns", ""),
                secondary = res.optString("vice_dns", ""),
                dhcpEnabled = res.optString("dhcpServer") == "1"
            )
        )
    }

    suspend fun setDns(primary: String, secondary: String): Result<String> {
        val p = primary.trim()
        val s = secondary.trim()
        if (!isIpv4(p)) {
            return Result.failure(IllegalArgumentException("Primary DNS must be a valid IPv4 address"))
        }
        if (s.isNotEmpty() && !isIpv4(s)) {
            return Result.failure(IllegalArgumentException("Secondary DNS must be a valid IPv4 address or blank"))
        }
        if (p == s) {
            return Result.failure(IllegalArgumentException("Primary and secondary DNS cannot be the same"))
        }

        val current = query(3)
        if (!current.optBoolean("success", false)) {
            return Result.failure(IllegalStateException(current.optString("message", "Could not read LAN settings")))
        }

        if (current.optString("dhcpServer") != "1") {
            return Result.failure(IllegalStateException("DHCP server is off on the router"))
        }

        val payload = mutableMapOf<String, Any>()
        for (k in lanSaveKeys) {
            payload[k] = current.optString(k, "")
        }
        if (current.has("supportList")) {
            payload["bindPort0"] = current.optString("bindPort0", "")
        }
        payload["main_dns"] = p
        payload["vice_dns"] = s

        // Token from cmd 233
        val tResp = query(233)
        payload["token"] = tResp.optString("token", "")

        val res = query(3, method = "POST", extra = payload)
        if (!res.optBoolean("success", false)) {
            val msg = res.optString("message", "Router rejected DNS change")
            return Result.failure(IllegalStateException(msg))
        }

        // The router restarts DHCP after the save; only a read-back proves the change stuck.
        var lastRead: DnsConfig? = null
        for (i in 0 until 8) {
            delay(2000)
            val (ok, check) = getDns()
            if (!ok) continue
            lastRead = check
            if (check.primary == p && check.secondary == s) {
                return Result.success("DNS updated and confirmed by router")
            }
        }
        if (lastRead != null) {
            val kept = "${lastRead.primary} / ${lastRead.secondary.ifEmpty { "router" }}"
            return Result.failure(IllegalStateException("Router accepted the save but kept $kept"))
        }
        return Result.success("DNS saved, but the router has not confirmed it yet. Tap Reload shortly.")
    }

    // ---- Wi-Fi Radio Management (2.4 GHz & 5 GHz) ----
    private fun decodeBase64Ssid(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val decoded = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            raw
        }
    }

    fun getWifiRadios(): Pair<Boolean, WifiRadioConfig> {
        val res24 = query(2, extra = mapOf("subcmd" to 0))
        val res5 = query(211, extra = mapOf("subcmd" to 0))

        val success24 = res24.optBoolean("success", false) || res24.has("wifiOpen")
        val success5 = res5.optBoolean("success", false) || res5.has("wifiOpen")

        if (!success24 && !success5) {
            return Pair(false, WifiRadioConfig())
        }

        val enabled24 = res24.optString("wifiOpen", "1") == "1"
        val ssid24Raw = res24.optString("ssid", "")
        val ssid24 = decodeBase64Ssid(ssid24Raw)
        val chan24 = res24.optString("wifi24Channel", "")

        val enabled5 = res5.optString("wifiOpen", "1") == "1"
        val ssid5Raw = res5.optString("ssid", "")
        val ssid5 = decodeBase64Ssid(ssid5Raw)
        val chan5 = res5.optString("wifi5Channel", "")

        return Pair(
            true,
            WifiRadioConfig(
                enabled24g = enabled24,
                ssid24g = ssid24,
                channel24g = chan24,
                enabled5g = enabled5,
                ssid5g = ssid5,
                channel5g = chan5
            )
        )
    }

    suspend fun setWifi24g(enabled: Boolean): Result<String> = setWifiBand(WifiBand.BAND_24G, enabled)

    suspend fun setWifi5g(enabled: Boolean): Result<String> = setWifiBand(WifiBand.BAND_5G, enabled)

    private enum class WifiBand(val cmd: Int, val label: String) {
        BAND_24G(2, "2.4 GHz"),
        BAND_5G(211, "5 GHz")
    }

    /** Reads whether a radio is broadcasting; null if the router did not answer. */
    private fun isBandEnabled(band: WifiBand): Boolean? {
        val res = query(band.cmd, extra = mapOf("subcmd" to 0))
        if (!res.has("wifiOpen")) return null
        return res.optString("wifiOpen") == "1"
    }

    private suspend fun setWifiBand(band: WifiBand, enabled: Boolean): Result<String> {
        val other = if (band == WifiBand.BAND_24G) WifiBand.BAND_5G else WifiBand.BAND_24G
        val state = if (enabled) "on" else "off"

        val current = query(band.cmd, extra = mapOf("subcmd" to 0))
        if (!current.has("wifiOpen")) {
            return Result.failure(IllegalStateException(current.optString("message", "").ifEmpty { "Could not read ${band.label} Wi-Fi settings" }))
        }
        if ((current.optString("wifiOpen") == "1") == enabled) {
            return Result.success("${band.label} Wi-Fi is already $state")
        }

        // Never leave both radios off: every wireless device, including this phone,
        // would lose the router and the app could not turn Wi-Fi back on.
        if (!enabled) {
            when (isBandEnabled(other)) {
                true -> Unit
                false -> return Result.failure(IllegalStateException(
                    "${other.label} is off. Turning off ${band.label} too would disconnect every wireless device, including this phone. Turn on ${other.label} first."))
                null -> return Result.failure(IllegalStateException(
                    "Could not confirm ${other.label} is on, so ${band.label} was left on."))
            }
        }

        // Same fields the router's own Wi-Fi page posts; everything else is echoed as read.
        val payload = mutableMapOf<String, Any>(
            "subcmd" to 0,
            "wifiOpen" to if (enabled) "1" else "0",
            "broadcast" to current.optString("broadcast", "1"),
            "ssid" to current.optString("ssid", ""),
            "key" to current.optString("key", ""),
            "authenticationType" to current.optString("authenticationType", "2")
        )
        if (current.has("wifiSames")) {
            payload["wifiSames"] = current.optString("wifiSames", "0")
        }
        payload["token"] = query(233).optString("token", "")

        // Only an explicit message is a rejection. An empty reply or HTTP error is
        // ambiguous (the Wi-Fi restart can cut the request), so the read-back decides.
        val res = query(band.cmd, method = "POST", extra = payload, timeoutSeconds = 20)
        val msg = res.optString("message", "")
        if (!res.optBoolean("success", false) && msg.isNotEmpty() && msg != "0" && msg != "success") {
            return Result.failure(IllegalStateException(msg))
        }

        // The wireless subsystem restarts for 5-10 s; poll until it reports the new state.
        var lastRead: Boolean? = null
        for (i in 0 until 10) {
            delay(2000)
            val now = isBandEnabled(band) ?: continue
            lastRead = now
            if (now == enabled) {
                return Result.success("${band.label} Wi-Fi turned $state, confirmed by router")
            }
        }
        if (lastRead != null) {
            return Result.failure(IllegalStateException("Router kept ${band.label} Wi-Fi ${if (lastRead) "on" else "off"}"))
        }
        return Result.failure(IllegalStateException(
            "Sent, but the router did not answer the check. If this phone was on ${band.label}, reconnect and refresh."))
    }

    private fun inferDeviceType(name: String, mac: String, band: String): DeviceType {
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

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun roundTo2(value: Double): Double {
        return (value * 100.0).roundToLong() / 100.0
    }
}
