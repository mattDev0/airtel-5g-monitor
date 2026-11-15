import Foundation

@MainActor
public final class ParentalControlRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var snapshot = ParentalControlSnapshot()

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    @discardableResult
    public func loadAll() async -> RouterResult<ParentalControlSnapshot> {
        let rSwitch = await session.execute(cmd: 391, method: "GET")
        if case .notLoggedIn(let msg) = rSwitch { return .notLoggedIn(msg) }

        let rTime = await session.execute(cmd: 385, method: "GET", extra: ["getfun": true])
        if case .notLoggedIn(let msg) = rTime { return .notLoggedIn(msg) }

        let rSpeed = await session.execute(cmd: 382, method: "GET", extra: ["getfun": true])
        if case .notLoggedIn(let msg) = rSpeed { return .notLoggedIn(msg) }

        let rUrl = await session.execute(cmd: 383, method: "GET", extra: ["getfun": true])
        if case .notLoggedIn(let msg) = rUrl { return .notLoggedIn(msg) }

        guard rSwitch.isOk || rTime.isOk || rSpeed.isOk || rUrl.isOk else {
            return rSwitch.castFailure()
        }

        let enabled = CodecHelpers.str(rSwitch.getOrNull() ?? [:], "enable") == "1"
        let timeRules = Self.parseTimeRules(rTime.getOrNull() ?? [:])
        let speedRules = Self.parseSpeedRules(rSpeed.getOrNull() ?? [:])
        let urlRules = Self.parseUrlRules(rUrl.getOrNull() ?? [:])

        let next = ParentalControlSnapshot(
            enabled: enabled,
            timeRules: timeRules,
            speedRules: speedRules,
            urlRules: urlRules
        )
        self.snapshot = next
        return .ok(next)
    }

    @discardableResult
    public func setEnabled(_ enabled: Bool) async -> RouterResult<ParentalControlSnapshot> {
        let target = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<Bool>(
                cmd: 391,
                buildPayload: { _ in ["enable": target] },
                verify: { rb in CodecHelpers.str(rb, "enable") == target },
                parseResult: { CodecHelpers.str($0, "enable") == "1" },
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        guard res.isOk else { return res.castFailure() }
        return await loadAll()
    }

    @discardableResult
    public func saveTimeRules(_ rules: [ParentalTimeRule]) async -> RouterResult<[ParentalTimeRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 parental schedule rules supported", rawBody: "")
        }
        var normalized: [ParentalTimeRule] = []
        for rule in rules {
            guard let mac = Phase71Validators.normalizeMac(rule.mac) else {
                return .badResponse(message: "Invalid MAC address: \(rule.mac)", rawBody: "")
            }
            if let timeErr = Phase71Validators.validateTimeWindow(startHhMm: rule.startTime, endHhMm: rule.endTime) {
                return .badResponse(message: timeErr, rawBody: "")
            }
            let days = rule.week.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            if days.isEmpty {
                return .badResponse(message: "Select at least one day of the week", rawBody: "")
            }
            normalized.append(ParentalTimeRule(
                mac: mac,
                startTime: rule.startTime.trimmingCharacters(in: .whitespaces),
                endTime: rule.endTime.trimmingCharacters(in: .whitespaces),
                week: days.joined(separator: ","),
                enableRule: rule.enableRule
            ))
        }

        let datas: [[String: Any]] = normalized.map {
            [
                "enableRule": $0.enableRule,
                "mac": $0.mac,
                "startTime": $0.startTime,
                "endTime": $0.endTime,
                "scheduleDays": $0.week
            ]
        }

        let res = await writer.write(
            WriteSpec<[ParentalTimeRule]>(
                cmd: 385,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas] },
                verifyPost: { _ in nil },
                verify: { rb in
                    Self.parseTimeRules(rb).count == normalized.count
                },
                parseResult: { Self.parseTimeRules($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if res.isOk {
            _ = await writer.applyFilter()
            _ = await loadAll()
        }
        return res
    }

    @discardableResult
    public func saveSpeedRules(_ rules: [ParentalSpeedRule]) async -> RouterResult<[ParentalSpeedRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 speed limit rules supported", rawBody: "")
        }
        var seenIps = Set<String>()
        var normalized: [ParentalSpeedRule] = []
        for rule in rules {
            let ip = rule.ip.trimmingCharacters(in: .whitespacesAndNewlines)
            if let ipErr = Phase71Validators.validateIpOrRange(ip, ippro: "IPV4", allowRange: false) {
                return .badResponse(message: ipErr, rawBody: "")
            }
            if rule.speedKbps < 1 || rule.speedKbps > 1_000_000 {
                return .badResponse(message: "Speed limit must be 1–1,000,000 KB/s", rawBody: "")
            }
            if !seenIps.insert(ip).inserted {
                return .badResponse(message: "Duplicate speed limit rule for \(ip)", rawBody: "")
            }
            normalized.append(ParentalSpeedRule(ip: ip, speedKbps: rule.speedKbps, enableRule: rule.enableRule))
        }

        let datas: [[String: Any]] = normalized.map {
            [
                "enableRule": $0.enableRule,
                "ip": $0.ip,
                "maxSpeed": "\($0.speedKbps)",
                "ippro": "IPV4"
            ]
        }

        let res = await writer.write(
            WriteSpec<[ParentalSpeedRule]>(
                cmd: 382,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas] },
                verify: { rb in Self.parseSpeedRules(rb).count == normalized.count },
                parseResult: { Self.parseSpeedRules($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if res.isOk {
            _ = await writer.applyFilter()
            _ = await loadAll()
        }
        return res
    }

    @discardableResult
    public func saveUrlRules(_ rules: [ParentalUrlRule]) async -> RouterResult<[ParentalUrlRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 URL filter rules supported", rawBody: "")
        }
        var perMacCount: [String: Int] = [:]
        var normalized: [ParentalUrlRule] = []
        for rule in rules {
            guard let mac = Phase71Validators.normalizeMac(rule.mac) else {
                return .badResponse(message: "Invalid MAC address: \(rule.mac)", rawBody: "")
            }
            if let urlErr = Phase71Validators.validateFilterUrl(rule.url) {
                return .badResponse(message: urlErr, rawBody: "")
            }
            let count = (perMacCount[mac] ?? 0) + 1
            if count > 10 {
                return .badResponse(message: "Maximum 10 URL rules per device (\(mac))", rawBody: "")
            }
            perMacCount[mac] = count
            normalized.append(ParentalUrlRule(
                mac: mac,
                url: rule.url.trimmingCharacters(in: .whitespacesAndNewlines),
                enableRule: rule.enableRule
            ))
        }

        let datas: [[String: Any]] = normalized.map {
            [
                "enableRule": $0.enableRule,
                "mac": $0.mac,
                "url": $0.url,
                "enableLink": false,
                "ippro": "IPV4V6"
            ]
        }

        let res = await writer.write(
            WriteSpec<[ParentalUrlRule]>(
                cmd: 383,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas] },
                verify: { rb in Self.parseUrlRules(rb).count == normalized.count },
                parseResult: { Self.parseUrlRules($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if res.isOk {
            _ = await writer.applyFilter()
            _ = await loadAll()
        }
        return res
    }

    public static func parseTimeRules(_ json: [String: Any]) -> [ParentalTimeRule] {
        guard let arr = json["datas"] as? [[String: Any]] else { return [] }
        return arr.compactMap { row in
            let mac = CodecHelpers.str(row, "mac").uppercased()
            let start = CodecHelpers.str(row, "startTime")
            let end = CodecHelpers.str(row, "endTime")
            let week = CodecHelpers.firstNonBlank(row, keys: ["scheduleDays", "week"], defaultValue: "1,2,3,4,5")
            guard !mac.isEmpty else { return nil }
            let enabled = boolField(row, "enableRule", defaultVal: true)
            return ParentalTimeRule(mac: mac, startTime: start, endTime: end, week: week, enableRule: enabled)
        }
    }

    public static func parseSpeedRules(_ json: [String: Any]) -> [ParentalSpeedRule] {
        guard let arr = json["datas"] as? [[String: Any]] else { return [] }
        return arr.compactMap { row in
            let ip = CodecHelpers.str(row, "ip")
            let speedStr = CodecHelpers.firstNonBlank(row, keys: ["maxSpeed", "speed", "speedKbps"], defaultValue: "0")
            guard !ip.isEmpty, let speed = Int(speedStr) else { return nil }
            let enabled = boolField(row, "enableRule", defaultVal: true)
            return ParentalSpeedRule(ip: ip, speedKbps: speed, enableRule: enabled)
        }
    }

    public static func parseUrlRules(_ json: [String: Any]) -> [ParentalUrlRule] {
        guard let arr = json["datas"] as? [[String: Any]] else { return [] }
        return arr.compactMap { row in
            let mac = CodecHelpers.str(row, "mac").uppercased()
            let url = CodecHelpers.str(row, "url")
            guard !mac.isEmpty, !url.isEmpty else { return nil }
            let enabled = boolField(row, "enableRule", defaultVal: true)
            return ParentalUrlRule(mac: mac, url: url, enableRule: enabled)
        }
    }

    private static func boolField(_ dict: [String: Any], _ key: String, defaultVal: Bool) -> Bool {
        if let b = dict[key] as? Bool { return b }
        let s = CodecHelpers.str(dict, key)
        if s == "1" || s.lowercased() == "true" { return true }
        if s == "0" || s.lowercased() == "false" { return false }
        return defaultVal
    }
}
