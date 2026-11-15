package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.AliasLookup
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.util.NetworkFormatters.roundTo2
import com.airtel.monitor.data.local.DailyUsageStore
import com.airtel.monitor.data.dto.formatDurationText
import com.airtel.monitor.data.dto.parseAlignmentSnapshot
import com.airtel.monitor.data.dto.parseCarriers
import com.airtel.monitor.data.dto.parseCellIdentity
import com.airtel.monitor.data.dto.parseCellularMetrics
import com.airtel.monitor.data.dto.parseDevices
import com.airtel.monitor.data.dto.parseHardwareMetrics
import com.airtel.monitor.data.dto.parseLatestChartRow
import com.airtel.monitor.data.dto.parseLinkDetails
import com.airtel.monitor.data.dto.parseMonthlyUsage
import com.airtel.monitor.data.dto.parseNetworkMode218
import com.airtel.monitor.data.dto.parseSignalHistory1018
import com.airtel.monitor.data.dto.parseSmsUnreadCount
import com.airtel.monitor.data.dto.parseWanBytes
import com.airtel.monitor.data.model.BandwidthPoint
import com.airtel.monitor.data.model.CellLockInfo
import com.airtel.monitor.data.model.ConnectionStatus
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.model.WanMetrics
import com.airtel.monitor.domain.AlignmentSnapshot
import com.airtel.monitor.domain.SignalHistoryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class StatusRepository(
    private val session: RouterSession,
    private val aliasLookup: AliasLookup,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dailyUsageStore: DailyUsageStore? = null,
    private val featureFlagRepository: FeatureFlagRepository? = null
) {
    private val _routerState = MutableStateFlow<RouterState?>(null)
    val routerState: StateFlow<RouterState?> = _routerState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _smsUnreadCount = MutableStateFlow(0)
    val smsUnreadCount: StateFlow<Int> = _smsUnreadCount.asStateFlow()

    private val _lastError = MutableStateFlow<RouterResult<Nothing>?>(null)
    val lastError: StateFlow<RouterResult<Nothing>?> = _lastError.asStateFlow()

    private val _lastNet205 = MutableStateFlow<JSONObject?>(null)
    val lastNet205: StateFlow<JSONObject?> = _lastNet205.asStateFlow()

    private val _history1018 = MutableStateFlow<List<JSONObject>>(emptyList())
    val history1018: StateFlow<List<JSONObject>> = _history1018.asStateFlow()

    private val _signalHistory = MutableStateFlow<List<SignalHistoryPoint>>(emptyList())
    val signalHistory: StateFlow<List<SignalHistoryPoint>> = _signalHistory.asStateFlow()

    private val _alignmentSnapshot = MutableStateFlow<AlignmentSnapshot?>(null)
    val alignmentSnapshot: StateFlow<AlignmentSnapshot?> = _alignmentSnapshot.asStateFlow()

    private var prevWanRxBytes: Long? = null
    private var prevWanTxBytes: Long? = null
    private var prevWanTimeMs: Long = 0

    private val history = Collections.synchronizedList(mutableListOf<BandwidthPoint>())
    private val maxHistory = 60

    private val liveSignalPoints = Collections.synchronizedList(mutableListOf<SignalHistoryPoint>())
    private var baselineHistory1018: List<SignalHistoryPoint> = emptyList()
    private var lastSignalSampleMs: Long = 0L

    private var lastSlowPollTimeMs: Long = 0
    private val slowIntervalMs = 20_000L
    private var cachedLatestChart: JSONObject = JSONObject()
    private var cachedCell160: JSONObject = JSONObject()
    private var cachedBand161: JSONObject = JSONObject()
    private var cachedNetMode218: JSONObject = JSONObject()
    private var cachedRouterInfo133: JSONObject = JSONObject()
    private var cachedDeviceInfo207: JSONObject = JSONObject()
    private var cachedTraffic337: JSONObject = JSONObject()
    private var cachedStatusBar394: JSONObject = JSONObject()

    var peakDlMbps: Double = 0.0
        private set
    var peakUlMbps: Double = 0.0
        private set

    fun resetState() {
        session.resetSession()
        prevWanRxBytes = null
        prevWanTxBytes = null
        prevWanTimeMs = 0
        lastSlowPollTimeMs = 0
        cachedLatestChart = JSONObject()
        cachedCell160 = JSONObject()
        cachedBand161 = JSONObject()
        cachedNetMode218 = JSONObject()
        cachedRouterInfo133 = JSONObject()
        cachedDeviceInfo207 = JSONObject()
        cachedTraffic337 = JSONObject()
        cachedStatusBar394 = JSONObject()
        history.clear()
        liveSignalPoints.clear()
        baselineHistory1018 = emptyList()
        lastSignalSampleMs = 0L
        _signalHistory.value = emptyList()
        _alignmentSnapshot.value = null
        peakDlMbps = 0.0
        peakUlMbps = 0.0
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _lastError.value = null
        featureFlagRepository?.reset()
    }

    fun updateCachedNetMode218(json: JSONObject) {
        if (json.has("flightMode")) {
            cachedNetMode218 = json
        }
    }

    fun updateCachedTraffic337(json: JSONObject) {
        if (json.length() > 1 && json.optBoolean("success", true)) {
            cachedTraffic337 = json
        }
    }

    fun markDisconnected() {
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun pollFast(forceSlow: Boolean = false): RouterResult<RouterState> {
        val nowMs = clock()

        val dashRes = session.execute(401)
        if (dashRes is RouterResult.NotLoggedIn) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _lastError.value = dashRes
            return dashRes
        }

        val sysRes = session.execute(113)
        if (sysRes is RouterResult.NotLoggedIn) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _lastError.value = sysRes
            return sysRes
        }

        val dash = dashRes.getOrNull() ?: JSONObject()
        val sysStatus = sysRes.getOrNull() ?: JSONObject()

        val hasValidResponse = (dash.length() > 0 && !dash.has("error")) ||
            (sysStatus.length() > 0 && !sysStatus.has("error"))
        if (!hasValidResponse) {
            val failure = when {
                dashRes is RouterResult.Unreachable -> dashRes
                sysRes is RouterResult.Unreachable -> sysRes
                dashRes is RouterResult.BadResponse -> dashRes
                sysRes is RouterResult.BadResponse -> sysRes
                else -> RouterResult.Unreachable("Router returned empty or invalid status")
            }
            _connectionStatus.value = ConnectionStatus.RECONNECTING
            _lastError.value = failure
            return failure
        }

        val net205 = session.execute(205).getOrNull() ?: JSONObject()
        if (net205.length() > 0) {
            _lastNet205.value = net205
        }
        val dhcp = session.execute(223).getOrNull() ?: JSONObject()
        val wifi5g = session.execute(225).getOrNull() ?: JSONObject()
        val wifi24g = session.execute(224).getOrNull() ?: JSONObject()

        refreshSlowData(nowMs, force = forceSlow || lastSlowPollTimeMs == 0L)
        val latestChart = cachedLatestChart

        val (rxKbps, txKbps) = computeWanSpeed(nowMs)
        val dlMbps = roundTo2((rxKbps * 8.0) / 1024.0)
        val ulMbps = roundTo2((txKbps * 8.0) / 1024.0)
        if (dlMbps > peakDlMbps) peakDlMbps = dlMbps
        if (ulMbps > peakUlMbps) peakUlMbps = ulMbps

        val devices = parseDevices(dhcp, dash, wifi5g, wifi24g, aliasLookup)
        val routerInfo133 = cachedRouterInfo133
        val cellular = parseCellularMetrics(
            sysStatus = sysStatus,
            dash = dash,
            net205 = net205,
            latestChart = latestChart,
            routerInfo133 = routerInfo133,
            statusBar394 = cachedStatusBar394
        )

        val carriersFrom205 = parseCarriers(net205)
        val carriersFrom160 = parseCarriers(cachedCell160)
        val carriersFromDash = parseCarriers(dash)
        val carriers = when {
            carriersFrom205.isNotEmpty() -> carriersFrom205
            carriersFrom160.isNotEmpty() -> carriersFrom160
            else -> carriersFromDash
        }

        val cell160 = cachedCell160
        val band161 = cachedBand161
        val mode218 = parseNetworkMode218(cachedNetMode218)

        fun firstPci(vararg candidates: String): String =
            candidates.map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "-" && it != "null" } ?: "-"

        fun tf(key: String, json: JSONObject): Boolean =
            json.optString(key, "") in listOf("1", "true", "True")

        val cellLock = CellLockInfo(
            servingPci4g = firstPci(net205.optString("PCI"), cell160.optString("PCI"), latestChart.optString("pci")),
            servingFreq4g = firstPci(net205.optString("FREQ"), cell160.optString("FREQ")),
            servingPci5g = firstPci(net205.optString("PCI_5G"), cell160.optString("PCI_5G"), latestChart.optString("nr_pci"), latestChart.optString("pci_5g")),
            servingFreq5g = firstPci(net205.optString("FREQ_5G"), cell160.optString("FREQ_5G")),
            currentBands4g = firstPci(net205.optString("currentband"), cell160.optString("current_band")),
            currentBand5g = firstPci(net205.optString("currentband_5g"), cell160.optString("current_band_5g")),
            lteLockEnabled = tf("lte_lock_sw", cell160),
            lteLockPci = cell160.optString("lte_lock_pci", "-").ifEmpty { "-" },
            lteLockFreq = cell160.optString("lte_lock_freq", "-").ifEmpty { "-" },
            nrLockEnabled = tf("nr_lock_sw", cell160),
            nrLockPci = cell160.optString("nr_lock_pci", "-").ifEmpty { "-" },
            nrLockFreq = cell160.optString("nr_lock_freq", "-").ifEmpty { "-" },
            bandLock4gEnabled = tf("band_4g_switch", band161),
            bandLock5gEnabled = tf("band_5g_switch", band161),
            mobileDataOn = mode218.mobileDataOn,
            flightModeOn = mode218.flightModeOn
        )

        val hardware = parseHardwareMetrics(latestChart, cachedDeviceInfo207)

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

        if (net205.length() > 0) {
            val align = parseAlignmentSnapshot(net205, carriers, nowMs)
            _alignmentSnapshot.value = align
            if (lastSignalSampleMs == 0L || (nowMs - lastSignalSampleMs) >= 10_000L) {
                if (align.rsrp4g != null || align.sinr4g != null || align.rsrp5g != null || align.sinr5g != null) {
                    lastSignalSampleMs = nowMs
                    liveSignalPoints.add(
                        SignalHistoryPoint(
                            timeLabel = timeFmt,
                            rsrp4g = align.rsrp4g,
                            sinr4g = align.sinr4g,
                            rsrp5g = align.rsrp5g,
                            sinr5g = align.sinr5g,
                            pci = cellLock.servingPci4g
                        )
                    )
                    while (liveSignalPoints.size > 60) {
                        liveSignalPoints.removeAt(0)
                    }
                    _signalHistory.value = (baselineHistory1018 + liveSignalPoints.toList()).takeLast(90)
                }
            }
        }

        val wan = WanMetrics(
            downloadKbps = rxKbps,
            uploadKbps = txKbps,
            downloadMbps = dlMbps,
            uploadMbps = ulMbps,
            dlFlowMb = net205.optString("flow_dl").ifEmpty { dash.optString("flow_dl", "-") },
            ulFlowMb = net205.optString("flow_ul").ifEmpty { dash.optString("flow_ul", "-") },
            totalFlowMb = net205.optString("mon_total_flow").ifEmpty { dash.optString("mon_total_flow", "-") },
            peakDlMbps = peakDlMbps,
            peakUlMbps = peakUlMbps
        )

        val wanIp = routerInfo133.optString("wan_ip", "").trim()
            .ifEmpty { "-" }
        val wanIpv6 = routerInfo133.optString("wan_ipv6_ip", "").trim()
            .ifEmpty { "-" }
        val onlineDuration = formatDurationText(
            net205.optString("onlineDuration").ifEmpty { net205.optString("onlineTime") }
        )
        val uptimeText = formatDurationText(
            cachedDeviceInfo207.optString("uptime").ifEmpty {
                routerInfo133.optString("uptime").ifEmpty { sysStatus.optString("uptime") }
            }
        )

        val monthlyUsage = parseMonthlyUsage(net205, dash, cachedTraffic337)
        dailyUsageStore?.recordSample(nowMs, monthlyUsage)

        val state = RouterState(
            timestamp = nowMs,
            timeFormatted = timeFmt,
            wan = wan,
            cellular = cellular,
            carriers = carriers,
            cellLock = cellLock,
            hardware = hardware,
            devices = devices,
            history = history.toList(),
            wanIp = wanIp,
            wanIpv6 = wanIpv6,
            onlineDurationText = onlineDuration,
            uptimeText = uptimeText,
            linkDetails = parseLinkDetails(net205, routerInfo133),
            cellIdentity = parseCellIdentity(net205, routerInfo133),
            monthlyUsage = monthlyUsage,
            networkModeInfo = mode218
        )
        _routerState.value = state
        _connectionStatus.value = ConnectionStatus.CONNECTED
        _lastError.value = null
        return RouterResult.Ok(state)
    }

    fun updateSmsUnreadCount(count: Int) {
        _smsUnreadCount.value = count.coerceAtLeast(0)
    }

    /** Alignment mode polls ONLY cmd 205 at 2s interval. */
    fun pollAlignment(): RouterResult<JSONObject> {
        val nowMs = clock()
        val res = session.execute(205)
        if (res is RouterResult.Ok) {
            val json = res.data
            _lastNet205.value = json
            val carriers = parseCarriers(json)
            _alignmentSnapshot.value = parseAlignmentSnapshot(json, carriers, nowMs)
            _connectionStatus.value = ConnectionStatus.CONNECTED
            _lastError.value = null
        } else if (res is RouterResult.NotLoggedIn) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _lastError.value = res
        } else if (res is RouterResult.Unreachable) {
            _connectionStatus.value = ConnectionStatus.RECONNECTING
            _lastError.value = res
        }
        return res
    }

    private fun computeWanSpeed(nowMs: Long): Pair<Double, Double> {
        val countersJson = session.execute(18).getOrNull() ?: return Pair(0.0, 0.0)
        val counters = parseWanBytes(countersJson) ?: return Pair(0.0, 0.0)

        val prevRx = prevWanRxBytes
        val prevTx = prevWanTxBytes
        val prevTime = prevWanTimeMs

        prevWanRxBytes = counters.rxBytes
        prevWanTxBytes = counters.txBytes
        prevWanTimeMs = nowMs

        if (prevRx == null || prevTx == null || prevTime <= 0) return Pair(0.0, 0.0)

        val dt = (nowMs - prevTime) / 1000.0
        if (dt <= 0.05) return Pair(0.0, 0.0)

        val dRx = counters.rxBytes - prevRx
        val dTx = counters.txBytes - prevTx
        if (dRx < 0 || dTx < 0) return Pair(0.0, 0.0)

        val rxKbps = (dRx / dt) / 1024.0
        val txKbps = (dTx / dt) / 1024.0
        return Pair(roundTo2(rxKbps), roundTo2(txKbps))
    }

    private fun refreshSlowData(nowMs: Long, force: Boolean) {
        if (!force && (nowMs - lastSlowPollTimeMs) < slowIntervalMs) return
        lastSlowPollTimeMs = nowMs

        session.execute(1018).getOrNull()?.let { statusChart ->
            val row = parseLatestChartRow(statusChart)
            if (row.length() > 0) cachedLatestChart = row
            val arr = statusChart.optJSONArray("device_info")
            if (arr != null && arr.length() > 0) {
                val list = ArrayList<JSONObject>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(it) }
                }
                _history1018.value = list
                baselineHistory1018 = parseSignalHistory1018(list, limit = 60)
                _signalHistory.value = (baselineHistory1018 + liveSignalPoints.toList()).takeLast(90)
            }
        }

        session.execute(133).getOrNull()?.let { info ->
            if (info.length() > 1 && info.optBoolean("success", true)) {
                cachedRouterInfo133 = info
            }
        }

        session.execute(207).getOrNull()?.let { dev ->
            if (dev.length() > 1 && dev.optBoolean("success", true)) {
                cachedDeviceInfo207 = dev
            }
        }

        session.execute(337).getOrNull()?.let { traffic ->
            if (traffic.length() > 1 && traffic.optBoolean("success", true)) {
                cachedTraffic337 = traffic
            }
        }

        session.execute(160).getOrNull()?.let { cell ->
            if (cell.optBoolean("success", true) && cell.length() > 1) {
                cachedCell160 = cell
            }
        }

        session.execute(161).getOrNull()?.let { band ->
            if (band.optBoolean("success", true) && band.length() > 1) {
                cachedBand161 = band
            }
        }

        session.execute(218).getOrNull()?.let { mode ->
            updateCachedNetMode218(mode)
        }

        session.execute(394).getOrNull()?.let { bar ->
            cachedStatusBar394 = bar
            _smsUnreadCount.value = parseSmsUnreadCount(bar)
        }

        featureFlagRepository?.ensureLoaded()
    }
}
