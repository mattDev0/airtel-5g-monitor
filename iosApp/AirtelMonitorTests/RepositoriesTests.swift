import Foundation
import XCTest
@testable import AirtelMonitor

@MainActor
final class RepositoriesTests: XCTestCase {
    private func makeStack() -> (MockRouterTransport, RouterSession, SettingsWriter, AppPreferencesStore) {
        let transport = MockRouterTransport()
        let suite = "RepositoriesTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let prefs = AppPreferencesStore(defaults: defaults, keychain: InMemoryKeychainStore())
        prefs.updateConnectionSettings(host: "192.168.1.1", username: "user", password: "secret")
        let session = RouterSession(
            transport: transport,
            credentialsProvider: { await prefs.currentCredentials }
        )
        let writer = SettingsWriter(session: session, sleepFn: { _ in })
        return (transport, session, writer, prefs)
    }

    func testStatusRepositoryPollFastRecordsDevicesAndUsage() async {
        let (transport, session, _, prefs) = makeStack()
        await transport.setHandler(cmd: 18) { _ in .ok(["rxBytes": "2048", "txBytes": "1024"]) }
        await transport.setHandler(cmd: 205) { _ in
            .ok([
                "network_type": "5G_NSA",
                "RSRP": "-91",
                "SINR": "14",
                "5G_RSRP": "-82",
                "5G_SINR": "20",
                "currentband": "B3",
                "FREQ": "1650",
                "PCI": "12",
                "currentband_5g": "n78",
                "FREQ_5G": "633984",
                "PCI_5G": "201",
                "flow_dl": "300",
                "flow_ul": "100"
            ])
        }
        await transport.setHandler(cmd: 133) { _ in
            .ok(["wan_ip": "10.20.30.40"])
        }
        await transport.setHandler(cmd: 401) { _ in
            .ok(["wan_ip": "10.20.30.40"])
        }
        await transport.setHandler(cmd: 223) { _ in
            .ok([
                "dhcp_list_info": [
                    ["mac": "AA:BB:CC:DD:EE:FF", "ip": "192.168.1.101", "hostname": "Pixel-6", "interface": "wlan0", "expires": "3600"]
                ]
            ])
        }
        await transport.setHandler(cmd: 225) { _ in
            .ok(["wlan5g_wifi_info": [["mac": "AA:BB:CC:DD:EE:FF", "rssi": "-55", "txrate": "866", "rxrate": "780", "ssid": "Airtel5G"]]])
        }
        await transport.setHandler(cmd: 337) { _ in
            .ok(["limitSwitch": "1", "limitSize": "100", "flow_limit_unit": "1", "ul_mon_flow": "2048", "dl_mon_flow": "512"])
        }
        await transport.setHandler(cmd: 394) { _ in .ok(["sms_unread": "2"]) }

        let repo = StatusRepository(session: session, preferences: prefs)
        let res = await repo.pollFast(forceSlow: true)
        guard case .ok(let state) = res else {
            return XCTFail("Expected pollFast to succeed")
        }
        XCTAssertEqual(state.wanIp, "10.20.30.40")
        XCTAssertEqual(state.unreadSmsCount, 2)
        XCTAssertEqual(state.devices.count, 1)
        XCTAssertEqual(state.devices.first?.band, "5 GHz")
        XCTAssertEqual(state.monthlyUsage.usedMb, 2560, accuracy: 0.01)
        XCTAssertEqual(prefs.dailyUsageEntries.count, 1)
    }

    func testWifiRepositoryGuardsLastActiveRadio() async {
        let (transport, session, writer, _) = makeStack()
        await transport.setHandler(cmd: 2, method: "GET") { _ in .ok(["wifiOpen": "1", "ssid": "Airtel_24G"]) }
        await transport.setHandler(cmd: 211, method: "GET") { _ in .ok(["wifiOpen": "0", "ssid": "Airtel_5G"]) }

        let repo = WifiRepository(session: session, writer: writer)
        let res = await repo.setWifiEnabled(band: .band24g, enabled: false)
        XCTAssertFalse(res.isOk)
        XCTAssertTrue((res.errorMessage ?? "").contains("5 GHz"))
    }

