import Foundation
import XCTest
@testable import AirtelMonitor

@MainActor
final class WifiLanSimWriteTests: XCTestCase {

    func testWifiTogglePreReadGuardVerifyAndExactPayloadKeys() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var open24 = false
        var open5 = true
        env.transport.on(2) { req in
            if req.method == "POST" {
                open24 = (req.payload["wifiOpen"] as? String) == "1"
                return ["success": true, "message": "0"]
            }
            return RouterFixtures.wifiRecord(cmd: 2, on: open24, ssidPlain: "Home_24G", key: "secret24")
        }
        env.transport.on(211) { req in
            if req.method == "POST" {
                open5 = (req.payload["wifiOpen"] as? String) == "1"
                return ["success": true, "message": "0"]
            }
            return RouterFixtures.wifiRecord(cmd: 211, on: open5, ssidPlain: "Home_5G", key: "secret5g")
        }

        let repo = WifiRepository(session: env.session, writer: env.writer)

        // Turning off 5 GHz while 2.4 GHz is off must block locally with zero POSTs.
        let blocked = await repo.setWifiEnabled(band: .band5g, enabled: false)
        XCTAssertFalse(blocked.isOk)
        XCTAssertTrue((blocked.errorMessage ?? "").contains("2.4 GHz"))
        XCTAssertEqual(env.transport.sent(211, method: "POST").count, 0)

        // Already on -> no-op without POST
        let noop = await repo.setWifiEnabled(band: .band5g, enabled: true)
        XCTAssertTrue(noop.isOk)
        XCTAssertEqual(env.transport.sent(211, method: "POST").count, 0)

        // Turn 2.4 GHz on, then turn 5 GHz off.
        open24 = true
        let offRes = await repo.setWifiEnabled(band: .band5g, enabled: false)
        XCTAssertTrue(offRes.isOk, "Expected 5 GHz toggle off to succeed, got \(offRes)")

