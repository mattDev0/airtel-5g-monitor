package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.NetworkFormatters.isIpv4
import com.airtel.monitor.data.dto.parseDnsConfig
import com.airtel.monitor.data.model.DnsConfig
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

    companion object {
        private val LAN_SAVE_KEYS = listOf(
            "lanIp", "netMask", "dhcpServer", "main_dns", "vice_dns",
            "ipBegin", "ipEnd", "expireTime", "ipv6_mode",
            "ipv6_startIp", "ipv6_endIp", "ipv6_main_dns", "ipv6_vice_dns"
        )
    }
}
