import Foundation
import XCTest
@testable import AirtelMonitor

@MainActor
final class ParsersFixturesParityTests: XCTestCase {

    func testFixture205CaCarriersMetricsLinkDetailsAndCellIdentity() {
        let j205 = RouterFixtures.json205Ca()
        let j133 = RouterFixtures.json133()
        let j207 = RouterFixtures.json207()
        let row1018 = RouterParsers.parseLatestChartRow(RouterFixtures.json1018())

        let carriers = CarrierParser.parseCarriers(j205)
        XCTAssertEqual(carriers.count, 2)
        XCTAssertEqual(carriers[0].tech, .lte)
        XCTAssertEqual(carriers[0].band, "B7")
        XCTAssertEqual(carriers[0].earfcn, "3400")
        XCTAssertEqual(carriers[0].pci, "393")
        XCTAssertEqual(carriers[0].bandwidthMHz, 10)
        XCTAssertTrue(carriers[0].isPrimary)
        XCTAssertEqual(carriers[1].band, "B1")
        XCTAssertEqual(carriers[1].bandwidthMHz, 15)
        XCTAssertFalse(carriers[1].isPrimary)

        XCTAssertEqual(CarrierParser.formatCaSummary(carriers), "B7 + B1 · 25 MHz")
        XCTAssertEqual(CarrierParser.formatBandPill(carriers), "B7+B1")
        XCTAssertEqual(CarrierParser.formatCellPill(carriers), "PCI 393 · 2CC")

        let cellular = RouterParsers.parseCellularMetrics(
            net205: j205,
            routerInfo133: j133,
            deviceInfo207: j207,
            chartRow: row1018
        )
        XCTAssertEqual(cellular.networkType, "4G+")
        XCTAssertEqual(cellular.signalLvl, 4)
        XCTAssertEqual(cellular.rsrp4g, "-84")
        XCTAssertEqual(cellular.sinr4g, "16")
        XCTAssertEqual(cellular.operatorName, "Airtel NG")
        XCTAssertEqual(cellular.boardType, "ZLT X17M")
        XCTAssertEqual(cellular.iduType, "ZLT W304VA PRO")

        let link = Phase1Parsers.parseLinkDetails(j205, routerInfo133: j133)
        XCTAssertEqual(link.dlMcs4g, "22")
        XCTAssertEqual(link.ulMcs4g, "18")
        XCTAssertEqual(link.maxDlQam4g, "256QAM")
        XCTAssertEqual(link.maxUlQam4g, "64QAM")
        XCTAssertEqual(link.rank4g, "2")
        XCTAssertEqual(link.bler4g, "0.4")
        XCTAssertFalse(link.has5gDetails)

        let id = Phase1Parsers.parseCellIdentity(j205, routerInfo133: j133)
        XCTAssertEqual(id.plmn, "62120")
        XCTAssertEqual(id.enbId4g, "10456")
        XCTAssertEqual(id.cellId4g, "2676737")
        XCTAssertEqual(id.tac4g, "4102")
        XCTAssertEqual(id.ecgi, "62120-2676737")
    }

    func testFixture205NsaCarriersAndAlignmentSnapshot() throws {
        let j205 = RouterFixtures.json205Nsa()
        let carriers = CarrierParser.parseCarriers(j205)

        XCTAssertEqual(carriers.count, 3)
        XCTAssertEqual(carriers.map(\.band), ["B3", "B1", "n78"])
        XCTAssertEqual(carriers[2].tech, .nr)
        XCTAssertEqual(carriers[2].pci, "916")
        XCTAssertEqual(carriers[2].bandwidthMHz, 100)
        XCTAssertTrue(carriers[2].isPrimary)

        XCTAssertEqual(CarrierParser.formatCaSummary(carriers), "B3 + B1 + n78 · 125 MHz")
        XCTAssertEqual(CarrierParser.formatBandPill(carriers), "B3+B1+n78")
        XCTAssertEqual(CarrierParser.formatCellPill(carriers), "PCI 916 · 3CC")

        let cellular = RouterParsers.parseCellularMetrics(net205: j205)
        XCTAssertEqual(cellular.networkType, "5G NSA")
        XCTAssertEqual(cellular.rsrp5g, "-82")
        XCTAssertEqual(cellular.sinr5g, "21")
        XCTAssertEqual(cellular.nrQamDl, "256QAM")

        let snap = Phase1Parsers.parseAlignmentSnapshot(j205, carriers: carriers, timestampMs: 12345)
        XCTAssertEqual(snap.activeTechLabel, "5G NR")
        XCTAssertEqual(try XCTUnwrap(snap.primaryRsrp), -82.0, accuracy: 0.01)
        XCTAssertEqual(try XCTUnwrap(snap.primarySinr), 21.0, accuracy: 0.01)
        XCTAssertEqual(snap.carrierSummary, "B3 + B1 + n78 · 125 MHz")
    }

