package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.data.dto.parseWifiRadioConfig
import com.airtel.monitor.data.model.WifiRadioConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class WifiRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    enum class WifiBand(val cmd: Int, val label: String) {
        BAND_24G(2, "2.4 GHz"),
        BAND_5G(211, "5 GHz")
    }

    private val _config = MutableStateFlow<WifiRadioConfig?>(null)
    val config: StateFlow<WifiRadioConfig?> = _config.asStateFlow()

    fun loadWifiRadios(): RouterResult<WifiRadioConfig> {
        val res24 = session.execute(2, extra = mapOf("subcmd" to 0))
        val res5 = session.execute(211, extra = mapOf("subcmd" to 0))

        val j24 = res24.getOrNull()
        val j5 = res5.getOrNull()
        val ok24 = j24 != null && (j24.optBoolean("success", false) || j24.has("wifiOpen"))
        val ok5 = j5 != null && (j5.optBoolean("success", false) || j5.has("wifiOpen"))

        if (!ok24 && !ok5) {
            return when {
                res24 is RouterResult.NotLoggedIn -> res24
                res5 is RouterResult.NotLoggedIn -> res5
                res24 is RouterResult.LimitedAccess -> res24
                res5 is RouterResult.LimitedAccess -> res5
                res24 is RouterResult.Unreachable -> res24
                res5 is RouterResult.Unreachable -> res5
                else -> RouterResult.BadResponse("Could not read Wi-Fi radio status")
            }
        }

        val parsed = parseWifiRadioConfig(j24 ?: JSONObject(), j5 ?: JSONObject())
        _config.value = parsed
        return RouterResult.Ok(parsed)
    }

    suspend fun setWifi24g(enabled: Boolean): RouterResult<String> = setWifiBand(WifiBand.BAND_24G, enabled)

    suspend fun setWifi5g(enabled: Boolean): RouterResult<String> = setWifiBand(WifiBand.BAND_5G, enabled)

    private suspend fun setWifiBand(band: WifiBand, enabled: Boolean): RouterResult<String> {
        val other = if (band == WifiBand.BAND_24G) WifiBand.BAND_5G else WifiBand.BAND_24G
        val state = if (enabled) "on" else "off"

        // Pre-check current band state before invoking SettingsWriter to avoid no-op or orphan lockout.
        val currentRes = session.execute(band.cmd, extra = mapOf("subcmd" to 0))
        val current = when (currentRes) {
            is RouterResult.Ok -> currentRes.data
            is RouterResult.NotLoggedIn -> return currentRes
            is RouterResult.LimitedAccess -> return currentRes
            is RouterResult.Unreachable -> return currentRes
            is RouterResult.BadResponse -> return currentRes
        }
        if (!current.has("wifiOpen")) {
            return RouterResult.BadResponse(
                current.optString("message", "").ifEmpty { "Could not read ${band.label} Wi-Fi settings" },
                current.toString()
            )
        }
        if ((current.optString("wifiOpen") == "1") == enabled) {
            return RouterResult.Ok("${band.label} Wi-Fi is already $state")
        }

        // Never leave both radios off: every wireless device, including this phone,
        // would lose the router and the app could not turn Wi-Fi back on.
        if (!enabled) {
            val otherRes = session.execute(other.cmd, extra = mapOf("subcmd" to 0)).getOrNull()
            val otherEnabled = otherRes?.takeIf { it.has("wifiOpen") }?.optString("wifiOpen") == "1"
            when {
                otherRes == null || !otherRes.has("wifiOpen") ->
                    return RouterResult.BadResponse("Could not confirm ${other.label} is on, so ${band.label} was left on.")
                !otherEnabled ->
                    return RouterResult.BadResponse(
                        "${other.label} is off. Turning off ${band.label} too would disconnect every wireless device, including this phone. Turn on ${other.label} first."
                    )
            }
        }

        val writeRes = writer.write(
            WriteSpec(
                cmd = band.cmd,
                readExtra = mapOf("subcmd" to 0),
                buildPayload = { cur ->
                    buildMap {
                        put("subcmd", 0)
                        put("wifiOpen", if (enabled) "1" else "0")
                        put("broadcast", cur.optString("broadcast", "1"))
                        put("ssid", cur.optString("ssid", ""))
                        put("key", cur.optString("key", ""))
                        put("authenticationType", cur.optString("authenticationType", "2"))
                        if (cur.has("wifiSames")) {
                            put("wifiSames", cur.optString("wifiSames", "0"))
                        }
                    }
                },
                verify = { readBack ->
                    readBack.has("wifiOpen") && (readBack.optString("wifiOpen") == "1") == enabled
                },
                parseResult = { "${band.label} Wi-Fi turned $state, confirmed by router" },
                writeTimeoutSeconds = 20L,
                verifyRetries = 10,
                verifyDelayMs = 2000L,
                allowEmptyWriteReply = true
            )
        )

        return when (writeRes) {
            is RouterResult.Ok -> {
                _config.value?.let { prev ->
                    _config.value = if (band == WifiBand.BAND_24G) {
                        prev.copy(enabled24g = enabled)
                    } else {
                        prev.copy(enabled5g = enabled)
                    }
                }
                writeRes
            }
            is RouterResult.BadResponse -> {
                val rawJson = runCatching { JSONObject(writeRes.rawBody) }.getOrNull()
                if (rawJson != null && rawJson.has("wifiOpen")) {
                    val actual = rawJson.optString("wifiOpen") == "1"
                    if (actual != enabled) {
                        return RouterResult.BadResponse(
                            "Router kept ${band.label} Wi-Fi ${if (actual) "on" else "off"}",
                            writeRes.rawBody
                        )
                    }
                }
                if (writeRes.rawBody.isEmpty() || writeRes.message.contains("did not answer")) {
                    RouterResult.BadResponse(
                        "Sent, but the router did not answer the check. If this phone was on ${band.label}, reconnect and refresh.",
                        writeRes.rawBody
                    )
                } else {
                    writeRes
                }
            }
            is RouterResult.LimitedAccess -> RouterResult.LimitedAccess(band.cmd, writeRes.message)
            else -> writeRes
        }
    }
}
