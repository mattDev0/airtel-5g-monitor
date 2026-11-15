import Foundation

@MainActor
public final class MobileRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var trafficLimit: TrafficLimitSettings?
    @Published public private(set) var mobileAdvanced = MobileAdvancedConfig()
    @Published public private(set) var apnConfig = ApnConfigSnapshot()

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    public func setMobileDataEnabled(_ enabled: Bool) async -> RouterResult<Unit> {
        let target = enabled ? "1" : "0"
        return await writer.write(
            WriteSpec<Unit>(
                cmd: 222,
                readCmd: 218,
                buildPayload: { _ in ["dialMode": target] },
                verify: { rb in CodecHelpers.str(rb, "dialMode") == target },
                parseResult: { _ in Unit() },
                verifyRetries: 5,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
    }

    public func setFlightModeEnabled(_ enabled: Bool) async -> RouterResult<Unit> {
        let target = enabled ? "1" : "0"
        return await writer.write(
            WriteSpec<Unit>(
                cmd: 226,
                readCmd: 218,
                buildPayload: { _ in ["flightMode": target] },
                verify: { rb in CodecHelpers.str(rb, "flightMode") == target },
                parseResult: { _ in Unit() },
                verifyRetries: 5,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
    }

    @discardableResult
    public func loadTrafficLimit() async -> RouterResult<TrafficLimitSettings> {
        let res = await session.execute(cmd: 337, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let parsed = Self.parseTrafficLimit(json)
        self.trafficLimit = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func saveTrafficLimit(enabled: Bool, size: String, unitCode: String, startDate: Int) async -> RouterResult<TrafficLimitSettings> {
        let cleanSize = size.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !enabled || (Int(cleanSize).map { (0...99999).contains($0) } == true) else {
            return .badResponse(message: "Limit size must be 0–99999", rawBody: "")
        }
        guard (1...28).contains(startDate) else {
            return .badResponse(message: "Billing start day must be 1–28", rawBody: "")
        }
        let unitLabel = Self.normalizeUnitCode(unitCode)
        let res = await writer.write(
            WriteSpec<TrafficLimitSettings>(
                cmd: 337,
                buildPayload: { cur in
                    [
                        "limitSwitch": enabled ? "1" : "0",
                        "limitSize": enabled ? cleanSize : "0",
                        "flow_limit_unit": unitLabel,
                        "startDate": "\(startDate)",
                        "warn_percentage": CodecHelpers.firstNonBlank(cur, keys: ["warn_percentage"], defaultValue: "80")
                    ]
                },
                verify: { rb in
                    let parsed = Self.parseTrafficLimit(rb)
                    return parsed.limitEnabled == enabled && parsed.startDate == startDate
                },
                parseResult: { Self.parseTrafficLimit($0) },
                verifyRetries: 4,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true
            )
        )
        if case .ok(let saved) = res { self.trafficLimit = saved }
        return res
    }

    /// Writes `cmd 337` with `statisticsReset: "1"` and explicit `usedFlow` in MB.
    @discardableResult
    public func calibrateTrafficStatistics(targetUsedMb: Double) async -> RouterResult<TrafficLimitSettings> {
        guard targetUsedMb.isFinite, targetUsedMb >= 0 else {
            return .badResponse(message: "Calibration total cannot be negative", rawBody: "")
        }
        let formatted = Self.formatDecimalMb(targetUsedMb)
        let res = await writer.write(
            WriteSpec<TrafficLimitSettings>(
                cmd: 337,
                buildPayload: { _ in
                    ["statisticsReset": "1", "usedFlow": formatted]
                },
                verify: { rb in
                    let p = Self.parseTrafficLimit(rb)
                    return abs(p.totalUsedMb - targetUsedMb) <= max(1.0, targetUsedMb * 0.02)
                },
                parseResult: { Self.parseTrafficLimit($0) },
                verifyRetries: 4,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res { self.trafficLimit = saved }
        return res
    }

    /// Clears router monthly traffic counters (`cmd 337` POST `{ statisticsReset: "1" }`).
    @discardableResult
    public func clearTrafficStatistics() async -> RouterResult<TrafficLimitSettings> {
        let res = await writer.write(
            WriteSpec<TrafficLimitSettings>(
                cmd: 337,
                buildPayload: { _ in ["statisticsReset": "1"] },
                verify: { rb in Self.parseTrafficLimit(rb).totalUsedMb <= 1.0 },
                parseResult: { Self.parseTrafficLimit($0) },
                verifyRetries: 4,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res { self.trafficLimit = saved }
        return res
    }

    @discardableResult
    public func loadMobileAdvanced() async -> RouterResult<MobileAdvancedConfig> {
        let r218 = await session.execute(cmd: 218, method: "GET")
        let r256 = await session.execute(cmd: 256, method: "GET")
        if case .notLoggedIn(let msg) = r218 { return .notLoggedIn(msg) }
        guard case .ok(let j218) = r218 else { return r218.castFailure() }
        let j256 = r256.getOrNull() ?? [:]

        let cfg = MobileAdvancedConfig(
            networkMode: CodecHelpers.firstNonBlank(j256, keys: ["networkMode"], defaultValue: CodecHelpers.firstNonBlank(j218, keys: ["networkMode"], defaultValue: "1C")),
            displayMode: CodecHelpers.firstNonBlank(j256, keys: ["display_mode"], defaultValue: "1C"),
            mode5g: CodecHelpers.firstNonBlank(j218, keys: ["mode5g"], defaultValue: "0"),
            lteCa: CodecHelpers.str(j218, "lteCA", fallback: "1") == "1",
            nrCa: CodecHelpers.str(j218, "nrCA", fallback: "1") == "1",
            roamingEnable: CodecHelpers.str(j218, "roamingEnable") == "1",
            mobileDataOn: CodecHelpers.str(j218, "dialMode", fallback: "1") == "1",
            flightModeOn: CodecHelpers.str(j218, "flightMode", fallback: "0") == "1"
        )
        self.mobileAdvanced = cfg
        return .ok(cfg)
    }

    @discardableResult
    public func setNetworkMode(_ modeCode: String, flags: RouterFeatureFlags) async -> RouterResult<MobileAdvancedConfig> {
        if flags.lockPlmnFlag {
            return .badResponse(message: "Network mode is locked by carrier PLMN policy", rawBody: "")
        }
        let clean = modeCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !clean.isEmpty else {
            return .badResponse(message: "Select a valid network mode", rawBody: "")
        }
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 256,
                buildPayload: { _ in ["networkMode": clean] },
                verify: { rb in CodecHelpers.str(rb, "networkMode").uppercased() == clean },
                parseResult: { _ in Unit() },
                verifyRetries: 5,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
        if !res.isOk { return res.castFailure() }
        return await loadMobileAdvanced()
    }

    @discardableResult
    public func setLteCa(_ enabled: Bool) async -> RouterResult<MobileAdvancedConfig> {
        let target = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 226,
                readCmd: 218,
                buildPayload: { _ in ["lteCA": target] },
                verify: { rb in CodecHelpers.str(rb, "lteCA") == target },
                parseResult: { _ in Unit() },
                verifyRetries: 4,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
        if !res.isOk { return res.castFailure() }
        return await loadMobileAdvanced()
    }

    @discardableResult
    public func setNrCa(_ enabled: Bool) async -> RouterResult<MobileAdvancedConfig> {
        let target = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 226,
                readCmd: 218,
                buildPayload: { _ in ["nrCA": target] },
                verify: { rb in CodecHelpers.str(rb, "nrCA") == target },
                parseResult: { _ in Unit() },
                verifyRetries: 4,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
        if !res.isOk { return res.castFailure() }
        return await loadMobileAdvanced()
    }

    @discardableResult
    public func setRoaming(_ enabled: Bool) async -> RouterResult<MobileAdvancedConfig> {
        let target = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 220,
                readCmd: 218,
                buildPayload: { _ in ["roamingEnable": target] },
                verify: { rb in CodecHelpers.str(rb, "roamingEnable") == target },
                parseResult: { _ in Unit() },
                verifyRetries: 4,
                verifyDelaySeconds: 1.0,
                allowEmptyWriteReply: true,
                skipPreRead: true
            )
        )
        if !res.isOk { return res.castFailure() }
        return await loadMobileAdvanced()
    }

    @discardableResult
    public func loadApnConfig() async -> RouterResult<ApnConfigSnapshot> {
        let r213 = await session.execute(cmd: 213, method: "GET", extra: ["subcmd": 3])
        if case .notLoggedIn(let msg) = r213 { return .notLoggedIn(msg) }
        guard case .ok(let j213) = r213 else { return r213.castFailure() }
        let r248 = await session.execute(cmd: 248, method: "GET")
        let j248 = r248.getOrNull() ?? [:]

        let snap = Self.parseApnSnapshot(j213: j213, j248: j248)
        self.apnConfig = snap
        return .ok(snap)
    }

    @discardableResult
    public func saveApnBasic(selectType: String, mtu: Int, natEnabled: Bool) async -> RouterResult<ApnConfigSnapshot> {
        if let err = Phase71Validators.validateApnMtu("\(mtu)", selectType: selectType) {
            return .badResponse(message: err, rawBody: "")
        }
        let natVal = natEnabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<ApnConfigSnapshot>(
                cmd: 213,
                readExtra: ["subcmd": 3],
                buildPayload: { _ in
                    [
                        "subcmd": 3,
                        "selectType": selectType,
                        "apnMTU": "\(mtu)",
                        "apnNatName": natVal
                    ]
                },
                verify: { rb in
                    CodecHelpers.str(rb, "selectType") == selectType &&
                        CodecHelpers.str(rb, "apnMTU") == "\(mtu)" &&
                        CodecHelpers.str(rb, "apnNatName") == natVal
                },
                parseResult: { Self.parseApnSnapshot(j213: $0, j248: ["apn_list": []]) },
                verifyRetries: 4,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true
            )
        )
        if case .ok = res {
            return await loadApnConfig()
        }
        return res
    }

    @discardableResult
    public func saveApnProfiles(_ profiles: [ApnProfile]) async -> RouterResult<[ApnProfile]> {
        for p in profiles {
            if let err = Phase71Validators.validateApnString(p.name, fieldLabel: "Profile name") {
                return .badResponse(message: err, rawBody: "")
            }
            if let err = Phase71Validators.validateApnString(p.apnName, fieldLabel: "APN") {
                return .badResponse(message: err, rawBody: "")
            }
            if let err = Phase71Validators.validateApnCredential(p.username, fieldLabel: "Username") {
                return .badResponse(message: err, rawBody: "")
            }
            if let err = Phase71Validators.validateApnCredential(p.password, fieldLabel: "Password") {
                return .badResponse(message: err, rawBody: "")
            }
        }
        let wire: [[String: Any]] = profiles.map {
            [
                "name": $0.name.trimmingCharacters(in: .whitespacesAndNewlines),
                "apnName": $0.apnName.trimmingCharacters(in: .whitespacesAndNewlines),
                "selectAuthtication": $0.authType,
                "apnUserName": $0.username,
                "apnUserPassword": $0.password,
                "edit_flag": "1",
                "default_flag": $0.isDefault ? "1" : "0"
            ]
        }
        let res = await writer.write(
            WriteSpec<[ApnProfile]>(
                cmd: 248,
                buildPayload: { _ in ["apn_list": wire] },
                verify: { rb in
                    let read = Self.parseApnList(rb)
                    return read.count == profiles.count &&
                        zip(read, profiles).allSatisfy { $0.apnName == $1.apnName.trimmingCharacters(in: .whitespacesAndNewlines) }
                },
                parseResult: { Self.parseApnList($0) },
                verifyRetries: 4,
                verifyDelaySeconds: 1.5,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res {
            apnConfig = ApnConfigSnapshot(
                selectType: apnConfig.selectType,
                mtu: apnConfig.mtu,
                natEnabled: apnConfig.natEnabled,
                apnSwitch: apnConfig.apnSwitch,
                customApnName: apnConfig.customApnName,
                username: apnConfig.username,
                password: apnConfig.password,
                authType: apnConfig.authType,
                profiles: saved
            )
        }
        return res
    }

    private static func parseTrafficLimit(_ json: [String: Any]) -> TrafficLimitSettings {
        // ZLT X17M swaps dl_mon_flow and ul_mon_flow on cmd 337.
        let dlMb = CodecHelpers.doubleVal(CodecHelpers.str(json, "ul_mon_flow")) ?? 0.0
        let ulMb = CodecHelpers.doubleVal(CodecHelpers.str(json, "dl_mon_flow")) ?? 0.0
        return TrafficLimitSettings(
            limitEnabled: CodecHelpers.str(json, "limitSwitch") == "1",
            limitSize: CodecHelpers.str(json, "limitSize", fallback: "0"),
            unitCode: unitToCode(CodecHelpers.str(json, "flow_limit_unit")),
            startDate: min(max(Int(CodecHelpers.str(json, "startDate")) ?? 1, 1), 28),
            dlMonthMb: dlMb,
            ulMonthMb: ulMb
        )
    }

    private static func parseApnSnapshot(j213: [String: Any], j248: [String: Any]) -> ApnConfigSnapshot {
        ApnConfigSnapshot(
            selectType: CodecHelpers.firstNonBlank(j213, keys: ["selectType"], defaultValue: "IP"),
            mtu: Int(CodecHelpers.str(j213, "apnMTU")) ?? 1500,
            natEnabled: CodecHelpers.str(j213, "apnNatName", fallback: "1") == "1",
            apnSwitch: CodecHelpers.str(j213, "apnSwitch") == "1",
            customApnName: CodecHelpers.str(j213, "apnName"),
            username: CodecHelpers.str(j213, "apnUserName"),
            password: CodecHelpers.str(j213, "apnUserPassword"),
            authType: CodecHelpers.firstNonBlank(j213, keys: ["selectAuthtication"], defaultValue: "0"),
            profiles: parseApnList(j248)
        )
    }

    private static func parseApnList(_ json: [String: Any]) -> [ApnProfile] {
        guard let arr = json["apn_list"] as? [[String: Any]] else { return [] }
        return arr.map { row in
            ApnProfile(
                name: CodecHelpers.str(row, "name"),
                apnName: CodecHelpers.str(row, "apnName"),
                selectType: CodecHelpers.firstNonBlank(row, keys: ["selectType"], defaultValue: "IP"),
                username: CodecHelpers.str(row, "apnUserName"),
                password: CodecHelpers.str(row, "apnUserPassword"),
                authType: CodecHelpers.firstNonBlank(row, keys: ["selectAuthtication"], defaultValue: "0"),
                mtu: Int(CodecHelpers.str(row, "apnMTU")) ?? 1500,
                natEnabled: CodecHelpers.str(row, "apnNatName", fallback: "1") == "1",
                isDefault: CodecHelpers.str(row, "default_flag") == "1"
            )
        }
    }

    private static func unitToCode(_ raw: String) -> String {
        let up = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch up {
        case "0", "MB": return "0"
        case "2", "TB": return "2"
        default: return "1"
        }
    }

    private static func normalizeUnitCode(_ code: String) -> String {
        switch code.uppercased() {
        case "0", "MB": return "0"
        case "2", "TB": return "2"
        default: return "1"
        }
    }

    private static func formatDecimalMb(_ value: Double) -> String {
        if value.truncatingRemainder(dividingBy: 1.0) == 0 {
            return String(format: "%.0f", value)
        }
        return String(format: "%.2f", value)
            .replacingOccurrences(of: #"0+$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\.$"#, with: "", options: .regularExpression)
    }
}