    func testFixtures160CaAnd161BandAndCellLock() {
        let bands = RouterParsers.parseBandLock(RouterFixtures.json161())
        XCTAssertTrue(bands.lock4gEnabled)
        XCTAssertEqual(bands.supported4g, [1, 3, 7, 8, 20, 28, 38, 41])
        XCTAssertEqual(bands.locked4g, [1, 3, 7])
        XCTAssertFalse(bands.lock5gEnabled)
        XCTAssertEqual(bands.supported5g, [41, 78])
        XCTAssertEqual(bands.locked5g, [41, 78])

        XCTAssertEqual(BandMask.encode([1, 3, 7, 8, 20, 28, 38, 41]), "120080800c5")
        XCTAssertEqual(BandMask.encode([]), "")
        XCTAssertEqual(BandMask.decode(""), [])

        let cells = RouterParsers.parseCellLock(RouterFixtures.json160Ca())
        XCTAssertTrue(cells.lteEnabled)
        XCTAssertEqual(cells.lteState, .locked)
        XCTAssertEqual(cells.lteCells, [
            LteLockCell(earfcn: "3400", pci: "393"),
            LteLockCell(earfcn: "225", pci: "393")
        ])
        XCTAssertEqual(cells.current4g, LteLockCell(earfcn: "3400", pci: "393"))
        XCTAssertFalse(cells.nrEnabled)
        XCTAssertEqual(cells.nrState, .unlocked)

        let failedNr = RouterParsers.parseCellLock([
            "nr_lock_sw": "1",
            "nr_lock_freq": "627264",
            "nr_lock_pci": "916",
            "nr_lock_cell_band": "78",
            "lock_5g_flag": "2"
        ])
        XCTAssertEqual(failedNr.nrState, .failed)
        XCTAssertEqual(failedNr.nrCells, [NrLockCell(band: "78", arfcn: "627264", pci: "916")])
    }

    func testFixture337And205MonthlyUsageSwappedFieldsAndTwoSampleProof() throws {
        let fixedDate = Calendar(identifier: .gregorian).date(from: DateComponents(year: 2026, month: 11, day: 15))!
        let summary = Phase1Parsers.parseMonthlyUsage(
            net205: RouterFixtures.json205Ca(),
            traffic337: RouterFixtures.json337(),
            date: fixedDate
        )
        XCTAssertEqual(summary.dlMonthMb, 54366.45, accuracy: 0.01) // from ul_mon_flow
        XCTAssertEqual(summary.ulMonthMb, 210000.0, accuracy: 0.01) // from dl_mon_flow
        XCTAssertEqual(summary.usedMb, 264366.45, accuracy: 0.01)
        XCTAssertTrue(summary.limitEnabled)
        XCTAssertEqual(try XCTUnwrap(summary.quotaMb), 512000.0, accuracy: 0.01)
        XCTAssertEqual(summary.warnPercent, 80)
        XCTAssertEqual(summary.billingStartDay, 10)
        XCTAssertEqual(summary.daysLeftInCycle, 25) // Nov has 30 days: (30 - 15) + 10

        // Two-sample proof: heavy download sample grows ul_mon_flow more than dl_mon_flow
        let sample1 = Phase1Parsers.parseMonthlyUsage(
            net205: [:],
            traffic337: ["ul_mon_flow": "1000.0", "dl_mon_flow": "200.0"],
            date: fixedDate
        )
        let sample2 = Phase1Parsers.parseMonthlyUsage(
            net205: [:],
            traffic337: ["ul_mon_flow": "2500.0", "dl_mon_flow": "300.0"],
            date: fixedDate
        )
        XCTAssertEqual(sample2.dlMonthMb - sample1.dlMonthMb, 1500.0, accuracy: 0.01)
        XCTAssertEqual(sample2.ulMonthMb - sample1.ulMonthMb, 100.0, accuracy: 0.01)
        XCTAssertEqual(sample2.usedMb, 2800.0, accuracy: 0.01)
    }

