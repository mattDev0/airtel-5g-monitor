import Foundation
import XCTest
@testable import AirtelMonitor

@MainActor
final class SecurityParentalSystemWriteTests: XCTestCase {

    func testSecurityFirewallAlgDmzDdosAndUpnpWith202Fallback() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var fwState: [String: Any] = ["success": true, "fw_switch": "0", "level": "low"]
        var algState: [String: Any] = [
            "success": true,
            "L2TPSW_Flag": "1", "IPSecSW_Flag": "1", "H323SW_Flag": "1",
            "RTSPSW_Flag": "1", "SIPSW_Flag": "1", "FTPSW_Flag": "1", "PPTPSW_Flag": "1"
        ]
        var dmzState: [String: Any] = ["success": true, "enabled": "0", "ip": ""]
        var ddosState: [String: Any] = ["success": true, "ddosSwich": "0"]
        var upnp333Supported = false
        var upnpState: [String: Any] = ["success": true, "upnpSwitch": "0"]

        env.transport.on(367) { req in
            if req.method == "POST" {
                fwState["fw_switch"] = req.payload["fw_switch"]
                fwState["level"] = req.payload["level"]
                return ["success": true, "message": "0"]
            }
            return fwState
        }
        env.transport.on(504) { req in
            if req.method == "POST" {
                for key in ["L2TPSW_Flag", "IPSecSW_Flag", "H323SW_Flag", "RTSPSW_Flag", "SIPSW_Flag", "FTPSW_Flag", "PPTPSW_Flag"] {
                    algState[key] = req.payload[key]
                }
                return ["success": true, "message": "0"]
            }
            return algState
        }
        env.transport.on(172) { req in
            if req.method == "POST" {
                dmzState["enabled"] = req.payload["enabled"]
                dmzState["ip"] = req.payload["ip"]
                return ["success": true, "message": "0"]
            }
            return dmzState
        }
        env.transport.on(275) { req in
            if req.method == "POST" {
                ddosState["ddosSwich"] = req.payload["ddosSwich"]
                return ["success": true, "message": "0"]
            }
            return ddosState
        }
        env.transport.on(333) { req in
            if !upnp333Supported {
                return ["success": false, "message": "unsupported cmd 333"]
            }
            if req.method == "POST" {
                upnpState["upnpSwitch"] = req.payload["upnpSwitch"]
                return ["success": true, "message": "0"]
            }
            return upnpState
        }
        env.transport.on(202) { req in
            if req.method == "POST" {
                upnpState["upnpSwitch"] = req.payload["upnpSwitch"]
                return ["success": true, "message": "0"]
            }
            return upnpState
        }

        let repo = SecurityRepository(session: env.session, writer: env.writer)

        // Firewall 367
        let writeRes1 = await repo.saveFirewallSwitch(enabled: true, level: .medium)
        XCTAssertTrue(writeRes1.isOk)
        let p367 = try XCTUnwrap(env.transport.sent(367, method: "POST").first)
        assertPayloadKeys(p367, exactDataKeys: ["fw_switch", "level", "success"])
        XCTAssertEqual(p367.payload["fw_switch"] as? String, "1")
        XCTAssertEqual(p367.payload["level"] as? String, "medium")

        // ALG 504
        let nextAlg = AlgSettings(l2tp: true, ipsec: false, h323: true, rtsp: false, sip: false, ftp: true, pptp: false)
        let writeRes2 = await repo.saveAlg(nextAlg)
        XCTAssertEqual(writeRes2.value, nextAlg)
        let p504 = try XCTUnwrap(env.transport.sent(504, method: "POST").first)
        assertPayloadKeys(p504, exactDataKeys: [
            "L2TPSW_Flag", "IPSecSW_Flag", "H323SW_Flag",
            "RTSPSW_Flag", "SIPSW_Flag", "FTPSW_Flag", "PPTPSW_Flag"
        ])
        XCTAssertEqual(p504.payload["IPSecSW_Flag"] as? String, "0")
        XCTAssertEqual(p504.payload["SIPSW_Flag"] as? String, "0")

