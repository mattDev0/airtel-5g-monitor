import Foundation

@MainActor
public final class LanRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var lanConfig = LanConfig()
    @Published public private(set) var dnsConfig = DnsConfig()
    @Published public private(set) var staticLeases: [StaticDhcpLease] = []

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    @discardableResult
    public func loadLanConfig() async -> RouterResult<LanConfig> {
        let res = await session.execute(cmd: 3, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let parsed = RouterParsers.parseLanConfig(json)
        self.lanConfig = parsed
        self.dnsConfig = RouterParsers.parseDnsConfig(json)
        return .ok(parsed)
    }

    @discardableResult
    public func saveDns(primary: String, secondary: String) async -> RouterResult<DnsConfig> {
        let cleanPrimary = primary.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSecondary = secondary.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanPrimary.isEmpty && !NetworkFormatters.isIpv4(cleanPrimary) {
            return .badResponse(message: "Primary DNS must be a valid IPv4 address", rawBody: "")
        }
        if !cleanSecondary.isEmpty && !NetworkFormatters.isIpv4(cleanSecondary) {
            return .badResponse(message: "Secondary DNS must be a valid IPv4 address", rawBody: "")
        }
        if !cleanSecondary.isEmpty && cleanPrimary.isEmpty {
            return .badResponse(message: "Enter a Primary DNS before setting Secondary DNS", rawBody: "")
        }

        let res = await writer.write(
            WriteSpec<DnsConfig>(
                cmd: 3,
                buildPayload: { cur in
                    Self.mergeCmd3Payload(cur: cur, primaryDns: cleanPrimary, secondaryDns: cleanSecondary)
                },
                verify: { readBack in
                    let p = RouterParsers.parseDnsConfig(readBack)
                    return p.primary == cleanPrimary && p.secondary == cleanSecondary
                },
                parseResult: { RouterParsers.parseDnsConfig($0) },
                writeTimeoutSeconds: 15.0,
                verifyRetries: 5,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true
            )
        )
        if case .ok(let cfg) = res {
            self.dnsConfig = cfg
            _ = await loadLanConfig()
        }
        return res
    }

    @discardableResult
    public func saveLanDhcpSettings(
        lanIp: String,
        netMask: String,
        dhcpEnabled: Bool,
        ipBegin: String,
        ipEnd: String,
        expireTime: String,
        primaryDns: String,
        secondaryDns: String
    ) async -> RouterResult<LanConfig> {
        let cleanIp = lanIp.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanMask = netMask.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanBegin = ipBegin.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanEnd = ipEnd.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanExpire = expireTime.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "24h" : expireTime.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDns1 = primaryDns.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDns2 = secondaryDns.trimmingCharacters(in: .whitespacesAndNewlines)

        if let err = WifiAndLanValidators.validateDhcpSettings(
            lanIp: cleanIp,
            netMask: cleanMask,
            dhcpEnabled: dhcpEnabled,
            ipBegin: cleanBegin,
            ipEnd: cleanEnd
        ) {
            return .badResponse(message: err, rawBody: "")
        }
        if !cleanDns1.isEmpty && !NetworkFormatters.isIpv4(cleanDns1) {
            return .badResponse(message: "Primary DNS must be a valid IPv4 address", rawBody: "")
        }
        if !cleanDns2.isEmpty && !NetworkFormatters.isIpv4(cleanDns2) {
            return .badResponse(message: "Secondary DNS must be a valid IPv4 address", rawBody: "")
        }

        let res = await writer.write(
            WriteSpec<LanConfig>(
                cmd: 3,
                buildPayload: { cur in
                    var payload = Self.mergeCmd3Payload(cur: cur, primaryDns: cleanDns1, secondaryDns: cleanDns2)
                    payload["lanIp"] = cleanIp
                    payload["netMask"] = cleanMask
                    payload["dhcpServer"] = dhcpEnabled ? "1" : "0"
                    if dhcpEnabled {
                        payload["ipBegin"] = cleanBegin
                        payload["ipEnd"] = cleanEnd
                        payload["expireTime"] = cleanExpire
                    }
                    return payload
                },
                verify: { readBack in
                    let p = RouterParsers.parseLanConfig(readBack)
                    return p.lanIp == cleanIp &&
                        p.netMask == cleanMask &&
                        p.dhcpEnabled == dhcpEnabled &&
                        p.primaryDns == cleanDns1 &&
                        p.secondaryDns == cleanDns2
                },
                parseResult: { RouterParsers.parseLanConfig($0) },
                writeTimeoutSeconds: 18.0,
                verifyRetries: 5,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true
            )
        )
        if case .ok(let cfg) = res {
            self.lanConfig = cfg
            self.dnsConfig = DnsConfig(primary: cfg.primaryDns, secondary: cfg.secondaryDns, dhcpEnabled: cfg.dhcpEnabled)
        }
        return res
    }

    @discardableResult
    public func loadStaticLeases() async -> RouterResult<[StaticDhcpLease]> {
        let res = await session.execute(cmd: 115, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let list = Self.parseStaticLeases(json)
        self.staticLeases = list
        return .ok(list)
    }

    @discardableResult
    public func saveStaticLeases(_ leases: [StaticDhcpLease], syncIpMacBinding: Bool = false) async -> RouterResult<[StaticDhcpLease]> {
        var clean: [StaticDhcpLease] = []
        var seenIps = Set<String>()
        var seenMacs = Set<String>()
        for lease in leases {
            let ip = lease.ip.trimmingCharacters(in: .whitespacesAndNewlines)
            if !NetworkFormatters.isIpv4(ip) {
                return .badResponse(message: "Invalid IPv4 address: \(ip)", rawBody: "")
            }
            guard let mac = Phase71Validators.normalizeMac(lease.mac),
                  Phase71Validators.validateUnicastMac(mac) == nil else {
                return .badResponse(message: "Invalid unicast MAC address: \(lease.mac)", rawBody: "")
            }
            if !seenIps.insert(ip).inserted {
                return .badResponse(message: "Duplicate IP in static leases: \(ip)", rawBody: "")
            }
            if !seenMacs.insert(mac).inserted {
                return .badResponse(message: "Duplicate MAC in static leases: \(mac)", rawBody: "")
            }
            clean.append(StaticDhcpLease(ip: ip, mac: mac))
        }

        let wireDatas = clean.map { ["ip": $0.ip, "mac": $0.mac] }
        let res = await writer.write(
            WriteSpec<[StaticDhcpLease]>(
                cmd: 115,
                buildPayload: { _ in ["datas": wireDatas] },
                verify: { Self.parseStaticLeases($0) == clean },
                parseResult: { Self.parseStaticLeases($0) },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        guard case .ok(let saved) = res else { return res }
        self.staticLeases = saved

        if syncIpMacBinding {
            let bindDatas: [[String: Any]] = saved.map {
                ["ip": $0.ip, "mac": $0.mac, "ippro": "IPV4"]
            }
            _ = await writer.write(
                WriteSpec<Unit>(
                    cmd: 24,
                    buildPayload: { _ in ["datas": bindDatas] },
                    verify: { _ in true },
                    parseResult: { _ in Unit() },
                    skipPreRead: true,
                    skipVerifyRead: true
                )
            )
        }
        _ = await writer.applyFilter()
        return .ok(saved)
    }

    private static func parseStaticLeases(_ json: [String: Any]) -> [StaticDhcpLease] {
        guard let arr = json["datas"] as? [[String: Any]] else { return [] }
        return arr.compactMap { row in
            let ip = CodecHelpers.str(row, "ip").trimmingCharacters(in: .whitespacesAndNewlines)
            let mac = CodecHelpers.str(row, "mac").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            guard !ip.isEmpty, !mac.isEmpty else { return nil }
            return StaticDhcpLease(ip: ip, mac: mac)
        }
    }

    private static func mergeCmd3Payload(cur: [String: Any], primaryDns: String, secondaryDns: String) -> [String: Any] {
        var payload: [String: Any] = [
            "lanIp": CodecHelpers.firstNonBlank(cur, keys: ["lanIp"], defaultValue: "192.168.1.1"),
            "netMask": CodecHelpers.firstNonBlank(cur, keys: ["netMask"], defaultValue: "255.255.255.0"),
            "dhcpServer": CodecHelpers.str(cur, "dhcpServer", fallback: "1"),
            "ipBegin": CodecHelpers.firstNonBlank(cur, keys: ["ipBegin"], defaultValue: "192.168.1.100"),
            "ipEnd": CodecHelpers.firstNonBlank(cur, keys: ["ipEnd"], defaultValue: "192.168.1.200"),
            "expireTime": CodecHelpers.firstNonBlank(cur, keys: ["expireTime"], defaultValue: "24h"),
            "main_dns": primaryDns,
            "vice_dns": secondaryDns
        ]
        for key in ["bindPort0", "ipv6_mode", "ipv6_startIp", "ipv6_endIp", "ipv6_main_dns", "ipv6_vice_dns", "relay_sw", "mtu", "domain", "supportList", "lanMark"] {
            if let v = cur[key] {
                payload[key] = v
            }
        }
        return payload
    }
}
