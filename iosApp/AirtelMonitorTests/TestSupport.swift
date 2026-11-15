import Foundation
import XCTest
@testable import AirtelMonitor

enum TestRouterError: Error, LocalizedError {
    case network(String)

    var errorDescription: String? {
        switch self {
        case .network(let message): return message
        }
    }
}

final class TestBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func get() -> Value {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    @discardableResult
    func withLock<R>(_ body: (inout Value) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(&value)
    }
}

struct TestRouterRequest {
    let url: URL
    let cmd: Int
    let method: String
    let payload: [String: Any]
    let timeoutSeconds: TimeInterval
}

enum TestRouterReply {
    case json([String: Any])
    case text(String)
    case http(statusCode: Int, body: String)
    case error(Error)
}

final class TestRouterTransport: RouterTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var recordedRequests: [TestRouterRequest] = []
    private var handlers: [String: (TestRouterRequest) -> TestRouterReply] = [:]
    private var tokenCounter = 0

    init() {
        on(232, method: "GET") { _ in ["success": true, "token": "CHALLENGE-1"] }
        on(100, method: "POST") { _ in ["success": true, "sessionId": "SID-1", "account_level": "1"] }
        onReply(233, method: "GET") { [weak self] _ in
            guard let self else { return .json(["success": true, "token": "T0"]) }
            let next = self.nextTokenNumber()
            return .json(["success": true, "token": "T\(next)"])
        }
    }

    private func nextTokenNumber() -> Int {
        lock.lock()
        defer { lock.unlock() }
        tokenCounter += 1
        return tokenCounter
    }

    func on(_ cmd: Int, method: String? = nil, handler: @escaping (TestRouterRequest) -> [String: Any]) {
        onReply(cmd, method: method) { .json(handler($0)) }
    }

    func onReply(_ cmd: Int, method: String? = nil, handler: @escaping (TestRouterRequest) -> TestRouterReply) {
        lock.lock()
        defer { lock.unlock() }
        let key = method == nil ? "\(cmd)" : "\(cmd):\(method!.uppercased())"
        handlers[key] = handler
    }

    func onText(_ cmd: Int, method: String? = nil, handler: @escaping (TestRouterRequest) -> String) {
        onReply(cmd, method: method) { .text(handler($0)) }
    }

    func onError(_ cmd: Int, method: String? = nil, message: String = "simulated transport failure") {
        onReply(cmd, method: method) { _ in .error(TestRouterError.network(message)) }
    }

    private func recordAndLookupHandler(_ req: TestRouterRequest) -> ((TestRouterRequest) -> TestRouterReply)? {
        lock.lock()
        defer { lock.unlock() }
        recordedRequests.append(req)
        return handlers["\(req.cmd):\(req.method)"] ?? handlers["\(req.cmd)"]
    }

    func post(
        url: URL,
        jsonBody: [String: Any],
        timeoutSeconds: TimeInterval
    ) async throws -> RouterTransportResponse {
        XCTAssertTrue(
            JSONSerialization.isValidJSONObject(jsonBody),
            "Outgoing router payload for url \(url) is not valid JSON: \(jsonBody)"
        )
        let cmd = (jsonBody["cmd"] as? Int) ?? (jsonBody["cmd"] as? NSNumber)?.intValue ?? -1
        let method = ((jsonBody["method"] as? String) ?? "").uppercased()
        let req = TestRouterRequest(
            url: url,
            cmd: cmd,
            method: method,
            payload: jsonBody,
            timeoutSeconds: timeoutSeconds
        )

        let handler = recordAndLookupHandler(req)

        let reply = handler?(req) ?? .json(["success": true])
        switch reply {
        case .json(let dict):
            let data = try JSONSerialization.data(withJSONObject: dict)
            let body = String(data: data, encoding: .utf8) ?? "{}"
            return RouterTransportResponse(statusCode: 200, rawBody: body)
        case .text(let raw):
            return RouterTransportResponse(statusCode: 200, rawBody: raw)
        case .http(let statusCode, let body):
            return RouterTransportResponse(statusCode: statusCode, rawBody: body)
        case .error(let err):
            throw err
        }
    }

    func allRequests() -> [TestRouterRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recordedRequests
    }

    func sent(_ cmd: Int, method: String? = nil) -> [TestRouterRequest] {
        let wantMethod = method?.uppercased()
        return allRequests().filter { req in
            req.cmd == cmd && (wantMethod == nil || req.method == wantMethod)
        }
    }

    func cmdsSent() -> [Int] {
        allRequests().map(\.cmd)
    }

    func clearRequests() {
        lock.lock()
        defer { lock.unlock() }
        recordedRequests.removeAll()
    }
}

