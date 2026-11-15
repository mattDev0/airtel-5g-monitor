import Foundation

@MainActor
public final class SecurityRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var macFilters: [MacFilterRule] = []
    @Published public private(set) var firewallEnabled: Bool = false
    @Published public private(set) var firewallLevel: FirewallLevel = .low
    @Published public private(set) var alg = AlgSettings()
    @Published public private(set) var dmz = DmzConfig()
    @Published public private(set) var ddos = DdosConfig()
    @Published public private(set) var upnp = UpnpConfig()
    @Published public private(set) var portForwards: [PortForwardRule] = []
    @Published public private(set) var ipPortFilters: [IpPortFilterRule] = []
    @Published public private(set) var portFilters: [PortFilterRule] = []
    @Published public private(set) var urlFilters: [UrlFilterRule] = []
    @Published public private(set) var trustedIp = TrustedIpConfig()
    @Published public private(set) var defaultPolicy = DefaultFilterPolicy()
    @Published public private(set) var systemPorts: Set<Int> = []

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    @discardableResult
    public func loadAll() async -> RouterResult<Unit> {
        let r367 = await session.execute(cmd: 367, method: "GET")
        if case .notLoggedIn(let msg) = r367 { return .notLoggedIn(msg) }
        if case .ok(let j) = r367 {
            firewallEnabled = CodecHelpers.str(j, "fw_switch") == "1"
            firewallLevel = FirewallLevel.fromRaw(CodecHelpers.str(j, "level"))
        }

        if case .ok(let j) = await session.execute(cmd: 21, method: "GET", extra: ["getfun": true]) {
            macFilters = Self.parseMacFilters(j)
        }
        if case .ok(let j) = await session.execute(cmd: 504, method: "GET") {
            alg = Self.parseAlg(j)
        }
        if case .ok(let j) = await session.execute(cmd: 172, method: "GET", extra: ["subcmd": 6]) {
            dmz = DmzConfig(enabled: CodecHelpers.str(j, "enabled") == "1", ip: CodecHelpers.str(j, "ip"))
        }
        if case .ok(let j) = await session.execute(cmd: 275, method: "GET") {
            ddos = DdosConfig(enabled: CodecHelpers.str(j, "ddosSwich") == "1")
        }
        let rUpnp = await session.execute(cmd: 333, method: "GET")
        if case .ok(let j) = rUpnp {
            upnp = DmzAndUpnp.parseUpnp(j)
        } else if case .ok(let j202) = await session.execute(cmd: 202, method: "GET") {
            upnp = DmzAndUpnp.parseUpnp(j202)
        }
        if case .ok(let j) = await session.execute(cmd: 400, method: "GET") {
            systemPorts = Self.parseSystemPorts(j)
        }
        if case .ok(let j) = await session.execute(cmd: 27, method: "GET", extra: ["getfun": true]) {
            portForwards = Self.parsePortForwards(j)
        }
        if case .ok(let j) = await session.execute(cmd: 32, method: "GET", extra: ["getfun": true]) {
            ipPortFilters = Self.parseIpPortFilters(j)
        }
        if case .ok(let j) = await session.execute(cmd: 23, method: "GET", extra: ["getfun": true]) {
            portFilters = Self.parsePortFilters(j)
        }
        if case .ok(let j) = await session.execute(cmd: 26, method: "GET", extra: ["getfun": true]) {
            urlFilters = Self.parseUrlFilters(j)
        }
        if case .ok(let j) = await session.execute(cmd: 502, method: "GET") {
            trustedIp = Self.parseTrustedIp(j)
        }
        _ = await loadDefaultPolicy()

        return .ok(Unit())
    }

    @discardableResult
    public func loadDefaultPolicy() async -> RouterResult<DefaultFilterPolicy> {
        let r28 = await session.execute(cmd: 28, method: "GET")
        let r29 = await session.execute(cmd: 29, method: "GET")
        let r30 = await session.execute(cmd: 30, method: "GET")
        if case .notLoggedIn(let msg) = r28 { return .notLoggedIn(msg) }
        guard r28.isOk || r29.isOk || r30.isOk else { return r28.castFailure() }

        let parsed = Self.parseDefaultPolicy(r30.getOrNull() ?? r28.getOrNull() ?? r29.getOrNull() ?? [:])
        defaultPolicy = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func saveFirewallSwitch(enabled: Bool, level: FirewallLevel) async -> RouterResult<Unit> {
        let sw = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 367,
                buildPayload: { _ in ["fw_switch": sw, "level": level.rawValue, "success": true] },
                verify: { rb in
                    CodecHelpers.str(rb, "fw_switch") == sw &&
                        FirewallLevel.fromRaw(CodecHelpers.str(rb, "level")) == level
                },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if res.isOk {
            firewallEnabled = enabled
            firewallLevel = level
        }
        return res
    }

    @discardableResult
    public func saveAlg(_ next: AlgSettings) async -> RouterResult<AlgSettings> {
        let res = await writer.write(
            WriteSpec<AlgSettings>(
                cmd: 504,
                buildPayload: { _ in
                    [
                        "L2TPSW_Flag": next.l2tp ? "1" : "0",
                        "IPSecSW_Flag": next.ipsec ? "1" : "0",
                        "H323SW_Flag": next.h323 ? "1" : "0",
                        "RTSPSW_Flag": next.rtsp ? "1" : "0",
                        "SIPSW_Flag": next.sip ? "1" : "0",
                        "FTPSW_Flag": next.ftp ? "1" : "0",
                        "PPTPSW_Flag": next.pptp ? "1" : "0"
                    ]
                },
                verify: { rb in Self.parseAlg(rb) == next },
                parseResult: { Self.parseAlg($0) },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let saved) = res {
            self.alg = saved
        }
        return res
    }

    @discardableResult
    public func saveDmz(enabled: Bool, ip: String) async -> RouterResult<DmzConfig> {
        let cleanIp = enabled ? ip.trimmingCharacters(in: .whitespacesAndNewlines) : ""
        if enabled {
            if let err = Phase71Validators.validateIpOrRange(cleanIp, ippro: "IPV4", allowRange: false) {
                return .badResponse(message: err, rawBody: "")
            }
        }
        let targetFlag = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<DmzConfig>(
                cmd: 172,
                readExtra: ["subcmd": 6],
                buildPayload: { _ in
                    ["subcmd": 6, "enabled": targetFlag, "ip": cleanIp, "success": true]
                },
                verify: { rb in
                    (CodecHelpers.str(rb, "enabled") == "1") == enabled &&
                        (!enabled || CodecHelpers.str(rb, "ip") == cleanIp)
                },
                parseResult: {
                    DmzConfig(enabled: CodecHelpers.str($0, "enabled") == "1", ip: CodecHelpers.str($0, "ip"))
                },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let cfg) = res { self.dmz = cfg }
        return res
    }

    @discardableResult
    public func saveDdos(enabled: Bool) async -> RouterResult<DdosConfig> {
        let flag = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<DdosConfig>(
                cmd: 275,
                buildPayload: { _ in ["ddosSwich": flag] },
                verify: { rb in CodecHelpers.str(rb, "ddosSwich") == flag },
                parseResult: { DdosConfig(enabled: CodecHelpers.str($0, "ddosSwich") == "1") },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let cfg) = res { self.ddos = cfg }
        return res
    }

    @discardableResult
    public func saveUpnp(enabled: Bool) async -> RouterResult<UpnpConfig> {
        let flag = enabled ? "1" : "0"
        var res = await writer.write(
            WriteSpec<UpnpConfig>(
                cmd: 333,
                buildPayload: { _ in ["upnpSwitch": flag] },
                verify: { rb in DmzAndUpnp.parseUpnp(rb).enabled == enabled },
                parseResult: { DmzAndUpnp.parseUpnp($0) },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .badResponse = res {
            res = await writer.write(
                WriteSpec<UpnpConfig>(
                    cmd: 202,
                    buildPayload: { _ in ["upnpSwitch": flag] },
                    verify: { rb in DmzAndUpnp.parseUpnp(rb).enabled == enabled },
                    parseResult: { DmzAndUpnp.parseUpnp($0) },
                    skipPreRead: true,
                    verifyRetries: 3,
                    verifyDelaySeconds: 1.0
                )
            )
        }
        if case .ok(let cfg) = res { self.upnp = cfg }
        return res
    }

    @discardableResult
    public func saveMacFilters(_ rules: [MacFilterRule]) async -> RouterResult<[MacFilterRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 MAC filter rules allowed", rawBody: "")
        }
        var seen = Set<String>()
        var normalized: [MacFilterRule] = []
        for rule in rules {
            if let err = Phase71Validators.validateUnicastMac(rule.mac),
               let mac = Phase71Validators.normalizeMac(rule.mac) {
                return .badResponse(message: "\(mac): \(err)", rawBody: "")
            } else if Phase71Validators.normalizeMac(rule.mac) == nil {
                return .badResponse(message: "Invalid MAC address: \(rule.mac)", rawBody: "")
            }
            let mac = Phase71Validators.normalizeMac(rule.mac)!
            if !seen.insert(mac).inserted {
                return .badResponse(message: "Duplicate MAC rule for \(mac)", rawBody: "")
            }
            normalized.append(MacFilterRule(
                mac: mac,
                remark: rule.remark.trimmingCharacters(in: .whitespacesAndNewlines),
                ippro: rule.ippro,
                enableRule: rule.enableRule,
                enableLink: defaultPolicy.isWhitelistMode
            ))
        }

        let datas: [[String: Any]] = normalized.map {
            [
                "mac": $0.mac,
                "remark": $0.remark,
                "ippro": $0.ippro,
                "enableRule": $0.enableRule,
                "enableLink": $0.enableLink
            ]
        }

        let res = await writer.write(
            WriteSpec<[MacFilterRule]>(
                cmd: 21,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas, "success": true] },
                verify: { rb in Self.parseMacFilters(rb).count == normalized.count },
                parseResult: { Self.parseMacFilters($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res {
            self.macFilters = saved
            _ = await writer.applyFilter()
        }
        return res
    }

    @discardableResult
    public func savePortForwards(_ rules: [PortForwardRule]) async -> RouterResult<[PortForwardRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 port forward rules allowed", rawBody: "")
        }
        var datas: [[String: Any]] = []
        for r in rules {
            if let pErr = Phase71Validators.validatePortOrRange(r.port, required: true) {
                return .badResponse(message: "External \(pErr)", rawBody: "")
            }
            if let mErr = Phase71Validators.validatePortOrRange(r.mappingPort, required: true) {
                return .badResponse(message: "Internal \(mErr)", rawBody: "")
            }
            if let conflict = Phase71Validators.findPortConflict(portExpr: r.port, reservedPorts: systemPorts) {
                return .badResponse(message: "External port \(conflict) is reserved by the router", rawBody: "")
            }
            if let ipErr = Phase71Validators.validateIpOrRange(r.mappingIp, ippro: r.ippro, allowRange: false) {
                return .badResponse(message: ipErr, rawBody: "")
            }
            let normExt = Phase71Validators.normalizePortRange(r.port, separator: ":")
            let normInt = Phase71Validators.normalizePortRange(r.mappingPort, separator: "-")
            let ipClean = r.mappingIp.trimmingCharacters(in: .whitespacesAndNewlines)
            datas.append([
                "port": normExt,
                "mappingPort": normInt,
                "mappingIpPort": "\(ipClean):\(normInt)",
                "protocol": r.protocolName,
                "ippro": r.ippro,
                "remark": r.remark,
                "enableRule": r.enableRule,
                "intername": r.interfaceName.isEmpty ? "DEFAULT" : r.interfaceName
            ])
        }

        let res = await writer.write(
            WriteSpec<[PortForwardRule]>(
                cmd: 27,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas, "success": true] },
                verify: { rb in Self.parsePortForwards(rb).count == rules.count },
                parseResult: { Self.parsePortForwards($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res {
            self.portForwards = saved
            _ = await writer.applyFilter()
        }
        return res
    }

    @discardableResult
    public func saveIpPortFilters(_ rules: [IpPortFilterRule]) async -> RouterResult<[IpPortFilterRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 IP/Port filter rules allowed", rawBody: "")
        }
        var datas: [[String: Any]] = []
        for r in rules {
            if !r.ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                if let err = Phase71Validators.validateIpOrRange(r.ip, ippro: r.ippro, allowRange: true, required: false) {
                    return .badResponse(message: "Source \(err)", rawBody: "")
                }
            }
            if !r.destIp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                if let err = Phase71Validators.validateIpOrRange(r.destIp, ippro: r.ippro, allowRange: true, required: false) {
                    return .badResponse(message: "Destination \(err)", rawBody: "")
                }
            }
            let protoUpper = r.protocolName.uppercased()
            let clearPorts = protoUpper == "ALL" || protoUpper == "ICMP"
            let sPort = clearPorts ? "" : r.srcPort.trimmingCharacters(in: .whitespacesAndNewlines)
            let dPort = clearPorts ? "" : r.destPort.trimmingCharacters(in: .whitespacesAndNewlines)
            if !clearPorts {
                if let err = Phase71Validators.validatePortOrRange(sPort, required: false) {
                    return .badResponse(message: "Source \(err)", rawBody: "")
                }
                if let err = Phase71Validators.validatePortOrRange(dPort, required: false) {
                    return .badResponse(message: "Destination \(err)", rawBody: "")
                }
            }
            datas.append([
                "ip": r.ip.trimmingCharacters(in: .whitespacesAndNewlines),
                "destIp": r.destIp.trimmingCharacters(in: .whitespacesAndNewlines),
                "srcPort": sPort,
                "destPort": dPort,
                "protocol": r.protocolName,
                "ippro": r.ippro,
                "remark": r.remark,
                "enableRule": r.enableRule,
                "enableLink": defaultPolicy.isWhitelistMode
            ])
        }

        let res = await writer.write(
            WriteSpec<[IpPortFilterRule]>(
                cmd: 32,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas, "success": true] },
                verify: { rb in Self.parseIpPortFilters(rb).count == rules.count },
                parseResult: { Self.parseIpPortFilters($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res {
            self.ipPortFilters = saved
            _ = await writer.applyFilter()
        }
        return res
    }

    @discardableResult
    public func savePortFilters(_ rules: [PortFilterRule]) async -> RouterResult<[PortFilterRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 port filter rules allowed", rawBody: "")
        }
        var datas: [[String: Any]] = []
        for r in rules {
            if let err = Phase71Validators.validatePortOrRange(r.port, required: true) {
                return .badResponse(message: err, rawBody: "")
            }
            datas.append([
                "port": r.port.trimmingCharacters(in: .whitespacesAndNewlines),
                "protocol": r.protocolName,
                "ippro": r.ippro,
                "remark": r.remark,
                "enableRule": r.enableRule,
                "enableLink": defaultPolicy.isWhitelistMode
            ])
        }

        let res = await writer.write(
            WriteSpec<[PortFilterRule]>(
                cmd: 23,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas, "success": true] },
                verify: { rb in Self.parsePortFilters(rb).count == rules.count },
                parseResult: { Self.parsePortFilters($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res {
            self.portFilters = saved
            _ = await writer.applyFilter()
        }
        return res
    }

    @discardableResult
    public func saveUrlFilters(_ rules: [UrlFilterRule]) async -> RouterResult<[UrlFilterRule]> {
        if rules.count > 32 {
            return .badResponse(message: "Maximum 32 URL filter rules allowed", rawBody: "")
        }
        var datas: [[String: Any]] = []
        for r in rules {
            if let err = Phase71Validators.validateFilterUrl(r.url) {
                return .badResponse(message: err, rawBody: "")
            }
            datas.append([
                "url": r.url.trimmingCharacters(in: .whitespacesAndNewlines),
                "ippro": r.ippro,
                "remark": r.remark,
                "enableRule": r.enableRule,
                "enableLink": defaultPolicy.isWhitelistMode
            ])
        }

        let res = await writer.write(
            WriteSpec<[UrlFilterRule]>(
                cmd: 26,
                readExtra: ["getfun": true],
                buildPayload: { _ in ["datas": datas, "success": true] },
                verify: { rb in Self.parseUrlFilters(rb).count == rules.count },
                parseResult: { Self.parseUrlFilters($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res {
            self.urlFilters = saved
            _ = await writer.applyFilter()
        }
        return res
    }

    @discardableResult
    public func saveDefaultPolicy(acceptAll: Bool) async -> RouterResult<DefaultFilterPolicy> {
        let policyDatas: [[String: Any]] = [
            ["enableRule": true, "acceptAll": acceptAll, "ippro": "IPV4"],
            ["enableRule": true, "acceptAll": acceptAll, "ippro": "IPV6"]
        ]
        for policyCmd in [28, 29, 30] {
            let res = await writer.write(
                WriteSpec<DefaultFilterPolicy>(
                    cmd: policyCmd,
                    buildPayload: { _ in ["datas": policyDatas, "success": true] },
                    verify: { rb in Self.parseDefaultPolicy(rb).acceptAllV4 == acceptAll },
                    parseResult: { Self.parseDefaultPolicy($0) },
                    verifyRetries: 2,
                    verifyDelaySeconds: 0.7,
                    skipPreRead: true
                )
            )
            if !res.isOk { return res }
        }

        // Sync enableLink on existing rule tables per web UI default/default2/defaultMac pattern
        if !macFilters.isEmpty { _ = await saveMacFilters(macFilters) }
        if !portFilters.isEmpty { _ = await savePortFilters(portFilters) }
        if !urlFilters.isEmpty { _ = await saveUrlFilters(urlFilters) }
        _ = await writer.applyFilter()

        return await loadDefaultPolicy()
    }

    @discardableResult
    public func saveTrustedIps(enabled: Bool, ips: [String]) async -> RouterResult<TrustedIpConfig> {
        let cleaned = ips.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        if cleaned.count > 15 {
            return .badResponse(message: "Maximum 15 trusted IPs supported", rawBody: "")
        }
        for ip in cleaned {
            if let err = Phase71Validators.validateIpOrRange(ip, ippro: "IPV4", allowRange: false) {
                return .badResponse(message: err, rawBody: "")
            }
        }
        let sw = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<TrustedIpConfig>(
                cmd: 502,
                buildPayload: { _ in
                    var map: [String: Any] = ["trusted_ip_sw": sw]
                    for idx in 0..<15 {
                        map["trusted_ip_\(idx)"] = idx < cleaned.count ? cleaned[idx] : ""
                    }
                    return map
                },
                verify: { rb in
                    let p = Self.parseTrustedIp(rb)
                    return p.enabled == enabled && p.ips == cleaned
                },
                parseResult: { Self.parseTrustedIp($0) },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let cfg) = res { self.trustedIp = cfg }
        return res
    }

    // MARK: - Parsers

    public static func parseAlg(_ json: [String: Any]) -> AlgSettings {
        AlgSettings(
            l2tp: CodecHelpers.str(json, "L2TPSW_Flag", fallback: "1") == "1",
            ipsec: CodecHelpers.str(json, "IPSecSW_Flag", fallback: "1") == "1",
            h323: CodecHelpers.str(json, "H323SW_Flag", fallback: "1") == "1",
            rtsp: CodecHelpers.str(json, "RTSPSW_Flag", fallback: "1") == "1",
            sip: CodecHelpers.str(json, "SIPSW_Flag", fallback: "1") == "1",
            ftp: CodecHelpers.str(json, "FTPSW_Flag", fallback: "1") == "1",
            pptp: CodecHelpers.str(json, "PPTPSW_Flag", fallback: "1") == "1"
        )
    }

    public static func parseSystemPorts(_ json: [String: Any]) -> Set<Int> {
        let raw = CodecHelpers.str(json, "systemPort")
        return Set(raw.split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Int($0) })
    }

    public static func parseMacFilters(_ json: [String: Any]) -> [MacFilterRule] {
        guard let rows = json["datas"] as? [[String: Any]] else { return [] }
        return rows.compactMap { r in
            let mac = CodecHelpers.str(r, "mac").uppercased()
            guard !mac.isEmpty else { return nil }
            return MacFilterRule(
                mac: mac,
                remark: CodecHelpers.str(r, "remark"),
                ippro: CodecHelpers.str(r, "ippro", fallback: "IPV4"),
                enableRule: boolVal(r["enableRule"], defaultVal: true),
                enableLink: boolVal(r["enableLink"], defaultVal: false)
            )
        }
    }

    public static func parsePortForwards(_ json: [String: Any]) -> [PortForwardRule] {
        guard let rows = json["datas"] as? [[String: Any]] else { return [] }
        return rows.compactMap { r in
            let port = CodecHelpers.str(r, "port")
            let combined = CodecHelpers.str(r, "mappingIpPort")
            let fallbackIp = CodecHelpers.str(r, "mappingIp")
            let fallbackPort = CodecHelpers.str(r, "mappingPort")
            let splitPair = combined.split(separator: ":", maxSplits: 1).map(String.init)
            let ip = !fallbackIp.isEmpty ? fallbackIp : (splitPair.first ?? "")
            let mapPort = !fallbackPort.isEmpty ? fallbackPort : (splitPair.count > 1 ? splitPair[1] : port)
            guard !port.isEmpty, !ip.isEmpty else { return nil }
            return PortForwardRule(
                port: port,
                mappingPort: mapPort,
                mappingIp: ip,
                protocolName: CodecHelpers.str(r, "protocol", fallback: "TCP"),
                ippro: CodecHelpers.str(r, "ippro", fallback: "IPV4"),
                remark: CodecHelpers.str(r, "remark"),
                enableRule: boolVal(r["enableRule"], defaultVal: true),
                interfaceName: CodecHelpers.str(r, "intername")
            )
        }
    }

    public static func parseIpPortFilters(_ json: [String: Any]) -> [IpPortFilterRule] {
        guard let rows = json["datas"] as? [[String: Any]] else { return [] }
        return rows.map { r in
            IpPortFilterRule(
                ip: CodecHelpers.str(r, "ip"),
                destIp: CodecHelpers.str(r, "destIp"),
                srcPort: CodecHelpers.str(r, "srcPort"),
                destPort: CodecHelpers.str(r, "destPort"),
                protocolName: CodecHelpers.str(r, "protocol", fallback: "All"),
                ippro: CodecHelpers.str(r, "ippro", fallback: "IPV4"),
                remark: CodecHelpers.str(r, "remark"),
                enableRule: boolVal(r["enableRule"], defaultVal: true),
                enableLink: boolVal(r["enableLink"], defaultVal: false)
            )
        }
    }

    public static func parsePortFilters(_ json: [String: Any]) -> [PortFilterRule] {
        guard let rows = json["datas"] as? [[String: Any]] else { return [] }
        return rows.compactMap { r in
            let port = CodecHelpers.str(r, "port")
            guard !port.isEmpty else { return nil }
            return PortFilterRule(
                port: port,
                protocolName: CodecHelpers.str(r, "protocol", fallback: "tcp"),
                ippro: CodecHelpers.str(r, "ippro", fallback: "IPV4"),
                remark: CodecHelpers.str(r, "remark"),
                enableRule: boolVal(r["enableRule"], defaultVal: true),
                enableLink: boolVal(r["enableLink"], defaultVal: false)
            )
        }
    }

    public static func parseUrlFilters(_ json: [String: Any]) -> [UrlFilterRule] {
        guard let rows = json["datas"] as? [[String: Any]] else { return [] }
        return rows.compactMap { r in
            let url = CodecHelpers.str(r, "url")
            guard !url.isEmpty else { return nil }
            return UrlFilterRule(
                url: url,
                ippro: CodecHelpers.str(r, "ippro", fallback: "IPV4"),
                remark: CodecHelpers.str(r, "remark"),
                enableRule: boolVal(r["enableRule"], defaultVal: true),
                enableLink: boolVal(r["enableLink"], defaultVal: false)
            )
        }
    }

    public static func parseTrustedIp(_ json: [String: Any]) -> TrustedIpConfig {
        let enabled = CodecHelpers.str(json, "trusted_ip_sw") == "1"
        var ips: [String] = []
        for idx in 0..<15 {
            let v = CodecHelpers.str(json, "trusted_ip_\(idx)").trimmingCharacters(in: .whitespacesAndNewlines)
            if !v.isEmpty { ips.append(v) }
        }
        return TrustedIpConfig(enabled: enabled, ips: ips)
    }

    public static func parseDefaultPolicy(_ json: [String: Any]) -> DefaultFilterPolicy {
        guard let rows = json["datas"] as? [[String: Any]], !rows.isEmpty else {
            return DefaultFilterPolicy()
        }
        let v4 = rows.first { CodecHelpers.str($0, "ippro").uppercased() == "IPV4" } ?? rows[0]
        let v6 = rows.first { CodecHelpers.str($0, "ippro").uppercased() == "IPV6" } ?? rows[0]
        return DefaultFilterPolicy(
            acceptAllV4: boolVal(v4["acceptAll"], defaultVal: true),
            acceptAllV6: boolVal(v6["acceptAll"], defaultVal: true),
            enableRuleV4: boolVal(v4["enableRule"], defaultVal: true),
            enableRuleV6: boolVal(v6["enableRule"], defaultVal: true)
        )
    }

    private static func boolVal(_ raw: Any?, defaultVal: Bool) -> Bool {
        if let b = raw as? Bool { return b }
        guard let raw, !(raw is NSNull) else { return defaultVal }
        let s = "\(raw)".trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s == "1" || s == "true" { return true }
        if s == "0" || s == "false" { return false }
        return defaultVal
    }
}

private enum DmzAndUpnp {
    static func parseUpnp(_ json: [String: Any]) -> UpnpConfig {
        let raw = CodecHelpers.firstNonBlank(json, keys: ["upnpSwitch", "upnp_switch", "enable"], defaultValue: "0")
        return UpnpConfig(enabled: raw == "1")
    }
}