        // DMZ 172 validation + payload
        let writeRes3 = await repo.saveDmz(enabled: true, ip: "not-an-ip")
        XCTAssertFalse(writeRes3.isOk)
        XCTAssertEqual(env.transport.sent(172, method: "POST").count, 0)
        let dmzOk = await repo.saveDmz(enabled: true, ip: "192.168.1.90")
        XCTAssertEqual(dmzOk.value, DmzConfig(enabled: true, ip: "192.168.1.90"))
        let p172 = try XCTUnwrap(env.transport.sent(172, method: "POST").first)
        assertPayloadKeys(p172, exactDataKeys: ["subcmd", "enabled", "ip", "success"])
        XCTAssertEqual(p172.payload["subcmd"] as? Int, 6)

        // DDoS 275 uses router firmware spelling `ddosSwich` only
        let ddosOk = await repo.saveDdos(enabled: true)
        XCTAssertEqual(ddosOk.value, DdosConfig(enabled: true))
        let p275 = try XCTUnwrap(env.transport.sent(275, method: "POST").first)
        assertPayloadKeys(p275, exactDataKeys: ["ddosSwich"])
        XCTAssertEqual(p275.payload["ddosSwich"] as? String, "1")

        // UPnP falls back from 333 -> 202 on badResponse, and stays on 333 when supported
        let upnpFallback = await repo.saveUpnp(enabled: true)
        XCTAssertEqual(upnpFallback.value, UpnpConfig(enabled: true))
        let p202 = try XCTUnwrap(env.transport.sent(202, method: "POST").first)
        assertPayloadKeys(p202, exactDataKeys: ["upnpSwitch"])

