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
                "limitSwitch": "1",
                "limitSize": "100",
                "flow_limit_unit": "1", // GB -> 102400 MB
                "startDate": "1",
                // Web UI quirk: ul_mon_flow is Download, dl_mon_flow is Upload
                "ul_mon_flow": "4096",
                "dl_mon_flow": "1024",
                "roam_dl_mon_flow": "50",
                "roam_ul_mon_flow": "200"
            ]
        )

        XCTAssertNotNil(usage.currentFlowMb)
        XCTAssertTrue(usage.limitEnabled)
        XCTAssertEqual(usage.dlMonthMb, 4096, accuracy: 0.01)
        XCTAssertEqual(usage.ulMonthMb, 1024, accuracy: 0.01)
        XCTAssertEqual(usage.usedMb, 5120, accuracy: 0.01)
        XCTAssertEqual(usage.currentFlowMb ?? 0, 600, accuracy: 0.01)
        XCTAssertEqual(usage.roamDlMb, 50, accuracy: 0.01)
        XCTAssertEqual(usage.roamUlMb, 200, accuracy: 0.01)
        XCTAssertEqual(usage.quotaMb ?? 0, 102_400, accuracy: 0.01)
        XCTAssertEqual(usage.percentUsed, 5)
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
        XCTAssertEqual(CarrierParser.formatCaSummary(carriers), "B3 + B1 + N78 · 135 MHz")
        XCTAssertEqual(CarrierParser.formatBandPill(carriers), "B3+B1+N78")
        XCTAssertEqual(CarrierParser.formatCellPill(carriers), "PCI 201 · 3CC")

        let alignment = Phase1Parsers.parseAlignmentSnapshot(
            ["RSRP_5G": "-84", "SINR_5G": "19.5", "RSRP": "-92", "SINR": "12", "network_type_str": "5G_NSA"],
            carriers: carriers,
            timestampMs: 1_000
        )
        XCTAssertEqual(alignment.activeTechLabel, "5G NR")
        XCTAssertEqual(alignment.primarySinr, 19.5)
        XCTAssertEqual(alignment.primaryRsrp, -84)

        let neighbors = Phase1Parsers.parseNeighborCells([
            "lte_info": [
                ["earfcn": "1650", "pci": "12", "rsrp": "-85", "rsrq": "-9"],
                ["earfcn": "3050", "pci": "44", "rsrp": "-95", "rsrq": "-12"]
            ],
            "nr_info": [
                ["arfcn": "633984", "pci": "201", "rsrp": "-82", "sinr": "18.0"]
            ]
        ])
        XCTAssertEqual(neighbors.count, 3)
        XCTAssertEqual(neighbors.first?.tech, .lte)
        XCTAssertEqual(neighbors.last?.tech, .nr)

        let hist = Phase1Parsers.parseSignalHistory1018([
            ["time": "12:00", "rsrp_4g": "-90", "sinr_4g": "10", "rsrp_5g": "-85", "sinr_5g": "18"]
        ])
        XCTAssertEqual(hist.count, 1)
        XCTAssertEqual(hist.first?.rsrp5g, -85)
    }

    func testValidatorsAndUssdUtf16BeDecoder() {
        XCTAssertNotNil(WifiAndLanValidators.validateSsid("Admin", forbiddenSsids: ["Admin"]))
        XCTAssertNotNil(WifiAndLanValidators.validateWifiPassword("12345678", authenticationType: "2", forbiddenKeys: ["12345678"]))
        XCTAssertNil(WifiAndLanValidators.validateWifiPassword("StrongPass#2026", authenticationType: "2"))

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
        XCTAssertEqual(SmsValidators.weightedSmsLength("Hi😀"), 6)

        // UCS2 UTF-16BE hex for "Bal: N500"
        let ucs2Hex = "Bal: N500".utf16.map { String(format: "%04X", $0) }.joined()
        if case .text(let decodedText) = Phase71Validators.decodeUssdResponse(ucs2Hex) {
            XCTAssertEqual(decodedText, "Bal: N500")
        } else {
            XCTFail("Expected decoded UCS2 USSD text")
        }

        if case .timeout = Phase71Validators.decodeUssdResponse("507") {
            // expected
        } else {
            XCTFail("Expected .timeout for USSD code 507")
        }

        XCTAssertEqual(Phase71Validators.normalizePortRange("80-90", separator: ":"), "80:90")
        XCTAssertEqual(Phase71Validators.findPortConflict(portExpr: "75-85", reservedPorts: [80, 443]), 80)
        XCTAssertEqual(SensitiveFormatters.maskIdentifier("123456789012345"), "•••••••••••2345")
    }

    @MainActor
    func testBuildNetworkModeSummary() {
        let state = RouterState(
            timestampMs: 0,
            timeFormatted: "12:00:00",
            wan: WanMetrics(downloadKbps: 0, uploadKbps: 0, downloadMbps: 0, uploadMbps: 0, dlFlowMb: "0 MB", ulFlowMb: "0 MB", totalFlowMb: "0 MB", peakDlMbps: 0, peakUlMbps: 0),
            cellular: CellularMetrics(networkType: "5G_NSA"),
            cellLock: CellLockInfo(),
            hardware: HardwareMetrics(cpuUsage: 20, temperature: "42", memory: "40"),
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
        XCTAssertTrue(summary.contains("NR CA"))
        XCTAssertTrue(summary.contains("Roaming"))
    }
}