    func testDataSizeThroughputAndDurationFormatters() {
        XCTAssertEqual(Phase1Parsers.formatDataSizeMb(512.0), "512.00 MB")
        XCTAssertEqual(Phase1Parsers.formatDataSizeMb(264366.45), "258.17 GB")
        XCTAssertEqual(Phase1Parsers.formatDataSizeMb(1_572_864.0), "1.50 TB")

        XCTAssertEqual(NetworkFormatters.formatDataMb(512.0), "512.0 MB")
        XCTAssertEqual(NetworkFormatters.formatDataMb(25600.0), "25.00 GB")
        XCTAssertEqual(NetworkFormatters.formatDataMb(2_097_152.0), "2.00 TB")

        let mbps = NetworkFormatters.formatThroughput(80.0, unit: .mbps)
        XCTAssertEqual(mbps.0, "80.00")
        XCTAssertEqual(mbps.1, "Mbps")
        let mbs = NetworkFormatters.formatThroughput(80.0, unit: .mbs)
        XCTAssertEqual(mbs.0, "10.00")
        XCTAssertEqual(mbs.1, "MB/s")

        XCTAssertEqual(NetworkFormatters.formatPeakThroughput(64.0, unit: .mbps), "Peak 64.0M")
        XCTAssertEqual(NetworkFormatters.formatPeakThroughput(64.0, unit: .mbs), "Peak 8.00 MB/s")
        XCTAssertEqual(NetworkFormatters.maskMac("aa:bb:cc:dd:ee:ff"), "aa:bb:**:**:ee:ff")
        XCTAssertEqual(SensitiveFormatters.maskIdentifier("1234567890", visibleSuffix: 4), "••••••7890")
        XCTAssertEqual(SensitiveFormatters.maskIdentifier("REDACTED", visibleSuffix: 4), "REDACTED")

        XCTAssertEqual(Phase1Parsers.formatDurationText("14230"), "3 h 57 m")
        XCTAssertEqual(Phase1Parsers.formatDurationText("172800"), "2 d 0 h")
        XCTAssertEqual(Phase1Parsers.formatDurationText("1-2-30-5"), "1 d 2 h")
        XCTAssertEqual(Phase1Parsers.formatDurationText("02:15:10"), "2 h 15 m")
        XCTAssertEqual(Phase1Parsers.formatDurationText("--"), "--")
        XCTAssertEqual(SensitiveFormatters.formatUptimeSeconds("86465"), "1d 00h 01m")
    }

    func testFixture282And1018And207And133NeighborsAndHardware() {
        let neighbors = Phase1Parsers.parseNeighborCells(RouterFixtures.json282())
        XCTAssertEqual(neighbors.count, 3)
        XCTAssertEqual(neighbors[0], NeighborCell(tech: .lte, earfcn: "1850", pci: "372", rsrp: "-74", rsrq: "-13.5", sinr: "13"))
        XCTAssertEqual(neighbors[2].pci, "75")

        let j1018 = RouterFixtures.json1018()
        let rows = (j1018["device_info"] as? [[String: Any]]) ?? []
        let history = Phase1Parsers.parseSignalHistory1018(rows)
        XCTAssertEqual(history.count, 2)
        XCTAssertEqual(history[0].timeLabel, "14:20:00")
        XCTAssertEqual(history[1].rsrp4g, -84.0)
        XCTAssertEqual(history[1].sinr4g, 16.0)

        let latest = RouterParsers.parseLatestChartRow(j1018)
        let hw = RouterParsers.parseHardwareMetrics(chartRow: latest, deviceInfo207: RouterFixtures.json207())
        XCTAssertEqual(hw.cpuUsage, 7.0, accuracy: 0.01)
        XCTAssertEqual(hw.temperature, "54")
        XCTAssertEqual(hw.memory, "43")

        let hwFallback = RouterParsers.parseHardwareMetrics(chartRow: nil, deviceInfo207: RouterFixtures.json207())
        XCTAssertEqual(hwFallback.cpuUsage, 12.5, accuracy: 0.01)
        XCTAssertEqual(hwFallback.temperature, "52")
        XCTAssertEqual(hwFallback.memory, "44")
    }