@MainActor
struct TestRouterEnv {
    let defaults: UserDefaults
    let keychain: InMemoryKeychainStore
    let preferences: AppPreferencesStore
    let transport: TestRouterTransport
    let session: RouterSession
    let writer: SettingsWriter

    func cleanup() {
        defaults.removePersistentDomain(forName: suiteName)
    }

    let suiteName: String
}

@MainActor
func makeTestEnv(
    host: String = "192.168.1.1",
    username: String = "root",
    password: String = "admin",
    onLoginSuccess: (@Sendable () async -> Void)? = nil
) -> TestRouterEnv {
    let suite = "dev.mattdev0.airtelmonitor.tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    let keychain = InMemoryKeychainStore()
    let preferences = AppPreferencesStore(defaults: defaults, keychain: keychain)
    preferences.updateConnectionSettings(host: host, username: username, password: password)
    let transport = TestRouterTransport()
    let session = RouterSession(
        transport: transport,
        credentialsProvider: { @MainActor in preferences.currentCredentials },
        onLoginSuccess: onLoginSuccess
    )
    let writer = SettingsWriter(session: session, sleepFn: { _ in })
    return TestRouterEnv(
        defaults: defaults,
        keychain: keychain,
        preferences: preferences,
        transport: transport,
        session: session,
        writer: writer,
        suiteName: suite
    )
}

func assertPayloadKeys(
    _ req: TestRouterRequest,
    exactDataKeys: Set<String>,
    requireToken: Bool = true,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    let sessionId = req.payload["sessionId"] as? String ?? ""
    XCTAssertFalse(sessionId.isEmpty, "Missing sessionId on cmd \(req.cmd)", file: file, line: line)
    if requireToken {
        let token = req.payload["token"] as? String ?? ""
        XCTAssertFalse(token.isEmpty, "Missing write token on cmd \(req.cmd)", file: file, line: line)
    }
    let envelope: Set<String> = ["cmd", "method", "sessionId", "token"]
    let actualDataKeys = Set(req.payload.keys.filter { !envelope.contains($0) })
    XCTAssertEqual(actualDataKeys, exactDataKeys, "Unexpected payload key set for cmd \(req.cmd)", file: file, line: line)
}

enum RouterFixtures {
    static func decode(_ raw: String) -> [String: Any] {
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return obj
    }

    static func json205Ca() -> [String: Any] { decode(raw205Ca) }
    static func json205Nsa() -> [String: Any] { decode(raw205Nsa) }
    static func json160Ca() -> [String: Any] { decode(raw160Ca) }
    static func json161() -> [String: Any] { decode(raw161) }
    static func json337() -> [String: Any] { decode(raw337) }
    static func json282() -> [String: Any] { decode(raw282) }
    static func json1018() -> [String: Any] { decode(raw1018) }
    static func json223() -> [String: Any] { decode(raw223) }
    static func json225() -> [String: Any] { decode(raw225) }
    static func json207() -> [String: Any] { decode(raw207) }
    static func json133() -> [String: Any] { decode(raw133) }
    static func json394() -> [String: Any] { decode(raw394) }
    static func json3() -> [String: Any] { decode(raw3) }

    static func wifiRecord(
        cmd: Int,
        on: Bool = true,
        ssidPlain: String = "Airtel_5G_Test",
        key: String = "placeholder-key",
        authType: String = "2",
        broadcast: String = "1",
        wifiSames: String = "0",
        maxStaNum: String = "32"
    ) -> [String: Any] {
        [
            "success": true,
            "cmd": cmd,
            "wifiOpen": on ? "1" : "0",
            "broadcast": broadcast,
            "wifiSames": wifiSames,
            "authenticationType": authType,
            "ssid": CodecHelpers.encodeBase64Utf8(ssidPlain),
            "key": key,
            "maxStaNum": maxStaNum
        ]
    }

