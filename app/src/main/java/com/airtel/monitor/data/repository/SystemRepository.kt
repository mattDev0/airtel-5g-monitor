package com.airtel.monitor.data.repository

import com.airtel.monitor.data.model.RouterTimeConfig
import com.airtel.monitor.data.model.WanLinkDetectConfig

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.network.map
import com.airtel.monitor.core.util.SensitiveFormatters
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.model.AboutRouterInfo
import com.airtel.monitor.data.model.LogGrade
import com.airtel.monitor.data.model.LogSettingsConfig
import com.airtel.monitor.data.model.ScheduledRebootConfig
import com.airtel.monitor.data.model.SelfCheckSnapshot
import org.json.JSONObject
import java.util.Locale

class SystemRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter = SettingsWriter(session)
) {
    fun reboot(): RouterResult<Unit> {
        val res = session.execute(6, method = "POST", extra = mapOf("rebootType" to 1))
        return when (res) {
            is RouterResult.Ok -> {
                val json = res.data
                if (json.optBoolean("success", false) || json.optString("message") == "success") {
                    RouterResult.Ok(Unit)
                } else {
                    RouterResult.BadResponse(
                        json.optString("message", "Reboot rejected by router"),
                        json.toString()
                    )
                }
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    fun startPing(target: String, pingTimes: Int = 4): RouterResult<Unit> {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) {
            return RouterResult.BadResponse("Target IP or hostname is required")
        }
        val res = session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf(
                "subcmd" to 0,
                "pingTimes" to pingTimes,
                "url" to cleanTarget,
                "wan_index" to ""
            )
        )
        return when (res) {
            is RouterResult.Ok -> {
                if (res.data.optBoolean("success", false)) RouterResult.Ok(Unit)
                else RouterResult.BadResponse(res.data.optString("message", "Failed to start ping"), res.data.toString())
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    fun getPingOutput(): String {
        val raw = session.executeRaw(
            cmd = 204,
            method = "GET",
            extra = mapOf("url" to "/tmp/tzwww/pingrt", "subcmd" to 168)
        ).getOrNull().orEmpty()
        if (raw.trim().startsWith("{") && raw.contains("\"success\"")) return ""
        return raw.trim()
    }

    fun stopPing() {
        session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf("subcmd" to 0, "pingTimes" to 0, "url" to "", "wan_index" to "")
        )
    }

    fun startTrace(target: String): RouterResult<Unit> {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) {
            return RouterResult.BadResponse("Target IP or hostname is required")
        }
        val res = session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf(
                "subcmd" to 2,
                "stopped" to "0",
                "port" to -1,
                "url" to cleanTarget,
                "wan_index" to ""
            )
        )
        return when (res) {
            is RouterResult.Ok -> {
                if (res.data.optBoolean("success", false)) RouterResult.Ok(Unit)
                else RouterResult.BadResponse(res.data.optString("message", "Failed to start traceroute"), res.data.toString())
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    fun isTraceRunning(): Boolean {
        val res = session.execute(cmd = 168, method = "GET", extra = mapOf("subcmd" to 2)).getOrNull() ?: return false
        return res.optString("message", "0") == "1"
    }

    fun getTraceOutput(): String {
        val raw = session.executeRaw(
            cmd = 204,
            method = "GET",
            extra = mapOf("url" to "/tmp/tzwww/tracepathrt", "subcmd" to 168)
        ).getOrNull().orEmpty()
        if (raw.trim().startsWith("{") && raw.contains("\"success\"")) return ""
        return raw.trim()
    }

    fun stopTrace() {
        session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf("subcmd" to 2, "stopped" to "1", "port" to -1, "url" to "", "wan_index" to "")
        )
    }

    fun loadAboutInfo(): RouterResult<AboutRouterInfo> {
        val devRes = session.execute(cmd = 207, method = "GET")
        val dev207 = when (devRes) {
            is RouterResult.Ok -> devRes.data
            is RouterResult.NotLoggedIn -> return devRes
            is RouterResult.LimitedAccess -> JSONObject()
            is RouterResult.Unreachable -> return devRes
            is RouterResult.BadResponse -> JSONObject()
        }
        val info133 = session.execute(cmd = 133, method = "GET").getOrNull() ?: JSONObject()
        val sys113 = session.execute(cmd = 113, method = "GET").getOrNull() ?: JSONObject()

        if (dev207.length() == 0 && info133.length() == 0 && sys113.length() == 0 && devRes !is RouterResult.Ok) {
            return when (devRes) {
                is RouterResult.NotLoggedIn -> devRes
                is RouterResult.LimitedAccess -> devRes
                is RouterResult.Unreachable -> devRes
                is RouterResult.BadResponse -> devRes
                is RouterResult.Ok -> error("Unreachable")
            }
        }

        fun pick(key: String, vararg fallbackKeys: String): String {
            val allKeys = arrayOf(key, *fallbackKeys)
            for (k in allKeys) {
                val from207 = dev207.optString(k, "").trim()
                if (from207.isNotEmpty()) return from207
                val from133 = info133.optString(k, "").trim()
                if (from133.isNotEmpty()) return from133
                val from113 = sys113.optString(k, "").trim()
                if (from113.isNotEmpty()) return from113
            }
            return ""
        }

        val rawUptime = pick("uptime")
        val rawCpu = pick("cpu_usage")
        val rawTemp = pick("device_temperature")
        val linkRates = dev207.optString("negotiatton_rate", "")
            .split(",")
            .mapIndexedNotNull { idx, rawRate ->
                val mbps = rawRate.trim().toIntOrNull() ?: return@mapIndexedNotNull null
                if (mbps > 0) "Port ${idx + 1}: $mbps Mbps" else null
            }

        return RouterResult.Ok(
            AboutRouterInfo(
                oduModel = pick("board_type").ifEmpty { "ZLT X17M" },
                iduModel = pick("idu_dev_type"),
                hardwareVersion = pick("hwversion"),
                firmwareVersion = pick("real_fwversion", "fake_version"),
                fakeVersion = pick("fake_version"),
                configVersion = pick("config_version"),
                buildDate = pick("build_date"),
                imei = pick("module_imei", "imei"),
                imsi = pick("IMSI", "imsi"),
                iccid = pick("ICCID", "iccid"),
                serialNumber = pick("device_sn", "sn"),
                msisdn = pick("device_msisdn", "msisdn"),
                eid = pick("eid"),
                moduleType = pick("module_type"),
                moduleFirmware = pick("module_softver", "module_cp_softver"),
                uptimeText = SensitiveFormatters.formatUptimeSeconds(rawUptime),
                cpuUsage = if (rawCpu.isEmpty()) "" else if (rawCpu.endsWith("%")) rawCpu else "$rawCpu%",
                temperature = if (rawTemp.isEmpty()) "" else if (rawTemp.contains("C")) rawTemp else "$rawTemp °C",
                memoryText = formatMemoryField(pick("memory")),
                iduMac = pick("idu_device_mac"),
                lanMac = pick("lan_mac"),
                linkRates = linkRates
            )
        )
    }

    fun runSelfCheck(): RouterResult<SelfCheckSnapshot> {
        return session.execute(cmd = 264, method = "GET", timeoutSeconds = 10L).map { json ->
            SelfCheckSnapshot(
                boardType = json.optString("board_type", "").trim(),
                simStatus = json.optString("sim_status", "").trim(),
                antennaStatus = json.optString("antenna_status", "").trim(),
                flashLevel = json.optString("deviceFlashLevel", "").trim(),
                memoryLevel = json.optString("deviceMemoryLevel", "").trim(),
                temperatureLevel = json.optString("deviceTemperatureLevel", "").trim(),
                dhcpServer = json.optString("dhcpServer", "").trim(),
                networkType = json.optString("network_type_str", "").trim(),
                signalLevel = json.optString("signal_level", "").trim(),
                pdpType = json.optString("pdpType", "").trim(),
                wanIpv4 = json.optString("wan_ip", "").trim(),
                wanIpv6 = json.optString("wan_ipv6", "").trim(),
                wifi2gEnabled = json.optString("wlan2g_switch", "0") == "1",
                wifi5gEnabled = json.optString("wla5g_switch", "0") == "1"
            )
        }
    }

    fun loadRouterTime(): RouterResult<String> =
        session.execute(cmd = 11, method = "GET").map { json ->
            json.optString("systime", "").trim()
        }

    /** Loads router time, timezone, and NTP server configuration (`cmd 11`). */
    fun loadRouterTimeConfig(): RouterResult<RouterTimeConfig> =
        session.execute(cmd = 11, method = "GET").map(::parseRouterTimeConfig)

    /** Saves timezone, NTP switch/servers, and optional manual datetime (`cmd 11`). */
    suspend fun saveRouterTimeConfig(
        config: RouterTimeConfig,
        manualDateTime: String
    ): RouterResult<RouterTimeConfig> {
        val cleanTz = config.timezone.trim().replace(Regex("_\\d+$"), "").ifEmpty { "UTC-8" }
        val wireDateTime = formatCmd11Datetime(manualDateTime)
            ?: return RouterResult.BadResponse("Invalid datetime format (expected yyyy-MM-dd HH:mm or yyyy-MM-dd HH:mm:ss)")
        val targetNtp = if (config.ntpEnabled) "1" else "0"
        val s1 = config.server1.trim()
        val s2 = config.server2.trim()
        val s3 = config.server3.trim()
        val s4 = config.server4.trim()
        return writer.write(
            WriteSpec(
                cmd = 11,
                buildPayload = {
                    mapOf(
                        "timezone" to cleanTz,
                        "ntpSwitch" to targetNtp,
                        "timeServer" to s1,
                        "timeServer2" to s2,
                        "timeServer3" to s3,
                        "timeServer4" to s4,
                        "datetime" to wireDateTime
                    )
                },
                verify = { readBack ->
                    val readNtp = readBack.optString("ntpSwitch", "") == targetNtp
                    val readTz = readBack.optString("timezone", "").trim().replace(Regex("_\\d+$"), "")
                    readNtp && (readTz.isEmpty() || readTz == cleanTz)
                },
                parseResult = ::parseRouterTimeConfig,
                verifyRetries = 2,
                verifyDelayMs = 300L
            )
        )
    }

    /** Syncs router clock to phone timestamp (`cmd 321`). */
    suspend fun syncClientTime(clientTime: String): RouterResult<Unit> {
        val clean = clientTime.trim()
        if (!Regex("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(:\\d{2})?").matches(clean)) {
            return RouterResult.BadResponse("Invalid client time format")
        }
        return writer.write(
            WriteSpec(
                cmd = 321,
                skipPreRead = true,
                skipVerifyRead = true,
                buildPayload = { mapOf("clientTime" to clean) },
                verify = { true },
                parseResult = { Unit }
            )
        )
    }

    /** Loads WAN link detection and auto-recovery configuration (`cmd 336`). */
    fun loadWanLinkDetect(): RouterResult<WanLinkDetectConfig> =
        session.execute(cmd = 336, method = "GET").map(::parseWanLinkDetect)

    /** Validates and saves WAN link detection configuration (`cmd 336`). */
    suspend fun saveWanLinkDetect(
        config: WanLinkDetectConfig,
        rescanAllowed: Boolean
    ): RouterResult<WanLinkDetectConfig> {
        val mixMode = config.isMixMode || config.networkMode in setOf("5", "7", "9")
        val effectiveEnabled = mixMode || config.enabled
        if (config.checkTimeSec !in 1..1800) {
            return RouterResult.BadResponse("Check interval must be 1–1800 seconds")
        }
        if (config.rebootWaitMinutes !in 3..30) {
            return RouterResult.BadResponse("Reboot wait time must be 3–30 minutes")
        }
        if (config.rescanNetworkSec !in 10..600) {
            return RouterResult.BadResponse("Rescan interval must be 10–600 seconds")
        }
        val ip1 = config.ip1.trim()
        val ip2 = config.ip2.trim()
        val ip3 = config.ip3.trim()
        if (ip1.isEmpty()) {
            return RouterResult.BadResponse("Primary probe target (IP/Host 1) is required")
        }
        val hosts = listOf(ip1, ip2, ip3).filter { it.isNotEmpty() }
        for (host in hosts) {
            if (!isValidProbeHost(host, domainOnly = config.checkMode == 3)) {
                return RouterResult.BadResponse("Invalid probe target: $host")
            }
        }
        val action = config.action.trim().ifEmpty { "0" }
        if (action == "3" && !rescanAllowed) {
            return RouterResult.BadResponse("Network rescan recovery action is not supported in current WAN mode")
        }
        if (effectiveEnabled && action == "1" && (config.rebootWaitMinutes * 60) < config.checkTimeSec) {
            return RouterResult.BadResponse("Reboot wait time (minutes * 60) must be >= check interval")
        }
        val v4 = listOf(config.ipv4Dns1.trim(), config.ipv4Dns2.trim(), config.ipv4Dns3.trim())
        if (effectiveEnabled && config.ipv4DnsSw) {
            if (v4.all { it.isEmpty() }) {
                return RouterResult.BadResponse("Enter at least one IPv4 DNS server when custom IPv4 DNS check is enabled")
            }
            v4.filter { it.isNotEmpty() }.forEach { addr ->
                if (!isValidIpv4Address(addr)) return RouterResult.BadResponse("Invalid IPv4 DNS server: $addr")
            }
        }
        val v6 = listOf(config.ipv6Dns1.trim(), config.ipv6Dns2.trim(), config.ipv6Dns3.trim())
        if (effectiveEnabled && config.ipv6DnsSw) {
            if (v6.all { it.isEmpty() }) {
                return RouterResult.BadResponse("Enter at least one IPv6 DNS server when custom IPv6 DNS check is enabled")
            }
            v6.filter { it.isNotEmpty() }.forEach { addr ->
                if (!isValidIpv6Address(addr)) return RouterResult.BadResponse("Invalid IPv6 DNS server: $addr")
            }
        }
        val targetSwitchInt = if (effectiveEnabled) 1 else 0
        val targetSwitchStr = if (effectiveEnabled) "1" else "0"
        return writer.write(
            WriteSpec(
                cmd = 336,
                buildPayload = {
                    mapOf(
                        "wanLinkDetectSwitch" to targetSwitchInt,
                        "wanLinkDetectCheckTime" to config.checkTimeSec,
                        "checkWanLinkDetectMode" to config.checkMode,
                        "wanLinkDetectIP1" to ip1,
                        "wanLinkDetectIP2" to ip2,
                        "wanLinkDetectIP3" to ip3,
                        "LinkDetectAction" to action,
                        "reboot_wait_time" to config.rebootWaitMinutes,
                        "dial_wait_time" to config.rescanNetworkSec,
                        "dnsv4_server_sw" to if (config.ipv4DnsSw) "1" else "0",
                        "dnsv4_server1" to v4[0],
                        "dnsv4_server2" to v4[1],
                        "dnsv4_server3" to v4[2],
                        "dnsv6_server_sw" to if (config.ipv6DnsSw) "1" else "0",
                        "dnsv6_server1" to v6[0],
                        "dnsv6_server2" to v6[1],
                        "dnsv6_server3" to v6[2]
                    )
                },
                verify = { readBack ->
                    val readSw = readBack.optString("wanLinkDetectSwitch", "")
                    val readSec = readBack.optString("wanLinkDetectCheckTime", "").toIntOrNull()
                    (readSw == targetSwitchStr || mixMode) && readSec == config.checkTimeSec
                },
                parseResult = ::parseWanLinkDetect,
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    /** Triggers irreversible router factory reset (`cmd 112`). Caller must obtain Tier-3 confirmation. */
    suspend fun factoryReset(): RouterResult<Unit> =
        writer.write(
            WriteSpec(
                cmd = 112,
                skipPreRead = true,
                skipVerifyRead = true,
                allowEmptyWriteReply = true,
                buildPayload = { emptyMap() },
                verify = { true },
                parseResult = { Unit }
            )
        )

    private fun parseRouterTimeConfig(json: JSONObject): RouterTimeConfig = RouterTimeConfig(
        systemTime = json.optString("systime", "").trim(),
        timezone = json.optString("timezone", "UTC-8").trim().ifEmpty { "UTC-8" },
        ntpEnabled = json.optString("ntpSwitch", "1") == "1",
        server1 = json.optString("timeServer", "").trim(),
        server2 = json.optString("timeServer2", "").trim(),
        server3 = json.optString("timeServer3", "").trim(),
        server4 = json.optString("timeServer4", "").trim()
    )

    private fun formatCmd11Datetime(raw: String): String? {
        val digits = raw.replace(Regex("[-:\\s]"), "")
        return when {
            digits.length >= 12 && digits.all { it.isDigit() } -> digits.drop(4) + digits.take(4)
            else -> null
        }
    }

    private fun parseWanLinkDetect(json: JSONObject): WanLinkDetectConfig {
        val wanMode = json.optString("wan_mode", "").trim()
        val mixMode = wanMode in setOf("5", "7", "9")
        val rawAction = json.optString("LinkDetectAction", "0").trim().ifEmpty { "0" }
        val action = if (wanMode !in setOf("1", "5", "7", "9") && rawAction == "3") "0" else rawAction
        return WanLinkDetectConfig(
            enabled = mixMode || json.optString("wanLinkDetectSwitch", "0") == "1",
            isMixMode = mixMode,
            networkMode = wanMode,
            checkTimeSec = json.optString("wanLinkDetectCheckTime", "10").toIntOrNull() ?: 10,
            checkMode = json.optString("checkWanLinkDetectMode", "0").toIntOrNull() ?: 0,
            ip1 = json.optString("wanLinkDetectIP1", "").trim(),
            ip2 = json.optString("wanLinkDetectIP2", "").trim(),
            ip3 = json.optString("wanLinkDetectIP3", "").trim(),
            action = action,
            rebootWaitMinutes = json.optString("reboot_wait_time", "3").toIntOrNull() ?: 3,
            rescanNetworkSec = json.optString("dial_wait_time", "10").toIntOrNull() ?: 10,
            ipv4DnsSw = json.optString("dnsv4_server_sw", "0") != "0",
            ipv4Dns1 = json.optString("dnsv4_server1", "").trim(),
            ipv4Dns2 = json.optString("dnsv4_server2", "").trim(),
            ipv4Dns3 = json.optString("dnsv4_server3", "").trim(),
            ipv6DnsSw = json.optString("dnsv6_server_sw", "0") != "0",
            ipv6Dns1 = json.optString("dnsv6_server1", "").trim(),
            ipv6Dns2 = json.optString("dnsv6_server2", "").trim(),
            ipv6Dns3 = json.optString("dnsv6_server3", "").trim()
        )
    }

    private fun isValidProbeHost(host: String, domainOnly: Boolean): Boolean {
        if (isValidIpv4Address(host) || isValidIpv6Address(host)) return !domainOnly
        return Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$").matches(host)
    }

    private fun isValidIpv4Address(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { seg ->
            val n = seg.toIntOrNull()
            n != null && n in 0..255 && (seg == "0" || !seg.startsWith("0"))
        }
    }

    private fun isValidIpv6Address(ip: String): Boolean =
        ip.contains(':') && runCatching { java.net.InetAddress.getByName(ip) is java.net.Inet6Address }.getOrDefault(false)

    fun loadScheduledReboot(): RouterResult<ScheduledRebootConfig> =
        session.execute(cmd = 263, method = "GET").map(::parseScheduledReboot)

    suspend fun saveScheduledReboot(config: ScheduledRebootConfig): RouterResult<ScheduledRebootConfig> {
        val cleanHour = config.hour.coerceIn(0, 23)
        val cleanMinute = config.minute.coerceIn(0, 59)
        if (config.enabled && config.weekDays.isEmpty()) {
            return RouterResult.BadResponse("Select at least one weekday for scheduled reboot")
        }
        val orderedWeek = ZLT_WEEKDAY_ORDER.filter { it in config.weekDays }
        val weekString = orderedWeek.joinToString(",")
        val hourStr = String.format(Locale.US, "%02d", cleanHour)
        val minuteStr = String.format(Locale.US, "%02d", cleanMinute)
        val targetSwitch = if (config.enabled) "1" else "0"

        return writer.write(
            WriteSpec(
                cmd = 263,
                buildPayload = {
                    if (config.enabled) {
                        mapOf(
                            "timrRestartSwitch" to "1",
                            "restartHour" to hourStr,
                            "restartMinute" to minuteStr,
                            "restartSecond" to "00",
                            "restartWeek" to weekString
                        )
                    } else {
                        mapOf("timrRestartSwitch" to "0")
                    }
                },
                verify = { readBack ->
                    val switchOk = readBack.optString("timrRestartSwitch", "") == targetSwitch
                    if (!config.enabled) {
                        switchOk
                    } else {
                        val readDays = parseWeekDays(readBack.optString("restartWeek", ""))
                        switchOk &&
                            readBack.optString("restartHour", "").toIntOrNull() == cleanHour &&
                            readBack.optString("restartMinute", "").toIntOrNull() == cleanMinute &&
                            readDays == orderedWeek.toSet()
                    }
                },
                parseResult = ::parseScheduledReboot,
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    fun loadLogSettings(): RouterResult<LogSettingsConfig> =
        session.execute(cmd = 322, method = "GET").map(::parseLogSettings)

    suspend fun saveLogSettings(grade: LogGrade, sizeKb: Int): RouterResult<LogSettingsConfig> {
        val err = SensitiveFormatters.validateLogSizeKb(sizeKb.toString())
        if (err != null) return RouterResult.BadResponse(err)
        val targetBytes = sizeKb * 1000
        return writer.write(
            WriteSpec(
                cmd = 322,
                buildPayload = {
                    mapOf(
                        "logGrade" to grade.wireValue,
                        "logSize" to targetBytes
                    )
                },
                verify = { readBack ->
                    val readGrade = readBack.optString("logGrade", "")
                    val readKb = (readBack.optString("logSize", "").toLongOrNull() ?: -1L) / 1000L
                    readGrade == grade.wireValue && readKb == sizeKb.toLong()
                },
                parseResult = ::parseLogSettings,
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    fun loadSysLog(): RouterResult<List<String>> {
        val rawRes = session.executeRaw(cmd = 17, method = "GET", timeoutSeconds = 8L)
        val raw = when (rawRes) {
            is RouterResult.Ok -> rawRes.data
            is RouterResult.NotLoggedIn -> return rawRes
            is RouterResult.LimitedAccess -> return rawRes
            is RouterResult.Unreachable -> return rawRes
            is RouterResult.BadResponse -> return rawRes
        }
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return RouterResult.Ok(emptyList())
        if (trimmed.startsWith("{") && trimmed.contains("\"success\"")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            if (json != null && !json.optBoolean("success", false)) {
                val msg = json.optString("message", "")
                return RouterResult.BadResponse(msg.ifEmpty { "Failed to load system log" }, trimmed)
            }
        }
        val lines = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
            .reversed()
        return RouterResult.Ok(lines)
    }

    fun clearSysLog(): RouterResult<Unit> {
        val tokenRes = session.fetchWriteToken()
        val token = when (tokenRes) {
            is RouterResult.Ok -> tokenRes.data
            is RouterResult.NotLoggedIn -> return tokenRes
            is RouterResult.LimitedAccess -> return tokenRes
            is RouterResult.Unreachable -> return tokenRes
            is RouterResult.BadResponse -> return tokenRes
        }
        val postRes = session.execute(
            cmd = 17,
            method = "POST",
            extra = mapOf("token" to token),
            timeoutSeconds = 8L,
            allowEmptyReply = true
        )
        return when (postRes) {
            is RouterResult.Ok -> {
                val json = postRes.data
                if (json.length() == 0 || json.optBoolean("success", false) || json.optString("message") in listOf("0", "success", "")) {
                    RouterResult.Ok(Unit)
                } else {
                    RouterResult.BadResponse(json.optString("message", "Clear log rejected"), json.toString())
                }
            }
            is RouterResult.NotLoggedIn -> postRes
            is RouterResult.LimitedAccess -> postRes
            is RouterResult.Unreachable -> postRes
            is RouterResult.BadResponse -> postRes
        }
    }

    fun changeAdminPassword(
        oldPassword: String,
        newPassword: String,
        confirmPassword: String
    ): RouterResult<Unit> {
        val validationError = SensitiveFormatters.validateRouterPassword(
            oldPassword = oldPassword,
            newPassword = newPassword,
            confirmPassword = confirmPassword
        )
        if (validationError != null) {
            return RouterResult.BadResponse(validationError)
        }

        val tokenRes = session.fetchWriteToken()
        val token = when (tokenRes) {
            is RouterResult.Ok -> tokenRes.data
            is RouterResult.NotLoggedIn -> return tokenRes
            is RouterResult.LimitedAccess -> return tokenRes
            is RouterResult.Unreachable -> return tokenRes
            is RouterResult.BadResponse -> return tokenRes
        }

        val subcmd = if (session.currentAccountLevel() == "2") 3 else 0
        val res = session.execute(
            cmd = 102,
            method = "POST",
            extra = mapOf(
                "subcmd" to subcmd,
                "old_passwd" to encodeBase64Utf8(oldPassword),
                "newPasswd" to encodeBase64Utf8(newPassword),
                "token" to token
            ),
            timeoutSeconds = 10L
        )
        return when (res) {
            is RouterResult.Ok -> {
                val msg = res.data.optString("message", "")
                when {
                    msg == "success" || res.data.optBoolean("success", false) -> RouterResult.Ok(Unit)
                    msg == "passwd error" -> RouterResult.BadResponse(
                        "Current router password is incorrect",
                        res.data.toString()
                    )
                    else -> RouterResult.BadResponse(
                        msg.ifEmpty { "Router rejected password change" },
                        res.data.toString()
                    )
                }
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    private fun parseScheduledReboot(json: JSONObject): ScheduledRebootConfig = ScheduledRebootConfig(
        enabled = json.optString("timrRestartSwitch", "0") == "1",
        hour = (json.optString("restartHour", "3").toIntOrNull() ?: 3).coerceIn(0, 23),
        minute = (json.optString("restartMinute", "0").toIntOrNull() ?: 0).coerceIn(0, 59),
        weekDays = parseWeekDays(json.optString("restartWeek", ""))
    )

    private fun parseWeekDays(raw: String): Set<Int> =
        raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..6 }
            .toSet()

    private fun parseLogSettings(json: JSONObject): LogSettingsConfig {
        val rawBytes = json.optString("logSize", "").toLongOrNull() ?: 500_000L
        val kb = (rawBytes / 1000L).toInt().coerceIn(1, 2000)
        return LogSettingsConfig(
            grade = LogGrade.fromWire(json.optString("logGrade", "0")),
            sizeKb = kb
        )
    }

    private fun formatMemoryField(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return ""
        val parts = clean.split(",").mapNotNull { part ->
            Regex("\\d+").find(part)?.value?.toLongOrNull()
        }
        return when {
            parts.size >= 4 && parts[0] > 0 -> {
                val totalMb = parts[0] / 1024.0
                val usedMb = (parts[0] - parts[3]).coerceAtLeast(0L) / 1024.0
                String.format(Locale.US, "%.0f MB / %.0f MB", usedMb, totalMb)
            }
            clean.all { it.isDigit() } -> "$clean%"
            else -> clean
        }
    }

    companion object {
        val ZLT_WEEKDAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
    }
}