    func testFixtures223And225AndBundledOuiLookup() {
        let bundled = OuiVendorLookup.fromBundle(Bundle(for: AppContainer.self))
        XCTAssertGreaterThan(bundled.count, 300)
        XCTAssertEqual(bundled.lookup("00:40:0B:11:22:33").vendor, "Cisco")
        XCTAssertEqual(bundled.lookup("44-00-10-aa-bb-cc").vendor, "Apple")
        XCTAssertEqual(bundled.lookup("14007D112233").vendor, "ZTE")

        let lookup = OuiVendorLookup.fromLines(["0017F2,Apple", "001A11,Google"])
        let devices = RouterParsers.parseDevices(
            dhcp: RouterFixtures.json223(),
            wifi5g: RouterFixtures.json225(),
            wifi24g: [:],
            aliasLookup: { id, fallback in id == "02:11:22:33:44:01" ? "Matt's Pixel" : fallback },
            hasAliasLookup: { id in id == "02:11:22:33:44:01" },
            ouiLookup: lookup
        )
        let byIp = Dictionary(uniqueKeysWithValues: devices.map { ($0.ip, $0) })

        let phone = byIp["192.168.1.192"]
        XCTAssertEqual(phone?.alias, "Matt's Pixel")
        XCTAssertEqual(phone?.hasCustomAlias, true)
        XCTAssertEqual(phone?.type, .phone)
        XCTAssertEqual(phone?.band, "5 GHz")
        XCTAssertEqual(phone?.rssi, "-58")
        XCTAssertEqual(phone?.signalPercent, 100) // interface="wlan" matches contains("lan") in Parsers.parseDevices
        XCTAssertEqual(phone?.isPrivateMac, true) // 02:... locally administered unicast
        XCTAssertEqual(phone?.isOnline, true)

        let lan = byIp["192.168.1.50"]
        XCTAssertEqual(lan?.hostname, "Unknown Device")
        XCTAssertEqual(lan?.band, "Ethernet (LAN)")
        XCTAssertEqual(lan?.signalPercent, 100)
        XCTAssertEqual(lan?.isOnline, true)

        // Wi-Fi-only device missing from DHCP + expired offline device
        let extraDevices = RouterParsers.parseDevices(
            dhcp: ["dhcp_list_info": [[
                "mac": "00:17:F2:00:00:01",
                "ip": "192.168.1.60",
                "hostname": "MacBook",
                "interface": "wlan",
                "expires": "0"
            ]]],
            wifi5g: [:],
            wifi24g: ["wlan24g_wifi_info": [[
                "mac": "00:1A:11:22:33:44",
                "ip": "192.168.1.77",
                "rssi": "-65",
                "txrate": "144",
                "rxrate": "144",
                "ssid": "Airtel_2G"
            ]]],
            aliasLookup: { _, fb in fb },
            hasAliasLookup: { _ in false },
            ouiLookup: lookup
        )
        let extraMap = Dictionary(uniqueKeysWithValues: extraDevices.map { ($0.ip, $0) })
        XCTAssertEqual(extraMap["192.168.1.60"]?.isOnline, false)
        XCTAssertEqual(extraMap["192.168.1.60"]?.vendor, "Apple")
        XCTAssertEqual(extraMap["192.168.1.77"]?.vendor, "Google")
        XCTAssertEqual(extraMap["192.168.1.77"]?.band, "2.4 GHz")
        XCTAssertEqual(extraMap["192.168.1.77"]?.signalPercent, 70) // 2 * (-65 + 100)
        XCTAssertEqual(extraMap["192.168.1.77"]?.isOnline, true)
    }