    func testMobileRepositoryPlmnGuardAndTrafficCalibration() async {
        let (transport, session, writer, _) = makeStack()
        let repo = MobileRepository(session: session, writer: writer)

        let lockedFlags = RouterFeatureFlags(lockPlmnFlag: true)
        let denied = await repo.setNetworkMode("4", flags: lockedFlags)
        XCTAssertFalse(denied.isOk)

        await transport.setHandler(cmd: 337, method: "GET") { _ in
            .ok(["limitSwitch": "1", "limitSize": "100", "flow_limit_unit": "1", "ul_mon_flow": "1000", "dl_mon_flow": "200"])
        }
        await transport.setHandler(cmd: 337, method: "POST") { _ in
            await transport.setHandler(cmd: 337, method: "GET") { _ in
                .ok(["limitSwitch": "1", "limitSize": "100", "flow_limit_unit": "1", "ul_mon_flow": "5000", "dl_mon_flow": "0"])
            }
            return .ok(["success": true])
        }

        let calibrated = await repo.calibrateTrafficStatistics(targetUsedMb: 5000)
        XCTAssertTrue(calibrated.isOk)
        let posts = await transport.recordedRequests().filter { $0.cmd == 337 && $0.method == "POST" }
        XCTAssertEqual(posts.count, 1)
        XCTAssertNotNil(posts.first?.payload["usedFlow"])
    }

    func testSimSmsAndUssdRepositories() async {
        let (transport, session, writer, _) = makeStack()

        // SIM change PIN (cmd 51 subcmd 4 -> pin + pinPasswordChange + enable: "2")
        await transport.setHandler(cmd: 51, method: "POST") { _ in .ok(["message": "0", "success": true]) }
        await transport.setHandler(cmd: 134, method: "GET") { _ in
            .ok(["lock_pin_switch": "1", "lock_pin_flag": "0", "pin_left_times": "3"])
        }
        await transport.setHandler(cmd: 113, method: "GET") { _ in .ok(["sim_status": "1"]) }
        let simRepo = SimRepository(session: session, writer: writer)
        let pinRes = await simRepo.changePin(oldPin: "1234", newPin: "4321")
        XCTAssertTrue(pinRes.isOk)
        let pinPost = await transport.recordedRequests().first { $0.cmd == 51 && $0.method == "POST" }
        XCTAssertEqual(pinPost?.payload["subcmd"] as? String, "4")
        XCTAssertEqual(pinPost?.payload["pinPasswordChange"] as? String, "4321")
        XCTAssertEqual(pinPost?.payload["enable"] as? String, "2")

        // SMS load & send (Base64 UTF-8 of "<idx> <readFlag> <phone> <yyyy-MM-dd HH:mm> <body...>")
        let rowB64 = CodecHelpers.encodeBase64Utf8("1 0 +234800 2026-05-01 10:00 Welcome to Airtel 5G")
        await transport.setHandler(cmd: 12, method: "GET") { _ in
            .ok([
                "sms_list": rowB64,
                "sms_total": "1"
            ])
        }
        await transport.setHandler(cmd: 14, method: "POST") { _ in .ok(["success": true, "message": "0"]) }
        var syncedUnread: Int?
        let smsRepo = SmsRepository(session: session, writer: writer, onUnreadCountSync: { syncedUnread = $0 })
        let smsPage = await smsRepo.loadPage(folder: .inbox)
        XCTAssertEqual(smsPage.getOrNull()?.messages.first?.text, "Welcome to Airtel 5G")
        XCTAssertEqual(syncedUnread, 1)

        let sendRes = await smsRepo.sendSms(phoneNumber: "+234800", body: "Hello")
        XCTAssertTrue(sendRes.isOk)

        // USSD send
        await transport.setHandler(cmd: 350, method: "POST") { _ in
            .ok(["success": true, "message": "1. Check Balance\n2. Data Plan", "need_reply": "1"])
        }
        let ussdRepo = UssdRepository(session: session, writer: writer)
        let ussdRes = await ussdRepo.send(input: "*123#")
        guard case .ok(let reply) = ussdRes else {
            return XCTFail("Expected USSD reply")
        }
        XCTAssertTrue(reply.needReply)
        XCTAssertTrue(reply.text.contains("Check Balance"))
    }

