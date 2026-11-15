package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.data.model.ParentalControlSnapshot
import com.airtel.monitor.data.model.ParentalSpeedRule
import com.airtel.monitor.data.model.ParentalTimeRule
import com.airtel.monitor.data.model.ParentalUrlRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parental control master toggle and rules (`cmd 391`, `cmd 382`, `cmd 383`, `cmd 385` + `cmd 20`).
 */
class ParentalControlRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    private val _snapshot = MutableStateFlow(ParentalControlSnapshot())
    val snapshot: StateFlow<ParentalControlSnapshot> = _snapshot.asStateFlow()

    fun loadAll(): RouterResult<ParentalControlSnapshot> {
        val statusRes = session.execute(cmd = 391, method = "GET")
        val statusJson = when (statusRes) {
            is RouterResult.Ok -> statusRes.data
            is RouterResult.NotLoggedIn -> return statusRes
            is RouterResult.LimitedAccess -> return statusRes
            is RouterResult.Unreachable -> return statusRes
            is RouterResult.BadResponse -> return statusRes
        }
        val enabled = statusJson.optString("enable", "0") == "1"
        val speed = parseSpeedRules(session.execute(cmd = 382, method = "GET", extra = mapOf("getfun" to true)).getOrNull() ?: JSONObject())
        val urls = parseUrlRules(session.execute(cmd = 383, method = "GET", extra = mapOf("getfun" to true)).getOrNull() ?: JSONObject())
        val times = parseTimeRules(session.execute(cmd = 385, method = "GET", extra = mapOf("getfun" to true)).getOrNull() ?: JSONObject())

        val snap = ParentalControlSnapshot(
            enabled = enabled,
            speedRules = speed,
            urlRules = urls,
            timeRules = times
        )
        _snapshot.value = snap
        return RouterResult.Ok(snap)
    }

    suspend fun setEnabled(enabled: Boolean): RouterResult<ParentalControlSnapshot> {
        val flag = if (enabled) "1" else "0"
        val writeRes = writer.write(
            WriteSpec(
                cmd = 391,
                readCmd = 391,
                buildPayload = { mapOf("enable" to flag) },
                verify = { readBack -> readBack.optString("enable", "") == flag },
                parseResult = { _snapshot.value.copy(enabled = enabled) },
                verifyRetries = 3,
                verifyDelayMs = 400L
            )
        )
        if (writeRes is RouterResult.Ok) {
            _snapshot.value = writeRes.data
        }
        return writeRes
    }

    suspend fun saveSpeedRules(rules: List<ParentalSpeedRule>): RouterResult<ParentalControlSnapshot> {
        if (rules.size > 32) return RouterResult.BadResponse("Maximum 32 speed rules allowed")
        for (rule in rules) {
            Phase71Validators.validateIpOrRange(rule.ip, rule.ippro)?.let { return RouterResult.BadResponse(it) }
            if (rule.maxSpeedKbps !in 1..10_000) {
                return RouterResult.BadResponse("Max speed must be 1–10000 KB/s")
            }
        }
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("enableRule", r.enableRule)
                    put("ip", r.ip.trim())
                    put("maxSpeed", r.maxSpeedKbps.toString())
                    put("ippro", r.ippro)
                }
            )
        }
        val writeRes = writer.write(
            WriteSpec(
                cmd = 382,
                readCmd = 382,
                readExtra = mapOf("getfun" to true),
                buildPayload = { mapOf("success" to true, "datas" to arr) },
                verify = { readBack -> parseSpeedRules(readBack).size == rules.size },
                parseResult = { readBack -> parseSpeedRules(readBack) },
                verifyRetries = 3,
                verifyDelayMs = 400L
            )
        )
        if (writeRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return writeRes as RouterResult<ParentalControlSnapshot>
        }
        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<ParentalControlSnapshot>
        }
        val next = _snapshot.value.copy(speedRules = writeRes.data)
        _snapshot.value = next
        return RouterResult.Ok(next)
    }

    suspend fun saveUrlRules(rules: List<ParentalUrlRule>): RouterResult<ParentalControlSnapshot> {
        if (rules.size > 32) return RouterResult.BadResponse("Maximum 32 URL filter rules allowed")
        val perMacCounts = HashMap<String, Int>()
        val normalized = ArrayList<ParentalUrlRule>(rules.size)
        for (rule in rules) {
            Phase71Validators.validateUnicastMac(rule.mac)?.let { return RouterResult.BadResponse(it) }
            val mac = Phase71Validators.normalizeMac(rule.mac) ?: return RouterResult.BadResponse("Invalid MAC")
            Phase71Validators.validateFilterUrl(rule.url)?.let { return RouterResult.BadResponse(it) }
            val count = (perMacCounts[mac] ?: 0) + 1
            if (count > 10) return RouterResult.BadResponse("Maximum 10 blocked URLs per device ($mac)")
            perMacCounts[mac] = count
            normalized.add(rule.copy(mac = mac, url = rule.url.trim()))
        }
        val arr = JSONArray()
        normalized.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("enableRule", r.enableRule)
                    put("enableLink", r.enableLink)
                    put("mac", r.mac)
                    put("url", r.url)
                    put("ippro", r.ippro)
                }
            )
        }
        val writeRes = writer.write(
            WriteSpec(
                cmd = 383,
                readCmd = 383,
                readExtra = mapOf("getfun" to true),
                buildPayload = { mapOf("success" to true, "datas" to arr) },
                verify = { readBack -> parseUrlRules(readBack).size == normalized.size },
                parseResult = { readBack -> parseUrlRules(readBack) },
                verifyRetries = 3,
                verifyDelayMs = 400L
            )
        )
        if (writeRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return writeRes as RouterResult<ParentalControlSnapshot>
        }
        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<ParentalControlSnapshot>
        }
        val next = _snapshot.value.copy(urlRules = writeRes.data)
        _snapshot.value = next
        return RouterResult.Ok(next)
    }

    suspend fun saveTimeRules(rules: List<ParentalTimeRule>): RouterResult<ParentalControlSnapshot> {
        if (rules.size > 32) return RouterResult.BadResponse("Maximum 32 time schedule rules allowed")
        val normalized = ArrayList<ParentalTimeRule>(rules.size)
        for (rule in rules) {
            Phase71Validators.validateUnicastMac(rule.mac)?.let { return RouterResult.BadResponse(it) }
            val mac = Phase71Validators.normalizeMac(rule.mac) ?: return RouterResult.BadResponse("Invalid MAC")
            Phase71Validators.validateTimeWindow(rule.startTime, rule.endTime)?.let { return RouterResult.BadResponse(it) }
            if (rule.scheduleDays.isBlank()) return RouterResult.BadResponse("Select at least one weekday")
            normalized.add(rule.copy(mac = mac))
        }
        val arr = JSONArray()
        normalized.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("enableRule", r.enableRule)
                    put("mac", r.mac)
                    put("startTime", r.startTime)
                    put("endTime", r.endTime)
                    put("scheduleDays", r.scheduleDays)
                }
            )
        }
        val writeRes = writer.write(
            WriteSpec(
                cmd = 385,
                readCmd = 385,
                readExtra = mapOf("getfun" to true),
                buildPayload = { mapOf("success" to true, "datas" to arr) },
                verify = { readBack -> parseTimeRules(readBack).size == normalized.size },
                parseResult = { readBack -> parseTimeRules(readBack) },
                verifyRetries = 3,
                verifyDelayMs = 400L
            )
        )
        if (writeRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return writeRes as RouterResult<ParentalControlSnapshot>
        }
        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<ParentalControlSnapshot>
        }
        val next = _snapshot.value.copy(timeRules = writeRes.data)
        _snapshot.value = next
        return RouterResult.Ok(next)
    }

    private fun parseSpeedRules(json: JSONObject): List<ParentalSpeedRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        val list = ArrayList<ParentalSpeedRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                ParentalSpeedRule(
                    ip = obj.optString("ip", ""),
                    maxSpeedKbps = obj.optString("maxSpeed", "100").toIntOrNull() ?: 100,
                    ippro = obj.optString("ippro", "IPV4"),
                    enableRule = obj.optBoolean("enableRule", true)
                )
            )
        }
        return list
    }

    private fun parseUrlRules(json: JSONObject): List<ParentalUrlRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        val list = ArrayList<ParentalUrlRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                ParentalUrlRule(
                    mac = Phase71Validators.normalizeMac(obj.optString("mac", "")) ?: obj.optString("mac", ""),
                    url = obj.optString("url", ""),
                    enableRule = obj.optBoolean("enableRule", true),
                    enableLink = obj.optBoolean("enableLink", false),
                    ippro = obj.optString("ippro", "IPV4V6")
                )
            )
        }
        return list
    }

    private fun parseTimeRules(json: JSONObject): List<ParentalTimeRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        val list = ArrayList<ParentalTimeRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                ParentalTimeRule(
                    mac = Phase71Validators.normalizeMac(obj.optString("mac", "")) ?: obj.optString("mac", ""),
                    startTime = obj.optString("startTime", "00:00"),
                    endTime = obj.optString("endTime", "23:59"),
                    scheduleDays = obj.optString("scheduleDays", "1,2,3,4,5,6,0"),
                    enableRule = obj.optBoolean("enableRule", true)
                )
            )
        }
        return list
    }
}