    func testFixtures394And3LanDnsAndFeatureFlags80() {
        let j394 = RouterFixtures.json394()
        XCTAssertEqual(RouterParsers.parseSmsUnreadCount(j394), 3)
        let modeSnap = RouterParsers.parseNetworkModeSnapshot([:], statusBar394: j394)
        XCTAssertEqual(modeSnap.mobileDataOn, true)
        XCTAssertEqual(modeSnap.flightModeOn, false)

        let lan = RouterParsers.parseLanConfig(RouterFixtures.json3())
        XCTAssertEqual(lan.lanIp, "192.168.1.1")
        XCTAssertEqual(lan.netMask, "255.255.255.0")
        XCTAssertTrue(lan.dhcpEnabled)
        XCTAssertEqual(lan.ipBegin, "192.168.1.100")
        XCTAssertEqual(lan.ipEnd, "192.168.1.200")
        XCTAssertEqual(lan.expireTime, "24h")
        XCTAssertEqual(lan.primaryDns, "1.1.1.1")
        XCTAssertEqual(lan.secondaryDns, "8.8.8.8")

        let ngFlags = RouterFeatureFlags.fromJson80([
            "account_level": "1",
            "aeraId": "NG0131",
            "network_mode": "5",
            "web_page_hide": "0",
            "lock_plmn_flag": "1",
            "ip_passthrough_sw": "1",
            "fw_switch": "1",
            "parentControlSwitch": "1",
            "smsSw": "1",
            "iad_ready_status": "1",
            "wifi_ssid_forbid": "ForbiddenA, ForbiddenB;ForbiddenC",
            "wifi_pwd_forbid": "12345678;password"
        ])
        XCTAssertTrue(ngFlags.isLoaded)
        XCTAssertTrue(ngFlags.wifiPowerCmd411Available)
        XCTAssertTrue(ngFlags.lockPlmnFlag)
        XCTAssertFalse(ngFlags.dmzAvailable)
        XCTAssertFalse(ngFlags.portForwardAvailable)
        XCTAssertFalse(ngFlags.trustedIpAvailable)
        XCTAssertTrue(ngFlags.wanDetectRescanAvailable)
        XCTAssertEqual(ngFlags.wifiSsidForbid, ["ForbiddenA", "ForbiddenB", "ForbiddenC"])
        XCTAssertEqual(ngFlags.wifiPwdForbid, ["12345678", "password"])

        let userLevelFlags = RouterFeatureFlags.fromJson80([
            "account_level": "3",
            "aeraId": "TZ0355",
            "web_page_hide": "0",
            "ip_passthrough_sw": "0",
            "network_mode": "1"
        ])
        XCTAssertFalse(userLevelFlags.wifiPowerCmd411Available)
        XCTAssertFalse(userLevelFlags.ussdAvailable)
        XCTAssertTrue(userLevelFlags.dmzAvailable)
        XCTAssertFalse(userLevelFlags.wanDetectRescanAvailable)
    }

    func testStatusRepositoryPollFastWanDeltasSlowCacheAndAlignment() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let nowMs = TestBox<Int64>(1_000_000)
        var rx: Int64 = 1_000_000
        var tx: Int64 = 500_000

        env.transport.on(18) { _ in ["success": true, "rxBytes": "\(rx)", "txBytes": "\(tx)"] }
        env.transport.on(401) { _ in ["success": true, "network_type_str": "4G+", "signal_lvl": 4, "mon_total_flow": "264366.45"] }
        env.transport.on(113) { _ in ["success": true, "network_type_str": "4G+", "signal_lvl": 4] }
        env.transport.on(205) { _ in RouterFixtures.json205Ca() }
        env.transport.on(223) { _ in RouterFixtures.json223() }
        env.transport.on(224) { _ in ["success": true, "wlan24g_wifi_info": []] }
        env.transport.on(225) { _ in RouterFixtures.json225() }
        env.transport.on(1018) { _ in RouterFixtures.json1018() }
        env.transport.on(160) { _ in RouterFixtures.json160Ca() }
        env.transport.on(161) { _ in RouterFixtures.json161() }
        env.transport.on(218) { _ in ["success": true, "dialMode": "1", "flightMode": "0", "networkMode": "1C"] }
        env.transport.on(394) { _ in RouterFixtures.json394() }
        env.transport.on(133) { _ in RouterFixtures.json133() }
        env.transport.on(207) { _ in RouterFixtures.json207() }
        env.transport.on(337) { _ in RouterFixtures.json337() }