    func testSecurityParentalAndSystemRepositories() async {
        let (transport, session, writer, prefs) = makeStack()

        // Security: reserved system port check & firewall switch
        await transport.setHandler(cmd: 400, method: "GET") { _ in .ok(["systemPort": "80 443"]) }
        await transport.setHandler(cmd: 367, method: "GET") { _ in .ok(["fw_switch": "1", "level": "high"]) }
        let secRepo = SecurityRepository(session: session, writer: writer)
        _ = await secRepo.loadAll()
        let badPort = await secRepo.savePortForwards([
            PortForwardRule(port: "80", mappingPort: "80", mappingIp: "192.168.1.50")
        ])
        XCTAssertFalse(badPort.isOk)

        await transport.setHandler(cmd: 367, method: "POST") { _ in .ok(["success": true]) }
        let fwRes = await secRepo.saveFirewallSwitch(enabled: true, level: .high)
        XCTAssertTrue(fwRes.isOk)

        // Parental Control: speed rules cmd 382
        await transport.setHandler(cmd: 382, method: "GET") { _ in
            .ok(["datas": [["ip": "192.168.1.50", "maxSpeed": "512", "enableRule": true]]])
        }
        await transport.setHandler(cmd: 382, method: "POST") { _ in .ok(["success": true]) }
        let pcRepo = ParentalControlRepository(session: session, writer: writer)
        let speedRes = await pcRepo.saveSpeedRules([ParentalSpeedRule(ip: "192.168.1.50", speedKbps: 512)])
        XCTAssertEqual(speedRes.getOrNull()?.first?.speedKbps, 512)

        // System: log settings + logs + syncClientTime
        await transport.setHandler(cmd: 322, method: "GET") { _ in
            .ok(["logSize": "50000", "logGrade": "4"])
        }
        await transport.setHandler(cmd: 322, method: "POST") { _ in .ok(["success": true]) }
        await transport.setHandler(cmd: 17, method: "GET") { _ in
            .raw(200, "2026-05-01 10:00:00 [info] Modem connected\n")
        }
        await transport.setHandler(cmd: 321, method: "POST") { _ in .ok(["success": true]) }

        let sysRepo = SystemRepository(session: session, writer: writer, preferences: prefs)
        let logSave = await sysRepo.saveLogSettings(logSizeKb: 50, logGrade: .info)
        XCTAssertEqual(logSave.getOrNull()?.logSizeKb, 50)

        let logsRes = await sysRepo.loadSystemLogs()
        XCTAssertEqual(logsRes.getOrNull()?.count, 1)

        let timeSync = await sysRepo.syncClientTime(now: Date(timeIntervalSince1970: 1_777_629_600))
        XCTAssertTrue(timeSync.isOk)
    }

    func testAlertEvaluatorDeduplicationAndRecovery() {
        let cfg = MonitorAlertConfig(
            notifyQuotaWarning: true,
            notifyNewSms: true,
            notifyNewDevice: true,
            notifyUnreachable: true
        )
        let s0 = MonitorState()

        // Unreachable fires only after 2 consecutive failures
        let f1 = AlertEvaluator.evaluate(previous: s0, config: cfg, observation: .pollFailure)
        XCTAssertTrue(f1.alerts.isEmpty)
        let f2 = AlertEvaluator.evaluate(previous: f1.nextState, config: cfg, observation: .pollFailure)
        XCTAssertEqual(f2.alerts, [.routerUnreachable])

        // Recovery + baseline devices + SMS baseline + quota alert once per cycle
        let ok1 = AlertEvaluator.evaluate(
            previous: f2.nextState,
            config: cfg,
            observation: .pollSuccess(
                usageUsedPercent: 85,
                usageWarnPercent: 80,
                cycleKey: "2026-05-01",
                smsUnreadCount: 2,
                activeDeviceMacs: ["aa:bb:cc:11:22:33"]
            )
        )
        XCTAssertTrue(ok1.alerts.contains(.routerRecovered))
        XCTAssertTrue(ok1.alerts.contains(.quotaWarning(usedPercent: 85, thresholdPercent: 80)))

        // Next poll same cycle: no duplicate quota, new SMS delta + new device detected
        let ok2 = AlertEvaluator.evaluate(
            previous: ok1.nextState,
            config: cfg,
            observation: .pollSuccess(
                usageUsedPercent: 90,
                usageWarnPercent: 80,
                cycleKey: "2026-05-01",
                smsUnreadCount: 5,
                activeDeviceMacs: ["aa:bb:cc:11:22:33", "dd:ee:ff:44:55:66"]
            )
        )
        XCTAssertEqual(ok2.alerts, [
            .newSms(deltaCount: 3, totalUnread: 5),
            .newDevices(macs: ["DD:EE:FF:44:55:66"])
        ])
    }
}
