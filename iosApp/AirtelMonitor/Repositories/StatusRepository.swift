import Foundation

@MainActor
public final class StatusRepository: ObservableObject {
    private let session: RouterSession
    private let preferences: AppPreferencesStore
    private let ouiLookup: OuiVendorLookup
    private let clockMs: @Sendable () -> Int64

    @Published public private(set) var routerState: RouterState?
    @Published public private(set) var connectionStatus: ConnectionStatus = .connecting
    @Published public private(set) var lastErrorMessage: String?
    @Published public private(set) var isAuthError: Bool = false
    @Published public private(set) var signalHistory: [SignalHistoryPoint] = []
    @Published public private(set) var alignmentSnapshot: AlignmentSnapshot?

    private var prevRxBytes: Int64?
    private var prevTxBytes: Int64?
    private var prevSampleMs: Int64?
    private var peakDlMbps: Double = 0
    private var peakUlMbps: Double = 0
    private var bandwidthHistory: [BandwidthPoint] = []
    private var lastSlowPollMs: Int64 = 0
    private var lastSignalSampleMs: Int64 = 0
    private var cached133: [String: Any] = [:]
    private var cached207: [String: Any] = [:]
    private var cached337: [String: Any] = [:]
    private var unreadSmsOverride: Int?

    private static let maxBandwidthPoints = 60
    private static let maxSignalHistoryPoints = 60

    public init(
        session: RouterSession,
        preferences: AppPreferencesStore,
        ouiLookup: OuiVendorLookup = .empty,
        clockMs: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.session = session
        self.preferences = preferences
        self.ouiLookup = ouiLookup
        self.clockMs = clockMs
    }

    public func resetState() {
        routerState = nil
        connectionStatus = .connecting
        lastErrorMessage = nil
        isAuthError = false
        signalHistory = []
        alignmentSnapshot = nil
        prevRxBytes = nil
        prevTxBytes = nil
        prevSampleMs = nil
        peakDlMbps = 0
        peakUlMbps = 0
        bandwidthHistory = []
        lastSlowPollMs = 0
        lastSignalSampleMs = 0
        cached133 = [:]
        cached207 = [:]
        cached337 = [:]
        unreadSmsOverride = nil
    }

    public func setUnreadSmsCount(_ count: Int) {
        let clamped = max(0, count)
        unreadSmsOverride = clamped
        if let current = routerState {
            routerState = RouterState(
                timestampMs: current.timestampMs,
                timeFormatted: current.timeFormatted,
                wan: current.wan,
                cellular: current.cellular,
                cellLock: current.cellLock,
                hardware: current.hardware,
                devices: current.devices,
                history: current.history,
                carriers: current.carriers,
                linkDetails: current.linkDetails,
                cellIdentity: current.cellIdentity,
                signalHistory: current.signalHistory,
                monthlyUsage: current.monthlyUsage,
                networkModeSnapshot: current.networkModeSnapshot,
                unreadSmsCount: clamped,
                wanIp: current.wanIp,
                wanIpv6: current.wanIpv6,
                onlineDurationText: current.onlineDurationText,
                uptimeText: current.uptimeText
            )
        }
    }

    public func reEnrichDevicesFromPreferences() {
        guard let current = routerState else { return }
        let refreshed = current.devices.map { dev in
            let id = dev.mac.isEmpty ? dev.ip : dev.mac
            var copy = dev
            copy.hasCustomAlias = preferences.hasAlias(for: id)
            copy.alias = preferences.getAlias(for: id, fallback: dev.hostname)
            return copy
        }
        routerState = RouterState(
            timestampMs: current.timestampMs,
            timeFormatted: current.timeFormatted,
            wan: current.wan,
            cellular: current.cellular,
            cellLock: current.cellLock,
            hardware: current.hardware,
            devices: refreshed,
            history: current.history,
            carriers: current.carriers,
            linkDetails: current.linkDetails,
            cellIdentity: current.cellIdentity,
            signalHistory: current.signalHistory,
            monthlyUsage: current.monthlyUsage,
            networkModeSnapshot: current.networkModeSnapshot,
            unreadSmsCount: current.unreadSmsCount,
            wanIp: current.wanIp,
            wanIpv6: current.wanIpv6,
            onlineDurationText: current.onlineDurationText,
            uptimeText: current.uptimeText
        )
    }