        let post = try XCTUnwrap(env.transport.sent(211, method: "POST").first)
        assertPayloadKeys(post, exactDataKeys: [
            "subcmd", "wifiOpen", "broadcast", "ssid", "key",
            "authenticationType", "wifiSames", "maxStaNum"
        ])
        XCTAssertEqual(post.payload["subcmd"] as? Int, 0)
        XCTAssertEqual(post.payload["wifiOpen"] as? String, "0")
        XCTAssertEqual(post.payload["ssid"] as? String, CodecHelpers.encodeBase64Utf8("Home_5G"))
        XCTAssertEqual(post.payload["key"] as? String, "secret5g")
        XCTAssertFalse(repo.config.enabled5g)
    }

    func testWifiBandSaveValidationForbidListsAndSameToBothBands() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var band24 = RouterFixtures.wifiRecord(cmd: 2, on: true, ssidPlain: "Airtel_24G", key: "pass1234", wifiSames: "0", maxStaNum: "32")
        var band5 = RouterFixtures.wifiRecord(cmd: 211, on: true, ssidPlain: "Airtel_5G", key: "pass5678", wifiSames: "0", maxStaNum: "64")

        env.transport.on(80, method: "GET") { _ in
            [
                "success": true,
                "aeraId": "NG0131",
                "account_level": "1",
                "wifi_ssid_forbid": "ForbiddenSsid",
                "wifi_pwd_forbid": "ForbiddenPass1"
            ]
        }
        env.transport.on(2) { req in
            if req.method == "POST" {
                for key in ["wifiOpen", "ssid", "key", "authenticationType", "broadcast", "wifiSames", "maxStaNum"] {
                    if let val = req.payload[key] { band24[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return band24
        }
        env.transport.on(211) { req in
            if req.method == "POST" {
                for key in ["wifiOpen", "ssid", "key", "authenticationType", "broadcast", "wifiSames", "maxStaNum"] {
                    if let val = req.payload[key] { band5[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return band5
        }

        let repo = WifiRepository(session: env.session, writer: env.writer)
        let loaded = await repo.loadWifiBandDetails()
        XCTAssertTrue(loaded.isOk)

        // Forbidden SSID, forbidden key, and short key rejected locally before any POST.
        let badForbidSsid = await repo.saveWifiBandConfig(
            band: .band24g,
            ssid: "ForbiddenSsid",
            key: "ValidPass99",
            authenticationType: "2",
            visibleBroadcast: true
        )
        XCTAssertFalse(badForbidSsid.isOk)

        let badForbidKey = await repo.saveWifiBandConfig(
            band: .band24g,
            ssid: "CleanSsid",
            key: "ForbiddenPass1",
            authenticationType: "2",
            visibleBroadcast: true
        )
        XCTAssertFalse(badForbidKey.isOk)

        let shortKey = await repo.saveWifiBandConfig(
            band: .band24g,
            ssid: "CleanSsid",
            key: "short",
            authenticationType: "2",
            visibleBroadcast: true
        )
        XCTAssertFalse(shortKey.isOk)
        XCTAssertEqual(env.transport.sent(2, method: "POST").count, 0)

        // Single-band write with hidden broadcast + Base64 SSID
        let singleRes = await repo.saveWifiBandConfig(
            band: .band24g,
            ssid: "Matt Lab 2.4",
            key: "NewSecret24!",
            authenticationType: "3",
            visibleBroadcast: false,
            wifiSames: false,
            applySameToBothBands: false
        )
        XCTAssertTrue(singleRes.isOk)
        XCTAssertEqual(env.transport.sent(211, method: "POST").count, 0)
        let p2 = try XCTUnwrap(env.transport.sent(2, method: "POST").first)
        assertPayloadKeys(p2, exactDataKeys: [
            "subcmd", "wifiOpen", "ssid", "key", "authenticationType", "broadcast", "wifiSames", "maxStaNum"
        ])
        XCTAssertEqual(p2.payload["ssid"] as? String, CodecHelpers.encodeBase64Utf8("Matt Lab 2.4"))
        XCTAssertEqual(p2.payload["broadcast"] as? String, "0")
        XCTAssertEqual(p2.payload["wifiSames"] as? String, "0")
        XCTAssertEqual(p2.payload["authenticationType"] as? String, "3")
        XCTAssertEqual(p2.payload["key"] as? String, "NewSecret24!")

        // Open auth ("0") preserves existing router key
        env.transport.clearRequests()
        let openRes = await repo.saveWifiBandConfig(
            band: .band5g,
            ssid: "OpenCafe",
            key: "",
            authenticationType: "0",
            visibleBroadcast: true
        )
        XCTAssertTrue(openRes.isOk)
        let openPost = try XCTUnwrap(env.transport.sent(211, method: "POST").first)
        XCTAssertEqual(openPost.payload["key"] as? String, "pass5678")

        // applySameToBothBands: true updates BOTH 2.4G and 5G with wifiSames == "1"
        env.transport.clearRequests()
        let bothRes = await repo.saveWifiBandConfig(
            band: .band24g,
            ssid: "UnifiedWiFi",
            key: "UnifiedKey88",
            authenticationType: "2",
            visibleBroadcast: true,
            applySameToBothBands: true
        )
        XCTAssertTrue(bothRes.isOk)
        let u2 = try XCTUnwrap(env.transport.sent(2, method: "POST").first)
        let u5 = try XCTUnwrap(env.transport.sent(211, method: "POST").first)
        XCTAssertEqual(u2.payload["wifiSames"] as? String, "1")
        XCTAssertEqual(u5.payload["wifiSames"] as? String, "1")
        XCTAssertEqual(u2.payload["ssid"] as? String, CodecHelpers.encodeBase64Utf8("UnifiedWiFi"))
        XCTAssertEqual(u5.payload["ssid"] as? String, CodecHelpers.encodeBase64Utf8("UnifiedWiFi"))
    }

    func testGuestWifiPreflight417ValidationAndPayloadKeys() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var wifiInitStatus = "0"
        var guest24: [String: Any] = [
            "success": true,
            "wifiOpen": "0",
            "ssid": CodecHelpers.encodeBase64Utf8("OldGuest"),
            "key": "oldguest12",
            "authenticationType": "2",
            "encryption": "1",
            "broadcast": "1",
            "wmm": "1"
        ]
        env.transport.on(417, method: "GET") { _ in ["success": true, "wifiStatus": wifiInitStatus] }
        env.transport.on(2) { req in
            if (req.payload["subcmd"] as? Int) == 1 && req.method == "POST" {
                for key in ["wifiOpen", "ssid", "key", "authenticationType", "encryption", "broadcast", "wmm", "customPort"] {
                    if let val = req.payload[key] { guest24[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            if (req.payload["subcmd"] as? Int) == 1 {
                return guest24
            }
            return RouterFixtures.wifiRecord(cmd: 2, on: true)
        }

        let repo = WifiRepository(session: env.session, writer: env.writer)
        let cfg = GuestWifiBandConfig(
            wifiOpen: true,
            ssid: "GuestNet",
            key: "guest1234",
            authenticationType: "2",
            encryption: "1",
            broadcast: false,
            wmm: false,
            mainWifiOpen: true,
            customPort: "8080"
        )

        // Blocked while wifiStatus != "1"
        let notReady = await repo.saveGuestWifi(band: .band24g, config: cfg)
        XCTAssertFalse(notReady.isOk)
        XCTAssertEqual(env.transport.sent(2, method: "POST").count, 0)

        // Allowed once 417 reports ready; includes customPort when non-empty
        wifiInitStatus = "1"
        let okRes = await repo.saveGuestWifi(band: .band24g, config: cfg)
        XCTAssertTrue(okRes.isOk)
        let postWithPort = try XCTUnwrap(env.transport.sent(2, method: "POST").first)
        assertPayloadKeys(postWithPort, exactDataKeys: [
            "subcmd", "wifiOpen", "ssid", "key", "authenticationType",
            "encryption", "broadcast", "wmm", "customPort"
        ])
        XCTAssertEqual(postWithPort.payload["subcmd"] as? Int, 1)
        XCTAssertEqual(postWithPort.payload["ssid"] as? String, CodecHelpers.encodeBase64Utf8("GuestNet"))
        XCTAssertEqual(postWithPort.payload["broadcast"] as? String, "0")
        XCTAssertEqual(postWithPort.payload["wmm"] as? String, "0")
        XCTAssertEqual(postWithPort.payload["customPort"] as? String, "8080")

        // Empty customPort omits key
        env.transport.clearRequests()
        let noPortCfg = GuestWifiBandConfig(
            wifiOpen: false,
            ssid: "",
            key: "",
            authenticationType: "0",
            encryption: "1",
            broadcast: true,
            wmm: true,
            mainWifiOpen: true,
            customPort: ""
        )
        let offRes = await repo.saveGuestWifi(band: .band24g, config: noPortCfg)
        XCTAssertTrue(offRes.isOk)
        let postNoPort = try XCTUnwrap(env.transport.sent(2, method: "POST").first)
        assertPayloadKeys(postNoPort, exactDataKeys: [
            "subcmd", "wifiOpen", "ssid", "key", "authenticationType",
            "encryption", "broadcast", "wmm"
        ])
        XCTAssertEqual(postNoPort.payload["wifiOpen"] as? String, "0")
    }

    func testWifiAdvancedStaGuardRangeCheckDfs160ClampAndPayloadKeys() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var state5: [String: Any] = [
            "success": true,
            "wifiOpen": "1",
            "countryCode": "NG",
            "wifi_workMode": "1",
            "channel": "36",
            "bandWidth": "3",
            "txPower": "100",
            "maxNum": "32",
            "maxNumLimit": "64",
            "apIsolate": "0",
            "dfs_switch": "0"
        ]
        var state24: [String: Any] = [
            "success": true,
            "wifiOpen": "1",
            "countryCode": "NG",
            "wifi_workMode": "0",
            "channel": "6",
            "bandWidth": "1",
            "txPower": "50",
            "maxNum": "16",
            "maxNum24Limit": "32",
            "apIsolate": "0"
        ]

        env.transport.on(231) { req in
            if req.method == "POST" {
                for key in ["wifiOpen", "channel", "bandWidth", "maxNum", "apIsolate", "txPower", "dfs_switch", "countryCode", "wifi_workMode"] {
                    if let val = req.payload[key] { state5[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return state5
        }
        env.transport.on(208) { req in
            if req.method == "POST" {
                for key in ["wifiOpen", "channel", "bandWidth", "maxNum", "apIsolate", "txPower", "countryCode", "wifi_workMode"] {
                    if let val = req.payload[key] { state24[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return state24
        }

        let repo = WifiRepository(session: env.session, writer: env.writer)

        // clientStaReadOnly and out-of-range maxNum rejected locally
        let roErr = await repo.saveWifiAdvanced(
            band: .band5g,
            config: WifiAdvancedBandConfig(clientStaReadOnly: true, staLimitPrompt: "Locked by ISP")
        )
        XCTAssertEqual(roErr.errorMessage, "Locked by ISP")

        let rangeErr = await repo.saveWifiAdvanced(
            band: .band5g,
            config: WifiAdvancedBandConfig(maxNum: 65, maxNumLimit: 64)
        )
        XCTAssertFalse(rangeErr.isOk)
        XCTAssertEqual(env.transport.sent(231, method: "POST").count, 0)

        // 5 GHz with bandWidth == "4" (160 MHz) & dfsSwitch == false clamps to "3" (80 MHz)
        let clampedRes = await repo.saveWifiAdvanced(
            band: .band5g,
            config: WifiAdvancedBandConfig(
                wifiOpen: true,
                channel: "149",
                bandWidth: "4",
                txPower: "50",
                maxNum: 24,
                maxNumLimit: 64,
                apIsolate: true,
                dfsSwitch: false
            )
        )
        XCTAssertTrue(clampedRes.isOk)
        let post5 = try XCTUnwrap(env.transport.sent(231, method: "POST").first)
        assertPayloadKeys(post5, exactDataKeys: [
            "wifiOpen", "channel", "bandWidth", "maxNum", "apIsolate",
            "txPower", "dfs_switch", "countryCode", "wifi_workMode"
        ])
        XCTAssertEqual(post5.payload["bandWidth"] as? String, "3")
        XCTAssertEqual(post5.payload["dfs_switch"] as? String, "0")
        XCTAssertEqual(post5.payload["countryCode"] as? String, "NG")
        XCTAssertEqual(post5.payload["wifi_workMode"] as? String, "1")
        XCTAssertEqual(post5.payload["apIsolate"] as? String, "1")

        // 5 GHz with dfsSwitch == true keeps bandWidth == "4"
        env.transport.clearRequests()
        let dfsRes = await repo.saveWifiAdvanced(
            band: .band5g,
            config: WifiAdvancedBandConfig(bandWidth: "4", maxNum: 32, maxNumLimit: 64, dfsSwitch: true)
        )
        XCTAssertTrue(dfsRes.isOk)
        let postDfs = try XCTUnwrap(env.transport.sent(231, method: "POST").first)
        XCTAssertEqual(postDfs.payload["bandWidth"] as? String, "4")
        XCTAssertEqual(postDfs.payload["dfs_switch"] as? String, "1")

        // 2.4 GHz omits dfs_switch
        let res24 = await repo.saveWifiAdvanced(
            band: .band24g,
            config: WifiAdvancedBandConfig(channel: "11", bandWidth: "1", txPower: "100", maxNum: 20, maxNumLimit: 32)
        )
        XCTAssertTrue(res24.isOk)
        let post24 = try XCTUnwrap(env.transport.sent(208, method: "POST").first)
        assertPayloadKeys(post24, exactDataKeys: [
            "wifiOpen", "channel", "bandWidth", "maxNum", "apIsolate",
            "txPower", "countryCode", "wifi_workMode"
        ])
    }

    func testWifiPower411ValidationAndPayloadKeys() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var curPower = "15"
        env.transport.on(411) { req in
            if req.method == "POST" {
                curPower = req.payload["txPower"] as? String ?? curPower
                return ["success": true, "message": "0"]
            }
            return ["success": true, "txPower": curPower, "txPower5g": curPower]
        }

        let repo = WifiRepository(session: env.session, writer: env.writer)
        let invalid = await repo.saveWifiPower411("75")
        XCTAssertFalse(invalid.isOk)
        XCTAssertEqual(env.transport.sent(411, method: "POST").count, 0)

        let saved = await repo.saveWifiPower411("50")
        XCTAssertEqual(saved.value, WifiPower411Config(txPower24g: "50", txPower5g: "50"))
        let post = try XCTUnwrap(env.transport.sent(411, method: "POST").first)
        assertPayloadKeys(post, exactDataKeys: ["txPower", "txPower5g"])
        XCTAssertEqual(post.payload["txPower"] as? String, "50")
        XCTAssertEqual(post.payload["txPower5g"] as? String, "50")
    }

    func testWpsEnablePreflight417PbcPinRandomPinAndSuccessPoll() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var wifiStatus = "0"
        var wps24 = "0"
        var wps5 = "1"
        var randomPinReply = ""
        var wpsSuccessFlag = "1"

        env.transport.on(417, method: "GET") { _ in ["success": true, "wifiStatus": wifiStatus] }
        env.transport.on(2, method: "GET") { _ in RouterFixtures.wifiRecord(cmd: 2, on: true) }
        env.transport.on(211, method: "GET") { _ in RouterFixtures.wifiRecord(cmd: 211, on: true) }
        env.transport.on(132) { req in
            let sub = req.payload["subcmd"] as? Int ?? 0
            if req.method == "POST" {
                if sub == 0 { wps24 = req.payload["wlan2g_wps_switch"] as? String ?? wps24 }
                if sub == 1 { wps5 = req.payload["wlan5g_wps_switch"] as? String ?? wps5 }
                return ["success": true, "message": "0"]
            }
            return sub == 0
                ? ["success": true, "wlan2g_wps_switch": wps24]
                : ["success": true, "wlan5g_wps_switch": wps5]
        }
        env.transport.on(243, method: "POST") { _ in ["success": true, "message": "0"] }
        env.transport.on(244, method: "POST") { _ in
            ["success": !randomPinReply.isEmpty, "random_ping": randomPinReply]
        }
        env.transport.on(325) { req in
            if req.method == "POST" {
                wpsSuccessFlag = req.payload["wps_is_success_state"] as? String ?? "0"
                return ["success": true]
            }
            return ["success": true, "wps_is_success_state": wpsSuccessFlag]
        }

        let repo = WifiRepository(session: env.session, writer: env.writer)

        // Preflight blocked
        let preflightBlocked = await repo.setWpsEnabled(band: .band24g, enabled: true)
        XCTAssertFalse(preflightBlocked.isOk)
        XCTAssertEqual(env.transport.sent(132, method: "POST").count, 0)

        // Enable 2.4G WPS, disable 5G WPS
        wifiStatus = "1"
        let en24 = await repo.setWpsEnabled(band: .band24g, enabled: true)
        XCTAssertEqual(en24.value?.band24g.enabled, true)
        let p132_0 = try XCTUnwrap(env.transport.sent(132, method: "POST").first)
        assertPayloadKeys(p132_0, exactDataKeys: ["subcmd", "wlan2g_wps_switch"])
        XCTAssertEqual(p132_0.payload["subcmd"] as? Int, 0)
        XCTAssertEqual(p132_0.payload["wlan2g_wps_switch"] as? String, "1")

        let dis5 = await repo.setWpsEnabled(band: .band5g, enabled: false)
        XCTAssertEqual(dis5.value?.band5g.enabled, false)
        let p132_1 = try XCTUnwrap(env.transport.sent(132, method: "POST").last)
        assertPayloadKeys(p132_1, exactDataKeys: ["subcmd", "wlan5g_wps_switch"])
        XCTAssertEqual(p132_1.payload["subcmd"] as? Int, 1)
        XCTAssertEqual(p132_1.payload["wlan5g_wps_switch"] as? String, "0")

        // PBC trigger: subcmd 2 (2.4G) / 3 (5G)
        let writeRes1 = await repo.triggerWpsPbc(band: .band24g)
        XCTAssertTrue(writeRes1.isOk)
        let writeRes2 = await repo.triggerWpsPbc(band: .band5g)
        XCTAssertTrue(writeRes2.isOk)
        let pbcPosts = env.transport.sent(243, method: "POST")
        XCTAssertEqual(pbcPosts.count, 2)
        assertPayloadKeys(pbcPosts[0], exactDataKeys: ["subcmd"])
        XCTAssertEqual(pbcPosts[0].payload["subcmd"] as? Int, 2)
        XCTAssertEqual(pbcPosts[1].payload["subcmd"] as? Int, 3)

        // Submit WPS PIN: bad checksum rejected locally, valid 12345670 accepted
        env.transport.clearRequests()
        let writeRes3 = await repo.submitWpsPin(band: .band5g, pin: "12345678")
        XCTAssertFalse(writeRes3.isOk)
        XCTAssertEqual(env.transport.sent(243, method: "POST").count, 0)

        let writeRes4 = await repo.submitWpsPin(band: .band5g, pin: "12345670")
        XCTAssertTrue(writeRes4.isOk)
        let pinPost = try XCTUnwrap(env.transport.sent(243, method: "POST").first)
        assertPayloadKeys(pinPost, exactDataKeys: ["subcmd", "wpsPin"])
        XCTAssertEqual(pinPost.payload["subcmd"] as? Int, 1)
        XCTAssertEqual(pinPost.payload["wpsPin"] as? String, "12345670")

        // Random PIN: empty random_ping rejected, non-empty returned
        let writeRes5 = await repo.openRandomWpsPin(band: .band24g)
        XCTAssertFalse(writeRes5.isOk)
        randomPinReply = "87654325"
        let randRes = await repo.openRandomWpsPin(band: .band24g)
        XCTAssertEqual(randRes.value, "87654325")
        let p244 = try XCTUnwrap(env.transport.sent(244, method: "POST").last)
        assertPayloadKeys(p244, exactDataKeys: ["subcmd"])
        XCTAssertEqual(p244.payload["subcmd"] as? Int, 0)

        // Poll random WPS success: clears flag on "1", no-ops on "0"
        let firstPoll = await repo.pollRandomWpsSuccess()
        XCTAssertEqual(firstPoll.value, true)
        let clearPost = try XCTUnwrap(env.transport.sent(325, method: "POST").first)
        assertPayloadKeys(clearPost, exactDataKeys: ["wps_is_success_state"])
        XCTAssertEqual(clearPost.payload["wps_is_success_state"] as? String, "0")

        let secondPoll = await repo.pollRandomWpsSuccess()
        XCTAssertEqual(secondPoll.value, false)
        XCTAssertEqual(env.transport.sent(325, method: "POST").count, 1)
    }

    func testWifiScheduleEnabledAndDisabledPayloads() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var scheduleState: [String: Any] = ["success": true, "wifiSwitch": "0"]
        env.transport.on(503) { req in
            if req.method == "POST" {
                scheduleState = ["success": true]
                for key in ["wifiSwitch", "wifi_on_start_hour", "wifi_on_start_min", "wifi_off_start_hour", "wifi_off_start_min"] {
                    if let val = req.payload[key] { scheduleState[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return scheduleState
        }

        let repo = WifiRepository(session: env.session, writer: env.writer)
        let onRes = await repo.saveWifiSchedule(
            WifiScheduleConfig(enabled: true, onHour: 8, onMinute: 30, offHour: 22, offMinute: 15)
        )
        XCTAssertEqual(onRes.value?.enabled, true)
        let onPost = try XCTUnwrap(env.transport.sent(503, method: "POST").first)
        assertPayloadKeys(onPost, exactDataKeys: [
            "wifiSwitch", "wifi_on_start_hour", "wifi_on_start_min",
            "wifi_off_start_hour", "wifi_off_start_min"
        ])
        XCTAssertEqual(onPost.payload["wifiSwitch"] as? String, "1")
        XCTAssertEqual(onPost.payload["wifi_on_start_hour"] as? String, "8")
        XCTAssertEqual(onPost.payload["wifi_on_start_min"] as? String, "30")
        XCTAssertEqual(onPost.payload["wifi_off_start_hour"] as? String, "22")
        XCTAssertEqual(onPost.payload["wifi_off_start_min"] as? String, "15")

        env.transport.clearRequests()
        let offRes = await repo.saveWifiSchedule(WifiScheduleConfig(enabled: false))
        XCTAssertEqual(offRes.value?.enabled, false)
        let offPost = try XCTUnwrap(env.transport.sent(503, method: "POST").first)
        assertPayloadKeys(offPost, exactDataKeys: ["wifiSwitch"])
        XCTAssertEqual(offPost.payload["wifiSwitch"] as? String, "0")
    }

    func testLanSaveDnsPreservesAll19Cmd3KeysAndRejectsInvalidIpv4() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var current3 = RouterFixtures.json3()
        env.transport.on(3) { req in
            if req.method == "POST" {
                for (k, v) in req.payload where !["cmd", "method", "sessionId", "token"].contains(k) {
                    current3[k] = v
                }
                return ["success": true, "message": "0"]
            }
            return current3
        }

        let repo = LanRepository(session: env.session, writer: env.writer)

        let writeRes1 = await repo.saveDns(primary: "999.1.1.1", secondary: "")
        XCTAssertFalse(writeRes1.isOk)
        let writeRes2 = await repo.saveDns(primary: "1.1.1.1", secondary: "not-an-ip")
        XCTAssertFalse(writeRes2.isOk)
        XCTAssertEqual(env.transport.sent(3, method: "POST").count, 0)

        let res = await repo.saveDns(primary: "9.9.9.9", secondary: "149.112.112.112")
        XCTAssertEqual(res.value, DnsConfig(primary: "9.9.9.9", secondary: "149.112.112.112"))

        let post = try XCTUnwrap(env.transport.sent(3, method: "POST").first)
        assertPayloadKeys(post, exactDataKeys: [
            "lanIp", "netMask", "dhcpServer", "ipBegin", "ipEnd", "expireTime",
            "main_dns", "vice_dns",
            "bindPort0", "ipv6_mode", "ipv6_startIp", "ipv6_endIp",
            "ipv6_main_dns", "ipv6_vice_dns", "relay_sw", "mtu", "domain", "supportList", "lanMark"
        ])
        XCTAssertEqual(post.payload["main_dns"] as? String, "9.9.9.9")
        XCTAssertEqual(post.payload["vice_dns"] as? String, "149.112.112.112")
        XCTAssertEqual(post.payload["bindPort0"] as? String, "f0f0f")
        XCTAssertEqual(post.payload["domain"] as? String, "m.home")
        XCTAssertEqual(post.payload["mtu"] as? String, "1500")
        XCTAssertEqual(post.payload["lanMark"] as? String, "1")
    }

    func testLanSaveDhcpSettingsValidationDisabledAndEnabledPayloads() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var current3 = RouterFixtures.json3()
        env.transport.on(3) { req in
            if req.method == "POST" {
                for (k, v) in req.payload where !["cmd", "method", "sessionId", "token"].contains(k) {
                    current3[k] = v
                }
                return ["success": true, "message": "0"]
            }
            return current3
        }

        let repo = LanRepository(session: env.session, writer: env.writer)

        // Invalid pool outside LAN subnet & reversed range must be blocked locally.
        let badSubnet = await repo.saveLanDhcpSettings(
            lanIp: "192.168.1.1",
            netMask: "255.255.255.0",
            dhcpEnabled: true,
            ipBegin: "192.168.2.100",
            ipEnd: "192.168.2.200",
            expireTime: "24h",
            primaryDns: "1.1.1.1",
            secondaryDns: "8.8.8.8"
        )
        XCTAssertFalse(badSubnet.isOk)

        let badRange = await repo.saveLanDhcpSettings(
            lanIp: "192.168.1.1",
            netMask: "255.255.255.0",
            dhcpEnabled: true,
            ipBegin: "192.168.1.200",
            ipEnd: "192.168.1.100",
            expireTime: "24h",
            primaryDns: "",
            secondaryDns: ""
        )
        XCTAssertFalse(badRange.isOk)
        XCTAssertEqual(env.transport.sent(3, method: "POST").count, 0)

        let okRes = await repo.saveLanDhcpSettings(
            lanIp: "10.0.0.1",
            netMask: "255.255.255.0",
            dhcpEnabled: true,
            ipBegin: "10.0.0.50",
            ipEnd: "10.0.0.150",
            expireTime: "12h",
            primaryDns: "1.1.1.1",
            secondaryDns: "8.8.8.8"
        )
        XCTAssertEqual(okRes.value?.lanIp, "10.0.0.1")
        XCTAssertEqual(okRes.value?.ipBegin, "10.0.0.50")
        let post = try XCTUnwrap(env.transport.sent(3, method: "POST").first)
        assertPayloadKeys(post, exactDataKeys: [
            "lanIp", "netMask", "dhcpServer", "ipBegin", "ipEnd", "expireTime",
            "main_dns", "vice_dns",
            "bindPort0", "ipv6_mode", "ipv6_startIp", "ipv6_endIp",
            "ipv6_main_dns", "ipv6_vice_dns", "relay_sw", "mtu", "domain", "supportList", "lanMark"
        ])
        XCTAssertEqual(post.payload["dhcpServer"] as? String, "1")
        XCTAssertEqual(post.payload["expireTime"] as? String, "12h")
    }

    func testLanStaticLeasesValidationMacNormalizationBindingSyncAndApplyFilter() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var storedDatas: [[String: Any]] = []
        env.transport.on(115) { req in
            if req.method == "POST" {
                storedDatas = req.payload["datas"] as? [[String: Any]] ?? []
                return ["success": true, "message": "0"]
            }
            return ["success": true, "datas": storedDatas]
        }

        let repo = LanRepository(session: env.session, writer: env.writer)

        // Multicast MAC, duplicate IP, duplicate MAC all rejected locally
        let writeRes1 = await repo.saveStaticLeases([StaticDhcpLease(ip: "192.168.1.50", mac: "01:11:22:33:44:55")])
        XCTAssertFalse(writeRes1.isOk)
        let writeRes2 = await repo.saveStaticLeases([
            StaticDhcpLease(ip: "192.168.1.50", mac: "AA:BB:CC:11:22:33"),
            StaticDhcpLease(ip: "192.168.1.50", mac: "AA:BB:CC:11:22:44")
        ])
        XCTAssertFalse(writeRes2.isOk)
        let writeRes3 = await repo.saveStaticLeases([
            StaticDhcpLease(ip: "192.168.1.50", mac: "AA:BB:CC:11:22:33"),
            StaticDhcpLease(ip: "192.168.1.51", mac: "aa:bb:cc:11:22:33")
        ])
        XCTAssertFalse(writeRes3.isOk)
        XCTAssertEqual(env.transport.sent(115, method: "POST").count, 0)

        // Without syncIpMacBinding: writes 115 + 20 (no 24)
        let savedNoSync = await repo.saveStaticLeases(
            [StaticDhcpLease(ip: "192.168.1.50", mac: "aa:bb:cc:11:22:33")],
            syncIpMacBinding: false
        )
        XCTAssertEqual(savedNoSync.value, [StaticDhcpLease(ip: "192.168.1.50", mac: "AA:BB:CC:11:22:33")])
        let p115 = try XCTUnwrap(env.transport.sent(115, method: "POST").first)
        assertPayloadKeys(p115, exactDataKeys: ["datas"])
        let row115 = try XCTUnwrap((p115.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(row115["mac"] as? String, "AA:BB:CC:11:22:33")
        XCTAssertEqual(env.transport.sent(24, method: "POST").count, 0)
        XCTAssertEqual(env.transport.sent(20, method: "POST").count, 1)

        // With syncIpMacBinding: writes 115 + 24 + 20
        env.transport.clearRequests()
        let savedSync = await repo.saveStaticLeases(
            [StaticDhcpLease(ip: "192.168.1.60", mac: "cc:dd:ee:44:55:66")],
            syncIpMacBinding: true
        )
        XCTAssertTrue(savedSync.isOk)
        let p24 = try XCTUnwrap(env.transport.sent(24, method: "POST").first)
        assertPayloadKeys(p24, exactDataKeys: ["datas"])
        let row24 = try XCTUnwrap((p24.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(row24.keys), ["ip", "mac", "ippro"])
        XCTAssertEqual(row24["mac"] as? String, "CC:DD:EE:44:55:66")
        XCTAssertEqual(row24["ippro"] as? String, "IPV4")
        XCTAssertEqual(env.transport.sent(20, method: "POST").count, 1)
    }

    func testSimPinLockVerifyUnlockWithPukAndChangePinWithoutPasswordConfirm() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let simStatus = "1"
        var pinLockSwitch = "0"
        var lockPinFlag = "0"
        var lockPukFlag = "0"
        var pinAttempts = 3

        env.transport.on(113, method: "GET") { _ in
            ["success": true, "sim_status": simStatus, "current_card_type": "USIM"]
        }
        env.transport.on(134, method: "GET") { _ in
            [
                "success": true,
                "lock_pin_switch": pinLockSwitch,
                "lock_pin_flag": lockPinFlag,
                "lock_puk_flag": lockPukFlag,
                "pin_left_times": "\(pinAttempts)",
                "puk_left_times": "10"
            ]
        }
        env.transport.on(51, method: "POST") { req in
            let sub = req.payload["subcmd"] as? String ?? ""
            switch sub {
            case "1":
                if (req.payload["pin"] as? String) != "1234" {
                    pinAttempts -= 1
                    return ["success": false, "message": "1"]
                }
                pinLockSwitch = req.payload["enable"] as? String ?? "0"
                return ["success": true, "message": "0"]
            case "2":
                lockPinFlag = "0"
                return ["success": true, "message": "0"]
            case "3":
                lockPukFlag = "0"
                lockPinFlag = "0"
                return ["success": true, "message": "0"]
            case "4":
                return ["success": true, "message": "0"]
            default:
                return ["success": false, "message": "unknown"]
            }
        }

        let repo = SimRepository(session: env.session, writer: env.writer)

        // Subcmd 1: bad PIN rejected locally; wrong PIN from router rejected without extra calls
        let writeRes1 = await repo.setPinLockEnabled(enable: true, pin: "12")
        XCTAssertFalse(writeRes1.isOk)
        let wrongPin = await repo.setPinLockEnabled(enable: true, pin: "9999")
        XCTAssertFalse(wrongPin.isOk)

        let lockRes = await repo.setPinLockEnabled(enable: true, pin: "1234")
        XCTAssertEqual(lockRes.value?.pinLockEnabled, true)
        let pSub1 = try XCTUnwrap(env.transport.sent(51, method: "POST").last)
        assertPayloadKeys(pSub1, exactDataKeys: ["subcmd", "enable", "pin"])
        XCTAssertEqual(pSub1.payload["subcmd"] as? String, "1")
        XCTAssertEqual(pSub1.payload["enable"] as? String, "1")

        // Subcmd 2: verify PIN
        env.transport.clearRequests()
        lockPinFlag = "1"
        let verRes = await repo.verifyPin("1234")
        XCTAssertEqual(verRes.value?.pinRequired, false)
        let pSub2 = try XCTUnwrap(env.transport.sent(51, method: "POST").first)
        assertPayloadKeys(pSub2, exactDataKeys: ["subcmd", "pin"])
        XCTAssertEqual(pSub2.payload["subcmd"] as? String, "2")

        // Subcmd 3: unlock with PUK (invalid PUK rejected locally first)
        let writeRes2 = await repo.unlockWithPuk(puk: "123", newPin: "5678")
        XCTAssertFalse(writeRes2.isOk)
        env.transport.clearRequests()
        lockPukFlag = "1"
        let pukRes = await repo.unlockWithPuk(puk: "12345678", newPin: "5678")
        XCTAssertEqual(pukRes.value?.pukRequired, false)
        let pSub3 = try XCTUnwrap(env.transport.sent(51, method: "POST").first)
        assertPayloadKeys(pSub3, exactDataKeys: ["subcmd", "puk", "pin"])
        XCTAssertEqual(pSub3.payload["subcmd"] as? String, "3")

        // Subcmd 4: equal PINs rejected locally; valid sends ONLY subcmd/enable/pin/pinPasswordChange
        let writeRes3 = await repo.changePin(oldPin: "1234", newPin: "1234")
        XCTAssertFalse(writeRes3.isOk)
        env.transport.clearRequests()
        let changeRes = await repo.changePin(oldPin: "1234", newPin: "4321")
        XCTAssertTrue(changeRes.isOk)
        let pSub4 = try XCTUnwrap(env.transport.sent(51, method: "POST").first)
        assertPayloadKeys(pSub4, exactDataKeys: ["subcmd", "enable", "pin", "pinPasswordChange"])
        XCTAssertEqual(pSub4.payload["subcmd"] as? String, "4")
        XCTAssertEqual(pSub4.payload["enable"] as? String, "2")
        XCTAssertEqual(pSub4.payload["pin"] as? String, "1234")
        XCTAssertEqual(pSub4.payload["pinPasswordChange"] as? String, "4321")
        XCTAssertNil(pSub4.payload["passwordChange"])
        XCTAssertNil(pSub4.payload["passwordConfirm"])
    }
}
