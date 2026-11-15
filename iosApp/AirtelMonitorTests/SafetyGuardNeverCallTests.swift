import Foundation
import XCTest
@testable import AirtelMonitor

@MainActor
final class SafetyGuardNeverCallTests: XCTestCase {

    func testNeverCallForbiddenCommandsAcrossAllReadsPollsAndInvalidWrites() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let forbidden: Set<Int> = [6, 99, 112, 120, 288]

        // Stub all benign GET endpoints cleanly.
        env.transport.on(80) { _ in ["success": true, "aeraId": "NG0131", "account_level": "1", "network_mode": "5"] }
        env.transport.on(18) { _ in ["success": true, "rxBytes": "1000", "txBytes": "500"] }
        env.transport.on(401) { _ in ["success": true, "network_type_str": "4G+", "signal_lvl": 4] }
        env.transport.on(113) { _ in ["success": true, "sim_status": "1", "current_card_type": "USIM"] }
        env.transport.on(134) { _ in ["success": true, "lock_pin_switch": "0", "pin_left_times": "3", "puk_left_times": "10"] }
        env.transport.on(205) { _ in RouterFixtures.json205Ca() }
        env.transport.on(223) { _ in RouterFixtures.json223() }
        env.transport.on(224) { _ in ["success": true, "wlan24g_wifi_info": []] }
        env.transport.on(225) { _ in RouterFixtures.json225() }
        env.transport.on(1018) { _ in RouterFixtures.json1018() }
        env.transport.on(160) { _ in RouterFixtures.json160Ca() }
        env.transport.on(161) { _ in RouterFixtures.json161() }
        env.transport.on(218) { _ in ["success": true, "dialMode": "1", "flightMode": "0", "networkMode": "1C"] }
        env.transport.on(256) { _ in ["success": true, "networkMode": "1C", "display_mode": "1C"] }
        env.transport.on(394) { _ in RouterFixtures.json394() }
        env.transport.on(133) { _ in RouterFixtures.json133() }
        env.transport.on(207) { _ in RouterFixtures.json207() }
        env.transport.on(337) { _ in RouterFixtures.json337() }
        env.transport.on(282) { _ in RouterFixtures.json282() }
        env.transport.on(2) { _ in RouterFixtures.wifiRecord(cmd: 2, on: true) }
        env.transport.on(211) { _ in RouterFixtures.wifiRecord(cmd: 211, on: false) }
        env.transport.on(3) { _ in RouterFixtures.json3() }
        env.transport.onText(17, method: "GET") { _ in "2026-11-15 10:00:00 [info] Boot complete\n" }
        env.transport.on(484, method: "GET") { _ in ["success": true, "check_result": "ok"] }

        // Feature flags, Status, Polling
        let flagsRepo = FeatureFlagRepository(session: env.session)
        _ = await flagsRepo.load(force: true)

        let statusRepo = StatusRepository(session: env.session, preferences: env.preferences)
        _ = await statusRepo.pollFast(forceSlow: true)
        _ = await statusRepo.pollAlignment()

        // Wi-Fi reads & invalid writes
        let wifiRepo = WifiRepository(session: env.session, writer: env.writer)
        _ = await wifiRepo.loadWifiConfig()
        _ = await wifiRepo.loadWifiBandDetails()
        _ = await wifiRepo.loadGuestWifi()
        _ = await wifiRepo.loadWifiAdvanced(band: .band24g)
        _ = await wifiRepo.loadWifiAdvanced(band: .band5g)
        _ = await wifiRepo.loadWifiPower411()
        _ = await wifiRepo.loadWpsStatus()
        _ = await wifiRepo.loadWifiSchedule()
        _ = await wifiRepo.setWifiEnabled(band: .band24g, enabled: false) // blocked because 5G is off
        _ = await wifiRepo.saveWifiBandConfig(band: .band24g, ssid: "", key: "123", authenticationType: "2", visibleBroadcast: true)
        _ = await wifiRepo.saveWifiPower411("999")
        _ = await wifiRepo.submitWpsPin(band: .band24g, pin: "12345678")

