package com.airtel.monitor.data.repository

import com.airtel.monitor.data.model.DdosConfig
import com.airtel.monitor.data.model.DmzConfig
import com.airtel.monitor.data.model.IpPortFilterRule
import com.airtel.monitor.data.model.PortFilterRule
import com.airtel.monitor.data.model.PortForwardRule
import com.airtel.monitor.data.model.TrustedIpConfig
import com.airtel.monitor.data.model.UpnpMapping
import com.airtel.monitor.data.model.UpnpStatus
import com.airtel.monitor.data.model.UrlFilterRule

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.network.map
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.data.model.AlgSettings
import com.airtel.monitor.data.model.FirewallConfig
import com.airtel.monitor.data.model.FirewallLevel
import com.airtel.monitor.data.model.MacFilterRule
import com.airtel.monitor.data.model.MacFilterSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Security & firewall settings repository:
 * - `cmd 367` (`FW_SWITCH_SETTING`): firewall on/off + level (`low`/`medium`/`high`)
 * - `cmd 504` (`ALG_SETTING`): 7 ALG protocol toggles
 * - `cmd 23` + `cmd 28` + `cmd 30` + `cmd 20`: MAC filtering & default filter policy
 */
class SecurityRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    private val _macFilter = MutableStateFlow(MacFilterSnapshot())
    val macFilter: StateFlow<MacFilterSnapshot> = _macFilter.asStateFlow()
    fun loadFirewall(): RouterResult<FirewallConfig> =
        session.execute(cmd = 367, method = "GET").map(::parseFirewall)

    suspend fun saveFirewall(
        enabled: Boolean,
        level: FirewallLevel
    ): RouterResult<FirewallConfig> {
        val targetSwitch = if (enabled) "1" else "0"
        return writer.write(
            WriteSpec(
                cmd = 367,
                buildPayload = {
                    mapOf(
                        "fw_switch" to targetSwitch,
                        "level" to level.wireValue,
                        "success" to true
                    )
                },
                verify = { readBack ->
                    val switchOk = readBack.optString("fw_switch", "") == targetSwitch
                    val levelOk = !enabled || readBack.optString("level", "").equals(level.wireValue, ignoreCase = true)
                    switchOk && levelOk
                },
                parseResult = ::parseFirewall,
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    fun loadAlgSettings(): RouterResult<AlgSettings> =
        session.execute(cmd = 504, method = "GET").map(::parseAlg)

    suspend fun saveAlgSettings(settings: AlgSettings): RouterResult<AlgSettings> {
        val flagMap = mapOf(
            "L2TPSW_Flag" to settings.l2tp.toFlag(),
            "IPSecSW_Flag" to settings.ipsec.toFlag(),
            "H323SW_Flag" to settings.h323.toFlag(),
            "RTSPSW_Flag" to settings.rtsp.toFlag(),
            "SIPSW_Flag" to settings.sip.toFlag(),
            "FTPSW_Flag" to settings.ftp.toFlag(),
            "PPTPSW_Flag" to settings.pptp.toFlag()
        )
        return writer.write(
            WriteSpec(
                cmd = 504,
                buildPayload = { flagMap },
                verify = { readBack ->
                    flagMap.all { (key, expected) -> readBack.optString(key, "") == expected }
                },
                parseResult = ::parseAlg,
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    /** Loads MAC filter rules (`cmd 23`) and default policy (`cmd 28` / `cmd 30`). */
    fun loadMacFilter(): RouterResult<MacFilterSnapshot> {
        val rulesRes = session.execute(cmd = 23, method = "GET", extra = mapOf("getfun" to true))
        val rulesJson = when (rulesRes) {
            is RouterResult.Ok -> rulesRes.data
            is RouterResult.NotLoggedIn -> return rulesRes
            is RouterResult.LimitedAccess -> return rulesRes
            is RouterResult.Unreachable -> return rulesRes
            is RouterResult.BadResponse -> return rulesRes
        }
        val def28 = session.execute(cmd = 28, method = "GET").getOrNull()
        val def30 = if (def28?.optJSONArray("datas") == null || def28.optJSONArray("datas")?.length() == 0) {
            session.execute(cmd = 30, method = "GET").getOrNull()
        } else {
            null
        }
        val acceptV4 = parseAcceptAll(def28 ?: def30, index = 0, defaultVal = true)
        val acceptV6 = parseAcceptAll(def28 ?: def30, index = 1, defaultVal = acceptV4)
        val snapshot = MacFilterSnapshot(
            rules = parseMacRules(rulesJson, defaultEnableLink = !acceptV4),
            acceptAllIpv4 = acceptV4,
            acceptAllIpv6 = acceptV6
        )
        _macFilter.value = snapshot
        return RouterResult.Ok(snapshot)
    }

    /** Saves MAC filter table (`cmd 23`) and commits via `cmd 20`. */
    suspend fun saveMacFilterRules(
        rules: List<MacFilterRule>,
        acceptAllOverride: Boolean? = null
    ): RouterResult<MacFilterSnapshot> {
        if (rules.size > 32) {
            return RouterResult.BadResponse("Maximum 32 MAC filter rules allowed")
        }
        val effectiveAcceptAll = acceptAllOverride ?: _macFilter.value.acceptAllIpv4
        val seenKeys = HashSet<Pair<String, String>>()
        val normalized = ArrayList<MacFilterRule>(rules.size)
        for (rule in rules) {
            Phase71Validators.validateUnicastMac(rule.mac)?.let { return RouterResult.BadResponse(it) }
            val mac = Phase71Validators.normalizeMac(rule.mac)
                ?: return RouterResult.BadResponse("Invalid MAC address: ${rule.mac}")
            val ippro = rule.ippro.uppercase().let { if (it == "IPV6") "IPV6" else "IPV4" }
            if (!seenKeys.add(mac to ippro)) {
                return RouterResult.BadResponse("Duplicate MAC filter rule for $mac ($ippro)")
            }
            normalized.add(
                MacFilterRule(
                    mac = mac,
                    remark = rule.remark.trim().take(32),
                    ippro = ippro,
                    enableRule = rule.enableRule,
                    enableLink = if (acceptAllOverride != null) !effectiveAcceptAll else rule.enableLink
                )
            )
        }
        val arr = JSONArray()
        normalized.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("enableRule", r.enableRule)
                    put("enableLink", r.enableLink)
                    put("remark", r.remark)
                    put("ippro", r.ippro)
                    put("mac", r.mac)
                }
            )
        }
        val expectedTriples = normalized.map { Triple(it.mac, it.ippro, it.enableRule) }.toSet()
        val writeRes = writer.write(
            WriteSpec(
                cmd = 23,
                readCmd = 23,
                readExtra = mapOf("getfun" to true),
                buildPayload = { mapOf("success" to true, "datas" to arr) },
                verify = { readBack ->
                    parseMacRules(readBack, defaultEnableLink = !effectiveAcceptAll)
                        .map { Triple(it.mac, it.ippro, it.enableRule) }
                        .toSet() == expectedTriples
                },
                parseResult = { readBack -> parseMacRules(readBack, defaultEnableLink = !effectiveAcceptAll) },
                verifyRetries = 3,
                verifyDelayMs = 400L
            )
        )
        if (writeRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return writeRes as RouterResult<MacFilterSnapshot>
        }
        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<MacFilterSnapshot>
        }
        val next = _macFilter.value.copy(
            rules = writeRes.data,
            acceptAllIpv4 = effectiveAcceptAll,
            acceptAllIpv6 = effectiveAcceptAll
        )
        _macFilter.value = next
        return RouterResult.Ok(next)
    }

    /**
     * Switches default filter policy (`cmd 28` + `cmd 30`), flips `enableLink = !acceptAll` across
     * `cmd 23` / `32` / `21` rules when non-empty, and commits via `cmd 20`.
     */
    suspend fun saveDefaultFilterPolicy(acceptAll: Boolean): RouterResult<MacFilterSnapshot> {
        val datas28 = JSONArray().apply {
            put(JSONObject().apply { put("enableRule", true); put("acceptAll", acceptAll); put("ippro", "IPV4") })
            put(JSONObject().apply { put("enableRule", true); put("acceptAll", acceptAll); put("ippro", "IPV6") })
        }
        val res28 = writer.write(
            WriteSpec(
                cmd = 28,
                readCmd = 28,
                buildPayload = { mapOf("success" to true, "datas" to datas28) },
                verify = { readBack -> parseAcceptAll(readBack, 0, defaultVal = !acceptAll) == acceptAll },
                parseResult = { Unit },
                verifyRetries = 3,
                verifyDelayMs = 350L
            )
        )
        if (res28 !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return res28 as RouterResult<MacFilterSnapshot>
        }

        val datas30 = JSONArray().apply {
            put(JSONObject().apply { put("enableRule", true); put("acceptAll", acceptAll.toString()); put("ippro", "IPV4") })
            put(JSONObject().apply { put("enableRule", true); put("acceptAll", acceptAll.toString()); put("ippro", "IPV6") })
        }
        val res30 = writer.write(
            WriteSpec(
                cmd = 30,
                readCmd = 30,
                buildPayload = { mapOf("success" to true, "datas" to datas30) },
                verify = { readBack -> parseAcceptAll(readBack, 0, defaultVal = !acceptAll) == acceptAll },
                parseResult = { Unit },
                verifyRetries = 3,
                verifyDelayMs = 350L
            )
        )
        if (res30 !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return res30 as RouterResult<MacFilterSnapshot>
        }

        val desiredEnableLink = !acceptAll
        val macSyncRes = syncFilterTableLinkFlag(cmd = 23, enableLink = desiredEnableLink)
        if (macSyncRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return macSyncRes as RouterResult<MacFilterSnapshot>
        }
        // Keep IP/Port and Port tables aligned per default.js when accessible.
        syncFilterTableLinkFlag(cmd = 32, enableLink = desiredEnableLink)
        syncFilterTableLinkFlag(cmd = 21, enableLink = desiredEnableLink)

        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<MacFilterSnapshot>
        }
        return loadMacFilter()
    }

    private val _dmz = MutableStateFlow(DmzConfig())
    val dmz: StateFlow<DmzConfig> = _dmz.asStateFlow()

    private val _ddos = MutableStateFlow(DdosConfig())
    val ddos: StateFlow<DdosConfig> = _ddos.asStateFlow()

    private val _upnp = MutableStateFlow(UpnpStatus())
    val upnp: StateFlow<UpnpStatus> = _upnp.asStateFlow()

    private val _systemPorts = MutableStateFlow<Set<Int>>(emptySet())
    val systemPorts: StateFlow<Set<Int>> = _systemPorts.asStateFlow()

    private val _portForward = MutableStateFlow<List<PortForwardRule>>(emptyList())
    val portForward: StateFlow<List<PortForwardRule>> = _portForward.asStateFlow()

    private val _ipPortFilter = MutableStateFlow<List<IpPortFilterRule>>(emptyList())
    val ipPortFilter: StateFlow<List<IpPortFilterRule>> = _ipPortFilter.asStateFlow()

    private val _portFilter = MutableStateFlow<List<PortFilterRule>>(emptyList())
    val portFilter: StateFlow<List<PortFilterRule>> = _portFilter.asStateFlow()

    private val _urlFilter = MutableStateFlow<List<UrlFilterRule>>(emptyList())
    val urlFilter: StateFlow<List<UrlFilterRule>> = _urlFilter.asStateFlow()

    private val _urlDefaultAcceptAll = MutableStateFlow(true)
    val urlDefaultAcceptAll: StateFlow<Boolean> = _urlDefaultAcceptAll.asStateFlow()

    private val _trustedIp = MutableStateFlow(TrustedIpConfig())
    val trustedIp: StateFlow<TrustedIpConfig> = _trustedIp.asStateFlow()

    /** Reads DMZ status (`cmd 172 subcmd 6`). */
    fun loadDmz(): RouterResult<DmzConfig> =
        session.execute(cmd = 172, method = "GET", extra = mapOf("subcmd" to 6)).map { json ->
            DmzConfig(
                enabled = json.optString("enabled", "0") == "1",
                ip = json.optString("ip", "").trim()
            ).also { _dmz.value = it }
        }

    /** Saves DMZ toggle and host IP (`cmd 172 subcmd 6`). */
    suspend fun saveDmz(enabled: Boolean, ip: String): RouterResult<DmzConfig> {
        val cleanIp = ip.trim()
        if (enabled && !isValidIpv4(cleanIp)) {
            return RouterResult.BadResponse("Enter a valid DMZ host IPv4 address")
        }
        val targetFlag = enabled.toFlag()
        val targetIp = if (enabled) cleanIp else ""
        return writer.write(
            WriteSpec(
                cmd = 172,
                readCmd = 172,
                readExtra = mapOf("subcmd" to 6),
                buildPayload = {
                    mapOf(
                        "subcmd" to 6,
                        "enabled" to targetFlag,
                        "ip" to targetIp,
                        "success" to true
                    )
                },
                verify = { readBack ->
                    readBack.optString("enabled", "") == targetFlag &&
                        (!enabled || readBack.optString("ip", "").trim() == targetIp)
                },
                parseResult = { json ->
                    DmzConfig(
                        enabled = json.optString("enabled", "0") == "1",
                        ip = json.optString("ip", "").trim()
                    ).also { _dmz.value = it }
                },
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    /** Reads DDoS protection switch (`cmd 275`). */
    fun loadDdos(): RouterResult<DdosConfig> =
        session.execute(cmd = 275, method = "GET").map { json ->
            DdosConfig(enabled = json.optString("ddosSwich", "0") == "1").also { _ddos.value = it }
        }

    /** Saves DDoS protection switch (`cmd 275`). Note upstream typo `ddosSwich`. */
    suspend fun saveDdos(enabled: Boolean): RouterResult<DdosConfig> {
        val target = enabled.toFlag()
        return writer.write(
            WriteSpec(
                cmd = 275,
                buildPayload = { mapOf("ddosSwich" to target) },
                verify = { readBack -> readBack.optString("ddosSwich", "") == target },
                parseResult = { json ->
                    DdosConfig(enabled = json.optString("ddosSwich", "0") == "1").also { _ddos.value = it }
                },
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    /** Reads UPnP switch (`cmd 333`) and optional active lease table (`cmd 202`). */
    fun loadUpnp(): RouterResult<UpnpStatus> =
        session.execute(cmd = 333, method = "GET").map { json ->
            val enabled = json.optString("upnpSwitch", "0") == "1"
            val mappings = parseOptionalUpnpMappings()
            UpnpStatus(enabled = enabled, mappings = mappings).also { _upnp.value = it }
        }

    /** Saves UPnP switch (`cmd 333`). */
    suspend fun saveUpnp(enabled: Boolean): RouterResult<UpnpStatus> {
        val target = enabled.toFlag()
        return writer.write(
            WriteSpec(
                cmd = 333,
                buildPayload = { mapOf("upnpSwitch" to target) },
                verify = { readBack -> readBack.optString("upnpSwitch", "") == target },
                parseResult = { json ->
                    UpnpStatus(
                        enabled = json.optString("upnpSwitch", "0") == "1",
                        mappings = _upnp.value.mappings
                    ).also { _upnp.value = it }
                },
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    /** Queries reserved router system ports (`cmd 400`) to warn before port-forwarding conflicts. */
    fun loadSystemPorts(): RouterResult<Set<Int>> =
        session.execute(cmd = 400, method = "GET").map { json ->
            json.optString("systemPort", "")
                .split(Regex("\\s+"))
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
                .also { _systemPorts.value = it }
        }

    /** Reads Port Forwarding rules (`cmd 27`). */
    fun loadPortForward(): RouterResult<List<PortForwardRule>> =
        session.execute(cmd = 27, method = "GET", extra = mapOf("getfun" to true)).map { json ->
            parsePortForwardRules(json).also { _portForward.value = it }
        }

    /** Replaces and applies Port Forwarding rules (`cmd 27` -> `cmd 20`). */
    suspend fun savePortForwardRules(rules: List<PortForwardRule>): RouterResult<List<PortForwardRule>> {
        if (rules.size > 32) {
            return RouterResult.BadResponse("Maximum 32 port forwarding rules allowed")
        }
        val array = JSONArray()
        for (rule in rules) {
            val cleanIp = rule.mappingIp.trim()
            if (!isValidIpv4(cleanIp)) {
                return RouterResult.BadResponse("Invalid target IPv4 address: ${rule.mappingIp}")
            }
            val extRange = parsePortRangePair(rule.port, separator = ':')
                ?: return RouterResult.BadResponse("Invalid external port or range: ${rule.port}")
            val intRange = parsePortRangePair(rule.mappingPort, separator = '-')
                ?: return RouterResult.BadResponse("Invalid internal port or range: ${rule.mappingPort}")
            if (extRange.second - extRange.first != intRange.second - intRange.first) {
                return RouterResult.BadResponse("External and internal port range counts must match")
            }
            val wirePort = if (extRange.first == extRange.second) {
                extRange.first.toString()
            } else {
                "${extRange.first}:${extRange.second}"
            }
            val wireMappingPort = if (intRange.first == intRange.second) {
                intRange.first.toString()
            } else {
                "${intRange.first}-${intRange.second}"
            }
            array.put(
                JSONObject().apply {
                    put("port", wirePort)
                    put("mappingPort", wireMappingPort)
                    put("mappingIp", cleanIp)
                    put("mappingIpPort", "$cleanIp:$wireMappingPort")
                    put("protocol", rule.protocol.ifBlank { "TCP" })
                    put("remark", rule.remark.trim())
                    put("enableRule", rule.enableRule)
                    put("ifName", rule.ifName.ifBlank { "DEFAULT" })
                }
            )
        }
        return saveRuleListAndApply(cmd = 27, payloadArray = array) {
            loadPortForward()
        }
    }

    /** Reads IP/Port Filter rules (`cmd 32`). */
    fun loadIpPortFilter(): RouterResult<List<IpPortFilterRule>> =
        session.execute(cmd = 32, method = "GET", extra = mapOf("getfun" to true)).map { json ->
            parseIpPortFilterRules(json, defaultEnableLink = !_macFilter.value.acceptAllIpv4)
                .also { _ipPortFilter.value = it }
        }

    /** Replaces and applies IP/Port Filter rules (`cmd 32` -> `cmd 20`). */
    suspend fun saveIpPortFilterRules(rules: List<IpPortFilterRule>): RouterResult<List<IpPortFilterRule>> {
        if (rules.size > 32) {
            return RouterResult.BadResponse("Maximum 32 IP/port filter rules allowed")
        }
        val enableLink = !_macFilter.value.acceptAllIpv4
        val array = JSONArray()
        for (rule in rules) {
            val isIpv6 = rule.ippro.equals("IPV6", ignoreCase = true)
            val srcIp = rule.ip.trim()
            val dstIp = rule.destIp.trim()
            if (srcIp.isNotEmpty() && !isValidIpOrDashRange(srcIp, isIpv6)) {
                return RouterResult.BadResponse("Invalid source IP/range: $srcIp")
            }
            if (dstIp.isNotEmpty() && !isValidIpOrDashRange(dstIp, isIpv6)) {
                return RouterResult.BadResponse("Invalid destination IP/range: $dstIp")
            }
            if (srcIp.isNotEmpty() && srcIp == dstIp) {
                return RouterResult.BadResponse("Source and destination IP cannot be identical")
            }
            val protocol = rule.protocol.ifBlank { "TCP" }
            val portsDisabled = protocol.equals("ICMP", ignoreCase = true) || protocol.equals("All", ignoreCase = true)
            val srcPort = if (portsDisabled) "" else rule.srcPort.trim()
            val dstPort = if (portsDisabled) "" else rule.destPort.trim()
            if (srcPort.isNotEmpty() && (srcPort.toIntOrNull() !in 1..65535)) {
                return RouterResult.BadResponse("Invalid source port: $srcPort")
            }
            if (dstPort.isNotEmpty() && (dstPort.toIntOrNull() !in 1..65535)) {
                return RouterResult.BadResponse("Invalid destination port: $dstPort")
            }
            array.put(
                JSONObject().apply {
                    put("enableLink", enableLink)
                    put("remark", rule.remark.trim())
                    put("ip", srcIp)
                    put("destIp", dstIp)
                    put("srcPort", srcPort)
                    put("destPort", dstPort)
                    put("protocol", protocol)
                    put("ippro", if (isIpv6) "IPV6" else "IPV4")
                    put("enableRule", rule.enableRule)
                }
            )
        }
        return saveRuleListAndApply(cmd = 32, payloadArray = array) {
            loadIpPortFilter()
        }
    }

    /** Reads Port Filter rules (`cmd 21`). */
    fun loadPortFilter(): RouterResult<List<PortFilterRule>> =
        session.execute(cmd = 21, method = "GET", extra = mapOf("getfun" to true)).map { json ->
            parsePortFilterRules(json, defaultEnableLink = !_macFilter.value.acceptAllIpv4)
                .also { _portFilter.value = it }
        }

    /** Replaces and applies Port Filter rules (`cmd 21` -> `cmd 20`). */
    suspend fun savePortFilterRules(rules: List<PortFilterRule>): RouterResult<List<PortFilterRule>> {
        if (rules.size > 32) {
            return RouterResult.BadResponse("Maximum 32 port filter rules allowed")
        }
        val enableLink = !_macFilter.value.acceptAllIpv4
        val array = JSONArray()
        for (rule in rules) {
            val cleanPort = rule.port.trim()
            val pInt = cleanPort.toIntOrNull()
            if (pInt == null || pInt !in 1..65535) {
                return RouterResult.BadResponse("Invalid port (1–65535): ${rule.port}")
            }
            array.put(
                JSONObject().apply {
                    put("enableLink", enableLink)
                    put("remark", rule.remark.trim())
                    put("port", cleanPort)
                    put("protocol", rule.protocol.ifBlank { "tcp" })
                    put("ippro", if (rule.ippro.equals("IPV6", ignoreCase = true)) "IPV6" else "IPV4")
                    put("enableRule", rule.enableRule)
                }
            )
        }
        return saveRuleListAndApply(cmd = 21, payloadArray = array) {
            loadPortFilter()
        }
    }

    /** Reads URL Filter rules (`cmd 26`) and default accept/reject policy (`cmd 29`). */
    fun loadUrlFilter(): RouterResult<List<UrlFilterRule>> {
        val rulesRes = session.execute(cmd = 26, method = "GET", extra = mapOf("getfun" to true))
        if (rulesRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return rulesRes as RouterResult<List<UrlFilterRule>>
        }
        val defJson = session.execute(cmd = 29, method = "GET").getOrNull()
        val acceptAll = parseAcceptAll(defJson, 0, true)
        _urlDefaultAcceptAll.value = acceptAll
        val list = parseUrlFilterRules(rulesRes.data, defaultEnableLink = !acceptAll)
        _urlFilter.value = list
        return RouterResult.Ok(list)
    }

    /** Replaces and applies URL Filter rules (`cmd 26` -> `cmd 20`). */
    suspend fun saveUrlFilterRules(rules: List<UrlFilterRule>): RouterResult<List<UrlFilterRule>> {
        if (rules.size > 32) {
            return RouterResult.BadResponse("Maximum 32 URL filter rules allowed")
        }
        val enableLink = !_urlDefaultAcceptAll.value
        val array = JSONArray()
        for (rule in rules) {
            val cleanUrl = rule.url.trim()
            if (cleanUrl.isEmpty() || !URL_FILTER_REGEX.matches(cleanUrl)) {
                return RouterResult.BadResponse("Invalid URL or domain keyword: ${rule.url}")
            }
            array.put(
                JSONObject().apply {
                    put("enableLink", enableLink)
                    put("remark", rule.remark.trim())
                    put("ippro", if (rule.ippro.equals("IPV6", ignoreCase = true)) "IPV6" else "IPV4")
                    put("url", cleanUrl)
                    put("enableRule", rule.enableRule)
                }
            )
        }
        return saveRuleListAndApply(cmd = 26, payloadArray = array) {
            loadUrlFilter()
        }
    }

    /** Saves URL default accept/reject policy (`cmd 29`), syncs `enableLink` on `cmd 26`, and calls `cmd 20`. */
    suspend fun saveUrlDefaultPolicy(acceptAll: Boolean): RouterResult<Boolean> {
        val desiredEnableLink = !acceptAll
        val datas = JSONArray().apply {
            put(JSONObject().apply { put("enableRule", true); put("acceptAll", acceptAll); put("ippro", "IPV4") })
            put(JSONObject().apply { put("enableRule", true); put("acceptAll", acceptAll); put("ippro", "IPV6") })
        }
        val writeRes = writer.write(
            WriteSpec(
                cmd = 29,
                buildPayload = { mapOf("success" to true, "datas" to datas) },
                verify = { readBack -> parseAcceptAll(readBack, 0, !acceptAll) == acceptAll },
                parseResult = { parseAcceptAll(it, 0, acceptAll) },
                verifyRetries = 2,
                verifyDelayMs = 300L
            )
        )
        if (writeRes !is RouterResult.Ok) return writeRes
        _urlDefaultAcceptAll.value = acceptAll
        syncFilterTableLinkFlag(cmd = 26, enableLink = desiredEnableLink)
        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<Boolean>
        }
        loadUrlFilter()
        return RouterResult.Ok(acceptAll)
    }

    /** Reads web UI trusted management IPs (`cmd 502`, up to 15 addresses). */
    fun loadTrustedIps(): RouterResult<TrustedIpConfig> =
        session.execute(cmd = 502, method = "GET").map { json ->
            val enabled = json.optString("trusted_ip_sw", "0") == "1"
            val ips = (0 until 15).mapNotNull { i ->
                json.optString("trusted_ip_$i", "").trim().ifEmpty { null }
            }
            TrustedIpConfig(enabled = enabled, ips = ips).also { _trustedIp.value = it }
        }

    /** Saves web UI trusted management IPs (`cmd 502` -> `cmd 20`). */
    suspend fun saveTrustedIps(config: TrustedIpConfig): RouterResult<TrustedIpConfig> {
        val cleanIps = config.ips.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanIps.size > 15) {
            return RouterResult.BadResponse("Maximum 15 trusted IP addresses allowed")
        }
        if (cleanIps.distinct().size != cleanIps.size) {
            return RouterResult.BadResponse("Duplicate trusted IP addresses are not allowed")
        }
        for (ip in cleanIps) {
            if (!isValidIpv4(ip) && !isValidIpv6(ip)) {
                return RouterResult.BadResponse("Invalid IPv4 or IPv6 address: $ip")
            }
        }
        val sw = config.enabled.toFlag()
        val writeRes = writer.write(
            WriteSpec(
                cmd = 502,
                buildPayload = {
                    buildMap<String, Any> {
                        put("trusted_ip_sw", sw)
                        for (i in 0 until 15) {
                            put("trusted_ip_$i", cleanIps.getOrElse(i) { "" })
                        }
                    }
                },
                verify = { readBack ->
                    val swOk = readBack.optString("trusted_ip_sw", "") == sw
                    val firstOk = readBack.optString("trusted_ip_0", "").trim() == cleanIps.firstOrNull().orEmpty()
                    swOk && firstOk
                },
                parseResult = { json ->
                    TrustedIpConfig(
                        enabled = json.optString("trusted_ip_sw", "0") == "1",
                        ips = (0 until 15).mapNotNull { i ->
                            json.optString("trusted_ip_$i", "").trim().ifEmpty { null }
                        }
                    )
                },
                verifyRetries = 2,
                verifyDelayMs = 300L
            )
        )
        if (writeRes !is RouterResult.Ok) return writeRes
        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<TrustedIpConfig>
        }
        return loadTrustedIps()
    }

    private suspend fun <T> saveRuleListAndApply(
        cmd: Int,
        payloadArray: JSONArray,
        reload: () -> RouterResult<List<T>>
    ): RouterResult<List<T>> {
        val writeRes = writer.write(
            WriteSpec(
                cmd = cmd,
                readCmd = cmd,
                readExtra = mapOf("getfun" to true),
                buildPayload = { mapOf("success" to true, "datas" to payloadArray) },
                verify = { readBack ->
                    val arr = readBack.optJSONArray("datas")
                    arr != null && arr.length() == payloadArray.length()
                },
                parseResult = { Unit },
                verifyRetries = 2,
                verifyDelayMs = 300L
            )
        )
        if (writeRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return writeRes as RouterResult<List<T>>
        }
        val applyRes = writer.applyFilter()
        if (applyRes !is RouterResult.Ok) {
            @Suppress("UNCHECKED_CAST")
            return applyRes as RouterResult<List<T>>
        }
        return reload()
    }

    private fun parseOptionalUpnpMappings(): List<UpnpMapping> {
        val res = session.execute(cmd = 202, method = "GET", timeoutSeconds = 3L).getOrNull() ?: return emptyList()
        val arr = res.optJSONArray("datas") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val port = obj.optString("port", obj.optString("extPort", "")).trim()
                val ip = obj.optString("mappingIp", obj.optString("ip", "")).trim()
                if (port.isNotEmpty() || ip.isNotEmpty()) {
                    add(
                        UpnpMapping(
                            protocol = obj.optString("protocol", "").trim(),
                            port = port,
                            mappingIp = ip,
                            mappingPort = obj.optString("mappingPort", obj.optString("intPort", "")).trim()
                        )
                    )
                }
            }
        }
    }

    private fun parsePortForwardRules(json: JSONObject): List<PortForwardRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    PortForwardRule(
                        port = obj.optString("port", "").trim(),
                        mappingPort = obj.optString("mappingPort", "").trim(),
                        mappingIp = obj.optString("mappingIp", "").trim(),
                        protocol = obj.optString("protocol", "TCP").ifBlank { "TCP" },
                        remark = obj.optString("remark", "").trim(),
                        enableRule = obj.optBoolean("enableRule", true),
                        ifName = obj.optString("ifName", "DEFAULT").ifBlank { "DEFAULT" }
                    )
                )
            }
        }
    }

    private fun parseIpPortFilterRules(json: JSONObject, defaultEnableLink: Boolean): List<IpPortFilterRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    IpPortFilterRule(
                        ip = obj.optString("ip", "").trim(),
                        destIp = obj.optString("destIp", "").trim(),
                        srcPort = obj.optString("srcPort", "").trim(),
                        destPort = obj.optString("destPort", "").trim(),
                        protocol = obj.optString("protocol", "TCP").ifBlank { "TCP" },
                        ippro = obj.optString("ippro", "IPV4").ifBlank { "IPV4" },
                        remark = obj.optString("remark", "").trim(),
                        enableRule = obj.optBoolean("enableRule", true),
                        enableLink = obj.optBoolean("enableLink", defaultEnableLink)
                    )
                )
            }
        }
    }

    private fun parsePortFilterRules(json: JSONObject, defaultEnableLink: Boolean): List<PortFilterRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val port = obj.optString("port", "").trim()
                if (port.isNotEmpty()) {
                    add(
                        PortFilterRule(
                            port = port,
                            protocol = obj.optString("protocol", "tcp").ifBlank { "tcp" },
                            ippro = obj.optString("ippro", "IPV4").ifBlank { "IPV4" },
                            remark = obj.optString("remark", "").trim(),
                            enableRule = obj.optBoolean("enableRule", true),
                            enableLink = obj.optBoolean("enableLink", defaultEnableLink)
                        )
                    )
                }
            }
        }
    }

    private fun parseUrlFilterRules(json: JSONObject, defaultEnableLink: Boolean): List<UrlFilterRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").trim()
                if (url.isNotEmpty()) {
                    add(
                        UrlFilterRule(
                            url = url,
                            ippro = obj.optString("ippro", "IPV4").ifBlank { "IPV4" },
                            remark = obj.optString("remark", "").trim(),
                            enableRule = obj.optBoolean("enableRule", true),
                            enableLink = obj.optBoolean("enableLink", defaultEnableLink)
                        )
                    )
                }
            }
        }
    }

    private fun parsePortRangePair(raw: String, separator: Char): Pair<Int, Int>? {
        val parts = raw.trim().split(separator, '-', ':').map { it.trim() }.filter { it.isNotEmpty() }
        return when (parts.size) {
            1 -> {
                val p = parts[0].toIntOrNull() ?: return null
                if (p !in 1..65535) null else p to p
            }
            2 -> {
                val start = parts[0].toIntOrNull() ?: return null
                val end = parts[1].toIntOrNull() ?: return null
                if (start !in 1..65535 || end !in 1..65535 || end <= start) null else start to end
            }
            else -> null
        }
    }

    /** Returns the first conflicting system port for [rule] from `cmd 400`, or `null` if clear. */
    fun findSystemPortConflict(
        rule: PortForwardRule,
        reservedPorts: Set<Int> = _systemPorts.value
    ): Int? {
        if (reservedPorts.isEmpty()) return null
        val ext = parsePortRangePair(rule.port, ':')
        val int = parsePortRangePair(rule.mappingPort, '-')
        if (ext != null) {
            reservedPorts.firstOrNull { it in ext.first..ext.second }?.let { return it }
        }
        if (int != null) {
            reservedPorts.firstOrNull { it in int.first..int.second }?.let { return it }
        }
        return null
    }

    private fun isValidIpOrDashRange(raw: String, isIpv6: Boolean): Boolean {
        val parts = raw.split('-').map { it.trim() }
        if (parts.size > 2 || parts.any { it.isEmpty() }) return false
        val valid = parts.all { if (isIpv6) isValidIpv6(it) else isValidIpv4(it) }
        if (!valid) return false
        if (parts.size == 2) {
            val a = ipBytes(parts[0]) ?: return false
            val b = ipBytes(parts[1]) ?: return false
            if (a.size != b.size) return false
            for (i in a.indices) {
                val ua = a[i].toInt() and 0xFF
                val ub = b[i].toInt() and 0xFF
                if (ua < ub) return true
                if (ua > ub) return false
            }
            return false
        }
        return true
    }

    private fun ipBytes(ip: String): ByteArray? =
        runCatching { java.net.InetAddress.getByName(ip).address }.getOrNull()

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { seg ->
            val n = seg.toIntOrNull()
            n != null && n in 0..255 && (seg == "0" || !seg.startsWith("0"))
        }
    }

    private fun isValidIpv6(ip: String): Boolean {
        if (!ip.contains(':')) return false
        return runCatching {
            java.net.InetAddress.getByName(ip) is java.net.Inet6Address
        }.getOrDefault(false)
    }

    private companion object {
        val URL_FILTER_REGEX = Regex(
            "^((http|ftp|rtsp|mms|https)?://)?(([0-9a-zA-Z_!~*'().&=+\$%-]+: )?[0-9a-zA-Z_!~*'().&=+\$%-]+@)?(([0-9a-zA-Z_!~*'()-]+\\.)*([0-9a-zA-Z][0-9a-zA-Z-]{0,61})?[0-9a-zA-Z]\\.[a-zA-Z]{2,6})(:[0-9]{1,4})?((/?)|(/[0-9a-zA-Z_!~*'().;?:@&=+\$,%#-]+)+/?)$"
        )
    }

    private suspend fun syncFilterTableLinkFlag(cmd: Int, enableLink: Boolean): RouterResult<Unit> {
        val getRes = session.execute(cmd = cmd, method = "GET", extra = mapOf("getfun" to true))
        val json = when (getRes) {
            is RouterResult.Ok -> getRes.data
            else -> return RouterResult.Ok(Unit)
        }
        val existing = json.optJSONArray("datas") ?: return RouterResult.Ok(Unit)
        if (existing.length() == 0) return RouterResult.Ok(Unit)
        val updated = JSONArray()
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            val copy = JSONObject(item.toString())
            copy.put("enableLink", enableLink)
            updated.put(copy)
        }
        return writer.write(
            WriteSpec(
                cmd = cmd,
                readCmd = cmd,
                readExtra = mapOf("getfun" to true),
                buildPayload = { mapOf("success" to true, "datas" to updated) },
                verify = { readBack ->
                    val arr = readBack.optJSONArray("datas")
                    arr != null && (arr.length() == 0 || arr.optJSONObject(0)?.optBoolean("enableLink") == enableLink)
                },
                parseResult = { Unit },
                verifyRetries = 2,
                verifyDelayMs = 300L
            )
        )
    }

    private fun parseFirewall(json: JSONObject): FirewallConfig = FirewallConfig(
        enabled = json.optString("fw_switch", "0") == "1",
        level = FirewallLevel.fromWire(json.optString("level", "low"))
    )

    private fun parseAlg(json: JSONObject): AlgSettings = AlgSettings(
        l2tp = json.optString("L2TPSW_Flag", "0") == "1",
        ipsec = json.optString("IPSecSW_Flag", "0") == "1",
        h323 = json.optString("H323SW_Flag", "0") == "1",
        rtsp = json.optString("RTSPSW_Flag", "0") == "1",
        sip = json.optString("SIPSW_Flag", "0") == "1",
        ftp = json.optString("FTPSW_Flag", "0") == "1",
        pptp = json.optString("PPTPSW_Flag", "0") == "1"
    )

    private fun parseAcceptAll(json: JSONObject?, index: Int, defaultVal: Boolean): Boolean {
        val arr = json?.optJSONArray("datas") ?: return defaultVal
        val obj = arr.optJSONObject(index) ?: arr.optJSONObject(0) ?: return defaultVal
        val raw = obj.opt("acceptAll") ?: return defaultVal
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> defaultVal
        }
    }

    private fun parseMacRules(json: JSONObject, defaultEnableLink: Boolean): List<MacFilterRule> {
        val arr = json.optJSONArray("datas") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawMac = obj.optString("mac", "").trim()
                val normMac = Phase71Validators.normalizeMac(rawMac) ?: rawMac
                if (normMac.isNotEmpty()) {
                    add(
                        MacFilterRule(
                            mac = normMac,
                            remark = obj.optString("remark", "").trim(),
                            ippro = obj.optString("ippro", "IPV4").ifBlank { "IPV4" },
                            enableRule = obj.optBoolean("enableRule", true),
                            enableLink = obj.optBoolean("enableLink", defaultEnableLink)
                        )
                    )
                }
            }
        }
    }

    private fun Boolean.toFlag(): String = if (this) "1" else "0"
}
