import Foundation

public enum NetworkToolMode: String, CaseIterable, Identifiable, Sendable {
    case ping
    case traceroute

    public var id: String { rawValue }
    public var subcmd: Int { self == .ping ? 0 : 2 }
    public var label: String { self == .ping ? "Ping" : "Traceroute" }
}

@MainActor
public final class SystemRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter
    private let preferences: AppPreferencesStore

    @Published public private(set) var aboutInfo = RouterAboutInfo()
    @Published public private(set) var logs: [LogEntry] = []
    @Published public private(set) var logSettings = LogSettings()
    @Published public private(set) var scheduledReboot = ScheduledRebootConfig()
    @Published public private(set) var timeConfig = RouterTimeConfig()
    @Published public private(set) var wanLinkDetect = WanLinkDetectConfig()
    @Published public private(set) var lastSelfCheck: SelfCheckResult?

    public init(session: RouterSession, writer: SettingsWriter, preferences: AppPreferencesStore) {
        self.session = session
        self.writer = writer
        self.preferences = preferences
    }

    @discardableResult
    public func loadAboutInfo() async -> RouterResult<RouterAboutInfo> {
        let r207 = await session.execute(cmd: 207, method: "GET")
        let r133 = await session.execute(cmd: 133, method: "GET")
        let r1018 = await session.execute(cmd: 1018, method: "GET")
        if case .notLoggedIn(let msg) = r207 { return .notLoggedIn(msg) }
        guard r207.isOk || r133.isOk else { return r207.castFailure() }

        let j207 = r207.getOrNull() ?? [:]
        let j133 = r133.getOrNull() ?? [:]
        let row1018 = r1018.getOrNull().flatMap { RouterParsers.parseLatestChartRow($0) } ?? [:]

        let hw = RouterParsers.parseHardwareMetrics(chartRow: row1018, deviceInfo207: j207)
        let rawUptime = CodecHelpers.firstNonBlank(j133, keys: ["up_time", "uptime", "sys_uptime"], defaultValue: "")
        let formattedUptime = rawUptime.isEmpty
            ? "-"
            : Phase1Parsers.formatDurationText(rawUptime)

        let rawRates = CodecHelpers.firstNonBlank(j207, keys: ["negotiatton_rate", "negotiation_rate", "lan_rate"], defaultValue: "")
        let rates = Self.parseLinkRates(rawRates)

        let parsed = RouterAboutInfo(
            oduModel: CodecHelpers.firstNonBlank(j207, keys: ["board_type", "device_model", "model"], defaultValue: "ZLT X17M"),
            iduModel: CodecHelpers.firstNonBlank(j207, keys: ["idu_model", "product_model"], defaultValue: "-"),
            hardwareVersion: CodecHelpers.firstNonBlank(j207, keys: ["hwversion", "hw_version"], defaultValue: "-"),
            firmwareVersion: CodecHelpers.firstNonBlank(j207, keys: ["version", "swversion", "firmware_version"], defaultValue: "-"),
            buildDate: CodecHelpers.firstNonBlank(j207, keys: ["build_time", "build_date"], defaultValue: "-"),
            uptimeText: formattedUptime,
            cpuUsage: "\(Int(hw.cpuUsage.rounded()))%",
            temperature: hw.temperature,
            memoryText: hw.memory,
            imei: CodecHelpers.firstNonBlank(j207, keys: ["imei", "IMEI"], defaultValue: "-"),
            imsi: CodecHelpers.firstNonBlank(j207, keys: ["imsi", "IMSI"], defaultValue: "-"),
            iccid: CodecHelpers.firstNonBlank(j207, keys: ["iccid", "ICCID"], defaultValue: "-"),
            serialNumber: CodecHelpers.firstNonBlank(j207, keys: ["device_sn", "sn", "serial_number"], defaultValue: "-"),
            msisdn: CodecHelpers.firstNonBlank(j207, keys: ["msisdn", "phone_num"], defaultValue: "-"),
            eid: CodecHelpers.firstNonBlank(j207, keys: ["eid", "EID"], defaultValue: "-"),
            iduMac: CodecHelpers.firstNonBlank(j207, keys: ["idu_mac", "wan_mac"], defaultValue: "-"),
            lanMac: CodecHelpers.firstNonBlank(j207, keys: ["lan_mac", "mac"], defaultValue: "-"),
            linkRates: rates
        )
        self.aboutInfo = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func loadSystemLogs() async -> RouterResult<[LogEntry]> {
        let res = await session.executeRaw(cmd: 17, method: "GET", timeoutSeconds: 10.0)
        guard case .ok(let raw) = res else { return res.castFailure() }
        let entries = Self.parseSystemLogEntries(raw)
        self.logs = entries
        return .ok(entries)
    }

    @discardableResult
    public func clearSystemLogs() async -> RouterResult<Unit> {
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 17,
                buildPayload: { _ in ["subcmd": 1] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true,
                postAccepted: { _ in true }
            )
        )
        if res.isOk {
            self.logs = []
        }
        return res
    }

    @discardableResult
    public func loadLogSettings() async -> RouterResult<LogSettings> {
        let r322 = await session.execute(cmd: 322, method: "GET")
        if case .notLoggedIn(let msg) = r322 { return .notLoggedIn(msg) }
        guard case .ok(let j322) = r322 else { return r322.castFailure() }

        let sizeBytes = Int(CodecHelpers.str(j322, "logSize", fallback: "200000")) ?? 200_000
        let parsed = LogSettings(
            saveLogEnabled: sizeBytes > 0,
            logSizeKb: max(1, sizeBytes / 1000),
            logGrade: LogGrade.fromRaw(CodecHelpers.str(j322, "logGrade", fallback: "0"))
        )
        self.logSettings = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func saveLogSettings(logSizeKb: Int, logGrade: LogGrade) async -> RouterResult<LogSettings> {
        if let err = SensitiveFormatters.validateLogSizeKb("\(logSizeKb)") {
            return .badResponse(message: err, rawBody: "")
        }
        let targetSizeBytes = logSizeKb * 1000
        let res = await writer.write(
            WriteSpec<LogSettings>(
                cmd: 322,
                buildPayload: { _ in
                    ["logGrade": logGrade.rawValue, "logSize": targetSizeBytes]
                },
                verify: { rb in
                    let pSize = Int(CodecHelpers.str(rb, "logSize", fallback: "0")) ?? 0
                    let pGrade = LogGrade.fromRaw(CodecHelpers.str(rb, "logGrade", fallback: "0"))
                    return pSize == targetSizeBytes && pGrade == logGrade
                },
                parseResult: { rb in
                    let pSize = Int(CodecHelpers.str(rb, "logSize", fallback: "200000")) ?? 200_000
                    return LogSettings(
                        saveLogEnabled: pSize > 0,
                        logSizeKb: max(1, pSize / 1000),
                        logGrade: LogGrade.fromRaw(CodecHelpers.str(rb, "logGrade", fallback: "0"))
                    )
                },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let saved) = res { self.logSettings = saved }
        return res
    }

    @discardableResult
    public func loadScheduledReboot() async -> RouterResult<ScheduledRebootConfig> {
        let res = await session.execute(cmd: 263, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let parsed = Self.parseScheduledReboot(json)
        self.scheduledReboot = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func saveScheduledReboot(
        enabled: Bool,
        hour: Int,
        minute: Int,
        weekDays: String
    ) async -> RouterResult<ScheduledRebootConfig> {
        let cleanDays = weekDays.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if enabled && cleanDays.isEmpty {
            return .badResponse(message: "Select at least one day of the week", rawBody: "")
        }
        let hh = String(format: "%02d", max(0, min(23, hour)))
        let mm = String(format: "%02d", max(0, min(59, minute)))
        let daysStr = enabled ? cleanDays.joined(separator: ",") : "0"

        let res = await writer.write(
            WriteSpec<ScheduledRebootConfig>(
                cmd: 263,
                buildPayload: { _ in
                    if enabled {
                        return [
                            "timrRestartSwitch": "1",
                            "restartHour": hh,
                            "restartMinute": mm,
                            "restartSecond": "00",
                            "restartWeek": daysStr
                        ]
                    } else {
                        return ["timrRestartSwitch": "0"]
                    }
                },
                verify: { rb in
                    let p = Self.parseScheduledReboot(rb)
                    return p.enabled == enabled && (!enabled || (p.timeHhMm == "\(hh):\(mm)" && p.weekDays == daysStr))
                },
                parseResult: { Self.parseScheduledReboot($0) },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let saved) = res { self.scheduledReboot = saved }
        return res
    }

    @discardableResult
    public func loadTimeConfig() async -> RouterResult<RouterTimeConfig> {
        let res = await session.execute(cmd: 11, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let parsed = Self.parseTimeConfig(json)
        self.timeConfig = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func saveTimeConfig(
        timezone: String,
        ntpEnabled: Bool,
        server1: String,
        server2: String,
        manualDate: Date = Date()
    ) async -> RouterResult<RouterTimeConfig> {
        let cleanTz = Phase71Validators.stripTimezoneSuffix(timezone)
        let s1 = server1.trimmingCharacters(in: .whitespacesAndNewlines)
        let s2 = server2.trimmingCharacters(in: .whitespacesAndNewlines)
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "MMddHHmmyyyy"
        let dtPayload = df.string(from: manualDate)

        let res = await writer.write(
            WriteSpec<RouterTimeConfig>(
                cmd: 11,
                buildPayload: { cur in
                    var map: [String: Any] = [
                        "timezone": cleanTz,
                        "ntpSwitch": ntpEnabled ? "1" : "0",
                        "timeServer": s1,
                        "timeServer2": s2,
                        "datetime": dtPayload,
                        "success": true
                    ]
                    if let s3 = CodecHelpers.nonBlankStr(cur, "timeServer3") { map["timeServer3"] = s3 }
                    if let s4 = CodecHelpers.nonBlankStr(cur, "timeServer4") { map["timeServer4"] = s4 }
                    return map
                },
                verify: { rb in
                    let p = Self.parseTimeConfig(rb)
                    return p.ntpEnabled == ntpEnabled && p.server1 == s1 && p.timezone == cleanTz
                },
                parseResult: { Self.parseTimeConfig($0) },
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let saved) = res { self.timeConfig = saved }
        return res
    }

    @discardableResult
    public func syncClientTime(now: Date = Date()) async -> RouterResult<Unit> {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let clientTime = df.string(from: now)

        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 321,
                buildPayload: { _ in ["clientTime": clientTime] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        if res.isOk {
            _ = await loadTimeConfig()
        }
        return res
    }

    @discardableResult
    public func loadWanLinkDetect() async -> RouterResult<WanLinkDetectConfig> {
        let res = await session.execute(cmd: 336, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let parsed = Self.parseWanLinkDetect(json)
        self.wanLinkDetect = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func saveWanLinkDetect(
        _ next: WanLinkDetectConfig,
        flags: RouterFeatureFlags
    ) async -> RouterResult<WanLinkDetectConfig> {
        if next.action == "3" && !flags.wanDetectRescanAvailable {
            return .badResponse(message: "Network rescan action is not permitted on this router profile", rawBody: "")
        }
        if next.checkTimeSec < 1 || next.checkTimeSec > 1800 {
            return .badResponse(message: "Check interval must be 1–1800 seconds", rawBody: "")
        }
        if next.enabled && (next.action == "1" || next.action == "3") && (next.rebootWaitMinutes * 60 < next.checkTimeSec) {
            return .badResponse(message: "Wait before action (\(next.rebootWaitMinutes)m) must be >= check interval (\(next.checkTimeSec)s)", rawBody: "")
        }
        if next.mode == "2" || next.mode == "3" {
            // Ping/NTP/DNS probe checks: at least one target if mode requires hosts
        } else {
            let ip1 = next.ip1.trimmingCharacters(in: .whitespacesAndNewlines)
            if !ip1.isEmpty, let err = Phase71Validators.validateFilterUrl(ip1) {
                if !NetworkFormatters.isIpv4(ip1) { return .badResponse(message: err, rawBody: "") }
            }
        }

        let res = await writer.write(
            WriteSpec<WanLinkDetectConfig>(
                cmd: 336,
                buildPayload: { cur in
                    var map: [String: Any] = [
                        "wanLinkDetectSwitch": next.enabled ? 1 : 0,
                        "wanLinkDetectCheckTime": next.checkTimeSec,
                        "checkWanLinkDetectMode": Int(next.mode) ?? 0,
                        "wanLinkDetectIP1": next.ip1,
                        "wanLinkDetectIP2": next.ip2,
                        "LinkDetectAction": next.action,
                        "reboot_wait_time": "\(next.rebootWaitMinutes)",
                        "dial_wait_time": "\(next.dialWaitSec)"
                    ]
                    if let ip3 = CodecHelpers.nonBlankStr(cur, "wanLinkDetectIP3") {
                        map["wanLinkDetectIP3"] = ip3
                    }
                    return map
                },
                verify: { rb in
                    let p = Self.parseWanLinkDetect(rb)
                    return p.enabled == next.enabled &&
                        p.checkTimeSec == next.checkTimeSec &&
                        p.action == next.action
                },
                parseResult: { Self.parseWanLinkDetect($0) },
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true
            )
        )
        if case .ok(let saved) = res { self.wanLinkDetect = saved }
        return res
    }

    @discardableResult
    public func changeAdminPassword(
        oldPassword: String,
        newPassword: String,
        confirmPassword: String
    ) async -> RouterResult<Unit> {
        if let err = SensitiveFormatters.validateRouterPassword(
            oldPassword: oldPassword,
            newPassword: newPassword,
            confirmPassword: confirmPassword
        ) {
            return .badResponse(message: err, rawBody: "")
        }

        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 102,
                buildPayload: { _ in
                    [
                        "subcmd": 3,
                        "old_passwd": CodecHelpers.encodeBase64Utf8(oldPassword),
                        "newPasswd": CodecHelpers.encodeBase64Utf8(newPassword)
                    ]
                },
                verifyPost: { json in
                    let msg = CodecHelpers.str(json, "message")
                    if msg == "0" || json["success"] as? Bool == true {
                        return nil
                    }
                    return .badResponse(message: "Router rejected password change (check current password)", rawBody: "")
                },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        if res.isOk {
            let creds = preferences.currentCredentials
            preferences.updateConnectionSettings(host: creds.host, username: creds.username, password: newPassword)
            await session.resetSession()
        }
        return res
    }

    @discardableResult
    public func rebootRouter() async -> RouterResult<Unit> {
        let res = await session.execute(
            cmd: 99,
            method: "POST",
            timeoutSeconds: 10.0,
            allowEmptyReply: true,
            retryOnNetworkFailure: false
        )
        if res.isOk {
            await session.resetSession()
            return .ok(Unit())
        }
        return res.castFailure()
    }

    @discardableResult
    public func factoryReset() async -> RouterResult<Unit> {
        let res = await session.execute(
            cmd: 112,
            method: "POST",
            timeoutSeconds: 15.0,
            allowEmptyReply: true,
            retryOnNetworkFailure: false
        )
        if res.isOk {
            await session.resetSession()
            return .ok(Unit())
        }
        return res.castFailure()
    }

    @discardableResult
    public func runSelfCheck() async -> RouterResult<SelfCheckResult> {
        let res = await session.execute(
            cmd: 484,
            method: "GET",
            timeoutSeconds: 15.0,
            retryOnNetworkFailure: false
        )
        guard case .ok(let json) = res else { return res.castFailure() }

        let skipKeys: Set<String> = ["cmd", "method", "sessionId", "success"]
        var items: [(key: String, value: String)] = []
        for key in json.keys.sorted() where !skipKeys.contains(key) {
            let v = CodecHelpers.str(json, key)
            if !v.isEmpty {
                items.append((key: key, value: v))
            }
        }
        let overall = CodecHelpers.firstNonBlank(json, keys: ["check_result", "status", "result"], defaultValue: "")
        let passed = overall.lowercased() == "ok" || overall == "0" || overall.lowercased() == "pass" || (json["success"] as? Bool == true && overall.isEmpty)
        let result = SelfCheckResult(passed: passed, details: items)
        self.lastSelfCheck = result
        return .ok(result)
    }

    @discardableResult
    public func startNetworkTool(
        mode: NetworkToolMode,
        target: String,
        pingCount: Int = 4,
        tracePort: Int? = nil
    ) async -> RouterResult<Unit> {
        let cleanTarget = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTarget.isEmpty else {
            return .badResponse(message: "Enter a hostname or IP address", rawBody: "")
        }
        if let err = Phase71Validators.validateFilterUrl(cleanTarget), !NetworkFormatters.isIpv4(cleanTarget) {
            return .badResponse(message: err, rawBody: "")
        }

        var extra: [String: Any] = [
            "subcmd": mode.subcmd,
            "url": cleanTarget,
            "wan_index": ""
        ]
        if mode == .ping {
            extra["pingTimes"] = max(1, min(100, pingCount))
        } else {
            extra["stopped"] = "0"
            extra["port"] = tracePort ?? -1
        }

        let res = await session.execute(
            cmd: 168,
            method: "POST",
            extra: extra,
            timeoutSeconds: 10.0,
            allowEmptyReply: true,
            retryOnNetworkFailure: false
        )
        return res.isOk ? .ok(Unit()) : res.castFailure()
    }

    public func readNetworkToolOutput(mode: NetworkToolMode) async -> RouterResult<String> {
        let path = mode == .ping ? "/tmp/tzwww/pingrt" : "/tmp/tzwww/tracepathrt"
        let res = await session.executeRaw(
            cmd: 204,
            method: "POST",
            extra: ["url": path, "subcmd": mode.subcmd],
            timeoutSeconds: 5.0
        )
        return res
    }

    @discardableResult
    public func stopNetworkTool(mode: NetworkToolMode) async -> RouterResult<Unit> {
        var extra: [String: Any] = ["subcmd": mode.subcmd, "url": "", "wan_index": ""]
        if mode == .ping {
            extra["pingTimes"] = 0
        } else {
            extra["stopped"] = "1"
            extra["port"] = -1
        }
        let res = await session.execute(
            cmd: 168,
            method: "POST",
            extra: extra,
            timeoutSeconds: 5.0,
            allowEmptyReply: true,
            retryOnNetworkFailure: false
        )
        return res.isOk ? .ok(Unit()) : res.castFailure()
    }

    // MARK: - Parsers

    public static func parseLinkRates(_ raw: String) -> [String] {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean != "-" else { return [] }
        return clean
            .split(whereSeparator: { $0 == ";" || $0 == "," || $0 == "\n" })
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    public static func parseSystemLogEntries(_ raw: String) -> [LogEntry] {
        let lines = raw
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let parsed: [LogEntry] = lines.compactMap { line in
            // Handle common formats: "[2026-01-01 12:00:00][TYPE][LEVEL] message" or plain text
            if line.hasPrefix("{") || line.hasPrefix("{\"cmd\"") {
                return nil
            }
            let parts = line.split(separator: " ", maxSplits: 3, omittingEmptySubsequences: true).map(String.init)
            if parts.count >= 4, parts[0].contains("-") && parts[1].contains(":") {
                return LogEntry(
                    time: "\(parts[0]) \(parts[1])",
                    type: parts[2].trimmingCharacters(in: CharacterSet(charactersIn: "[]")),
                    level: "",
                    message: parts[3]
                )
            }
            return LogEntry(message: line)
        }
        return parsed.reversed()
    }

    public static func parseScheduledReboot(_ json: [String: Any]) -> ScheduledRebootConfig {
        let enabled = CodecHelpers.str(json, "timrRestartSwitch") == "1"
        let hh = CodecHelpers.str(json, "restartHour", fallback: "03")
        let mm = CodecHelpers.str(json, "restartMinute", fallback: "00")
        let hPadded = hh.count == 1 ? "0\(hh)" : hh
        let mPadded = mm.count == 1 ? "0\(mm)" : mm
        let week = CodecHelpers.str(json, "restartWeek", fallback: "0")
        return ScheduledRebootConfig(
            enabled: enabled,
            timeHhMm: "\(hPadded):\(mPadded)",
            weekDays: week.isEmpty ? "0" : week
        )
    }

    public static func parseTimeConfig(_ json: [String: Any]) -> RouterTimeConfig {
        RouterTimeConfig(
            currentSystemTime: CodecHelpers.firstNonBlank(json, keys: ["systime", "current_time", "sys_time"], defaultValue: "-"),
            timezone: Phase71Validators.stripTimezoneSuffix(CodecHelpers.str(json, "timezone", fallback: "UTC+1")),
            ntpEnabled: CodecHelpers.str(json, "ntpSwitch", fallback: "1") == "1",
            server1: CodecHelpers.str(json, "timeServer"),
            server2: CodecHelpers.str(json, "timeServer2")
        )
    }

    public static func parseWanLinkDetect(_ json: [String: Any]) -> WanLinkDetectConfig {
        let wanMode = CodecHelpers.firstNonBlank(json, keys: ["wan_mode", "networkMode"], defaultValue: "1")
        let swRaw = CodecHelpers.str(json, "wanLinkDetectSwitch", fallback: "0")
        return WanLinkDetectConfig(
            enabled: swRaw == "1" || swRaw.lowercased() == "true",
            networkMode: wanMode,
            isMixMode: ["5", "7"].contains(wanMode),
            checkTimeSec: Int(CodecHelpers.str(json, "wanLinkDetectCheckTime", fallback: "10")) ?? 10,
            mode: CodecHelpers.str(json, "checkWanLinkDetectMode", fallback: "0"),
            ip1: CodecHelpers.str(json, "wanLinkDetectIP1"),
            ip2: CodecHelpers.str(json, "wanLinkDetectIP2"),
            ipv6Ip1: CodecHelpers.str(json, "dnsv6_server1"),
            ipv6Ip2: CodecHelpers.str(json, "dnsv6_server2"),
            action: CodecHelpers.str(json, "LinkDetectAction", fallback: "0"),
            rebootWaitMinutes: Int(CodecHelpers.str(json, "reboot_wait_time", fallback: "3")) ?? 3,
            dialWaitSec: Int(CodecHelpers.str(json, "dial_wait_time", fallback: "10")) ?? 10
        )
    }
}
