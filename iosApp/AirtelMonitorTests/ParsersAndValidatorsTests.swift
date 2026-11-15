import XCTest
@testable import AirtelMonitor

final class ParsersAndValidatorsTests: XCTestCase {
    func testRouterFeatureFlagsParsingAndGating() {
        let flags = RouterFeatureFlags.fromJson80([
            "web_page_hide": "3C",
            "account_level": "1",
            "aeraId": "NG0131",
            "network_mode": "5",
            "fw_switch": "1",
            "parentControlSwitch": "1",
            "lock_plmn_flag": "1",
            "ip_passthrough_sw": "0",
            "smsSw": "1",
            "iad_ready_status": "1",
            "wifi_ssid_forbid": "Admin,Default",
            "wifi_pwd_forbid": "12345678;password"
        ])

        XCTAssertTrue(flags.isLoaded)
        XCTAssertTrue(flags.wifiPowerCmd411Available)
        XCTAssertTrue(flags.fwSwitch)
        XCTAssertTrue(flags.parentControlSwitch)
        XCTAssertTrue(flags.lockPlmnFlag)
        XCTAssertEqual(flags.wifiSsidForbid, ["Admin", "Default"])
        XCTAssertEqual(flags.wifiPwdForbid, ["12345678", "password"])
        // Parse hex nibble pairs reversed: "3C" -> ["0","3","3","0"].reversed() == ["0","3","3","0"]
        XCTAssertEqual(RouterFeatureFlags.parseHexPageHide("3C"), ["0", "3", "3", "0"])

        let passthroughFlags = RouterFeatureFlags.fromJson80([
            "ip_passthrough_sw": "1",
            "network_mode": "5",
            "account_level": "1"
        ])
        XCTAssertFalse(passthroughFlags.dmzAvailable)
        XCTAssertFalse(passthroughFlags.portForwardAvailable)
        XCTAssertFalse(passthroughFlags.trustedIpAvailable)

        let bridgeFlags = RouterFeatureFlags.fromJson80([
            "web_page_hide": String(repeating: "F", count: 120),
            "account_level": "1",
            "network_mode": "7"
        ])
        XCTAssertTrue(bridgeFlags.wanDetectRescanAvailable)
        XCTAssertFalse(bridgeFlags.dmzAvailable)
    }

    func testPhase1MonthlyUsageSwappedFieldsAndCurrentFlow() {
        let usage = Phase1Parsers.parseMonthlyUsage(
            net205: ["flow_dl": "450.5", "flow_ul": "149.5"],
            dash401: [:],
            traffic337: [
                "limit_switch": "1",
                "limit_size": "100",
                "limit_unit": "3", // GB -> 102400 MB
                "start_date": "1",
                // Web UI quirk: ul_mon_flow is Download, dl_mon_flow is Upload
                "ul_mon_flow": "4096",
                "dl_mon_flow": "1024",
                "roaming_ul_flow": "200",
                "roaming_dl_flow": "50"
            ]
        )

        XCTAssertTrue(usage.hasData)
        XCTAssertTrue(usage.limitEnabled)
        XCTAssertEqual(usage.dlMonthMb, 4096, accuracy: 0.01)
        XCTAssertEqual(usage.ulMonthMb, 1024, accuracy: 0.01)
        XCTAssertEqual(usage.usedMb, 5120, accuracy: 0.01)
        XCTAssertEqual(usage.currentFlowMb, 600, accuracy: 0.01)
        XCTAssertEqual(usage.roamingUsedMb, 250, accuracy: 0.01)
        XCTAssertEqual(usage.limitMb ?? 0, 102_400, accuracy: 0.01)
        XCTAssertEqual(usage.usedPercent, 5)
    }

    func testCarriersAlignmentNeighborsAndSignalHistory() {
        let carriers = CarrierParser.parseCarriers([
            "currentband": "B3+B1",
            "FREQ": "1650+300",
            "PCI": "12+45",
            "bandwidth": "20MHz+15MHz",
            "currentband_5g": "n78",
            "FREQ_5G": "633984",
            "PCI_5G": "201",
            "bandwidth_5g": "100MHz"
        ])
        XCTAssertEqual(carriers.count, 3)
        XCTAssertEqual(CarrierParser.formatCaSummary(carriers), "3CC (2×4G + 1×5G)")
        XCTAssertEqual(CarrierParser.formatBandPill(carriers), "B3+B1 + n78")
        XCTAssertEqual(CarrierParser.formatCellPill(carriers), "PCI 12 · B3+B1 + n78")

        let alignment = Phase1Parsers.parseAlignmentSnapshot(
            net205: ["5G_RSRP": "-84", "5G_SINR": "19.5", "RSRP": "-92", "SINR": "12", "PCI": "12", "PCI_5G": "201"],
            carriers: carriers
        )
        XCTAssertTrue(alignment.is5g)
        XCTAssertEqual(alignment.sinr, 19.5)
        XCTAssertEqual(alignment.rsrp, -84)

        let neighbors = Phase1Parsers.parseNeighborCells([
            "lte_neighbor_cell": "1650,12,-85,-9;3050,44,-95,-12",
            "nr_neighbor_cell": "633984,201,-82,18.0"
        ])
        XCTAssertEqual(neighbors.count, 3)
        XCTAssertEqual(neighbors.first?.tech, .lte)
        XCTAssertEqual(neighbors.last?.tech, .nr5g)

        let hist = Phase1Parsers.parseSignalHistory1018([
            ["time": "12:00", "rsrp": "-90", "sinr": "10", "5g_rsrp": "-85", "5g_sinr": "18"]
        ])
        XCTAssertEqual(hist.count, 1)
        XCTAssertEqual(hist.first?.rsrp5g, -85)
    }

