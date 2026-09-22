import Foundation
import CryptoKit

public actor ZltRouterClient {
    private let settingsStore: SettingsStore

    private var sessionId: String?
    private var lastPollTime: Date?
    private var prevWanRxBytes: Int64?
    private var prevWanTxBytes: Int64?
    private var prevWanTime: Date?

    private var history: [BandwidthPoint] = []
    private let maxHistory = 60

    private var lastSlowPollTime: Date?
    private let slowInterval: TimeInterval = 20.0
    private var cachedLatestChart: [String: Any] = [:]
    private var cachedCell160: [String: Any] = [:]
    private var cachedBand161: [String: Any] = [:]

    public private(set) var peakDlMbps: Double = 0.0
    public private(set) var peakUlMbps: Double = 0.0

    private let session: URLSession
    private var loginTask: Task<Bool, Never>?

    public init(settingsStore: SettingsStore = .shared) {
        self.settingsStore = settingsStore
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 25.0
        config.timeoutIntervalForResource = 35.0
        config.waitsForConnectivity = false
        self.session = URLSession(configuration: config)
    }

    private var cgiUrl: URL? {
        URL(string: "http://\(settingsStore.routerHost)/cgi-bin/http.cgi")
    }

    public func resetState() {
        sessionId = nil
        lastPollTime = nil
        prevWanRxBytes = nil
        prevWanTxBytes = nil
        prevWanTime = nil
        lastSlowPollTime = nil
        history.removeAll()
        peakDlMbps = 0.0
        peakUlMbps = 0.0
    }

    private func sha256(_ input: String) -> String {
        let digest = SHA256.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func post(body: [String: Any], timeout: TimeInterval = 4.0) async throws -> [String: Any] {
        guard let url = cgiUrl else {
            throw URLError(.badURL)
        }
        let jsonData = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = jsonData
        request.timeoutInterval = timeout
        request.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            return [:]
        }
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return json
        }
        return [:]
    }

    private func postRaw(body: [String: Any], timeout: TimeInterval = 5.0) async throws -> String {
        guard let url = cgiUrl else {
            throw URLError(.badURL)
        }
        let jsonData = try JSONSerialization.data(withJSONObject: body)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = jsonData
        request.timeoutInterval = timeout
        request.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            return ""
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public func login() async -> Bool {
        if let inFlight = loginTask {
            return await inFlight.value
        }
        let task = Task { [self] () -> Bool in
            await performLogin()
        }
        loginTask = task
        let result = await task.value
        loginTask = nil
        return result
    }

    private func performLogin() async -> Bool {
        do {
            // 1. Fetch challenge token via GET_NEXT_LOGIN_TIME (cmd 232)
            let tokenPayload: [String: Any] = [
                "cmd": 232,
                "method": "GET",
                "sessionId": ""
            ]
            let tokenResp = try await post(body: tokenPayload, timeout: 4.0)
            guard let token = tokenResp["token"] as? String, !token.isEmpty else {
                return false
            }

            // 2. Compute SHA256(token + password)
            let pwdHash = sha256(token + settingsStore.password)
            let newSessionId = UUID().uuidString.replacingOccurrences(of: "-", with: "") +
                               UUID().uuidString.replacingOccurrences(of: "-", with: "")

            // 3. Post authentication (cmd 100)
            let loginPayload: [String: Any] = [
                "cmd": 100,
                "method": "POST",
                "sessionId": newSessionId,
                "username": settingsStore.username,
                "passwd": pwdHash,
                "isAutoUpgrade": "0",
                "subcmd": 0
            ]
            let loginResp = try await post(body: loginPayload, timeout: 4.0)

            let isSuccess = (loginResp["success"] as? Bool == true) ||
                            (loginResp["message"] as? String == "success") ||
                            (loginResp["sessionId"] != nil)

            if isSuccess {
                if let sid = loginResp["sessionId"] as? String, !sid.isEmpty {
                    self.sessionId = sid
                } else {
                    self.sessionId = newSessionId
                }
                return true
            }
            return false
        } catch {
            return false
        }
    }

    public func query(
        cmdId: Int,
        method: String = "GET",
        extra: [String: Any] = [:],
        timeout: TimeInterval = 4.0
    ) async -> [String: Any] {
        if sessionId == nil {
            let ok = await login()
            if !ok { return [:] }
        }

        var payload: [String: Any] = [
            "cmd": cmdId,
            "method": method,
            "sessionId": sessionId ?? ""
        ]
        for (k, v) in extra {
            payload[k] = v
        }

        let isWrite = (method == "POST")

        do {
            var res = try await post(body: payload, timeout: timeout)
            let msg = res["message"] as? String ?? ""
            let sessionExpired = (msg == "NO_AUTH" || msg == "LOGIN_TIMEOUT")

            if sessionExpired || (!isWrite && res["success"] as? Bool == false) {
                if await login() {
                    payload["sessionId"] = sessionId ?? ""
                    if payload["token"] != nil {
                        let t233 = await query(cmdId: 233, method: "GET")
                        payload["token"] = t233["token"] as? String ?? ""
                    }
                    res = (try? await post(body: payload, timeout: timeout)) ?? [:]
                }
            }
            return res
        } catch {
            return [:]
        }
    }

    public func queryRaw(
        cmdId: Int,
        method: String = "GET",
        extra: [String: Any] = [:],
        timeout: TimeInterval = 5.0
    ) async -> String {
        if sessionId == nil {
            let ok = await login()
            if !ok { return "" }
        }

        var payload: [String: Any] = [
            "cmd": cmdId,
            "method": method,
            "sessionId": sessionId ?? ""
        ]
        for (k, v) in extra {
            payload[k] = v
        }

        do {
            var reply = try await postRaw(body: payload, timeout: timeout)
            if reply.contains("\"message\":\"NO_AUTH\"") || reply.contains("\"message\":\"LOGIN_TIMEOUT\"") {
                if await login() {
                    payload["sessionId"] = sessionId ?? ""
                    reply = (try? await postRaw(body: payload, timeout: timeout)) ?? ""
                }
            }
            return reply
        } catch {
            return ""
        }
    }

    private func readWanBytes() async -> (Int64, Int64)? {
        let res = await query(cmdId: 18)
        let rxStr = res["rxBytes"] as? String ?? "\(res["rxBytes"] ?? "")"
        let txStr = res["txBytes"] as? String ?? "\(res["txBytes"] ?? "")"

        guard let rx = Int64(rxStr), let tx = Int64(txStr), rx > 0 || tx > 0 else {
            return nil
        }
        return (rx, tx)
    }

    private func computeWanSpeed(now: Date) async -> (Double, Double) {
        guard let (rx, tx) = await readWanBytes() else {
            return (0.0, 0.0)
        }

        let prevRx = prevWanRxBytes
        let prevTx = prevWanTxBytes
        let prevT = prevWanTime

        prevWanRxBytes = rx
        prevWanTxBytes = tx
        prevWanTime = now

        guard let prevRx = prevRx, let prevTx = prevTx, let prevT = prevT else {
            return (0.0, 0.0)
        }

        let dt = now.timeIntervalSince(prevT)
        if dt <= 0.05 { return (0.0, 0.0) }

        let dRx = rx - prevRx
        let dTx = tx - prevTx

        if dRx < 0 || dTx < 0 {
            return (0.0, 0.0)
        }

        let rxKbps = (Double(dRx) / dt) / 1024.0
        let txKbps = (Double(dTx) / dt) / 1024.0

        return (
            (rxKbps * 100).rounded() / 100.0,
            (txKbps * 100).rounded() / 100.0
        )
    }

    private func refreshSlowData(now: Date, force: Bool = false) async {
        if !force, let lastSlow = lastSlowPollTime, now.timeIntervalSince(lastSlow) < slowInterval {
            return
        }
        lastSlowPollTime = now

        // cmd 1018: hardware stats & RF metrics
        let statusChart = await query(cmdId: 1018)
        if let devInfo = statusChart["device_info"] as? [[String: Any]], let last = devInfo.last {
            cachedLatestChart = last
        }

        // cmd 160: cellular cell lock status
        let cell = await query(cmdId: 160)
        if cell["success"] as? Bool != false && cell.count > 1 {
            cachedCell160 = cell
        }

        // cmd 161: band lock switches
        let band = await query(cmdId: 161)
        if band["success"] as? Bool != false && band.count > 1 {
            cachedBand161 = band
        }
    }

    public func pollMetrics() async -> Result<RouterState, Error> {
        let now = Date()

        if sessionId == nil {
            let ok = await login()
            if !ok {
                return .failure(URLError(.userAuthenticationRequired))
            }
        }

        var dash = await query(cmdId: 401)
        var sysStatus = await query(cmdId: 113)

        if dash.isEmpty && sysStatus.isEmpty {
            sessionId = nil
            if await login() {
                dash = await query(cmdId: 401)
                sysStatus = await query(cmdId: 113)
            }
            if dash.isEmpty && sysStatus.isEmpty {
                return .failure(URLError(.cannotConnectToHost))
            }
        }

        let hasValidResponse = (!dash.isEmpty && dash["error"] == nil) ||
                               (!sysStatus.isEmpty && sysStatus["error"] == nil)
        guard hasValidResponse else {
            return .failure(URLError(.badServerResponse))
        }

        let dhcp = await query(cmdId: 223)
        let wifi5g = await query(cmdId: 225)
        let wifi24g = await query(cmdId: 224)

        await refreshSlowData(now: now, force: (lastSlowPollTime == nil))
        let latestChart = cachedLatestChart

        // DHCP list
        let dhcpArray = (dhcp["dhcp_list_info"] as? [[String: Any]]) ??
                        (dash["dhcp_list_info"] as? [[String: Any]]) ?? []

        // Wi-Fi mapping
        var wifiMap: [String: [String: Any]] = [:]

        let w5g = (wifi5g["wlan5g_wifi_info"] as? [[String: Any]]) ?? []
        for var item in w5g {
            let mac = (item["mac"] as? String ?? "").lowercased().trimmingCharacters(in: .whitespaces)
            let ip = (item["ip"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            item["band"] = "5 GHz (Wi-Fi 6/ac)"
            if !mac.isEmpty { wifiMap[mac] = item }
            if !ip.isEmpty { wifiMap[ip] = item }
        }

        let w24g = (wifi24g["wlan24g_wifi_info"] as? [[String: Any]]) ?? []
        for var item in w24g {
            let mac = (item["mac"] as? String ?? "").lowercased().trimmingCharacters(in: .whitespaces)
            let ip = (item["ip"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            item["band"] = "2.4 GHz (Wi-Fi)"
            if !mac.isEmpty { wifiMap[mac] = item }
            if !ip.isEmpty { wifiMap[ip] = item }
        }

        // WAN Speeds
        let (rxKbps, txKbps) = await computeWanSpeed(now: now)
        let dlMbps = ((rxKbps * 8.0) / 1024.0 * 100).rounded() / 100.0
        let ulMbps = ((txKbps * 8.0) / 1024.0 * 100).rounded() / 100.0

        if dlMbps > peakDlMbps { peakDlMbps = dlMbps }
        if ulMbps > peakUlMbps { peakUlMbps = ulMbps }

        // Devices
        var devices: [DeviceItem] = []
        for d in dhcpArray {
            let mac = (d["mac"] as? String ?? "").lowercased().trimmingCharacters(in: .whitespaces)
            let ip = (d["ip"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            let rawHost = (d["hostname"] as? String ?? "").trimmingCharacters(in: .whitespaces)
            let hostname = (rawHost.isEmpty || rawHost.caseInsensitiveCompare("null") == .orderedSame) ? "Unknown Device" : rawHost

            let wInfo = wifiMap[mac] ?? wifiMap[ip]
            let rawInterface = d["interface"] as? String ?? "wlan"
            let band = (wInfo?["band"] as? String) ?? (rawInterface != "wlan" ? "Ethernet (LAN)" : "Wi-Fi")
            let rssi = wInfo?["rssi"] as? String ?? ""
            let txRate = wInfo?["txrate"] as? String ?? ""
            let rxRate = wInfo?["rxrate"] as? String ?? ""
            let ssid = wInfo?["ssid"] as? String ?? ""

            var signalPct = 0
            if !rssi.isEmpty, let rVal = Double(rssi) {
                signalPct = max(5, min(100, Int(((rVal + 100) * 1.42))))
            } else if band.contains("LAN") || band.contains("Ethernet") {
                signalPct = 100
            }

            let customAlias = settingsStore.getAlias(for: mac, fallback: settingsStore.getAlias(for: ip, fallback: hostname))
            let devType = inferDeviceType(name: "\(hostname) \(customAlias)", mac: mac, band: band)
            let isRouter = (ip == "192.168.1.1" || ip == "192.168.1.2" || hostname.lowercased().contains("zlt"))

            devices.append(
                DeviceItem(
                    mac: mac,
                    ip: ip,
                    hostname: hostname,
                    alias: customAlias == hostname ? "" : customAlias,
                    type: devType,
                    band: band,
                    ssid: ssid,
                    rssi: rssi,
                    signalPercent: signalPct,
                    txRateMbps: txRate.isEmpty ? "-" : txRate,
                    rxRateMbps: rxRate.isEmpty ? "-" : rxRate,
                    interfaceName: rawInterface,
                    expires: d["expires"] as? String ?? "",
                    ipv6: d["ipv6"] as? String ?? "",
                    isRouter: isRouter
                )
            )
        }

        // Cellular
        let netTypeRaw = (sysStatus["network_type_str"] as? String) ?? (dash["network_type_str"] as? String) ?? ""
        let netType = (netTypeRaw.isEmpty || netTypeRaw == "null") ? "-" : netTypeRaw

        func parseSignalLevel(_ dict: [String: Any]) -> Int {
            if let lvl = dict["signal_lvl"] as? Int { return lvl }
            if let str = dict["signal_lvl"] as? String, let lvl = Int(str.trimmingCharacters(in: .whitespaces)) { return lvl }
            return 0
        }
        let sysLvl = parseSignalLevel(sysStatus)
        let dashLvl = parseSignalLevel(dash)
        let signalLvl = sysLvl != 0 ? sysLvl : dashLvl

        let cellular = CellularMetrics(
            networkType: netType,
            signalLvl: signalLvl,
            rsrp5g: (latestChart["rsrp_5g"] as? String ?? dash["RSRP_5G"] as? String ?? "-"),
            sinr5g: (latestChart["sinr_5g"] as? String ?? "-"),
            rsrp4g: (latestChart["rsrp_4g"] as? String ?? dash["RSRP"] as? String ?? "-"),
            sinr4g: (latestChart["sinr_4g"] as? String ?? "-"),
            operatorName: (dash["network_operator"] as? String ?? "Airtel"),
            boardType: (sysStatus["board_type"] as? String ?? "ZLT X17M"),
            iduType: (sysStatus["idu_dev_type"] as? String ?? "ZLT W304VA PRO"),
            nrCqi: (latestChart["nr_cqi"] as? String ?? "-"),
            lteCqi: (latestChart["lte_cqi"] as? String ?? "-"),
            nrQamDl: (latestChart["nr_qam_dl"] as? String ?? "-")
        )

        // Cell Lock
        let cell160 = cachedCell160
        let band161 = cachedBand161

        func tf(_ key: String, in dict: [String: Any]) -> Bool {
            let v = "\(dict[key] ?? "")"
            return v == "1" || v.lowercased() == "true"
        }

        let cellLock = CellLockInfo(
            servingPci4g: cell160["PCI"] as? String ?? "-",
            servingFreq4g: cell160["FREQ"] as? String ?? "-",
            servingPci5g: cell160["PCI_5G"] as? String ?? "-",
            servingFreq5g: cell160["FREQ_5G"] as? String ?? "-",
            currentBands4g: cell160["current_band"] as? String ?? "-",
            currentBand5g: cell160["current_band_5g"] as? String ?? "-",
            lteLockEnabled: tf("lte_lock_sw", in: cell160),
            lteLockPci: cell160["lte_lock_pci"] as? String ?? "-",
            lteLockFreq: cell160["lte_lock_freq"] as? String ?? "-",
            nrLockEnabled: tf("nr_lock_sw", in: cell160),
            nrLockPci: cell160["nr_lock_pci"] as? String ?? "-",
            nrLockFreq: cell160["nr_lock_freq"] as? String ?? "-",
            bandLock4gEnabled: tf("band_4g_switch", in: band161),
            bandLock5gEnabled: tf("band_5g_switch", in: band161)
        )

        // Hardware
        let hardware = HardwareMetrics(
            cpuUsage: (latestChart["cpu_usage"] as? Double) ?? Double("\(latestChart["cpu_usage"] ?? "0")") ?? 0.0,
            temperature: latestChart["temperature"] as? String ?? "--",
            memory: latestChart["memory"] as? String ?? "--"
        )

        // History
        let df = DateFormatter()
        df.dateFormat = "HH:mm:ss"
        let timeFmt = df.string(from: now)

        history.append(
            BandwidthPoint(
                timeFormatted: timeFmt,
                downloadMbps: dlMbps,
                uploadMbps: ulMbps,
                rxKbps: rxKbps,
                txKbps: txKbps
            )
        )
        if history.count > maxHistory {
            history.removeFirst(history.count - maxHistory)
        }

        lastPollTime = now

        let wan = WanMetrics(
            downloadKbps: rxKbps,
            uploadKbps: txKbps,
            downloadMbps: dlMbps,
            uploadMbps: ulMbps,
            dlFlowMb: dash["flow_dl"] as? String ?? "-",
            ulFlowMb: dash["flow_ul"] as? String ?? "-",
            totalFlowMb: dash["mon_total_flow"] as? String ?? "-",
            peakDlMbps: peakDlMbps,
            peakUlMbps: peakUlMbps
        )

        let state = RouterState(
            timestamp: Int64(now.timeIntervalSince1970 * 1000),
            timeFormatted: timeFmt,
            wan: wan,
            cellular: cellular,
            cellLock: cellLock,
            hardware: hardware,
            devices: devices,
            history: history
        )

        return .success(state)
    }

    public func reboot() async -> Bool {
        let res = await query(cmdId: 6, method: "POST", extra: ["rebootType": 1])
        return (res["success"] as? Bool == true) || (res["message"] as? String == "success")
    }

    // MARK: - LAN DNS Configuration

    private let lanSaveKeys = [
        "lanIp", "netMask", "dhcpServer", "main_dns", "vice_dns",
        "ipBegin", "ipEnd", "expireTime", "ipv6_mode",
        "ipv6_startIp", "ipv6_endIp", "ipv6_main_dns", "ipv6_vice_dns"
    ]

    internal static func isIpv4(_ value: String) -> Bool {
        let parts = value.split(separator: ".")
        guard parts.count == 4 else { return false }
        for p in parts {
            guard let n = Int(p), (0...255).contains(n) else { return false }
            if p.count > 1 && p.hasPrefix("0") { return false }
        }
        return true
    }

    public func getDns() async -> (Bool, DnsConfig) {
        let res = await query(cmdId: 3)
        guard res["success"] as? Bool == true, let mainDns = res["main_dns"] as? String else {
            return (false, DnsConfig())
        }
        return (
            true,
            DnsConfig(
                primary: mainDns,
                secondary: res["vice_dns"] as? String ?? "",
                dhcpEnabled: "\(res["dhcpServer"] ?? "")" == "1"
            )
        )
    }

    public func setDns(primary: String, secondary: String) async -> Result<String, Error> {
        let p = primary.trimmingCharacters(in: .whitespaces)
        let s = secondary.trimmingCharacters(in: .whitespaces)

        guard Self.isIpv4(p) else {
            return .failure(NSError(domain: "AirtelMonitor", code: 1, userInfo: [NSLocalizedDescriptionKey: "Primary DNS must be a valid IPv4 address"]))
        }
        if !s.isEmpty && !Self.isIpv4(s) {
            return .failure(NSError(domain: "AirtelMonitor", code: 2, userInfo: [NSLocalizedDescriptionKey: "Secondary DNS must be a valid IPv4 address or blank"]))
        }
        if p == s {
            return .failure(NSError(domain: "AirtelMonitor", code: 3, userInfo: [NSLocalizedDescriptionKey: "Primary and secondary DNS cannot be the same"]))
        }

        let current = await query(cmdId: 3)
        guard current["success"] as? Bool == true else {
            let msg = current["message"] as? String ?? "Could not read LAN settings"
            return .failure(NSError(domain: "AirtelMonitor", code: 4, userInfo: [NSLocalizedDescriptionKey: msg]))
        }

        guard "\(current["dhcpServer"] ?? "")" == "1" else {
            return .failure(NSError(domain: "AirtelMonitor", code: 5, userInfo: [NSLocalizedDescriptionKey: "DHCP server is off on the router"]))
        }

        var payload: [String: Any] = [:]
        for k in lanSaveKeys {
            payload[k] = "\(current[k] ?? "")"
        }
        if current["supportList"] != nil {
            payload["bindPort0"] = "\(current["bindPort0"] ?? "")"
        }
        payload["main_dns"] = p
        payload["vice_dns"] = s

        let t233 = await query(cmdId: 233)
        payload["token"] = t233["token"] as? String ?? ""

        let res = await query(cmdId: 3, method: "POST", extra: payload)
        guard res["success"] as? Bool != false else {
            let msg = res["message"] as? String ?? "Router rejected DNS change"
            return .failure(NSError(domain: "AirtelMonitor", code: 6, userInfo: [NSLocalizedDescriptionKey: msg]))
        }

        var lastRead: DnsConfig? = nil
        for _ in 0..<8 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            let (ok, check) = await getDns()
            if !ok { continue }
            lastRead = check
            if check.primary == p && check.secondary == s {
                return .success("DNS updated and confirmed by router")
            }
        }
        if let last = lastRead {
            let kept = "\(last.primary) / \(last.secondary.isEmpty ? "router" : last.secondary)"
            return .failure(NSError(domain: "AirtelMonitor", code: 7, userInfo: [NSLocalizedDescriptionKey: "Router accepted the save but kept \(kept)"]))
        }
        return .success("DNS saved, but router confirmation is pending. Check reload shortly.")
    }

    // MARK: - Wi-Fi Radio Management

    private func decodeBase64Ssid(_ raw: String) -> String {
        guard let data = Data(base64Encoded: raw), let str = String(data: data, encoding: .utf8) else {
            return raw
        }
        return str
    }

    public func getWifiRadios() async -> (Bool, WifiRadioConfig) {
        let res24 = await query(cmdId: 2, extra: ["subcmd": 0])
        let res5 = await query(cmdId: 211, extra: ["subcmd": 0])

        let success24 = (res24["success"] as? Bool == true) || (res24["wifiOpen"] != nil)
        let success5 = (res5["success"] as? Bool == true) || (res5["wifiOpen"] != nil)

        guard success24 || success5 else {
            return (false, WifiRadioConfig())
        }

        let enabled24 = "\(res24["wifiOpen"] ?? "1")" == "1"
        let ssid24 = decodeBase64Ssid(res24["ssid"] as? String ?? "")
        let chan24 = res24["wifi24Channel"] as? String ?? ""

        let enabled5 = "\(res5["wifiOpen"] ?? "1")" == "1"
        let ssid5 = decodeBase64Ssid(res5["ssid"] as? String ?? "")
        let chan5 = res5["wifi5Channel"] as? String ?? ""

        return (
            true,
            WifiRadioConfig(
                enabled24g: enabled24,
                ssid24g: ssid24,
                channel24g: chan24,
                enabled5g: enabled5,
                ssid5g: ssid5,
                channel5g: chan5
            )
        )
    }

    private func isBandEnabled(cmd: Int) async -> Bool? {
        let res = await query(cmdId: cmd, extra: ["subcmd": 0])
        guard let open = res["wifiOpen"] else { return nil }
        return "\(open)" == "1"
    }

    public func setWifi24g(enabled: Bool) async -> Result<String, Error> {
        await setWifiBand(cmd: 2, label: "2.4 GHz", otherCmd: 211, otherLabel: "5 GHz", enabled: enabled)
    }

    public func setWifi5g(enabled: Bool) async -> Result<String, Error> {
        await setWifiBand(cmd: 211, label: "5 GHz", otherCmd: 2, otherLabel: "2.4 GHz", enabled: enabled)
    }

    private func setWifiBand(cmd: Int, label: String, otherCmd: Int, otherLabel: String, enabled: Bool) async -> Result<String, Error> {
        let stateStr = enabled ? "on" : "off"

        let current = await query(cmdId: cmd, extra: ["subcmd": 0])
        guard current["wifiOpen"] != nil else {
            let msg = current["message"] as? String ?? "Could not read \(label) Wi-Fi settings"
            return .failure(NSError(domain: "AirtelMonitor", code: 10, userInfo: [NSLocalizedDescriptionKey: msg]))
        }

        if ("\(current["wifiOpen"] ?? "")" == "1") == enabled {
            return .success("\(label) Wi-Fi is already \(stateStr)")
        }

        // Safety safeguard: never turn off both radios!
        if !enabled {
            let otherState = await isBandEnabled(cmd: otherCmd)
            if otherState == false {
                return .failure(NSError(domain: "AirtelMonitor", code: 11, userInfo: [NSLocalizedDescriptionKey: "\(otherLabel) is off. Turning off \(label) too would disconnect every wireless device, including this iPhone. Turn on \(otherLabel) first."]))
            } else if otherState == nil {
                return .failure(NSError(domain: "AirtelMonitor", code: 12, userInfo: [NSLocalizedDescriptionKey: "Could not confirm \(otherLabel) is on, so \(label) was left on."]))
            }
        }

        var payload: [String: Any] = [
            "subcmd": 0,
            "wifiOpen": enabled ? "1" : "0",
            "broadcast": "\(current["broadcast"] ?? "1")",
            "ssid": "\(current["ssid"] ?? "")",
            "key": "\(current["key"] ?? "")",
            "authenticationType": "\(current["authenticationType"] ?? "2")"
        ]
        if current["wifiSames"] != nil {
            payload["wifiSames"] = "\(current["wifiSames"] ?? "0")"
        }
        let t233 = await query(cmdId: 233)
        payload["token"] = t233["token"] as? String ?? ""

        let res = await query(cmdId: cmd, method: "POST", extra: payload, timeout: 20.0)
        let msg = res["message"] as? String ?? ""
        if res["success"] as? Bool == false && !msg.isEmpty && msg != "0" && msg != "success" {
            return .failure(NSError(domain: "AirtelMonitor", code: 13, userInfo: [NSLocalizedDescriptionKey: msg]))
        }

        var lastRead: Bool? = nil
        for _ in 0..<10 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if let now = await isBandEnabled(cmd: cmd) {
                lastRead = now
                if now == enabled {
                    return .success("\(label) Wi-Fi turned \(stateStr), confirmed by router")
                }
            }
        }
        if let last = lastRead {
            return .failure(NSError(domain: "AirtelMonitor", code: 14, userInfo: [NSLocalizedDescriptionKey: "Router kept \(label) Wi-Fi \(last ? "on" : "off")"]))
        }
        return .failure(NSError(domain: "AirtelMonitor", code: 15, userInfo: [NSLocalizedDescriptionKey: "Sent, but the router did not answer verification. If this phone was on \(label), reconnect and refresh."]))
    }

    // MARK: - Network Diagnosis (Ping & Traceroute)

    public func startPing(target: String, count: Int = 4) async -> Result<Void, Error> {
        let clean = target.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else {
            return .failure(NSError(domain: "AirtelMonitor", code: 20, userInfo: [NSLocalizedDescriptionKey: "Target IP or hostname is required"]))
        }
        let res = await query(
            cmdId: 168,
            method: "POST",
            extra: [
                "subcmd": 0,
                "pingTimes": count,
                "url": clean,
                "wan_index": ""
            ]
        )
        guard res["success"] as? Bool != false else {
            let msg = res["message"] as? String ?? "Failed to start ping"
            return .failure(NSError(domain: "AirtelMonitor", code: 21, userInfo: [NSLocalizedDescriptionKey: msg]))
        }
        return .success(())
    }

    public func getPingOutput() async -> String {
        let raw = await queryRaw(
            cmdId: 204,
            method: "GET",
            extra: [
                "url": "/tmp/tzwww/pingrt",
                "subcmd": 168
            ]
        )
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{") && trimmed.contains("\"success\"") {
            return ""
        }
        return trimmed
    }

    public func stopPing() async {
        _ = await query(
            cmdId: 168,
            method: "POST",
            extra: [
                "subcmd": 0,
                "pingTimes": 0,
                "url": "",
                "wan_index": ""
            ]
        )
    }

    public func startTrace(target: String) async -> Result<Void, Error> {
        let clean = target.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else {
            return .failure(NSError(domain: "AirtelMonitor", code: 30, userInfo: [NSLocalizedDescriptionKey: "Target IP or hostname is required"]))
        }
        let res = await query(
            cmdId: 168,
            method: "POST",
            extra: [
                "subcmd": 2,
                "stopped": "0",
                "port": -1,
                "url": clean,
                "wan_index": ""
            ]
        )
        guard res["success"] as? Bool != false else {
            let msg = res["message"] as? String ?? "Failed to start traceroute"
            return .failure(NSError(domain: "AirtelMonitor", code: 31, userInfo: [NSLocalizedDescriptionKey: msg]))
        }
        return .success(())
    }

    public func isTraceRunning() async -> Bool {
        let res = await query(cmdId: 168, method: "GET", extra: ["subcmd": 2])
        return "\(res["message"] ?? "0")" == "1"
    }

    public func getTraceOutput() async -> String {
        let raw = await queryRaw(
            cmdId: 204,
            method: "GET",
            extra: [
                "url": "/tmp/tzwww/tracepathrt",
                "subcmd": 168
            ]
        )
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{") && trimmed.contains("\"success\"") {
            return ""
        }
        return trimmed
    }

    public func stopTrace() async {
        _ = await query(
            cmdId: 168,
            method: "POST",
            extra: [
                "subcmd": 2,
                "stopped": "1",
                "port": -1,
                "url": "",
                "wan_index": ""
            ]
        )
    }

    private func inferDeviceType(name: String, mac: String, band: String) -> DeviceType {
        let h = name.lowercased()
        let phoneKeywords = ["pixel", "iphone", "galaxy", "android", "redmi", "xiaomi", "oppo", "vivo", "realme", "oneplus", "huawei", "honor", "tecno", "infinix"]
        if phoneKeywords.contains(where: { h.contains($0) }) { return .phone }

        let tabKeywords = ["ipad", "tab", "tablet"]
        if tabKeywords.contains(where: { h.contains($0) }) { return .tablet }

        let pcKeywords = ["pc", "laptop", "desktop", "macbook", "thinkpad", "dell", "hp", "lenovo", "asus", "acer", "msi", "surface"]
        if pcKeywords.contains(where: { h.contains($0) }) { return .pc }

        let tvKeywords = ["tv", "appletv", "roku", "firetv", "bravia", "chromecast", "webos", "tizen", "smarttv"]
        if tvKeywords.contains(where: { h.contains($0) }) { return .tv }

        let gameKeywords = ["playstation", "ps4", "ps5", "xbox", "nintendo", "switch"]
        if gameKeywords.contains(where: { h.contains($0) }) { return .gaming }

        let routerKeywords = ["zlt", "router", "ap", "repeater", "extender", "w304"]
        if routerKeywords.contains(where: { h.contains($0) }) { return .router }

        return .device
    }
}