        let statusRepo = StatusRepository(
            session: env.session,
            preferences: env.preferences,
            clockMs: { nowMs.get() }
        )

        let firstRes = await statusRepo.pollFast(forceSlow: true)
        let first = try XCTUnwrap(firstRes.value)
        XCTAssertEqual(first.wanIp, "10.8.42.19")
        XCTAssertEqual(first.onlineDurationText, "3 h 57 m")
        XCTAssertEqual(first.uptimeText, "2 d 0 h")
        XCTAssertEqual(first.wan.downloadKbps, 0.0, accuracy: 0.001)
        XCTAssertEqual(first.unreadSmsCount, 3)
        XCTAssertEqual(first.signalHistory.count, 3)

        // Second poll 2s later: +500_000 rx, +250_000 tx, slow cache hit (133/207/337 NOT called)
        env.transport.clearRequests()
        nowMs.withLock { $0 += 2_000 }
        rx += 500_000
        tx += 250_000
        let secondRes = await statusRepo.pollFast(forceSlow: false)
        let second = try XCTUnwrap(secondRes.value)
        XCTAssertEqual(second.wan.downloadKbps, 2000.0, accuracy: 0.001)
        XCTAssertEqual(second.wan.uploadKbps, 1000.0, accuracy: 0.001)
        XCTAssertEqual(second.wan.downloadMbps, 2.0, accuracy: 0.001)
        XCTAssertEqual(env.transport.sent(133).count, 0)
        XCTAssertEqual(env.transport.sent(207).count, 0)
        XCTAssertEqual(env.transport.sent(337).count, 0)

        // Counter reset after reboot resets throughput delta cleanly
        nowMs.withLock { $0 += 2_000 }
        rx = 10
        let thirdRes = await statusRepo.pollFast(forceSlow: false)
        let third = try XCTUnwrap(thirdRes.value)
        XCTAssertEqual(third.wan.downloadKbps, 0.0, accuracy: 0.001)

        // Alignment poll only sends cmd 205
        env.transport.clearRequests()
        let alignRes = await statusRepo.pollAlignment()
        let align = try XCTUnwrap(alignRes.value)
        XCTAssertEqual(try XCTUnwrap(align.primaryRsrp), -84.0, accuracy: 0.01)
        XCTAssertEqual(env.transport.cmdsSent(), [205])
    }

    func testDeviceRepositoryGatewayDetectorSpeedJitterAndNetworkModeCatalog() {
        let env = makeTestEnv()
        defer { env.cleanup() }
        let deviceRepo = DeviceRepository(preferences: env.preferences)

        let raw = [DeviceItem(mac: "AA:BB:CC:11:22:33", ip: "192.168.1.25", hostname: "Pixel-6")]
        deviceRepo.saveAlias("Matt's Pixel", for: "aa:bb:cc:11:22:33")
        let enriched = deviceRepo.enrichDevices(raw)
        XCTAssertEqual(enriched[0].alias, "Matt's Pixel")
        XCTAssertTrue(enriched[0].hasCustomAlias)

        deviceRepo.clearAlias(for: "AA:BB:CC:11:22:33")
        let cleared = deviceRepo.enrichDevices(raw)
        XCTAssertEqual(cleared[0].alias, "Pixel-6")
        XCTAssertFalse(cleared[0].hasCustomAlias)

        XCTAssertEqual(NetworkGatewayDetector.inferGatewayFromIpv4("192.168.8.42"), "192.168.8.1")
        XCTAssertNil(NetworkGatewayDetector.inferGatewayFromIpv4("not-an-ip"))

        XCTAssertEqual(SpeedTestRunner.computeJitter([10.0, 14.0, 11.0, 15.0]), 3.6666, accuracy: 0.01)
        XCTAssertEqual(SpeedTestRunner.computeJitter([10.0]), 0.0, accuracy: 0.001)

        XCTAssertEqual(NetworkModeCatalog.labelFor("1e"), "Auto (5G SA+NSA/4G/3G)")
        XCTAssertEqual(NetworkModeCatalog.labelFor("4"), "4G Only")
        XCTAssertEqual(NetworkModeCatalog.label5gFor("1"), "SA Only")
    }
}