    @discardableResult
    public func pollFast(forceSlow: Bool = false) async -> RouterResult<RouterState> {
        let res18 = await session.execute(cmd: 18, method: "GET")
        guard case .ok(let flow18) = res18 else {
            lastErrorMessage = res18.errorMessage
            if case .notLoggedIn = res18 {
                isAuthError = true
                connectionStatus = .disconnected
            } else {
                isAuthError = false
                connectionStatus = .disconnected
            }
            return res18.castFailure()
        }

        let res401 = await session.execute(cmd: 401, method: "GET")
        let dash401 = res401.getOrNull() ?? [:]
        let res113 = await session.execute(cmd: 113, method: "GET")
        let sys113 = res113.getOrNull() ?? [:]
        let res205 = await session.execute(cmd: 205, method: "GET")
        let net205 = res205.getOrNull() ?? [:]
        let res223 = await session.execute(cmd: 223, method: "GET")
        let dhcp223 = res223.getOrNull() ?? [:]
        let res224 = await session.execute(cmd: 224, method: "GET")
        let wifi24 = res224.getOrNull() ?? [:]
        let res225 = await session.execute(cmd: 225, method: "GET")
        let wifi5 = res225.getOrNull() ?? [:]
        let res1018 = await session.execute(cmd: 1018, method: "GET")
        let chart1018 = res1018.getOrNull() ?? [:]
        let res160 = await session.execute(cmd: 160, method: "GET")
        let lock160 = res160.getOrNull() ?? [:]
        let res161 = await session.execute(cmd: 161, method: "GET")
        let band161 = res161.getOrNull() ?? [:]
        let res218 = await session.execute(cmd: 218, method: "GET")
        let mode218 = res218.getOrNull() ?? [:]
        let res394 = await session.execute(cmd: 394, method: "GET")
        let bar394 = res394.getOrNull() ?? [:]

        let nowMs = clockMs()
        if forceSlow || lastSlowPollMs == 0 || (nowMs - lastSlowPollMs) >= 20_000 {
            lastSlowPollMs = nowMs
            if case .ok(let d133) = await session.execute(cmd: 133, method: "GET") { cached133 = d133 }
            if case .ok(let d207) = await session.execute(cmd: 207, method: "GET") { cached207 = d207 }
            if case .ok(let d337) = await session.execute(cmd: 337, method: "GET") { cached337 = d337 }
        }

        let rxBytes = Int64(CodecHelpers.firstNonBlank(flow18, keys: ["rxBytes", "rx_bytes", "dl_bytes"], defaultValue: "")) ?? 0
        let txBytes = Int64(CodecHelpers.firstNonBlank(flow18, keys: ["txBytes", "tx_bytes", "ul_bytes"], defaultValue: "")) ?? 0

        var dlKbps = 0.0
        var ulKbps = 0.0
        if let prevRx = prevRxBytes, let prevTx = prevTxBytes, let prevMs = prevSampleMs, nowMs > prevMs {
            let dtSec = max(0.25, Double(nowMs - prevMs) / 1000.0)
            if rxBytes >= prevRx { dlKbps = (Double(rxBytes - prevRx) * 8.0 / dtSec) / 1000.0 }
            if txBytes >= prevTx { ulKbps = (Double(txBytes - prevTx) * 8.0 / dtSec) / 1000.0 }
        }
        prevRxBytes = rxBytes
        prevTxBytes = txBytes
        prevSampleMs = nowMs

        let dlMbps = dlKbps / 1000.0
        let ulMbps = ulKbps / 1000.0
        peakDlMbps = max(peakDlMbps, dlMbps)
        peakUlMbps = max(peakUlMbps, ulMbps)

        let timeFormatted = Self.timeLabel(forMs: nowMs)
        bandwidthHistory.append(BandwidthPoint(
            timeFormatted: timeFormatted,
            downloadMbps: dlMbps,
            uploadMbps: ulMbps,
            rxKbps: dlKbps,
            txKbps: ulKbps
        ))
        if bandwidthHistory.count > Self.maxBandwidthPoints {
            bandwidthHistory.removeFirst(bandwidthHistory.count - Self.maxBandwidthPoints)
        }

        let flowDlNum = CodecHelpers.doubleVal(CodecHelpers.str(net205, "flow_dl"))
        let flowUlNum = CodecHelpers.doubleVal(CodecHelpers.str(net205, "flow_ul"))
        let wanMetrics = WanMetrics(
            downloadKbps: dlKbps,
            uploadKbps: ulKbps,
            downloadMbps: dlMbps,
            uploadMbps: ulMbps,
            dlFlowMb: flowDlNum.map { Phase1Parsers.formatDataSizeMb($0) } ?? "-",
            ulFlowMb: flowUlNum.map { Phase1Parsers.formatDataSizeMb($0) } ?? "-",
            totalFlowMb: (flowDlNum != nil || flowUlNum != nil)
                ? Phase1Parsers.formatDataSizeMb((flowDlNum ?? 0) + (flowUlNum ?? 0))
                : "-",
            peakDlMbps: peakDlMbps,
            peakUlMbps: peakUlMbps
        )

        let chartRow = RouterParsers.parseLatestChartRow(chart1018)
        let cellular = RouterParsers.parseCellularMetrics(
            net205: net205,
            dash401: dash401,
            sysStatus113: sys113,
            statusBar394: bar394,
            routerInfo133: cached133,
            deviceInfo207: cached207,
            chartRow: chartRow
        )
        let hardware = RouterParsers.parseHardwareMetrics(chartRow: chartRow, deviceInfo207: cached207)
        let carriers = CarrierParser.parseCarriers(net205)
        let linkDetails = Phase1Parsers.parseLinkDetails(net205, routerInfo133: cached133)
        let cellIdentity = Phase1Parsers.parseCellIdentity(net205, routerInfo133: cached133)
        let networkModeSnap = RouterParsers.parseNetworkModeSnapshot(mode218, statusBar394: bar394)
        let bandLock = RouterParsers.parseBandLock(band161)
        let parsedCell = RouterParsers.parseCellLock(lock160)
        let cellLock = CellLockInfo(
            servingPci4g: CodecHelpers.firstNonBlank(lock160, keys: ["PCI"], defaultValue: CodecHelpers.firstNonBlank(net205, keys: ["PCI"])),
            servingFreq4g: CodecHelpers.firstNonBlank(lock160, keys: ["FREQ"], defaultValue: CodecHelpers.firstNonBlank(net205, keys: ["FREQ"])),
            servingPci5g: CodecHelpers.firstNonBlank(lock160, keys: ["PCI_5G"], defaultValue: CodecHelpers.firstNonBlank(net205, keys: ["PCI_5G"])),
            servingFreq5g: CodecHelpers.firstNonBlank(lock160, keys: ["FREQ_5G"], defaultValue: CodecHelpers.firstNonBlank(net205, keys: ["FREQ_5G"])),
            currentBands4g: CodecHelpers.firstNonBlank(lock160, keys: ["current_band"], defaultValue: CodecHelpers.firstNonBlank(net205, keys: ["currentband"])),
            currentBand5g: CodecHelpers.firstNonBlank(lock160, keys: ["current_band_5g"], defaultValue: CodecHelpers.firstNonBlank(net205, keys: ["currentband_5g"])),
            lteLockEnabled: parsedCell.lteEnabled,
            lteLockPci: parsedCell.lteCells.map(\.pci).joined(separator: ","),
            lteLockFreq: parsedCell.lteCells.map(\.earfcn).joined(separator: ","),
            nrLockEnabled: parsedCell.nrEnabled,
            nrLockPci: parsedCell.nrCells.map(\.pci).joined(separator: ","),
            nrLockFreq: parsedCell.nrCells.map(\.arfcn).joined(separator: ","),
            bandLock4gEnabled: bandLock.lock4gEnabled,
            bandLock5gEnabled: bandLock.lock5gEnabled,
            mobileDataOn: networkModeSnap.mobileDataOn,
            flightModeOn: networkModeSnap.flightModeOn
        )

        let devices = RouterParsers.parseDevices(
            dhcp: dhcp223,
            wifi5g: wifi5,
            wifi24g: wifi24,
            aliasLookup: { id, fallback in preferences.getAlias(for: id, fallback: fallback) },
            hasAliasLookup: { id in preferences.hasAlias(for: id) },
            ouiLookup: ouiLookup
        )

        let nowDate = Date(timeIntervalSince1970: TimeInterval(nowMs) / 1000.0)
        let usage = Phase1Parsers.parseMonthlyUsage(
            net205: net205,
            dash401: dash401,
            traffic337: cached337,
            date: nowDate
        )
        if usage.usedMb > 0 {
            preferences.recordDailyUsageSample(monthlyUsedMb: usage.usedMb, date: nowDate)
        }

        // Combine cmd 1018 chart points + 10s-sampled live cmd 205 point
        let raw1018Rows = (chart1018["device_info"] as? [[String: Any]]) ?? []
        var mergedHistory = Phase1Parsers.parseSignalHistory1018(raw1018Rows)
        if lastSignalSampleMs == 0 || (nowMs - lastSignalSampleMs) >= 10_000 {
            lastSignalSampleMs = nowMs
            let livePoint = SignalHistoryPoint(
                timeLabel: timeFormatted,
                rsrp4g: CodecHelpers.doubleVal(cellular.rsrp4g),
                sinr4g: CodecHelpers.doubleVal(cellular.sinr4g),
                rsrp5g: CodecHelpers.doubleVal(cellular.rsrp5g),
                sinr5g: CodecHelpers.doubleVal(cellular.sinr5g),
                pci: carriers.first?.pci ?? "-"
            )
            if livePoint.rsrp4g != nil || livePoint.sinr4g != nil || livePoint.rsrp5g != nil || livePoint.sinr5g != nil {
                mergedHistory.append(livePoint)
            }
        } else if let prevLive = signalHistory.last, !mergedHistory.contains(where: { $0.timeLabel == prevLive.timeLabel }) {
            mergedHistory.append(prevLive)
        }
        if mergedHistory.count > Self.maxSignalHistoryPoints {
            mergedHistory = Array(mergedHistory.suffix(Self.maxSignalHistoryPoints))
        }
        self.signalHistory = mergedHistory

        let unread = unreadSmsOverride ?? RouterParsers.parseSmsUnreadCount(bar394)
        unreadSmsOverride = nil

        let wanIp = CodecHelpers.firstNonBlank(cached133, keys: ["wan_ip"], defaultValue: CodecHelpers.firstNonBlank(dash401, keys: ["wan_ip"], defaultValue: "--"))
        let wanIpv6 = CodecHelpers.str(cached133, "wan_ipv6_ip")
        let onlineDuration = Phase1Parsers.formatDurationText(CodecHelpers.str(net205, "onlineDuration"))
        let uptimeText = Phase1Parsers.formatDurationText(
            CodecHelpers.nonBlankStr(cached207, "uptime") ?? CodecHelpers.nonBlankStr(cached133, "uptime")
        )

        let state = RouterState(
            timestampMs: nowMs,
            timeFormatted: timeFormatted,
            wan: wanMetrics,
            cellular: cellular,
            cellLock: cellLock,
            hardware: hardware,
            devices: devices,
            history: bandwidthHistory,
            carriers: carriers,
            linkDetails: linkDetails,
            cellIdentity: cellIdentity,
            signalHistory: mergedHistory,
            monthlyUsage: usage,
            networkModeSnapshot: networkModeSnap,
            unreadSmsCount: unread,
            wanIp: wanIp,
            wanIpv6: wanIpv6,
            onlineDurationText: onlineDuration,
            uptimeText: uptimeText
        )
        self.routerState = state
        self.connectionStatus = .connected
        self.lastErrorMessage = nil
        self.isAuthError = false
        return .ok(state)
    }

    @discardableResult
    public func pollAlignment() async -> RouterResult<AlignmentSnapshot> {
        let res = await session.execute(cmd: 205, method: "GET")
        guard case .ok(let net205) = res else {
            return res.castFailure()
        }
        let nowMs = clockMs()
        let carriers = CarrierParser.parseCarriers(net205)
        let snap = Phase1Parsers.parseAlignmentSnapshot(net205, carriers: carriers, timestampMs: nowMs)
        self.alignmentSnapshot = snap
        return .ok(snap)
    }

    private static func timeLabel(forMs ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f.string(from: date)
    }
}
