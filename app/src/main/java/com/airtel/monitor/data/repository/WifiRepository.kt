package com.airtel.monitor.data.repository

import com.airtel.monitor.core.util.WifiAndLanValidators
import com.airtel.monitor.data.dto.decodeBase64Utf8
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.model.WifiBandDetail
import com.airtel.monitor.data.model.WifiBandDetails

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

    private val _bandDetails = MutableStateFlow<WifiBandDetails?>(null)
    val bandDetails: StateFlow<WifiBandDetails?> = _bandDetails.asStateFlow()

    private var cachedForbiddenSsids: List<String> = emptyList()
    private var cachedForbiddenKeys: List<String> = emptyList()

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
                _bandDetails.value?.let { prev ->
                    _bandDetails.value = if (band == WifiBand.BAND_24G) {
                        prev.copy(band24g = prev.band24g.copy(enabled = enabled))
                    } else {
                        prev.copy(band5g = prev.band5g.copy(enabled = enabled))
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

    /** Loads full writable 2.4 GHz and 5 GHz parameters (`cmd 2` / `cmd 211`, `subcmd: 0`). */
    fun loadWifiBandDetails(): RouterResult<WifiBandDetails> {
        val res24 = session.execute(2, "GET", extra = mapOf("subcmd" to 0), timeoutSeconds = 5L)
        if (res24 !is RouterResult.Ok) {
            return when (res24) {
                is RouterResult.NotLoggedIn -> res24
                is RouterResult.LimitedAccess -> res24
                is RouterResult.Unreachable -> res24
                is RouterResult.BadResponse -> res24
                is RouterResult.Ok -> error("unreachable")
            }
        }
        val res5 = session.execute(211, "GET", extra = mapOf("subcmd" to 0), timeoutSeconds = 5L)
        if (res5 !is RouterResult.Ok) {
            return when (res5) {
                is RouterResult.NotLoggedIn -> res5
                is RouterResult.LimitedAccess -> res5
                is RouterResult.Unreachable -> res5
                is RouterResult.BadResponse -> res5
                is RouterResult.Ok -> error("unreachable")
            }
        }

        // Best-effort forbid lists from cmd 80 without failing Wi-Fi load if unavailable.
        val initRes = session.execute(80, "GET", timeoutSeconds = 3L)
        if (initRes is RouterResult.Ok) {
            cachedForbiddenSsids = initRes.data.optString("wifi_ssid_forbid", "")
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            cachedForbiddenKeys = initRes.data.optString("wifi_pwd_forbid", "")
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        val band24 = parseBandDetail(res24.data)
        val band5 = parseBandDetail(res5.data)
        val details = WifiBandDetails(band24g = band24, band5g = band5)
        _bandDetails.value = details
        _config.value = WifiRadioConfig(
            enabled24g = band24.enabled,
            ssid24g = band24.ssid,
            channel24g = band24.channel,
            enabled5g = band5.enabled,
            ssid5g = band5.ssid,
            channel5g = band5.channel
        )
        return RouterResult.Ok(details)
    }

    /**
     * Updates SSID, password, security mode, hidden broadcast flag, and optional `wifiSames`
     * for [band] (and mirrors to the companion band when [applySameToBothBands] is true).
     */
    suspend fun saveWifiBandConfig(
        band: WifiBand,
        ssid: String,
        key: String,
        authenticationType: String,
        visibleBroadcast: Boolean,
        wifiSames: Boolean? = null,
        applySameToBothBands: Boolean = false
    ): RouterResult<WifiBandDetails> {
        WifiAndLanValidators.validateSsid(ssid, cachedForbiddenSsids)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }
        WifiAndLanValidators.validateWifiPassword(key, authenticationType, cachedForbiddenKeys)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }

        val primaryResult = writeSingleBand(
            band = band,
            ssid = ssid,
            key = key,
            authenticationType = authenticationType,
            visibleBroadcast = visibleBroadcast,
            wifiSames = wifiSames
        )
        if (primaryResult !is RouterResult.Ok) {
            return when (primaryResult) {
                is RouterResult.NotLoggedIn -> primaryResult
                is RouterResult.LimitedAccess -> primaryResult
                is RouterResult.Unreachable -> primaryResult
                is RouterResult.BadResponse -> primaryResult
                is RouterResult.Ok -> error("unreachable")
            }
        }

        var updatedDetails = mergeBandUpdate(band, primaryResult.data)
        if (applySameToBothBands) {
            val peerBand = if (band == WifiBand.BAND_24G) WifiBand.BAND_5G else WifiBand.BAND_24G
            val peerResult = writeSingleBand(
                band = peerBand,
                ssid = ssid,
                key = key,
                authenticationType = authenticationType,
                visibleBroadcast = visibleBroadcast,
                wifiSames = wifiSames
            )
            if (peerResult !is RouterResult.Ok) {
                return when (peerResult) {
                    is RouterResult.NotLoggedIn -> peerResult
                    is RouterResult.LimitedAccess -> peerResult
                    is RouterResult.Unreachable -> peerResult
                    is RouterResult.BadResponse -> peerResult
                    is RouterResult.Ok -> error("unreachable")
                }
            }
            updatedDetails = mergeBandUpdate(peerBand, peerResult.data)
        }
        return RouterResult.Ok(updatedDetails)
    }

    private suspend fun writeSingleBand(
        band: WifiBand,
        ssid: String,
        key: String,
        authenticationType: String,
        visibleBroadcast: Boolean,
        wifiSames: Boolean?
    ): RouterResult<WifiBandDetail> {
        val encodedSsid = encodeBase64Utf8(ssid)
        val expectedKey = if (authenticationType == "0") "" else key
        val expectedBroadcast = if (visibleBroadcast) "1" else "0"

        val writeRes = writer.write(
            WriteSpec(
                cmd = band.cmd,
                readExtra = mapOf("subcmd" to 0),
                buildPayload = { cur ->
                    buildMap {
                        val keys = cur.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k !in IGNORED_COPY_KEYS) {
                                val v = cur.opt(k)
                                if (v != null && v != JSONObject.NULL) {
                                    put(k, v)
                                }
                            }
                        }
                        put("subcmd", 0)
                        put("wifiOpen", cur.optString("wifiOpen", "1"))
                        put("broadcast", expectedBroadcast)
                        put("ssid", encodedSsid)
                        put("key", expectedKey)
                        put("authenticationType", authenticationType)
                        if (wifiSames != null) {
                            put("wifiSames", if (wifiSames) "1" else "0")
                        } else if (cur.has("wifiSames")) {
                            put("wifiSames", cur.optString("wifiSames", "0"))
                        }
                    }
                },
                verify = { readBack ->
                    val actualSsid = decodeBase64Utf8(readBack.optString("ssid", ""))
                    val actualAuth = readBack.optString("authenticationType", "")
                    val actualBroadcast = readBack.optString("broadcast", "1")
                    val keyMatches = authenticationType == "0" || readBack.optString("key", "") == expectedKey
                    actualSsid == ssid &&
                        actualAuth == authenticationType &&
                        actualBroadcast == expectedBroadcast &&
                        keyMatches
                },
                parseResult = ::parseBandDetail,
                writeTimeoutSeconds = 20L,
                verifyRetries = 10,
                verifyDelayMs = 2000L,
                allowEmptyWriteReply = true
            )
        )

        return when (writeRes) {
            is RouterResult.Ok -> writeRes
            is RouterResult.BadResponse -> {
                if (writeRes.rawBody.isEmpty() || writeRes.message.contains("did not answer")) {
                    RouterResult.BadResponse(
                        "Sent, but the router did not answer the check. If you changed the active ${band.label} network, reconnect and refresh.",
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

    private fun mergeBandUpdate(band: WifiBand, detail: WifiBandDetail): WifiBandDetails {
        val prev = _bandDetails.value ?: WifiBandDetails()
        val updated = if (band == WifiBand.BAND_24G) {
            prev.copy(band24g = detail)
        } else {
            prev.copy(band5g = detail)
        }
        _bandDetails.value = updated
        _config.value = WifiRadioConfig(
            enabled24g = updated.band24g.enabled,
            ssid24g = updated.band24g.ssid,
            channel24g = updated.band24g.channel,
            enabled5g = updated.band5g.enabled,
            ssid5g = updated.band5g.ssid,
            channel5g = updated.band5g.channel
        )
        return updated
    }

    private fun parseBandDetail(json: JSONObject): WifiBandDetail = WifiBandDetail(
        enabled = json.optString("wifiOpen") == "1",
        ssid = decodeBase64Utf8(json.optString("ssid", "")),
        key = json.optString("key", ""),
        visibleBroadcast = json.optString("broadcast", "1") != "0",
        authenticationType = json.optString("authenticationType", "2").ifBlank { "2" },
        channel = json.optString("channel", "").ifBlank { "Auto" },
        hasWifiSames = json.has("wifiSames"),
        wifiSames = json.optString("wifiSames") == "1"
    )

    companion object {
        private val IGNORED_COPY_KEYS = setOf("cmd", "method", "success", "message", "token", "sessionId")
    }
}