    static let raw205Ca = """
    {
      "success": true,
      "cmd": 205,
      "network_type_str": "4G+",
      "network_operator": "Airtel NG",
      "signal_lvl": 4,
      "currentband": "7+1",
      "FREQ": "3400+225",
      "PCI": "393+393",
      "bandwidth": "10+15",
      "RSRP": "-84",
      "RSRQ": "-9",
      "RSSI": "-57",
      "SINR": "16",
      "CQI": "12",
      "dl_mcs": "22",
      "ul_mcs": "18",
      "max_dl_qam": "256QAM",
      "max_ul_qam": "64QAM",
      "rank_4g": "2",
      "bler_4g": "0.4",
      "ENODEBID": "10456",
      "CELL_ID": "2676737",
      "ECGI": "62120-2676737",
      "PLMN": "62120",
      "tac_4g": "4102",
      "ca_state": "1",
      "ca_freq": "",
      "ca_pci": "",
      "ca_bandwidth": "",
      "lte_dl_cc": "2",
      "nr_dl_cc": "0",
      "currentband_5g": "",
      "FREQ_5G": "",
      "PCI_5G": "",
      "bandwidth_5g": "",
      "RSRP_5G": "",
      "RSRQ_5G": "",
      "SINR_5G": "",
      "RSSI_5G": "",
      "flow_dl": "1420.50",
      "flow_ul": "312.25",
      "mon_total_flow": "264366.45",
      "onlineDuration": "14230"
    }
    """

    static let raw205Nsa = """
    {
      "success": true,
      "cmd": 205,
      "network_type_str": "5G NSA",
      "network_operator": "Airtel NG",
      "signal_lvl": 4,
      "currentband": "3+1",
      "FREQ": "1850+225",
      "PCI": "456+456",
      "bandwidth": "15+10",
      "RSRP": "-86",
      "RSRQ": "-10",
      "RSSI": "-59",
      "SINR": "15",
      "CQI": "11",
      "currentband_5g": "78",
      "FREQ_5G": "627264",
      "PCI_5G": "916",
      "bandwidth_5g": "100",
      "RSRP_5G": "-82",
      "RSRQ_5G": "-11",
      "SINR_5G": "21",
      "RSSI_5G": "-54",
      "CQI_5G": "14",
      "max_dl_qam_5g": "256QAM",
      "rank_5g": "4",
      "mon_total_flow": "264366.45",
      "onlineDuration": "18540"
    }
    """

    static let raw160Ca = """
    {
      "success": true,
      "cmd": 160,
      "PCI": "393+393",
      "FREQ": "3400+225",
      "current_band": "7+1",
      "lte_lock_sw": "1",
      "lte_lock_freq": "3400,225",
      "lte_lock_pci": "393,393",
      "lock_4g_flag": "1",
      "nr_lock_sw": "0",
      "nr_lock_freq": "",
      "nr_lock_pci": "",
      "nr_lock_cell_band": "",
      "lock_5g_flag": "0",
      "PCI_5G": "",
      "FREQ_5G": "",
      "current_band_5g": ""
    }
    """

    static let raw161 = """
    {
      "success": true,
      "cmd": 161,
      "all_band_4g": "120080800C5",
      "band_4g_switch": "1",
      "band_4g_mask": "45",
      "all_band_5g": "20000000010000000000",
      "band_5g_switch": "0",
      "band_5g_mask": "20000000010000000000"
    }
    """

    static let raw337 = """
    {
      "success": true,
      "cmd": 337,
      "limitSwitch": "1",
      "limitSize": "500",
      "flow_limit_unit": "GB",
      "startDate": "10",
      "warn_percentage": "80",
      "dl_mon_flow": "210000",
      "ul_mon_flow": "54366.45"
    }
    """

    static let raw282 = """
    {
      "success": true,
      "cmd": 282,
      "network_type_str": "4G+",
      "lte_info": "1850,372,-74,-13.5,13;1850,373,-74,-18,14;225,75,-97,-15.5,8",
      "nr_info": "",
      "wcdmaInfo": ""
    }
    """