        // LAN & SIM reads + invalid writes
        let lanRepo = LanRepository(session: env.session, writer: env.writer)
        _ = await lanRepo.loadLanConfig()
        _ = await lanRepo.loadStaticLeases()
        _ = await lanRepo.saveDns(primary: "invalid", secondary: "")
        _ = await lanRepo.saveLanDhcpSettings(
            lanIp: "bad", netMask: "255.255.255.0", dhcpEnabled: true,
            ipBegin: "192.168.1.10", ipEnd: "192.168.1.20", expireTime: "24h",
            primaryDns: "", secondaryDns: ""
        )

        let simRepo = SimRepository(session: env.session, writer: env.writer)
        _ = await simRepo.loadStatus()
        _ = await simRepo.verifyPin("1")
        _ = await simRepo.unlockWithPuk(puk: "1", newPin: "2")

        // Mobile & Signal reads + invalid writes
        let mobileRepo = MobileRepository(session: env.session, writer: env.writer)
        _ = await mobileRepo.loadTrafficLimit()
        _ = await mobileRepo.loadMobileAdvanced()
        _ = await mobileRepo.loadApnConfig()
        _ = await mobileRepo.setNetworkMode("4", flags: RouterFeatureFlags(lockPlmnFlag: true))

        let signalRepo = SignalRepository(session: env.session, writer: env.writer)
        _ = await signalRepo.loadLocks()
        _ = await signalRepo.loadNeighborCells()
        _ = await signalRepo.saveBandLock(lock4g: true, bands4g: [], lock5g: false, bands5g: [])

        // SMS & USSD reads + invalid writes
        let smsRepo = SmsRepository(session: env.session, writer: env.writer)
        _ = await smsRepo.loadPage(folder: .inbox)
        _ = await smsRepo.loadPage(folder: .outbox)
        _ = await smsRepo.refreshUnreadCountFromStatusBar()
        _ = await smsRepo.loadSettings()
        _ = await smsRepo.sendSms(phoneNumber: "bad", body: "")

        let ussdRepo = UssdRepository(session: env.session, writer: env.writer)
        _ = await ussdRepo.send(input: "")

        // Security & Parental reads + invalid writes
        let secRepo = SecurityRepository(session: env.session, writer: env.writer)
        _ = await secRepo.loadAll()
        _ = await secRepo.saveDmz(enabled: true, ip: "bad")
        _ = await secRepo.saveTrustedIps(enabled: true, ips: ["bad"])

        let pcRepo = ParentalControlRepository(session: env.session, writer: env.writer)
        _ = await pcRepo.loadAll()
        _ = await pcRepo.saveTimeRules([ParentalTimeRule(mac: "bad", startTime: "08:00", endTime: "09:00")])

        // System reads + invalid writes
        let sysRepo = SystemRepository(session: env.session, writer: env.writer, preferences: env.preferences)
        _ = await sysRepo.loadAboutInfo()
        _ = await sysRepo.loadSystemLogs()
        _ = await sysRepo.loadLogSettings()
        _ = await sysRepo.loadScheduledReboot()
        _ = await sysRepo.loadTimeConfig()
        _ = await sysRepo.loadWanLinkDetect()
        _ = await sysRepo.runSelfCheck()
        _ = await sysRepo.changeAdminPassword(oldPassword: "", newPassword: "123", confirmPassword: "123")
        _ = await sysRepo.startNetworkTool(mode: .ping, target: "")