    func testValidatorsAndUssdUtf16BeDecoder() {
        XCTAssertNotNil(WifiAndLanValidators.validateSsid("Admin", forbiddenSsids: ["Admin"]))
        XCTAssertNotNil(WifiAndLanValidators.validateWifiPassword("12345678", forbiddenPasswords: ["12345678"]))
        XCTAssertNil(WifiAndLanValidators.validateWifiPassword("StrongPass#2026"))

        XCTAssertNil(WifiAndLanValidators.validateDhcpSettings(
            lanIp: "192.168.1.1",
            netMask: "255.255.255.0",
            dhcpEnabled: true,
            ipBegin: "192.168.1.100",
            ipEnd: "192.168.1.200"
        ))
        XCTAssertNotNil(WifiAndLanValidators.validateDhcpSettings(
            lanIp: "192.168.1.1",
            netMask: "255.255.255.0",
            dhcpEnabled: true,
            ipBegin: "192.168.2.100",
            ipEnd: "192.168.2.200"
        ))

        XCTAssertNotNil(SmsValidators.validatePhoneNumber("abc"))
        XCTAssertNil(SmsValidators.validatePhoneNumber("+2348012345678"))
        XCTAssertEqual(SmsValidators.weightedSmsLength("Hi"), 2)
        XCTAssertEqual(SmsValidators.weightedSmsLength("Hi😀"), 8)

        // UCS2 UTF-16BE hex for "Bal: N500"
        let ucs2Hex = "Bal: N500".utf16.map { String(format: "%04X", $0) }.joined()
        let decodedUcs2 = Phase71Validators.decodeUssdResponse(ucs2Hex)
        XCTAssertEqual(decodedUcs2.text, "Bal: N500")
        XCTAssertFalse(decodedUcs2.isError)

        let decoded507 = Phase71Validators.decodeUssdResponse("507")
        XCTAssertTrue(decoded507.isError)
        XCTAssertTrue(decoded507.text.contains("timed out"))

        XCTAssertEqual(Phase71Validators.normalizePortRange("80-90", separator: ":"), "80:90")
        XCTAssertEqual(Phase71Validators.findPortConflict(portExpr: "75-85", reservedPorts: [80, 443]), 80)
        XCTAssertEqual(SensitiveFormatters.maskIdentifier("123456789012345"), "•••••••••••2345")
    }

    func testBuildNetworkModeSummary() {
        let state = RouterState(
            timestampMs: 0,
            timeFormatted: "12:00:00",
            wan: WanMetrics(downloadKbps: 0, uploadKbps: 0, downloadMbps: 0, uploadMbps: 0, dlFlowMb: "0 MB", ulFlowMb: "0 MB", totalFlowMb: "0 MB", peakDlMbps: 0, peakUlMbps: 0),
            cellular: CellularMetrics(networkType: "5G_NSA", signalLvl: 4, rsrp5g: "-85", sinr5g: "18", rsrp4g: "-92", sinr4g: "10", operatorName: "Airtel", boardType: "X17M", iduType: "IDU", nrCqi: "12", lteCqi: "11", nrQamDl: "256", rsrq5g: "-9", rssi5g: "-65", rsrq4g: "-10", rssi4g: "-70", dlMcs: "20", ulMcs: "15", txPower: "20", nrBler: "0", lteBler: "0"),
            cellLock: CellLockInfo(),
            hardware: HardwareMetrics(cpuPercent: "20", memPercent: "40", tempCelsius: "42", uptimeSeconds: "3600", imei: "", imsi: ""),
            devices: [],
            history: [],
            carriers: [],
            linkDetails: LinkDetails(),
            cellIdentity: CellIdentity(),
            signalHistory: [],
            monthlyUsage: MonthlyUsageSummary(),
            networkModeSnapshot: NetworkModeSnapshot(mobileDataOn: true, flightModeOn: false, networkMode: "C", mode5g: "2", roamingEnable: true, lteCa: true, nrCa: true),
            unreadSmsCount: 0,
            wanIp: "10.0.0.1",
            wanIpv6: "",
            onlineDurationText: "1h",
            uptimeText: "1h"
        )
        let summary = SettingsHubViewModel.buildNetworkModeSummary(state)
        XCTAssertTrue(summary.contains("5G_NSA"))
        XCTAssertTrue(summary.contains("LTE CA"))
        XCTAssertTrue(summary.contains("5G CA"))
        XCTAssertTrue(summary.contains("Roaming"))
    }
}
