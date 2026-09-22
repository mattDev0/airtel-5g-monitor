import XCTest
@testable import AirtelMonitor

final class ZltRouterClientTests: XCTestCase {

    func testIpv4Validation() {
        XCTAssertTrue(ZltRouterClient.isIpv4("192.168.1.1"))
        XCTAssertTrue(ZltRouterClient.isIpv4("8.8.8.8"))
        XCTAssertTrue(ZltRouterClient.isIpv4("1.1.1.1"))
        XCTAssertTrue(ZltRouterClient.isIpv4("255.255.255.255"))
        XCTAssertTrue(ZltRouterClient.isIpv4("0.0.0.0"))

        // Invalid cases
        XCTAssertFalse(ZltRouterClient.isIpv4("256.1.1.1"))
        XCTAssertFalse(ZltRouterClient.isIpv4("1.1.1"))
        XCTAssertFalse(ZltRouterClient.isIpv4("1.1.1.1.1"))
        XCTAssertFalse(ZltRouterClient.isIpv4("not.an.ip.address"))
        XCTAssertFalse(ZltRouterClient.isIpv4(""))
        XCTAssertFalse(ZltRouterClient.isIpv4("01.1.1.1")) // leading zero disallowed
        XCTAssertFalse(ZltRouterClient.isIpv4("-1.0.0.1"))
    }

    func testSignalLevelParsing() {
        func parseSignalLevel(_ dict: [String: Any]) -> Int {
            if let lvl = dict["signal_lvl"] as? Int { return lvl }
            if let str = dict["signal_lvl"] as? String, let lvl = Int(str.trimmingCharacters(in: .whitespaces)) { return lvl }
            return 0
        }

        XCTAssertEqual(parseSignalLevel(["signal_lvl": "3"]), 3)
        XCTAssertEqual(parseSignalLevel(["signal_lvl": "5"]), 5)
        XCTAssertEqual(parseSignalLevel(["signal_lvl": 4]), 4)
        XCTAssertEqual(parseSignalLevel(["signal_lvl": 0]), 0)
        XCTAssertEqual(parseSignalLevel(["signal_lvl": "invalid"]), 0)
        XCTAssertEqual(parseSignalLevel([:]), 0)
    }

    func testDeviceTypeInference() {
        let phoneNames = ["iPhone 15 Pro", "My Galaxy S24", "Google Pixel 8", "Redmi Note"]
        for name in phoneNames {
            let item = DeviceItem(mac: "AA:BB:CC:DD:EE:01", ip: "192.168.1.10", hostname: name, type: .phone)
            XCTAssertEqual(item.type, .phone, "Expected \(name) to be phone")
            XCTAssertEqual(item.type.sfSymbol, "iphone")
        }

        let tablet = DeviceItem(hostname: "iPad Pro", type: .tablet)
        XCTAssertEqual(tablet.type.sfSymbol, "ipad")

        let laptop = DeviceItem(hostname: "MacBook Air", type: .pc)
        XCTAssertEqual(laptop.type.sfSymbol, "laptopcomputer")

        let tv = DeviceItem(hostname: "AppleTV-LivingRoom", type: .tv)
        XCTAssertEqual(tv.type.sfSymbol, "tv")

        let console = DeviceItem(hostname: "PlayStation-5", type: .gaming)
        XCTAssertEqual(console.type.sfSymbol, "gamecontroller")

        let router = DeviceItem(hostname: "ZLT-W304VA", type: .router)
        XCTAssertEqual(router.type.sfSymbol, "wifi.router")
    }

    func testDeviceItemDisplayNames() {
        var dev = DeviceItem(mac: "AA:BB:CC:11:22:33", ip: "192.168.1.100", hostname: "LAPTOP-XYZ", alias: "")
        XCTAssertEqual(dev.displayName, "LAPTOP-XYZ")

        dev.alias = "Work Laptop"
        XCTAssertEqual(dev.displayName, "Work Laptop")
    }

    func testSettingsStoreAliases() {
        let testDefaults = UserDefaults(suiteName: "test_suite_\(UUID().uuidString)")!
        let store = SettingsStore(defaults: testDefaults)

        XCTAssertEqual(store.getAlias(for: "AA:BB:CC:DD:EE:FF", fallback: "Fallback"), "Fallback")

        store.setAlias("Custom Name", for: "AA:BB:CC:DD:EE:FF")
        XCTAssertEqual(store.getAlias(for: "AA:BB:CC:DD:EE:FF", fallback: "Fallback"), "Custom Name")

        // Case insensitivity
        XCTAssertEqual(store.getAlias(for: "aa:bb:cc:dd:ee:ff", fallback: "Fallback"), "Custom Name")

        // Clearing alias
        store.setAlias("", for: "AA:BB:CC:DD:EE:FF")
        XCTAssertEqual(store.getAlias(for: "AA:BB:CC:DD:EE:FF", fallback: "Fallback"), "Fallback")
    }

    func testWifiRadioConfigModel() {
        let config = WifiRadioConfig(
            enabled24g: true,
            ssid24g: "Airtel_2.4G",
            channel24g: "6",
            enabled5g: false,
            ssid5g: "Airtel_5G",
            channel5g: "36"
        )
        XCTAssertTrue(config.enabled24g)
        XCTAssertFalse(config.enabled5g)
        XCTAssertEqual(config.ssid24g, "Airtel_2.4G")
        XCTAssertEqual(config.channel24g, "6")
    }

    func testDiagnosisStateModel() {
        var diag = DiagnosisState()
        XCTAssertEqual(diag.mode, .ping)
        XCTAssertEqual(diag.pingCount, 4)
        XCTAssertFalse(diag.isRunning)

        diag.mode = .traceroute
        XCTAssertEqual(diag.mode.rawValue, "Traceroute")
    }
}
