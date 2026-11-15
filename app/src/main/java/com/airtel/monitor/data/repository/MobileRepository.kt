package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.data.dto.NetworkModeSnapshot
import com.airtel.monitor.data.dto.parseNetworkMode218
import com.airtel.monitor.data.model.ApnConfigSnapshot
import com.airtel.monitor.data.model.ApnProfile
import com.airtel.monitor.data.model.MobileAdvancedConfig
import com.airtel.monitor.data.model.NetworkModeCatalog
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.TrafficLimitSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MobileRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter,
    private val statusRepository: StatusRepository? = null
) {
    private val _advancedConfig = MutableStateFlow<MobileAdvancedConfig?>(null)
    val advancedConfig: StateFlow<MobileAdvancedConfig?> = _advancedConfig.asStateFlow()

    private val _apnConfig = MutableStateFlow<ApnConfigSnapshot?>(null)
    val apnConfig: StateFlow<ApnConfigSnapshot?> = _apnConfig.asStateFlow()

    private val _trafficSettings = MutableStateFlow<TrafficLimitSettings?>(null)
    val trafficSettings: StateFlow<TrafficLimitSettings?> = _trafficSettings.asStateFlow()

    suspend fun setMobileData(on: Boolean): RouterResult<String> =
        setNetworkSwitch(cmd = 222, key = "dialMode", on = on, label = "Mobile data")

    suspend fun setFlightMode(on: Boolean): RouterResult<String> =
        setNetworkSwitch(cmd = 226, key = "flightMode", on = on, label = "Flight mode")

    private suspend fun setNetworkSwitch(cmd: Int, key: String, on: Boolean, label: String): RouterResult<String> {
        val value = if (on) "1" else "0"
        val state = if (on) "on" else "off"
        return writer.write(
            WriteSpec(
                cmd = cmd,
                readCmd = 218,
                buildPayload = { mapOf(key to value) },
                verify = { readBack ->
                    if (readBack.has("flightMode")) {
                        statusRepository?.updateCachedNetMode218(readBack)
                    }
                    readBack.optString(key) == value
                },
                parseResult = { "$label $state saved and confirmed by router" },
                writeTimeoutSeconds = 20L,
                verifyRetries = 8,
                verifyDelayMs = 2000L
            )
        )
    }

    /**
     * Makes the modem search for the mobile network again (cmd 288).
     * Never retried on I/O failure so it can never trigger a second search.
     */
    fun rescanNetwork(): RouterResult<String> {
        val replyRes = session.executeRaw(
            cmd = 288,
            method = "GET",
            timeoutSeconds = 20,
            retryOnNetworkFailure = false
        )
        val reply = when (replyRes) {
            is RouterResult.Ok -> replyRes.data
            is RouterResult.NotLoggedIn -> return replyRes
            is RouterResult.LimitedAccess -> return replyRes
            is RouterResult.Unreachable -> return replyRes
            is RouterResult.BadResponse -> return replyRes
        }
        if (reply.isBlank()) {
            return RouterResult.BadResponse("No answer from the router", reply)
        }
        val json = runCatching { JSONObject(reply) }.getOrElse { JSONObject() }
        return if (json.optString("message") == "0" || json.optBoolean("success", false)) {
            RouterResult.Ok("Searching for the network. The connection is back in about a minute.")
        } else {
            RouterResult.BadResponse(
                json.optString("message", "").ifEmpty { "Router rejected the network search" },
                reply
            )
        }
    }

    fun readNetworkMode(): RouterResult<NetworkModeSnapshot> {
        return when (val res = session.execute(218)) {
            is RouterResult.Ok -> RouterResult.Ok(parseNetworkMode218(res.data))
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    /** Reads `cmd 256` + `cmd 218` for network mode selection, data roaming, and LTE/NR CA switches. */
    fun loadMobileAdvanced(flags: RouterFeatureFlags = RouterFeatureFlags()): RouterResult<MobileAdvancedConfig> {
        val res256 = session.execute(256, method = "GET")
        val res218 = session.execute(218, method = "GET")

        val j256 = res256.getOrNull()
        val j218 = res218.getOrNull()
        if (j256 == null && j218 == null) {
            return when {
                res256 is RouterResult.NotLoggedIn -> res256
                res218 is RouterResult.NotLoggedIn -> res218
                res256 is RouterResult.LimitedAccess -> res256
                res218 is RouterResult.LimitedAccess -> res218
                res256 is RouterResult.Unreachable -> res256
                res218 is RouterResult.Unreachable -> res218
                else -> res256 as? RouterResult.BadResponse
                    ?: res218 as? RouterResult.BadResponse
                    ?: RouterResult.BadResponse("Could not read cellular settings")
            }
        }

        if (j218 != null) {
            statusRepository?.updateCachedNetMode218(j218)
        }
        val snap218 = j218?.let(::parseNetworkMode218) ?: NetworkModeSnapshot(
            mobileDataOn = null,
            flightModeOn = null
        )
        val displayMode = j256?.optString("display_mode", "").orEmpty().uppercase(Locale.US)
        val networkMode = j256?.optString("networkMode", "").orEmpty().ifEmpty { snap218.networkMode }.uppercase(Locale.US)
        val available = NetworkModeCatalog.availableOptions(flags.aeraId, displayMode)
        val cfg = MobileAdvancedConfig(
            networkMode = networkMode,
            displayMode = displayMode,
            roamingEnabled = snap218.roamingEnable,
            lteCaEnabled = snap218.lteCa,
            nrCaEnabled = snap218.nrCa,
            availableModes = available,
            plmnLocked = flags.lockPlmnFlag
        )
        _advancedConfig.value = cfg
        return RouterResult.Ok(cfg)
    }

    /** Saves radio technology preference (`cmd 256` `NETWORKSET_MODE_MT2731`). */
    suspend fun setNetworkMode(
        modeCode: String,
        flags: RouterFeatureFlags = RouterFeatureFlags()
    ): RouterResult<MobileAdvancedConfig> {
        if (flags.lockPlmnFlag || _advancedConfig.value?.plmnLocked == true) {
            return RouterResult.BadResponse("Network mode cannot be changed while PLMN lock is active")
        }
        val target = modeCode.trim().uppercase(Locale.US)
        if (target.isEmpty()) {
            return RouterResult.BadResponse("Select a network mode first")
        }
        val writeRes = writer.write(
            WriteSpec(
                cmd = 256,
                readCmd = 256,
                buildPayload = { mapOf("networkMode" to target) },
                verify = { readBack ->
                    readBack.optString("networkMode", "").trim().equals(target, ignoreCase = true)
                },
                parseResult = { readBack ->
                    val current = _advancedConfig.value ?: MobileAdvancedConfig()
                    val displayMode = readBack.optString("display_mode", current.displayMode).uppercase(Locale.US)
                    current.copy(
                        networkMode = readBack.optString("networkMode", target).uppercase(Locale.US),
                        displayMode = displayMode,
                        availableModes = NetworkModeCatalog.availableOptions(flags.aeraId, displayMode)
                    )
                },
                writeTimeoutSeconds = 20L,
                verifyRetries = 5,
                verifyDelayMs = 1500L
            )
        )
        if (writeRes is RouterResult.Ok) {
            _advancedConfig.value = writeRes.data
            session.execute(218, method = "GET").getOrNull()?.let { j218 ->
                statusRepository?.updateCachedNetMode218(j218)
            }
        }
        return writeRes
    }

    /** Toggles international/national data roaming (`cmd 220` `DATA_ROAMING`). */
    suspend fun setDataRoaming(enabled: Boolean): RouterResult<Boolean> {
        val value = if (enabled) "1" else "0"
        return writer.write(
            WriteSpec(
                cmd = 220,
                readCmd = 218,
                buildPayload = { mapOf("roamingEnable" to value) },
                verify = { readBack ->
                    if (readBack.has("flightMode")) {
                        statusRepository?.updateCachedNetMode218(readBack)
                    }
                    readBack.optString("roamingEnable") == value
                },
                parseResult = {
                    _advancedConfig.value = (_advancedConfig.value ?: MobileAdvancedConfig())
                        .copy(roamingEnabled = enabled)
                    enabled
                },
                verifyRetries = 4,
                verifyDelayMs = 1000L
            )
        )
    }

    /** Toggles LTE CA and/or 5G NR CA switches (`cmd 226` `FLIGHT_MODE_INFO`). */
    suspend fun setCarrierAggregation(
        lteCa: Boolean? = null,
        nrCa: Boolean? = null
    ): RouterResult<NetworkModeSnapshot> {
        if (lteCa == null && nrCa == null) {
            return RouterResult.BadResponse("Specify LTE CA or NR CA change")
        }
        return writer.write(
            WriteSpec(
                cmd = 226,
                readCmd = 218,
                buildPayload = {
                    buildMap {
                        if (lteCa != null) put("lteCA", if (lteCa) "1" else "0")
                        if (nrCa != null) put("nrCA", if (nrCa) "1" else "0")
                    }
                },
                verify = { readBack ->
                    if (readBack.has("flightMode")) {
                        statusRepository?.updateCachedNetMode218(readBack)
                    }
                    val okLte = lteCa == null || readBack.optString("lteCA") == (if (lteCa) "1" else "0")
                    val okNr = nrCa == null || readBack.optString("nrCA") == (if (nrCa) "1" else "0")
                    okLte && okNr
                },
                parseResult = { readBack ->
                    val snap = parseNetworkMode218(readBack)
                    _advancedConfig.value = (_advancedConfig.value ?: MobileAdvancedConfig()).copy(
                        lteCaEnabled = snap.lteCa,
                        nrCaEnabled = snap.nrCa
                    )
                    snap
                },
                verifyRetries = 4,
                verifyDelayMs = 1000L
            )
        )
    }

    /** Reads primary APN IP type/MTU/NAT (`cmd 213 subcmd 3`) and profile list (`cmd 248`). */
    fun loadApnConfig(): RouterResult<ApnConfigSnapshot> {
        val basicRes = session.execute(213, method = "GET", extra = mapOf("subcmd" to 3))
        val listRes = session.execute(248, method = "GET")
        val basicJson = basicRes.getOrNull()
        val listJson = listRes.getOrNull()

        if (basicJson == null && listJson == null) {
            return when {
                basicRes is RouterResult.NotLoggedIn -> basicRes
                listRes is RouterResult.NotLoggedIn -> listRes
                basicRes is RouterResult.LimitedAccess -> basicRes
                listRes is RouterResult.LimitedAccess -> listRes
                basicRes is RouterResult.Unreachable -> basicRes
                listRes is RouterResult.Unreachable -> listRes
                else -> basicRes as? RouterResult.BadResponse
                    ?: listRes as? RouterResult.BadResponse
                    ?: RouterResult.BadResponse("Could not read APN configuration")
            }
        }

        val snap = parseApnSnapshot(basicJson ?: JSONObject(), listJson ?: JSONObject())
        _apnConfig.value = snap
        return RouterResult.Ok(snap)
    }

    /** Saves APN IP type (`IP`/`IPV6`/`IPV4V6`), MTU, and NAT switch (`cmd 213 subcmd 3`). */
    suspend fun saveApnBasic(
        selectType: String,
        mtu: Int,
        natEnabled: Boolean
    ): RouterResult<ApnConfigSnapshot> {
        val cleanType = selectType.trim().uppercase(Locale.US)
        if (cleanType !in setOf("IP", "IPV6", "IPV4V6")) {
            return RouterResult.BadResponse("Unsupported IP type: $cleanType")
        }
        Phase71Validators.validateApnMtu(mtu.toString(), cleanType)?.let {
            return RouterResult.BadResponse(it)
        }
        val natStr = if (natEnabled) "1" else "0"
        return writer.write(
            WriteSpec(
                cmd = 213,
                readCmd = 213,
                readExtra = mapOf("subcmd" to 3),
                buildPayload = {
                    mapOf(
                        "subcmd" to 3,
                        "selectType" to cleanType,
                        "apnMTU" to mtu.toString(),
                        "apnNatName" to natStr
                    )
                },
                verify = { readBack ->
                    readBack.optString("selectType").equals(cleanType, ignoreCase = true) &&
                        readBack.optString("apnMTU") == mtu.toString() &&
                        readBack.optString("apnNatName") == natStr
                },
                parseResult = { readBack ->
                    val existing = _apnConfig.value ?: ApnConfigSnapshot()
                    val updated = existing.copy(
                        selectType = readBack.optString("selectType", cleanType).uppercase(Locale.US),
                        mtu = readBack.optString("apnMTU", mtu.toString()).toIntOrNull() ?: mtu,
                        natEnabled = readBack.optString("apnNatName", natStr) == "1"
                    )
                    _apnConfig.value = updated
                    updated
                },
                verifyRetries = 4,
                verifyDelayMs = 1500L
            )
        )
    }

    /** Saves the APN profile table (`cmd 248` `APN_INFO_PROCESS`). */
    suspend fun saveApnProfiles(profiles: List<ApnProfile>): RouterResult<ApnConfigSnapshot> {
        if (profiles.isEmpty()) {
            return RouterResult.BadResponse("At least one APN profile is required")
        }
        if (profiles.count { it.isEditable } > 4) {
            return RouterResult.BadResponse("Router allows up to 4 custom editable APN profiles")
        }
        for (p in profiles) {
            if (p.isEditable) {
                Phase71Validators.validateApnString(p.name, "Profile name")?.let {
                    return RouterResult.BadResponse(it)
                }
                Phase71Validators.validateApnString(p.apnName, "APN")?.let {
                    return RouterResult.BadResponse(it)
                }
                Phase71Validators.validateApnCredential(p.username, "Username")?.let {
                    return RouterResult.BadResponse(it)
                }
                Phase71Validators.validateApnCredential(p.password, "Password")?.let {
                    return RouterResult.BadResponse(it)
                }
            }
        }
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("name", p.name.trim())
                    put("apnName", p.apnName.trim())
                    put("selectAuthtication", p.selectAuthentication)
                    put("apnUserName", p.username)
                    put("apnUserPassword", p.password)
                    put("edit_flag", if (p.isEditable) "1" else "0")
                    put("default_flag", if (p.isDefault) "1" else "0")
                }
            )
        }
        val expectedDefault = profiles.firstOrNull { it.isDefault }?.apnName?.trim()
        return writer.write(
            WriteSpec(
                cmd = 248,
                readCmd = 248,
                buildPayload = { mapOf("apn_list" to array) },
                verify = { readBack ->
                    val readProfiles = parseApnProfiles(readBack)
                    val readDefault = readProfiles.firstOrNull { it.isDefault }?.apnName
                    readProfiles.size == profiles.size &&
                        (expectedDefault == null || readDefault == expectedDefault)
                },
                parseResult = { readBack ->
                    val existing = _apnConfig.value ?: ApnConfigSnapshot()
                    val updated = existing.copy(profiles = parseApnProfiles(readBack))
                    _apnConfig.value = updated
                    updated
                },
                verifyRetries = 5,
                verifyDelayMs = 1500L
            )
        )
    }

    /** Restores router factory APN profile defaults (`cmd 248` `apn_reset = "1"`). */
    suspend fun resetApnProfiles(): RouterResult<ApnConfigSnapshot> {
        return writer.write(
            WriteSpec(
                cmd = 248,
                readCmd = 248,
                buildPayload = { mapOf("apn_reset" to "1") },
                verify = { readBack -> readBack.has("apn_list") },
                parseResult = { readBack ->
                    val existing = _apnConfig.value ?: ApnConfigSnapshot()
                    val updated = existing.copy(profiles = parseApnProfiles(readBack))
                    _apnConfig.value = updated
                    updated
                },
                verifyRetries = 5,
                verifyDelayMs = 1500L
            )
        )
    }

    /** Reads monthly traffic limit and billing start day (`cmd 337`). */
    fun loadTrafficLimit(): RouterResult<TrafficLimitSettings> {
        return when (val res = session.execute(337, method = "GET")) {
            is RouterResult.Ok -> {
                val parsed = parseTrafficSettings(res.data)
                _trafficSettings.value = parsed
                statusRepository?.updateCachedTraffic337(res.data)
                RouterResult.Ok(parsed)
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    /** Saves quota switch, limit size, unit, and cycle start day (`cmd 337`). */
    suspend fun saveTrafficLimit(
        limitEnabled: Boolean,
        limitSize: String,
        unitCode: String,
        startDate: Int
    ): RouterResult<TrafficLimitSettings> {
        val cleanSize = limitSize.trim()
        val numSize = cleanSize.toIntOrNull()
        if (limitEnabled) {
            if (numSize == null || numSize !in 0..99999) {
                return RouterResult.BadResponse("Quota size must be an integer between 0 and 99999")
            }
        }
        val finalSize = if (cleanSize.isEmpty()) "0" else (numSize?.toString() ?: "0")
        if (startDate !in 1..28) {
            return RouterResult.BadResponse("Billing cycle start day must be between 1 and 28")
        }
        val cleanUnit = if (unitCode in setOf("0", "1", "2")) unitCode else "1"
        val switchValue = if (limitEnabled) "1" else "0"

        return writer.write(
            WriteSpec(
                cmd = 337,
                readCmd = 337,
                buildPayload = { current ->
                    val rawUnit = current.optString("flow_limit_unit", "").trim()
                    val useWord = rawUnit.equals("MB", ignoreCase = true) ||
                        rawUnit.equals("GB", ignoreCase = true) ||
                        rawUnit.equals("TB", ignoreCase = true)
                    val outUnit = if (useWord) {
                        when (cleanUnit) {
                            "0" -> "MB"
                            "2" -> "TB"
                            else -> "GB"
                        }
                    } else {
                        cleanUnit
                    }
                    mapOf(
                        "limitSwitch" to switchValue,
                        "limitSize" to finalSize,
                        "flow_limit_unit" to outUnit,
                        "startDate" to startDate.toString()
                    )
                },
                verify = { readBack ->
                    val rbSwitch = readBack.optString("limitSwitch") == switchValue
                    val rbDay = readBack.optString("startDate") == startDate.toString()
                    val rbSize = !limitEnabled || readBack.optString("limitSize") == finalSize
                    rbSwitch && rbDay && rbSize
                },
                parseResult = { readBack ->
                    statusRepository?.updateCachedTraffic337(readBack)
                    val parsed = parseTrafficSettings(readBack)
                    _trafficSettings.value = parsed
                    parsed
                },
                verifyRetries = 3,
                verifyDelayMs = 800L
            )
        )
    }

    /** Resets or calibrates the monthly counter in MB (`cmd 337`, `statisticsReset = "1"`). */
    suspend fun resetTrafficStatistics(targetUsedMb: Double = 0.0): RouterResult<TrafficLimitSettings> {
        if (targetUsedMb < 0.0 || targetUsedMb.isNaN() || targetUsedMb.isInfinite()) {
            return RouterResult.BadResponse("Calibrated usage must be 0 or greater")
        }
        val mbString = if (targetUsedMb == 0.0) {
            "0"
        } else {
            String.format(Locale.US, "%.2f", targetUsedMb).trimEnd('0').trimEnd('.')
        }
        return writer.write(
            WriteSpec(
                cmd = 337,
                readCmd = 337,
                buildPayload = {
                    mapOf(
                        "statisticsReset" to "1",
                        "usedFlow" to mbString
                    )
                },
                verify = { readBack ->
                    readBack.has("dl_mon_flow") || readBack.has("limitSwitch")
                },
                parseResult = { readBack ->
                    statusRepository?.updateCachedTraffic337(readBack)
                    val parsed = parseTrafficSettings(readBack)
                    _trafficSettings.value = parsed
                    parsed
                },
                verifyRetries = 3,
                verifyDelayMs = 800L
            )
        )
    }

    private fun parseApnSnapshot(basic: JSONObject, listJson: JSONObject): ApnConfigSnapshot {
        val type = basic.optString("selectType", "IPV4V6").trim().ifEmpty { "IPV4V6" }.uppercase(Locale.US)
        val mtu = basic.optString("apnMTU", "1500").trim().toIntOrNull() ?: 1500
        val nat = basic.optString("apnNatName", "1").trim() == "1"
        return ApnConfigSnapshot(
            selectType = type,
            mtu = mtu,
            natEnabled = nat,
            profiles = parseApnProfiles(listJson)
        )
    }

    private fun parseApnProfiles(json: JSONObject): List<ApnProfile> {
        val arr = json.optJSONArray("apn_list") ?: return emptyList()
        val list = ArrayList<ApnProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                ApnProfile(
                    name = obj.optString("name", ""),
                    apnName = obj.optString("apnName", ""),
                    selectAuthentication = obj.optString("selectAuthtication", "0").ifEmpty { "0" },
                    username = obj.optString("apnUserName", ""),
                    password = obj.optString("apnUserPassword", ""),
                    isDefault = obj.optString("default_flag", "0") == "1",
                    isEditable = obj.optString("edit_flag", "1") == "1"
                )
            )
        }
        return list
    }

    private fun parseTrafficSettings(json: JSONObject): TrafficLimitSettings {
        val rawUnit = json.optString("flow_limit_unit", "1").trim()
        val lower = rawUnit.lowercase(Locale.US)
        val usesWord = lower in setOf("mb", "gb", "tb")
        val unitCode = when (lower) {
            "0", "mb", "m" -> "0"
            "2", "tb", "t" -> "2"
            else -> "1"
        }
        return TrafficLimitSettings(
            limitEnabled = json.optString("limitSwitch", "0").trim() in setOf("1", "true", "True"),
            limitSize = json.optString("limitSize", "0").trim(),
            unitCode = unitCode,
            usesWordUnit = usesWord,
            startDate = json.optString("startDate", "1").trim().toIntOrNull()?.coerceIn(1, 28) ?: 1,
            dlMonthMb = json.optString("dl_mon_flow", "0").trim().toDoubleOrNull() ?: 0.0,
            ulMonthMb = json.optString("ul_mon_flow", "0").trim().toDoubleOrNull() ?: 0.0
        )
    }
}