    static let raw1018 = """
    {
      "success": true,
      "cmd": 1018,
      "device_info": [
        {
          "time": "14:20:00",
          "rsrp_4g": "-85",
          "sinr_4g": "14",
          "rsrp_5g": "",
          "sinr_5g": "",
          "lte_cqi": "11",
          "nr_cqi": "",
          "nr_qam_dl": "",
          "pci": "393",
          "cpu_usage": 6.5,
          "temperature": "53",
          "memory": "42"
        },
        {
          "time": "14:25:00",
          "rsrp_4g": "-84",
          "sinr_4g": "16",
          "rsrp_5g": "",
          "sinr_5g": "",
          "lte_cqi": "12",
          "nr_cqi": "",
          "nr_qam_dl": "",
          "pci": "393",
          "cpu_usage": 7.0,
          "temperature": "54",
          "memory": "43"
        }
      ]
    }
    """

    static let raw223 = """
    {
      "success": true,
      "cmd": 223,
      "dhcp_list_info": [
        {
          "mac": "02:11:22:33:44:01",
          "ip": "192.168.1.192",
          "hostname": "pixel-6",
          "interface": "wlan",
          "expires": "82340",
          "ipv6": ""
        },
        {
          "mac": "02:11:22:33:44:02",
          "ip": "192.168.1.50",
          "hostname": "NULL",
          "interface": "lan",
          "expires": "86100",
          "ipv6": ""
        }
      ]
    }
    """

    static let raw225 = """
    {
      "success": true,
      "cmd": 225,
      "wlan5g_wifi_info": [
        {
          "mac": "02:11:22:33:44:01",
          "ip": "192.168.1.192",
          "rssi": "-58",
          "txrate": "1201",
          "rxrate": "960",
          "ssid": "Airtel_5G_Test"
        }
      ]
    }
    """

    static let raw207 = """
    {
      "success": true,
      "cmd": 207,
      "uptime": "172800",
      "cpu_usage": "12.5",
      "device_temperature": "52",
      "memory": "44",
      "board_type": "ZLT X17M",
      "idu_dev_type": "ZLT W304VA PRO",
      "hwversion": "V1.0",
      "real_fwversion": "V1.2.3",
      "build_date": "2025-11-12",
      "IMSI": "REDACTED",
      "ICCID": "REDACTED",
      "module_imei": "REDACTED",
      "device_sn": "REDACTED",
      "idu_device_mac": "00:00:00:00:00:00",
      "lan_mac": "00:00:00:00:00:00"
    }
    """

    static let raw133 = """
    {
      "success": true,
      "cmd": 133,
      "network_type_str": "4G+",
      "wan_ip": "10.8.42.19",
      "wan_ipv6_ip": "",
      "uptime": "86410",
      "CELL_ID": "2676737",
      "PCI": "393+393",
      "FREQ": "3400+225",
      "RSRP": "-84",
      "RSRQ": "-9",
      "RSSI": "-57",
      "SINR": "16",
      "CQI": "12",
      "dl_mcs": "22",
      "ul_mcs": "18",
      "IMSI": "REDACTED",
      "ICCID": "REDACTED",
      "module_imei": "REDACTED",
      "device_sn": "REDACTED"
    }
    """

    static let raw394 = """
    {
      "success": true,
      "cmd": 394,
      "signal_lvl": 4,
      "network_type_str": "4G+",
      "sms_unread": "3",
      "data_switch": "1",
      "flightMode": "0"
    }
    """

    static let raw3 = """
    {
      "success": true,
      "cmd": 3,
      "lanIp": "192.168.1.1",
      "netMask": "255.255.255.0",
      "dhcpServer": "1",
      "main_dns": "1.1.1.1",
      "vice_dns": "8.8.8.8",
      "ipBegin": "192.168.1.100",
      "ipEnd": "192.168.1.200",
      "expireTime": "24h",
      "bindPort0": "f0f0f",
      "ipv6_mode": "0",
      "ipv6_startIp": "64",
      "ipv6_endIp": "ffaa",
      "ipv6_main_dns": "",
      "ipv6_vice_dns": "",
      "relay_sw": "0",
      "mtu": "1500",
      "domain": "m.home",
      "supportList": "70F0F03",
      "lanMark": "1"
    }
    """
}
