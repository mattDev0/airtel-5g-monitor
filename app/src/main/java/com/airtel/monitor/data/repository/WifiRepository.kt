package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.core.util.WifiAndLanValidators
import com.airtel.monitor.data.dto.decodeBase64Utf8
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.dto.parseWifiRadioConfig
import com.airtel.monitor.data.model.GuestWifiBandConfig
import com.airtel.monitor.data.model.GuestWifiSnapshot
import com.airtel.monitor.data.model.WifiAdvancedBandConfig
import com.airtel.monitor.data.model.WifiBandDetail
import com.airtel.monitor.data.model.WifiBandDetails
import com.airtel.monitor.data.model.WifiPower411Config
import com.airtel.monitor.data.model.WifiRadioConfig
import com.airtel.monitor.data.model.WifiScheduleConfig
import com.airtel.monitor.data.model.WpsBandStatus
import com.airtel.monitor.data.model.WpsSnapshot
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

    private val _guestWifi = MutableStateFlow(GuestWifiSnapshot())
    val guestWifi: StateFlow<GuestWifiSnapshot> = _guestWifi.asStateFlow()

    private val _adv24g = MutableStateFlow<WifiAdvancedBandConfig?>(null)
    val adv24g: StateFlow<WifiAdvancedBandConfig?> = _adv24g.asStateFlow()

    private val _adv5g = MutableStateFlow<WifiAdvancedBandConfig?>(null)
    val adv5g: StateFlow<WifiAdvancedBandConfig?> = _adv5g.asStateFlow()

    private val _power411 = MutableStateFlow<WifiPower411Config?>(null)
    val power411: StateFlow<WifiPower411Config?> = _power411.asStateFlow()

    private val _wps = MutableStateFlow(WpsSnapshot())
    val wps: StateFlow<WpsSnapshot> = _wps.asStateFlow()

    private val _schedule = MutableStateFlow<WifiScheduleConfig?>(null)
    val schedule: StateFlow<WifiScheduleConfig?> = _schedule.asStateFlow()

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

    /** Preflights Wi-Fi driver readiness (`cmd 417` `wifiStatus == "1"`). */
    fun preflightWifiReady(): RouterResult<Unit> {
        val res = session.execute(cmd = 417, method = "GET", timeoutSeconds = 5L)
        return when (res) {
            is RouterResult.Ok -> {
                if (res.data.optString("wifiStatus", "") == "1") {
                    RouterResult.Ok(Unit)
                } else {
                    RouterResult.BadResponse("Wi-Fi is still initializing on the router. Please wait a moment and try again.")
                }
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    /** Loads Guest Wi-Fi (`subcmd: 1` on `cmd 2` / `cmd 211`) for permitted bands. */
    fun loadGuestWifi(load24g: Boolean = true, load5g: Boolean = true): RouterResult<GuestWifiSnapshot> {
        var b24 = _guestWifi.value.band24g
        var b5 = _guestWifi.value.band5g
        var firstError: RouterResult<GuestWifiSnapshot>? = null

        if (load24g) {
            when (val r = loadGuestBand(WifiBand.BAND_24G)) {
                is RouterResult.Ok -> b24 = r.data
                is RouterResult.NotLoggedIn -> return r
                is RouterResult.Unreachable -> firstError = firstError ?: r
                is RouterResult.LimitedAccess -> firstError = firstError ?: r
                is RouterResult.BadResponse -> firstError = firstError ?: r
            }
        }
        if (load5g) {
            when (val r = loadGuestBand(WifiBand.BAND_5G)) {
                is RouterResult.Ok -> b5 = r.data
                is RouterResult.NotLoggedIn -> return r
                is RouterResult.Unreachable -> firstError = firstError ?: r
                is RouterResult.LimitedAccess -> firstError = firstError ?: r
                is RouterResult.BadResponse -> firstError = firstError ?: r
            }
        }
        val snapshot = GuestWifiSnapshot(band24g = b24, band5g = b5)
        _guestWifi.value = snapshot
        return if (!load24g && !load5g) {
            RouterResult.Ok(snapshot)
        } else if (firstError != null && ((!load24g || b24 == _guestWifi.value.band24g) && (!load5g || b5 == _guestWifi.value.band5g))) {
            firstError
        } else {
            RouterResult.Ok(snapshot)
        }
    }

    private fun loadGuestBand(band: WifiBand): RouterResult<GuestWifiBandConfig> {
        val mainRes = session.execute(cmd = band.cmd, method = "GET", extra = mapOf("subcmd" to 0), timeoutSeconds = 5L)
        val mainJson = when (mainRes) {
            is RouterResult.Ok -> mainRes.data
            is RouterResult.NotLoggedIn -> return mainRes
            is RouterResult.LimitedAccess -> return mainRes
            is RouterResult.Unreachable -> return mainRes
            is RouterResult.BadResponse -> return mainRes
        }
        val mainOpen = mainJson.optString("wifiOpen", "0") == "1"
        if (!mainOpen) {
            return RouterResult.Ok(GuestWifiBandConfig(wifiOpen = false, mainWifiOpen = false))
        }
        val guestRes = session.execute(cmd = band.cmd, method = "GET", extra = mapOf("subcmd" to 1), timeoutSeconds = 5L)
        val guestJson = when (guestRes) {
            is RouterResult.Ok -> guestRes.data
            is RouterResult.NotLoggedIn -> return guestRes
            is RouterResult.LimitedAccess -> return guestRes
            is RouterResult.Unreachable -> return guestRes
            is RouterResult.BadResponse -> return guestRes
        }
        return RouterResult.Ok(parseGuestBand(guestJson, mainOpen = true))
    }

    /** Saves Guest Wi-Fi (`subcmd: 1` on `cmd 2` / `cmd 211`) after `cmd 417` preflight. */
    suspend fun saveGuestWifi(band: WifiBand, config: GuestWifiBandConfig): RouterResult<GuestWifiSnapshot> {
        if (config.wifiOpen) {
            if (!config.mainWifiOpen) {
                return RouterResult.BadResponse("Turn on the primary ${band.label} Wi-Fi radio first before enabling Guest Wi-Fi")
            }
            WifiAndLanValidators.validateSsid(config.ssid, cachedForbiddenSsids)?.let { err ->
                return RouterResult.BadResponse(err)
            }
            if (config.forbiddenSameSsids.any { it == config.ssid }) {
                return RouterResult.BadResponse("Guest Wi-Fi SSID cannot be identical to another active Wi-Fi SSID")
            }
            WifiAndLanValidators.validateWifiPassword(config.key, config.authenticationType, cachedForbiddenKeys)?.let { err ->
                return RouterResult.BadResponse(err)
            }
        }
        when (val ready = preflightWifiReady()) {
            is RouterResult.Ok -> Unit
            is RouterResult.NotLoggedIn -> return ready
            is RouterResult.LimitedAccess -> return ready
            is RouterResult.Unreachable -> return ready
            is RouterResult.BadResponse -> return ready
        }

        val encodedSsid = encodeBase64Utf8(config.ssid)
        val expectedOpen = if (config.wifiOpen) "1" else "0"
        val expectedBroadcast = if (config.visibleBroadcast) "1" else "0"
        val expectedKey = if (config.authenticationType == "0") "" else config.key

        val writeRes = writer.write(
            WriteSpec(
                cmd = band.cmd,
                readExtra = mapOf("subcmd" to 1),
                buildPayload = {
                    mapOf(
                        "subcmd" to 1,
                        "wifiOpen" to expectedOpen,
                        "broadcast" to expectedBroadcast,
                        "ssid" to encodedSsid,
                        "key" to expectedKey,
                        "authenticationType" to config.authenticationType
                    )
                },
                verify = { readBack ->
                    readBack.optString("wifiOpen", "") == expectedOpen &&
                        decodeBase64Utf8(readBack.optString("ssid", "")) == config.ssid &&
                        readBack.optString("authenticationType", "") == config.authenticationType
                },
                parseResult = { readBack -> parseGuestBand(readBack, mainOpen = true) },
                writeTimeoutSeconds = 20L,
                verifyRetries = 6,
                verifyDelayMs = 1500L,
                allowEmptyWriteReply = true
            )
        )
        return when (writeRes) {
            is RouterResult.Ok -> {
                val updated = if (band == WifiBand.BAND_24G) {
                    _guestWifi.value.copy(band24g = writeRes.data)
                } else {
                    _guestWifi.value.copy(band5g = writeRes.data)
                }
                _guestWifi.value = updated
                RouterResult.Ok(updated)
            }
            is RouterResult.NotLoggedIn -> writeRes
            is RouterResult.LimitedAccess -> writeRes
            is RouterResult.Unreachable -> writeRes
            is RouterResult.BadResponse -> writeRes
        }
    }

    /** Loads advanced parameters (`cmd 230` for 2.4G, `cmd 231` for 5G). */
    fun loadWifiAdvanced(band: WifiBand): RouterResult<WifiAdvancedBandConfig> {
        val cmd = if (band == WifiBand.BAND_24G) 230 else 231
        val res = session.execute(cmd = cmd, method = "GET", timeoutSeconds = 5L)
        return when (res) {
            is RouterResult.Ok -> {
                val cfg = parseWifiAdvanced(band, res.data)
                if (band == WifiBand.BAND_24G) _adv24g.value = cfg else _adv5g.value = cfg
                RouterResult.Ok(cfg)
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    /** Saves advanced radio parameters (`cmd 230` / `cmd 231`) after `cmd 417` preflight. */
    suspend fun saveWifiAdvanced(band: WifiBand, config: WifiAdvancedBandConfig): RouterResult<WifiAdvancedBandConfig> {
        if (config.clientStaReadOnly) {
            return RouterResult.BadResponse("Advanced Wi-Fi settings are read-only while operating in client/station mode")
        }
        val limit = config.maxNumLimit.coerceIn(1, 128)
        if (config.maxNum !in 1..limit) {
            return RouterResult.BadResponse("Max client stations must be between 1 and $limit")
        }
        if (config.connectNum !in -99..0 || config.eliminateNum !in -99..0) {
            return RouterResult.BadResponse("RSSI thresholds must be between -99 and 0 dBm")
        }
        if (config.connectNum < config.eliminateNum) {
            return RouterResult.BadResponse("Connect RSSI threshold must be >= kickout RSSI threshold")
        }
        when (val ready = preflightWifiReady()) {
            is RouterResult.Ok -> Unit
            is RouterResult.NotLoggedIn -> return ready
            is RouterResult.LimitedAccess -> return ready
            is RouterResult.Unreachable -> return ready
            is RouterResult.BadResponse -> return ready
        }

        val cmd = if (band == WifiBand.BAND_24G) 230 else 231
        val effectiveBw = if (band == WifiBand.BAND_5G && !config.dfsSwitch && config.bandWidth == "4") {
            "3"
        } else {
            config.bandWidth
        }
        val openFlag = if (config.wifiOpen) "1" else "0"
        val wmmFlag = if (config.wifiWmm) "1" else "0"
        val perfFlag = if (config.performance) "1" else "0"
        val dfsFlag = if (config.dfsSwitch) "0" else "1"

        val writeRes = writer.write(
            WriteSpec(
                cmd = cmd,
                readCmd = cmd,
                buildPayload = {
                    buildMap {
                        put("wifiOpen", openFlag)
                        put("wifiwmm", wmmFlag)
                        put("txPower", config.txPower)
                        put("countryCode", config.countryCode)
                        put("channel", config.channel)
                        put("wifiWorkMode", config.wifiWorkMode)
                        put("bandWidth", effectiveBw)
                        put("maxNum", config.maxNum.toString())
                        put("connectNum", config.connectNum.toString())
                        put("eliminateNum", config.eliminateNum.toString())
                        put("performance", perfFlag)
                        if (band == WifiBand.BAND_5G) {
                            put("dfsSwitch", dfsFlag)
                        }
                    }
                },
                verify = { readBack ->
                    readBack.optString("bandWidth", "") == effectiveBw &&
                        readBack.optString("maxNum", "") == config.maxNum.toString() &&
                        readBack.optString("wifiOpen", "") == openFlag
                },
                parseResult = { readBack -> parseWifiAdvanced(band, readBack) },
                writeTimeoutSeconds = 20L,
                verifyRetries = 6,
                verifyDelayMs = 1500L,
                allowEmptyWriteReply = true
            )
        )
        if (writeRes is RouterResult.Ok) {
            if (band == WifiBand.BAND_24G) _adv24g.value = writeRes.data else _adv5g.value = writeRes.data
        }
        return writeRes
    }

    /** Loads legacy/coverage TX power (`cmd 411`, shown when `aeraId == "NG0131"`). */
    fun loadWifiPower411(): RouterResult<WifiPower411Config> {
        val res = session.execute(cmd = 411, method = "GET", timeoutSeconds = 5L)
        return when (res) {
            is RouterResult.Ok -> {
                val cfg = WifiPower411Config(
                    txPower24g = res.data.optString("txPower", "50").ifBlank { "50" },
                    txPower5g = res.data.optString("txPower5g", res.data.optString("txPower", "50")).ifBlank { "50" }
                )
                _power411.value = cfg
                RouterResult.Ok(cfg)
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    /** Saves TX power level (`"15"` Short / `"50"` Medium / `"100"` Long) on `cmd 411`. */
    suspend fun saveWifiPower411(txPower: String): RouterResult<WifiPower411Config> {
        if (txPower !in setOf("15", "50", "100")) {
            return RouterResult.BadResponse("Invalid TX power option: $txPower")
        }
        when (val ready = preflightWifiReady()) {
            is RouterResult.Ok -> Unit
            is RouterResult.NotLoggedIn -> return ready
            is RouterResult.LimitedAccess -> return ready
            is RouterResult.Unreachable -> return ready
            is RouterResult.BadResponse -> return ready
        }
        val writeRes = writer.write(
            WriteSpec(
                cmd = 411,
                readCmd = 411,
                skipPreRead = true,
                buildPayload = {
                    mapOf("txPower" to txPower, "txPower5g" to txPower)
                },
                verify = { readBack -> readBack.optString("txPower", "") == txPower },
                parseResult = { readBack ->
                    WifiPower411Config(
                        txPower24g = readBack.optString("txPower", txPower),
                        txPower5g = readBack.optString("txPower5g", txPower)
                    )
                },
                writeTimeoutSeconds = 20L,
                verifyRetries = 5,
                verifyDelayMs = 1500L,
                allowEmptyWriteReply = true
            )
        )
        if (writeRes is RouterResult.Ok) {
            _power411.value = writeRes.data
        }
        return writeRes
    }

    /** Loads WPS status across 2.4 GHz & 5 GHz (`cmd 132` + primary radio eligibility). */
    fun loadWpsStatus(): RouterResult<WpsSnapshot> {
        val w24Res = session.execute(cmd = 132, method = "GET", extra = mapOf("subcmd" to 0), timeoutSeconds = 5L)
        val w5Res = session.execute(cmd = 132, method = "GET", extra = mapOf("subcmd" to 1), timeoutSeconds = 5L)
        val w24 = w24Res.getOrNull()
        val w5 = w5Res.getOrNull()
        if (w24 == null && w5 == null) {
            return when {
                w24Res is RouterResult.NotLoggedIn -> w24Res
                w5Res is RouterResult.NotLoggedIn -> w5Res
                w24Res is RouterResult.Unreachable -> w24Res
                w5Res is RouterResult.Unreachable -> w5Res
                w24Res is RouterResult.LimitedAccess -> w24Res
                w5Res is RouterResult.LimitedAccess -> w5Res
                else -> RouterResult.BadResponse("Could not load WPS status")
            }
        }
        val main24 = session.execute(cmd = 2, method = "GET", extra = mapOf("subcmd" to 0), timeoutSeconds = 5L).getOrNull()
        val main5 = session.execute(cmd = 211, method = "GET", extra = mapOf("subcmd" to 0), timeoutSeconds = 5L).getOrNull()

        val open24 = main24?.optString("wifiOpen")?.ifBlank { null }
            ?: w24?.optString("wifiOpen")?.ifBlank { null }
            ?: "1"
        val open5 = main5?.optString("wifiOpen")?.ifBlank { null }
            ?: w5?.optString("wifiOpen")?.ifBlank { null }
            ?: "1"

        val snap = WpsSnapshot(
            band24g = WpsBandStatus(
                enabled = w24?.optString("wlan2g_wps_switch", "0") == "1",
                wifiOpen = open24 == "1",
                authType = main24?.optString("authenticationType", "2")?.ifBlank { "2" } ?: "2",
                visibleBroadcast = main24?.optString("broadcast", "1") != "0"
            ),
            band5g = WpsBandStatus(
                enabled = w5?.optString("wlan5g_wps_switch", "0") == "1",
                wifiOpen = open5 == "1",
                authType = main5?.optString("authenticationType", "2")?.ifBlank { "2" } ?: "2",
                visibleBroadcast = main5?.optString("broadcast", "1") != "0"
            )
        )
        _wps.value = snap
        return RouterResult.Ok(snap)
    }

    /** Enables or disables WPS (`cmd 132 subcmd 0|1`) after checking radio & WPA3/hidden-SSID guards. */
    suspend fun setWpsEnabled(band: WifiBand, enabled: Boolean): RouterResult<WpsSnapshot> {
        val status = _wps.value.let { if (band == WifiBand.BAND_24G) it.band24g else it.band5g }
        if (enabled && !status.eligibleForWps) {
            return RouterResult.BadResponse("WPS requires an active radio, visible SSID broadcast, and non-WPA3 encryption")
        }
        when (val ready = preflightWifiReady()) {
            is RouterResult.Ok -> Unit
            is RouterResult.NotLoggedIn -> return ready
            is RouterResult.LimitedAccess -> return ready
            is RouterResult.Unreachable -> return ready
            is RouterResult.BadResponse -> return ready
        }
        val subcmd = if (band == WifiBand.BAND_24G) 0 else 1
        val keyName = if (band == WifiBand.BAND_24G) "wlan2g_wps_switch" else "wlan5g_wps_switch"
        val flag = if (enabled) "1" else "0"
        val writeRes = writer.write(
            WriteSpec(
                cmd = 132,
                readCmd = 132,
                readExtra = mapOf("subcmd" to subcmd),
                skipPreRead = true,
                buildPayload = { mapOf("subcmd" to subcmd, keyName to flag) },
                verify = { readBack -> readBack.optString(keyName, "") == flag },
                parseResult = { Unit },
                writeTimeoutSeconds = 20L,
                verifyRetries = 6,
                verifyDelayMs = 1500L,
                allowEmptyWriteReply = true
            )
        )
        if (writeRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return writeRes as RouterResult<WpsSnapshot>
        }
        return loadWpsStatus()
    }

    /** Submits an 8-digit enrollee WPS PIN (`cmd 243 subcmd 0|1`). */
    suspend fun startWpsPin(band: WifiBand, pin: String): RouterResult<Unit> {
        Phase71Validators.validateWpsPin(pin)?.let { return RouterResult.BadResponse(it) }
        val subcmd = if (band == WifiBand.BAND_24G) 0 else 1
        return writer.write(
            WriteSpec(
                cmd = 243,
                skipPreRead = true,
                skipVerifyRead = true,
                buildPayload = { mapOf("subcmd" to subcmd, "wpsPin" to pin.trim()) },
                verify = { true },
                parseResult = { Unit }
            )
        )
    }

    /** Triggers virtual WPS Push Button (`cmd 243 subcmd 2|3`). */
    suspend fun startWpsPushButton(band: WifiBand): RouterResult<Unit> {
        val subcmd = if (band == WifiBand.BAND_24G) 2 else 3
        return writer.write(
            WriteSpec(
                cmd = 243,
                skipPreRead = true,
                skipVerifyRead = true,
                buildPayload = { mapOf("subcmd" to subcmd) },
                verify = { true },
                parseResult = { Unit }
            )
        )
    }

    /** Opens a 120s router random WPS PIN pairing window (`cmd 244`). */
    suspend fun openRandomWpsPin(band: WifiBand): RouterResult<String> {
        return writer.write(
            WriteSpec(
                cmd = 244,
                skipPreRead = true,
                skipVerifyRead = true,
                buildPayload = {
                    if (band == WifiBand.BAND_5G) mapOf("wpsType" to 1) else emptyMap()
                },
                postAccepted = { postJson -> postJson.optString("random_ping", "").isNotBlank() },
                verifyPost = { postJson ->
                    if (postJson.optString("random_ping", "").isBlank()) {
                        RouterResult.BadResponse("Router did not return a random WPS PIN")
                    } else null
                },
                verify = { true },
                parseResult = { postJson -> postJson.optString("random_ping", "") }
            )
        )
    }

    /** Polls `cmd 325` for random-PIN completion and clears `wps_is_success_state` when `"1"`. */
    suspend fun pollRandomWpsSuccess(): RouterResult<Boolean> {
        val res = session.execute(cmd = 325, method = "GET", timeoutSeconds = 5L)
        return when (res) {
            is RouterResult.Ok -> {
                val matched = res.data.optString("wps_is_success_state", "0") == "1"
                if (matched) {
                    writer.write(
                        WriteSpec(
                            cmd = 325,
                            skipPreRead = true,
                            skipVerifyRead = true,
                            buildPayload = { mapOf("wps_is_success_state" to "0") },
                            verify = { true },
                            parseResult = { Unit }
                        )
                    )
                }
                RouterResult.Ok(matched)
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    /** Loads Wi-Fi Schedule (`cmd 503`). */
    fun loadWifiSchedule(): RouterResult<WifiScheduleConfig> {
        val res = session.execute(cmd = 503, method = "GET", timeoutSeconds = 5L)
        return when (res) {
            is RouterResult.Ok -> {
                val cfg = parseWifiSchedule(res.data)
                _schedule.value = cfg
                RouterResult.Ok(cfg)
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    /** Saves Wi-Fi Schedule (`cmd 503`). */
    suspend fun saveWifiSchedule(config: WifiScheduleConfig): RouterResult<WifiScheduleConfig> {
        if (config.onHour !in 0..23 || config.offHour !in 0..23 || config.onMinute !in 0..59 || config.offMinute !in 0..59) {
            return RouterResult.BadResponse("Invalid schedule time")
        }
        val flag = if (config.enabled) "1" else "0"
        val writeRes = writer.write(
            WriteSpec(
                cmd = 503,
                readCmd = 503,
                skipPreRead = true,
                buildPayload = {
                    buildMap {
                        put("wifiSwitch", flag)
                        if (config.enabled) {
                            put("wifi_on_start_hour", config.onHour.toString())
                            put("wifi_on_start_min", config.onMinute.toString())
                            put("wifi_off_start_hour", config.offHour.toString())
                            put("wifi_off_start_min", config.offMinute.toString())
                        }
                    }
                },
                verify = { readBack ->
                    readBack.optString("wifiSwitch", "") == flag &&
                        (!config.enabled || (
                            readBack.optString("wifi_on_start_hour", "") == config.onHour.toString() &&
                                readBack.optString("wifi_off_start_hour", "") == config.offHour.toString()
                            ))
                },
                parseResult = ::parseWifiSchedule,
                verifyRetries = 3,
                verifyDelayMs = 400L
            )
        )
        if (writeRes is RouterResult.Ok) {
            _schedule.value = writeRes.data
        }
        return writeRes
    }

    private fun parseGuestBand(json: JSONObject, mainOpen: Boolean): GuestWifiBandConfig {
        val ssidList = buildList {
            val obj = json.optJSONObject("ssidList")
            if (obj != null) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val v = obj.optString(keys.next(), "")
                    if (v.isNotBlank()) add(v)
                }
            }
        }
        return GuestWifiBandConfig(
            wifiOpen = json.optString("wifiOpen", "0") == "1",
            visibleBroadcast = json.optString("broadcast", "1") != "0",
            ssid = decodeBase64Utf8(json.optString("ssid", "")),
            key = json.optString("key", ""),
            authenticationType = json.optString("authenticationType", "2").ifBlank { "2" },
            mainWifiOpen = mainOpen,
            forbiddenSameSsids = ssidList
        )
    }

    private fun parseWifiAdvanced(band: WifiBand, json: JSONObject): WifiAdvancedBandConfig {
        val limitKey = if (band == WifiBand.BAND_24G) "maxNum24Limit" else "maxNumLimit"
        val limit = json.optString(limitKey, "32").toIntOrNull()?.coerceIn(1, 128) ?: 32
        val maxNum = (json.optString("maxNum", "32").toIntOrNull() ?: 32).coerceIn(1, limit)
        val bw = json.optString("bandWidth", "0").ifBlank { "0" }
        val dfs = if (band == WifiBand.BAND_5G) {
            bw == "4" || json.optString("dfsSwitch", "1") == "0"
        } else {
            false
        }
        return WifiAdvancedBandConfig(
            wifiOpen = json.optString("wifiOpen", "1") == "1",
            clientStaReadOnly = json.optString("wifiMode", "0") == "1",
            txPower = json.optString("txPower", "100").ifBlank { "100" },
            countryCode = json.optString("countryCode", ""),
            channel = json.optString("channel", "auto").ifBlank { "auto" },
            wifiWorkMode = json.optString("wifiWorkMode", ""),
            bandWidth = bw,
            maxNum = maxNum,
            maxNumLimit = limit,
            connectNum = json.optString("connectNum", "0").toIntOrNull()?.coerceIn(-99, 0) ?: 0,
            eliminateNum = json.optString("eliminateNum", "0").toIntOrNull()?.coerceIn(-99, 0) ?: 0,
            wifiWmm = json.optString("wifiwmm", "1") == "1",
            performance = json.optString("performance", "0") == "1",
            dfsSwitch = dfs
        )
    }

    private fun parseWifiSchedule(json: JSONObject): WifiScheduleConfig = WifiScheduleConfig(
        enabled = json.optString("wifiSwitch", "0") == "1",
        onHour = json.optString("wifi_on_start_hour", "8").toIntOrNull()?.coerceIn(0, 23) ?: 8,
        onMinute = json.optString("wifi_on_start_min", "0").toIntOrNull()?.coerceIn(0, 59) ?: 0,
        offHour = json.optString("wifi_off_start_hour", "23").toIntOrNull()?.coerceIn(0, 23) ?: 23,
        offMinute = json.optString("wifi_off_start_min", "0").toIntOrNull()?.coerceIn(0, 59) ?: 0
    )

    companion object {
        private val IGNORED_COPY_KEYS = setOf("cmd", "method", "success", "message", "token", "sessionId")
    }
}
