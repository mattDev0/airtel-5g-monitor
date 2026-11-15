package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.NetworkFormatters.isIpv4
import com.airtel.monitor.core.util.WifiAndLanValidators
import com.airtel.monitor.data.dto.parseDnsConfig
import com.airtel.monitor.data.model.DnsConfig
import com.airtel.monitor.data.model.LanConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class LanRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    private val _dnsConfig = MutableStateFlow<DnsConfig?>(null)
    val dnsConfig: StateFlow<DnsConfig?> = _dnsConfig.asStateFlow()

    private val _lanConfig = MutableStateFlow<LanConfig?>(null)
    val lanConfig: StateFlow<LanConfig?> = _lanConfig.asStateFlow()

    fun loadDns(): RouterResult<DnsConfig> {
        return when (val res = session.execute(3)) {
            is RouterResult.Ok -> {
                val json = res.data
                if (!json.optBoolean("success", false)) {
                    RouterResult.BadResponse(json.optString("message", "Could not read LAN settings"), json.toString())
                } else {
                    val parsed = parseDnsConfig(json)
                    _dnsConfig.value = parsed
                    RouterResult.Ok(parsed)
                }
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    suspend fun saveDns(primary: String, secondary: String): RouterResult<DnsConfig> {
        val p = primary.trim()
        val s = secondary.trim()
        if (!isIpv4(p)) {
            return RouterResult.BadResponse("Primary DNS must be a valid IPv4 address")
        }
        if (s.isNotEmpty() && !isIpv4(s)) {
            return RouterResult.BadResponse("Secondary DNS must be a valid IPv4 address or blank")
        }
        if (p == s) {
            return RouterResult.BadResponse("Primary and secondary DNS cannot be the same")
        }

        val res = writer.write(
            WriteSpec(
                cmd = 3,
                preflight = { current ->
                    when {
                        !current.optBoolean("success", false) ->
                            RouterResult.BadResponse(current.optString("message", "Could not read LAN settings"), current.toString())
                        current.optString("dhcpServer") != "1" ->
                            RouterResult.BadResponse("DHCP server is off on the router", current.toString())
                        else -> null
                    }
                },
                buildPayload = { current ->
                    buildMap {
                        for (k in LAN_SAVE_KEYS) {
                            put(k, current.optString(k, ""))
                        }
                        if (current.has("supportList")) {
                            put("bindPort0", current.optString("bindPort0", ""))
                        }
                        put("main_dns", p)
                        put("vice_dns", s)
                    }
                },
                verify = { readBack ->
                    readBack.optBoolean("success", false) &&
                        readBack.optString("main_dns", "") == p &&
                        readBack.optString("vice_dns", "") == s
                },
                parseResult = { parseDnsConfig(it) },
                writeTimeoutSeconds = 4L,
                verifyRetries = 8,
                verifyDelayMs = 2000L
            )
        )

        return when (res) {
            is RouterResult.Ok -> {
                _dnsConfig.value = res.data
                res
            }
            is RouterResult.BadResponse -> {
                val readJson = runCatching { JSONObject(res.rawBody) }.getOrNull()
                if (readJson != null && readJson.optBoolean("success", false)) {
                    val kept = parseDnsConfig(readJson)
                    _dnsConfig.value = kept
                    val keptLabel = "${kept.primary} / ${kept.secondary.ifEmpty { "router" }}"
                    RouterResult.BadResponse("Router accepted the save but kept $keptLabel", res.rawBody)
                } else {
                    res
                }
            }
            else -> res
        }
    }

    /** Loads the full `cmd 3` LAN / DHCP / DNS configuration and updates both flows. */
    fun loadLanConfig(): RouterResult<LanConfig> {
        val res = session.execute(3, "GET", timeoutSeconds = 5L)
        if (res !is RouterResult.Ok) {
            return when (res) {
                is RouterResult.NotLoggedIn -> res
                is RouterResult.LimitedAccess -> res
                is RouterResult.Unreachable -> res
                is RouterResult.BadResponse -> res
                is RouterResult.Ok -> error("unreachable")
            }
        }
        val cfg = parseLanConfig(res.data)
        _lanConfig.value = cfg
        _dnsConfig.value = cfg.toDnsConfig()
        return RouterResult.Ok(cfg)
    }

    /**
     * Saves LAN/DHCP/DNS settings through `SettingsWriter` (`cmd 3`).
     * Preserves all `LAN_SAVE_KEYS` (+ `bindPort0` when `supportList` exists) and verifies read-back.
     */
    suspend fun saveLanDhcpSettings(
        lanIp: String,
        netMask: String,
        dhcpEnabled: Boolean,
        ipBegin: String,
        ipEnd: String,
        expireTime: String,
        primaryDns: String,
        secondaryDns: String
    ): RouterResult<LanConfig> {
        val cleanIp = lanIp.trim()
        val cleanMask = netMask.trim()
        val cleanBegin = ipBegin.trim()
        val cleanEnd = ipEnd.trim()
        val cleanExpire = expireTime.trim().ifEmpty { "24h" }
        val cleanPrimaryDns = primaryDns.trim()
        val cleanSecondaryDns = secondaryDns.trim()

        WifiAndLanValidators.validateDhcpSettings(
            lanIp = cleanIp,
            netMask = cleanMask,
            dhcpEnabled = dhcpEnabled,
            ipBegin = cleanBegin,
            ipEnd = cleanEnd
        )?.let { err ->
            return RouterResult.BadResponse(err, "")
        }

        if (cleanPrimaryDns.isNotEmpty() && !isIpv4(cleanPrimaryDns)) {
            return RouterResult.BadResponse("Invalid Primary DNS IPv4 address", "")
        }
        if (cleanSecondaryDns.isNotEmpty() && !isIpv4(cleanSecondaryDns)) {
            return RouterResult.BadResponse("Invalid Secondary DNS IPv4 address", "")
        }
        if (cleanPrimaryDns.isNotEmpty() && cleanSecondaryDns == cleanPrimaryDns) {
            return RouterResult.BadResponse("Secondary DNS cannot be the same as Primary DNS", "")
        }

        val expectedDhcp = if (dhcpEnabled) "1" else "0"
        val res = writer.write(
            WriteSpec(
                cmd = 3,
                preflight = { current ->
                    if (!current.optBoolean("success", false)) {
                        RouterResult.BadResponse(current.optString("message", "Could not read LAN settings"), current.toString())
                    } else {
                        null
                    }
                },
                buildPayload = { current ->
                    buildMap {
                        for (k in LAN_SAVE_KEYS) {
                            put(k, current.optString(k, ""))
                        }
                        if (current.has("supportList")) {
                            put("bindPort0", current.optString("bindPort0", ""))
                        }
                        put("lanIp", cleanIp)
                        put("netMask", cleanMask)
                        put("dhcpServer", expectedDhcp)
                        if (dhcpEnabled) {
                            put("ipBegin", cleanBegin)
                            put("ipEnd", cleanEnd)
                            put("expireTime", cleanExpire)
                        }
                        put("main_dns", cleanPrimaryDns)
                        put("vice_dns", cleanSecondaryDns)
                    }
                },
                verify = { readBack ->
                    val basicsMatch = readBack.optString("lanIp") == cleanIp &&
                        readBack.optString("netMask") == cleanMask &&
                        readBack.optString("dhcpServer") == expectedDhcp &&
                        readBack.optString("main_dns") == cleanPrimaryDns &&
                        readBack.optString("vice_dns") == cleanSecondaryDns
                    if (!basicsMatch) {
                        false
                    } else if (dhcpEnabled) {
                        readBack.optString("ipBegin") == cleanBegin &&
                            readBack.optString("ipEnd") == cleanEnd
                    } else {
                        true
                    }
                },
                parseResult = ::parseLanConfig
            )
        )

        return when (res) {
            is RouterResult.Ok -> {
                _lanConfig.value = res.data
                _dnsConfig.value = res.data.toDnsConfig()
                res
            }
            is RouterResult.LimitedAccess -> RouterResult.LimitedAccess(3, res.message)
            else -> res
        }
    }

    private fun parseLanConfig(json: JSONObject): LanConfig = LanConfig(
        lanIp = json.optString("lanIp", "192.168.1.1"),
        netMask = json.optString("netMask", "255.255.255.0"),
        dhcpEnabled = json.optString("dhcpServer", "1") == "1",
        ipBegin = json.optString("ipBegin", ""),
        ipEnd = json.optString("ipEnd", ""),
        expireTime = json.optString("expireTime", ""),
        primaryDns = json.optString("main_dns", ""),
        secondaryDns = json.optString("vice_dns", ""),
        mtu = json.optString("mtu", ""),
        domain = json.optString("domain", "")
    )

    companion object {
        private val LAN_SAVE_KEYS = listOf(
            "lanIp", "netMask", "dhcpServer", "main_dns", "vice_dns",
            "ipBegin", "ipEnd", "expireTime", "ipv6_mode",
            "ipv6_startIp", "ipv6_endIp", "ipv6_main_dns", "ipv6_vice_dns"
        )
    }
}