        let sentCmds = Set(env.transport.cmdsSent())
        XCTAssertTrue(
            sentCmds.isDisjoint(with: forbidden),
            "Forbidden disruptive command sent during non-destructive paths: \(sentCmds.intersection(forbidden))"
        )
    }

    func testExplicitRebootCmd99RunsOnceAllowsEmptyReplyResetsSessionAndNeverRetries() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let repo = SystemRepository(session: env.session, writer: env.writer, preferences: env.preferences)

        // Network error during reboot must NEVER retry cmd 99.
        env.transport.onError(99, method: "POST", message: "reboot cut connection")
        let errRes = await repo.rebootRouter()
        guard case .unreachable = errRes else {
            return XCTFail("Expected .unreachable when reboot socket drops, got \(errRes)")
        }
        XCTAssertEqual(env.transport.sent(99, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(233, method: "GET").count, 0)

        // Empty reply succeeds once, fetches no 233 token, and resets session so next request logs in again.
        env.transport.clearRequests()
        env.transport.onText(99, method: "POST") { _ in "" }
        let okRes = await repo.rebootRouter()
        XCTAssertTrue(okRes.isOk)
        XCTAssertEqual(env.transport.sent(99, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(233, method: "GET").count, 0)

        let loginsBefore = env.transport.sent(100, method: "POST").count
        _ = await repo.runSelfCheck()
        XCTAssertEqual(env.transport.sent(100, method: "POST").count, loginsBefore + 1)
    }

    func testExplicitFactoryResetCmd112RunsOnceAllowsEmptyReplyResetsSessionAndNeverRetries() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let repo = SystemRepository(session: env.session, writer: env.writer, preferences: env.preferences)

        // Network error must never retry cmd 112.
        env.transport.onError(112, method: "POST", message: "resetting nvram")
        let errRes = await repo.factoryReset()
        guard case .unreachable = errRes else {
            return XCTFail("Expected .unreachable on factory reset network error, got \(errRes)")
        }
        XCTAssertEqual(env.transport.sent(112, method: "POST").count, 1)

        // Empty reply succeeds once and clears session.
        env.transport.clearRequests()
        env.transport.onText(112, method: "POST") { _ in "" }
        let okRes = await repo.factoryReset()
        XCTAssertTrue(okRes.isOk)
        XCTAssertEqual(env.transport.sent(112, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(233, method: "GET").count, 0)

        let loginsBefore = env.transport.sent(100, method: "POST").count
        _ = await repo.runSelfCheck()
        XCTAssertEqual(env.transport.sent(100, method: "POST").count, loginsBefore + 1)
    }

    func testExplicitRescanNetworkCmd288RunsOnceAndNeverRetriesOnNetworkError() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let repo = SignalRepository(session: env.session, writer: env.writer)

        env.transport.onError(288, method: "POST", message: "rescan socket closed")
        let errRes = await repo.rescanNetwork()
        guard case .unreachable = errRes else {
            return XCTFail("Expected .unreachable when rescan drops, got \(errRes)")
        }
        XCTAssertEqual(env.transport.sent(288, method: "POST").count, 1)

        env.transport.clearRequests()
        env.transport.on(288, method: "POST") { _ in ["success": true, "message": "0"] }
        let okRes = await repo.rescanNetwork()
        XCTAssertTrue(okRes.isOk)
        let p288 = try XCTUnwrap(env.transport.sent(288, method: "POST").first)
        assertPayloadKeys(p288, exactDataKeys: [])
        XCTAssertEqual(env.transport.sent(288, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(233, method: "GET").count, 1)
    }

    func testAlertEvaluatorDisabledFlagsBaselinesQuotaCycleResetAndRecovery() {
        let disabledCfg = MonitorAlertConfig(
            notifyQuotaWarning: false,
            notifyNewSms: false,
            notifyNewDevice: false,
            notifyUnreachable: false
        )

        // Disabled unreachable suppresses alerts even after multiple failures.
        let d1 = AlertEvaluator.evaluate(previous: MonitorState(), config: disabledCfg, observation: .pollFailure)
        let d2 = AlertEvaluator.evaluate(previous: d1.nextState, config: disabledCfg, observation: .pollFailure)
        XCTAssertTrue(d2.alerts.isEmpty)
        XCTAssertFalse(d2.nextState.isUnreachableAlertActive)

        let enabledCfg = MonitorAlertConfig(
            notifyQuotaWarning: true,
            notifyNewSms: true,
            notifyNewDevice: true,
            notifyUnreachable: true
        )

        // Reachability requires 2 failures, avoids duplicate alerts, and recovers once.
        let f1 = AlertEvaluator.evaluate(previous: MonitorState(), config: enabledCfg, observation: .pollFailure)
        XCTAssertTrue(f1.alerts.isEmpty)
        let f2 = AlertEvaluator.evaluate(previous: f1.nextState, config: enabledCfg, observation: .pollFailure)
        XCTAssertEqual(f2.alerts, [.routerUnreachable])
        let f3 = AlertEvaluator.evaluate(previous: f2.nextState, config: enabledCfg, observation: .pollFailure)
        XCTAssertTrue(f3.alerts.isEmpty)

        let rec = AlertEvaluator.evaluate(
            previous: f3.nextState,
            config: enabledCfg,
            observation: .pollSuccess(
                usageUsedPercent: 82,
                usageWarnPercent: 80,
                cycleKey: "2026-11-10",
                smsUnreadCount: 3,
                activeDeviceMacs: ["aa:bb:cc:11:22:33"]
            )
        )
        XCTAssertEqual(rec.alerts, [.routerRecovered, .quotaWarning(usedPercent: 82, thresholdPercent: 80)])
        XCTAssertEqual(rec.nextState.knownDeviceMacs, ["AA:BB:CC:11:22:33"])

        // Same cycle deduplicates quota; SMS decrease doesn't fire; new MAC fires once.
        let next = AlertEvaluator.evaluate(
            previous: rec.nextState,
            config: enabledCfg,
            observation: .pollSuccess(
                usageUsedPercent: 90,
                usageWarnPercent: 80,
                cycleKey: "2026-11-10",
                smsUnreadCount: 1,
                activeDeviceMacs: ["AA:BB:CC:11:22:33", "dd:ee:ff:00:00:01"]
            )
        )
        XCTAssertEqual(next.alerts, [.newDevices(macs: ["DD:EE:FF:00:00:01"])])

        // New billing cycle resets quota warning deduplication; higher SMS fires delta.
        let newCycle = AlertEvaluator.evaluate(
            previous: next.nextState,
            config: enabledCfg,
            observation: .pollSuccess(
                usageUsedPercent: 85,
                usageWarnPercent: 80,
                cycleKey: "2026-12-10",
                smsUnreadCount: 4,
                activeDeviceMacs: ["AA:BB:CC:11:22:33", "DD:EE:FF:00:00:01"]
            )
        )
        XCTAssertEqual(newCycle.alerts, [
            .quotaWarning(usedPercent: 85, thresholdPercent: 80),
            .newSms(deltaCount: 3, totalUnread: 4)
        ])
    }

    func testPollingCoordinatorForegroundPauseModeSwitchAndImmediateRefresh() async throws {
        var normalTicks: [Bool] = []
        var alignmentCount = 0

        let coordinator = PollingCoordinator(
            pollIntervalProvider: { 10.0 },
            onNormalTick: { forceSlow in
                normalTicks.append(forceSlow)
            },
            onAlignmentTick: {
                alignmentCount += 1
            }
        )
        defer { coordinator.shutdown() }

        // Starts paused/backgrounded -> no ticks yet.
        try await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertTrue(normalTicks.isEmpty)

        // Moving to foreground runs an immediate initial normal tick.
        coordinator.onForegroundChanged(true)
        for _ in 0..<20 where normalTicks.isEmpty {
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        XCTAssertGreaterThanOrEqual(normalTicks.count, 1)

        // Triggering immediate slow refresh marks next normal tick as forceSlow = true.
        let beforeImmediate = normalTicks.count
        coordinator.triggerImmediateRefresh(includeSlow: true)
        for _ in 0..<20 where normalTicks.count == beforeImmediate {
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        XCTAssertEqual(normalTicks.last, true)

        // Switching to alignment mode routes immediate loop tick to onAlignmentTick.
        coordinator.setMode(.alignment)
        for _ in 0..<20 where alignmentCount == 0 {
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        XCTAssertGreaterThanOrEqual(alignmentCount, 1)

        // Pausing prevents loop ticks; togglePause toggles paused state cleanly.
        coordinator.setPaused(true)
        XCTAssertFalse(coordinator.togglePause()) // unpaused -> returns false
        XCTAssertTrue(coordinator.togglePause()) // paused -> returns true
    }
}