        upnp333Supported = true
        env.transport.clearRequests()
        let upnpPrimary = await repo.saveUpnp(enabled: false)
        XCTAssertEqual(upnpPrimary.value, UpnpConfig(enabled: false))
        let p333 = try XCTUnwrap(env.transport.sent(333, method: "POST").first)
        assertPayloadKeys(p333, exactDataKeys: ["upnpSwitch"])
        XCTAssertEqual(env.transport.sent(202, method: "POST").count, 0)
    }

    func testSecurityPortForwardIpPortPortUrlMacFiltersDefaultPolicyAndTrustedIps() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var macDatas: [[String: Any]] = []
        var pfDatas: [[String: Any]] = []
        var ipPortDatas: [[String: Any]] = []
        var portDatas: [[String: Any]] = []
        var urlDatas: [[String: Any]] = []
        var policyDatas: [[String: Any]] = [
            ["enableRule": true, "acceptAll": true, "ippro": "IPV4"],
            ["enableRule": true, "acceptAll": true, "ippro": "IPV6"]
        ]
        var trustedState: [String: Any] = ["success": true, "trusted_ip_sw": "0"]

        env.transport.on(400, method: "GET") { _ in ["success": true, "systemPort": "80 443 8080"] }
        env.transport.on(21) { req in
            if req.method == "POST" { macDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": macDatas]
        }
        env.transport.on(27) { req in
            if req.method == "POST" { pfDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": pfDatas]
        }
        env.transport.on(32) { req in
            if req.method == "POST" { ipPortDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": ipPortDatas]
        }
        env.transport.on(23) { req in
            if req.method == "POST" { portDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": portDatas]
        }
        env.transport.on(26) { req in
            if req.method == "POST" { urlDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": urlDatas]
        }
        for pCmd in [28, 29, 30] {
            env.transport.on(pCmd) { req in
                if req.method == "POST" {
                    policyDatas = req.payload["datas"] as? [[String: Any]] ?? policyDatas
                    return ["success": true]
                }
                return ["success": true, "datas": policyDatas]
            }
        }
        env.transport.on(502) { req in
            if req.method == "POST" {
                for (k, v) in req.payload where !["cmd", "method", "sessionId", "token"].contains(k) {
                    trustedState[k] = v
                }
                return ["success": true]
            }
            return trustedState
        }

        let repo = SecurityRepository(session: env.session, writer: env.writer)
        _ = await repo.loadAll()

        // Reserved system port 443 in range 440-445 blocked locally
        let writeRes1 = await repo.savePortForwards([
            PortForwardRule(port: "440-445", mappingPort: "440-445", mappingIp: "192.168.1.10")
        ])
        XCTAssertFalse(writeRes1.isOk)
        XCTAssertEqual(env.transport.sent(27, method: "POST").count, 0)

        // Valid Port Forward normalizes external ":" and internal "-", adds mappingIpPort + intername + cmd 20
        let pfRes = await repo.savePortForwards([
            PortForwardRule(port: "9000-9010", mappingPort: "8000:8010", mappingIp: "192.168.1.50", protocolName: "TCP", remark: "Game")
        ])
        XCTAssertEqual(pfRes.value?.count, 1)
        let p27 = try XCTUnwrap(env.transport.sent(27, method: "POST").first)
        assertPayloadKeys(p27, exactDataKeys: ["datas", "success"])
        let pfRow = try XCTUnwrap((p27.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(pfRow["port"] as? String, "9000:9010")
        XCTAssertEqual(pfRow["mappingPort"] as? String, "8000-8010")
        XCTAssertEqual(pfRow["mappingIpPort"] as? String, "192.168.1.50:8000-8010")
        XCTAssertEqual(pfRow["intername"] as? String, "DEFAULT")
        XCTAssertEqual(env.transport.sent(20, method: "POST").count, 1)

        // IP/Port filter: ICMP clears srcPort & destPort
        env.transport.clearRequests()
        let ipRes = await repo.saveIpPortFilters([
            IpPortFilterRule(ip: "192.168.1.10", destIp: "8.8.8.8", srcPort: "1234", destPort: "53", protocolName: "ICMP")
        ])
        XCTAssertEqual(ipRes.value?.count, 1)
        let p32 = try XCTUnwrap(env.transport.sent(32, method: "POST").first)
        assertPayloadKeys(p32, exactDataKeys: ["datas", "success"])
        let ipRow = try XCTUnwrap((p32.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(ipRow["srcPort"] as? String, "")
        XCTAssertEqual(ipRow["destPort"] as? String, "")
        XCTAssertEqual(ipRow["protocol"] as? String, "ICMP")

        // MAC Filter (normalizes uppercase), Port Filter, URL Filter
        env.transport.clearRequests()
        let writeRes2 = await repo.saveMacFilters([MacFilterRule(mac: "aa:bb:cc:11:22:33", remark: "Phone")])
        XCTAssertTrue(writeRes2.isOk)
        let p21 = try XCTUnwrap(env.transport.sent(21, method: "POST").first)
        assertPayloadKeys(p21, exactDataKeys: ["datas", "success"])
        let macRow = try XCTUnwrap((p21.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(macRow["mac"] as? String, "AA:BB:CC:11:22:33")

        let writeRes3 = await repo.savePortFilters([PortFilterRule(port: "25", protocolName: "tcp")])
        XCTAssertTrue(writeRes3.isOk)
        assertPayloadKeys(try XCTUnwrap(env.transport.sent(23, method: "POST").first), exactDataKeys: ["datas", "success"])

        let writeRes4 = await repo.saveUrlFilters([UrlFilterRule(url: "not valid url")])
        XCTAssertFalse(writeRes4.isOk)
        let writeRes5 = await repo.saveUrlFilters([UrlFilterRule(url: "ads.example.com")])
        XCTAssertTrue(writeRes5.isOk)
        assertPayloadKeys(try XCTUnwrap(env.transport.sent(26, method: "POST").first), exactDataKeys: ["datas", "success"])

        // Default Policy switches to whitelist (acceptAll == false): writes 28, 29, 30 + syncs mac/port/url with enableLink == true + cmd 20
        env.transport.clearRequests()
        let polRes = await repo.saveDefaultPolicy(acceptAll: false)
        XCTAssertEqual(polRes.value?.isWhitelistMode, true)
        for pCmd in [28, 29, 30] {
            let p = try XCTUnwrap(env.transport.sent(pCmd, method: "POST").first)
            assertPayloadKeys(p, exactDataKeys: ["datas", "success"])
        }
        XCTAssertEqual(env.transport.sent(21, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(23, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(26, method: "POST").count, 1)
        XCTAssertGreaterThanOrEqual(env.transport.sent(20, method: "POST").count, 1)

        // Trusted IPs: > 15 or invalid IP rejected; valid populates all 15 slots (trusted_ip_0..14)
        let writeRes6 = await repo.saveTrustedIps(enabled: true, ips: (1...16).map { "192.168.1.\($0)" })
        XCTAssertFalse(writeRes6.isOk)
        let writeRes7 = await repo.saveTrustedIps(enabled: true, ips: ["bad-ip"])
        XCTAssertFalse(writeRes7.isOk)

        env.transport.clearRequests()
        let trustedOk = await repo.saveTrustedIps(enabled: true, ips: ["192.168.1.10", "192.168.1.20"])
        XCTAssertEqual(trustedOk.value, TrustedIpConfig(enabled: true, ips: ["192.168.1.10", "192.168.1.20"]))
        let p502 = try XCTUnwrap(env.transport.sent(502, method: "POST").first)
        var expected502Keys: Set<String> = ["trusted_ip_sw"]
        for idx in 0..<15 { expected502Keys.insert("trusted_ip_\(idx)") }
        assertPayloadKeys(p502, exactDataKeys: expected502Keys)
        XCTAssertEqual(p502.payload["trusted_ip_sw"] as? String, "1")
        XCTAssertEqual(p502.payload["trusted_ip_0"] as? String, "192.168.1.10")
        XCTAssertEqual(p502.payload["trusted_ip_1"] as? String, "192.168.1.20")
        XCTAssertEqual(p502.payload["trusted_ip_14"] as? String, "")
    }

    func testParentalControlSwitchTimeSpeedAndUrlMax10PerMac() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var pcEnable = "0"
        var timeDatas: [[String: Any]] = []
        var speedDatas: [[String: Any]] = []
        var urlDatas: [[String: Any]] = []

        env.transport.on(391) { req in
            if req.method == "POST" { pcEnable = req.payload["enable"] as? String ?? "0"; return ["success": true] }
            return ["success": true, "enable": pcEnable]
        }
        env.transport.on(385) { req in
            if req.method == "POST" { timeDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": timeDatas]
        }
        env.transport.on(382) { req in
            if req.method == "POST" { speedDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": speedDatas]
        }
        env.transport.on(383) { req in
            if req.method == "POST" { urlDatas = req.payload["datas"] as? [[String: Any]] ?? []; return ["success": true] }
            return ["success": true, "datas": urlDatas]
        }

        let repo = ParentalControlRepository(session: env.session, writer: env.writer)

        let swRes = await repo.setEnabled(true)
        XCTAssertEqual(swRes.value?.enabled, true)
        assertPayloadKeys(try XCTUnwrap(env.transport.sent(391, method: "POST").first), exactDataKeys: ["enable"])

        // Time rules: malformed MAC or inverted time window rejected locally
        let writeRes1 = await repo.saveTimeRules([ParentalTimeRule(mac: "not-a-mac", startTime: "08:00", endTime: "20:00")])
        XCTAssertFalse(writeRes1.isOk)
        let writeRes2 = await repo.saveTimeRules([ParentalTimeRule(mac: "AA:BB:CC:11:22:33", startTime: "22:00", endTime: "08:00")])
        XCTAssertFalse(writeRes2.isOk)

        env.transport.clearRequests()
        let timeOk = await repo.saveTimeRules([
            ParentalTimeRule(mac: "aa:bb:cc:11:22:33", startTime: "08:00", endTime: "20:00", week: "1,2,3")
        ])
        XCTAssertEqual(timeOk.value?.first?.mac, "AA:BB:CC:11:22:33")
        let p385 = try XCTUnwrap(env.transport.sent(385, method: "POST").first)
        assertPayloadKeys(p385, exactDataKeys: ["datas"])
        let tRow = try XCTUnwrap((p385.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(tRow.keys), ["enableRule", "mac", "startTime", "endTime", "scheduleDays"])
        XCTAssertEqual(env.transport.sent(20, method: "POST").count, 1)

        // Speed rules: duplicate IP rejected locally; valid sends cmd 382 + 20
        let writeRes3 = await repo.saveSpeedRules([
            ParentalSpeedRule(ip: "192.168.1.50", speedKbps: 100),
            ParentalSpeedRule(ip: "192.168.1.50", speedKbps: 200)
        ])
        XCTAssertFalse(writeRes3.isOk)
        env.transport.clearRequests()
        let spdOk = await repo.saveSpeedRules([ParentalSpeedRule(ip: "192.168.1.50", speedKbps: 512)])
        XCTAssertEqual(spdOk.value?.first?.speedKbps, 512)
        let p382 = try XCTUnwrap(env.transport.sent(382, method: "POST").first)
        assertPayloadKeys(p382, exactDataKeys: ["datas"])
        let sRow = try XCTUnwrap((p382.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(sRow.keys), ["enableRule", "ip", "maxSpeed", "ippro"])
        XCTAssertEqual(sRow["maxSpeed"] as? String, "512")
        XCTAssertEqual(env.transport.sent(20, method: "POST").count, 1)

        // URL rules: 11 rules for same MAC rejected locally; 10 accepted with ippro IPV4V6 & enableLink false
        let elevenSameMac = (1...11).map { ParentalUrlRule(mac: "AA:BB:CC:11:22:33", url: "s\($0).example.com") }
        let writeRes4 = await repo.saveUrlRules(elevenSameMac)
        XCTAssertFalse(writeRes4.isOk)

        env.transport.clearRequests()
        let urlOk = await repo.saveUrlRules(Array(elevenSameMac.prefix(2)))
        XCTAssertEqual(urlOk.value?.count, 2)
        let p383 = try XCTUnwrap(env.transport.sent(383, method: "POST").first)
        assertPayloadKeys(p383, exactDataKeys: ["datas"])
        let uRow = try XCTUnwrap((p383.payload["datas"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(uRow.keys), ["enableRule", "mac", "url", "enableLink", "ippro"])
        XCTAssertEqual(uRow["ippro"] as? String, "IPV4V6")
        XCTAssertEqual(uRow["enableLink"] as? Bool, false)
        XCTAssertEqual(env.transport.sent(20, method: "POST").count, 1)
    }

    func testSystemAboutLogsSchedRebootTimeSyncWanDetectPasswordSelfCheckAndNetworkTools() async throws {
        let env = makeTestEnv(username: "root", password: "OldPassword1")
        defer { env.cleanup() }

        var logSettings322: [String: Any] = ["success": true, "logSize": "200000", "logGrade": "4"]
        var schedState263: [String: Any] = ["success": true, "timrRestartSwitch": "0"]
        var timeState11: [String: Any] = [
            "success": true,
            "systime": "2026-11-15 10:00:00",
            "timezone": "UTC+1",
            "ntpSwitch": "1",
            "timeServer": "pool.ntp.org",
            "timeServer2": "time.google.com",
            "timeServer3": "time.cloudflare.com",
            "timeServer4": "time.nist.gov"
        ]
        var wanDetectState336: [String: Any] = [
            "success": true,
            "wanLinkDetectSwitch": "0",
            "wan_mode": "5",
            "wanLinkDetectCheckTime": "10",
            "checkWanLinkDetectMode": "0",
            "wanLinkDetectIP1": "8.8.8.8",
            "wanLinkDetectIP2": "1.1.1.1",
            "wanLinkDetectIP3": "9.9.9.9",
            "LinkDetectAction": "0",
            "reboot_wait_time": "3",
            "dial_wait_time": "10"
        ]

        env.transport.on(207, method: "GET") { _ in RouterFixtures.json207() }
        env.transport.on(133, method: "GET") { _ in RouterFixtures.json133() }
        env.transport.on(1018, method: "GET") { _ in RouterFixtures.json1018() }
        env.transport.on(17, method: "POST") { _ in ["success": true] }
        env.transport.onText(17, method: "GET") { _ in "2026-11-15 10:00:00 [info] Link up\n" }
        env.transport.on(322) { req in
            if req.method == "POST" {
                logSettings322["logSize"] = "\(req.payload["logSize"] ?? "200000")"
                logSettings322["logGrade"] = req.payload["logGrade"]
                return ["success": true]
            }
            return logSettings322
        }
        env.transport.on(263) { req in
            if req.method == "POST" {
                for (k, v) in req.payload where !["cmd", "method", "sessionId", "token"].contains(k) {
                    schedState263[k] = v
                }
                return ["success": true]
            }
            return schedState263
        }
        env.transport.on(11) { req in
            if req.method == "POST" {
                for (k, v) in req.payload where !["cmd", "method", "sessionId", "token"].contains(k) {
                    timeState11[k] = v
                }
                return ["success": true]
            }
            return timeState11
        }
        env.transport.on(321, method: "POST") { _ in ["success": true] }
        env.transport.on(336) { req in
            if req.method == "POST" {
                for (k, v) in req.payload where !["cmd", "method", "sessionId", "token"].contains(k) {
                    wanDetectState336[k] = v
                }
                return ["success": true]
            }
            return wanDetectState336
        }
        env.transport.on(102, method: "POST") { _ in ["success": true, "message": "0"] }
        env.transport.on(484, method: "GET") { _ in ["success": true, "check_result": "ok", "sim": "Ready"] }
        env.transport.on(168, method: "POST") { _ in ["success": true] }
        env.transport.onText(204, method: "POST") { _ in "PING 8.8.8.8: 4 packets transmitted, 4 received" }

        let repo = SystemRepository(session: env.session, writer: env.writer, preferences: env.preferences)

        // About & logs (SystemRepository reads idu_model / product_model, returning "-" for raw207)
        let about = await repo.loadAboutInfo()
        XCTAssertEqual(about.value?.oduModel, "ZLT X17M")
        XCTAssertEqual(about.value?.iduModel, "-")
        let logsLoaded = await repo.loadSystemLogs()
        XCTAssertEqual(logsLoaded.value?.count, 1)
        let writeRes1 = await repo.clearSystemLogs()
        XCTAssertTrue(writeRes1.isOk)
        let p17Clr = try XCTUnwrap(env.transport.sent(17, method: "POST").first)
        assertPayloadKeys(p17Clr, exactDataKeys: ["subcmd"])
        XCTAssertEqual(p17Clr.payload["subcmd"] as? Int, 1)
        XCTAssertTrue(repo.logs.isEmpty)

        // Log settings 322 (KB -> bytes * 1000)
        let writeRes2 = await repo.saveLogSettings(logSizeKb: 0, logGrade: .info)
        XCTAssertFalse(writeRes2.isOk)
        let logRes = await repo.saveLogSettings(logSizeKb: 150, logGrade: .warning)
        XCTAssertEqual(logRes.value?.logSizeKb, 150)
        let p322 = try XCTUnwrap(env.transport.sent(322, method: "POST").first)
        assertPayloadKeys(p322, exactDataKeys: ["logGrade", "logSize"])
        XCTAssertEqual(p322.payload["logSize"] as? Int, 150_000)
        XCTAssertEqual(p322.payload["logGrade"] as? String, "2")

        // Scheduled reboot 263 (enabled & disabled)
        let writeRes3 = await repo.saveScheduledReboot(enabled: true, hour: 3, minute: 0, weekDays: "")
        XCTAssertFalse(writeRes3.isOk)
        let writeRes4 = await repo.saveScheduledReboot(enabled: true, hour: 3, minute: 5, weekDays: "1,3,5")
        XCTAssertTrue(writeRes4.isOk)
        let p263On = try XCTUnwrap(env.transport.sent(263, method: "POST").first)
        assertPayloadKeys(p263On, exactDataKeys: [
            "timrRestartSwitch", "restartHour", "restartMinute", "restartSecond", "restartWeek"
        ])
        XCTAssertEqual(p263On.payload["restartHour"] as? String, "03")
        XCTAssertEqual(p263On.payload["restartMinute"] as? String, "05")

        env.transport.clearRequests()
        let writeRes5 = await repo.saveScheduledReboot(enabled: false, hour: 0, minute: 0, weekDays: "")
        XCTAssertTrue(writeRes5.isOk)
        let p263Off = try XCTUnwrap(env.transport.sent(263, method: "POST").first)
        assertPayloadKeys(p263Off, exactDataKeys: ["timrRestartSwitch"])
        XCTAssertEqual(p263Off.payload["timrRestartSwitch"] as? String, "0")

        // Time config 11 preserves timeServer3/4, strips _N suffix, adds success: true; syncClientTime 321
        let tRes = await repo.saveTimeConfig(
            timezone: "UTC+1_2",
            ntpEnabled: false,
            server1: "ntp.ng.org",
            server2: "time.google.com",
            manualDate: Date(timeIntervalSince1970: 1_777_629_600)
        )
        XCTAssertEqual(tRes.value?.timezone, "UTC+1")
        let p11 = try XCTUnwrap(env.transport.sent(11, method: "POST").first)
        assertPayloadKeys(p11, exactDataKeys: [
            "timezone", "ntpSwitch", "timeServer", "timeServer2", "datetime", "success", "timeServer3", "timeServer4"
        ])
        XCTAssertEqual(p11.payload["timezone"] as? String, "UTC+1")
        XCTAssertEqual((p11.payload["datetime"] as? String)?.count, 12)
        let writeRes6 = await repo.syncClientTime(now: Date(timeIntervalSince1970: 1_777_629_600))
        XCTAssertTrue(writeRes6.isOk)
        let p321 = try XCTUnwrap(env.transport.sent(321, method: "POST").first)
        assertPayloadKeys(p321, exactDataKeys: ["clientTime"])

        // WAN Link Detect 336: rescan action "3" gated when !wanDetectRescanAvailable
        let restrictedFlags = RouterFeatureFlags(isLoaded: true, webPageHideHex: "F0F0", networkMode: "1")
        let allowedFlags = RouterFeatureFlags.fromJson80([
            "account_level": "1",
            "network_mode": "5",
            "web_page_hide": String(repeating: "F", count: 120)
        ])
        let wanCfg = WanLinkDetectConfig(
            enabled: true, networkMode: "5", isMixMode: true,
            checkTimeSec: 30, mode: "1", ip1: "8.8.8.8", ip2: "1.1.1.1",
            action: "3", rebootWaitMinutes: 3, dialWaitSec: 15
        )
        let writeRes7 = await repo.saveWanLinkDetect(wanCfg, flags: restrictedFlags)
        XCTAssertFalse(writeRes7.isOk)
        XCTAssertEqual(env.transport.sent(336, method: "POST").count, 0)

        let writeRes8 = await repo.saveWanLinkDetect(wanCfg, flags: allowedFlags)
        XCTAssertTrue(writeRes8.isOk)
        let p336 = try XCTUnwrap(env.transport.sent(336, method: "POST").first)
        assertPayloadKeys(p336, exactDataKeys: [
            "wanLinkDetectSwitch", "wanLinkDetectCheckTime", "checkWanLinkDetectMode",
            "wanLinkDetectIP1", "wanLinkDetectIP2", "LinkDetectAction",
            "reboot_wait_time", "dial_wait_time", "wanLinkDetectIP3"
        ])

        // Admin password 102: validation + Base64 + credential update + session reset
        let writeRes9 = await repo.changeAdminPassword(oldPassword: "OldPassword1", newPassword: "short", confirmPassword: "short")
        XCTAssertFalse(writeRes9.isOk)
        XCTAssertEqual(env.transport.sent(102, method: "POST").count, 0)

        let writeRes10 = await repo.changeAdminPassword(
            oldPassword: "OldPassword1",
            newPassword: "NewPassword2!",
            confirmPassword: "NewPassword2!"
        )
        XCTAssertTrue(writeRes10.isOk)
        let p102 = try XCTUnwrap(env.transport.sent(102, method: "POST").first)
        assertPayloadKeys(p102, exactDataKeys: ["subcmd", "old_passwd", "newPasswd"])
        XCTAssertEqual(p102.payload["old_passwd"] as? String, CodecHelpers.encodeBase64Utf8("OldPassword1"))
        XCTAssertEqual(p102.payload["newPasswd"] as? String, CodecHelpers.encodeBase64Utf8("NewPassword2!"))
        XCTAssertEqual(env.preferences.currentCredentials.password, "NewPassword2!")
        // Session was reset -> next execute forces fresh login with new credentials
        let loginCountBefore = env.transport.sent(100, method: "POST").count
        _ = await repo.runSelfCheck()
        XCTAssertEqual(env.transport.sent(100, method: "POST").count, loginCountBefore + 1)

        // Self-check 484
        let scRes = await repo.runSelfCheck()
        XCTAssertEqual(scRes.value?.passed, true)

        // Network tools 168 + 204 (Ping & Traceroute)
        let writeRes11 = await repo.startNetworkTool(mode: .ping, target: "bad host")
        XCTAssertFalse(writeRes11.isOk)
        let writeRes12 = await repo.startNetworkTool(mode: .ping, target: "8.8.8.8", pingCount: 4)
        XCTAssertTrue(writeRes12.isOk)
        let pingStart = try XCTUnwrap(env.transport.sent(168, method: "POST").last)
        assertPayloadKeys(pingStart, exactDataKeys: ["subcmd", "url", "wan_index", "pingTimes"], requireToken: false)
        XCTAssertEqual(pingStart.payload["subcmd"] as? Int, 0)
        XCTAssertEqual(pingStart.payload["pingTimes"] as? Int, 4)

        let toolOut = await repo.readNetworkToolOutput(mode: .ping)
        XCTAssertTrue((toolOut.value ?? "").contains("PING 8.8.8.8"))
        let p204 = try XCTUnwrap(env.transport.sent(204, method: "POST").first)
        assertPayloadKeys(p204, exactDataKeys: ["url", "subcmd"], requireToken: false)
        XCTAssertEqual(p204.payload["url"] as? String, "/tmp/tzwww/pingrt")

        let writeRes13 = await repo.stopNetworkTool(mode: .ping)
        XCTAssertTrue(writeRes13.isOk)
        let pingStop = try XCTUnwrap(env.transport.sent(168, method: "POST").last)
        XCTAssertEqual(pingStop.payload["pingTimes"] as? Int, 0)

        let writeRes14 = await repo.startNetworkTool(mode: .traceroute, target: "1.1.1.1", tracePort: 443)
        XCTAssertTrue(writeRes14.isOk)
        let traceStart = try XCTUnwrap(env.transport.sent(168, method: "POST").last)
        assertPayloadKeys(traceStart, exactDataKeys: ["subcmd", "url", "wan_index", "stopped", "port"], requireToken: false)
        XCTAssertEqual(traceStart.payload["subcmd"] as? Int, 2)
        XCTAssertEqual(traceStart.payload["stopped"] as? String, "0")
        XCTAssertEqual(traceStart.payload["port"] as? Int, 443)

        _ = await repo.readNetworkToolOutput(mode: .traceroute)
        XCTAssertEqual(try XCTUnwrap(env.transport.sent(204, method: "POST").last).payload["url"] as? String, "/tmp/tzwww/tracepathrt")

        let writeRes15 = await repo.stopNetworkTool(mode: .traceroute)
        XCTAssertTrue(writeRes15.isOk)
        let traceStop = try XCTUnwrap(env.transport.sent(168, method: "POST").last)
        XCTAssertEqual(traceStop.payload["stopped"] as? String, "1")
        XCTAssertEqual(traceStop.payload["port"] as? Int, -1)
    }
}
