import Foundation
import XCTest
@testable import AirtelMonitor

@MainActor
final class MobileSmsUssdDeviceWriteTests: XCTestCase {

    func testMobileDataAndFlightModeExactPayloadsAndVerifyReadBack() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var state218: [String: Any] = [
            "success": true,
            "dialMode": "1",
            "flightMode": "0",
            "lteCA": "1",
            "nrCA": "1",
            "roamingEnable": "0",
            "networkMode": "1C",
            "mode5g": "0"
        ]
        env.transport.on(218, method: "GET") { _ in state218 }
        env.transport.on(222, method: "POST") { req in
            if let v = req.payload["dialMode"] { state218["dialMode"] = v }
            return ["success": true, "message": "0"]
        }
        env.transport.on(226, method: "POST") { req in
            if let v = req.payload["flightMode"] { state218["flightMode"] = v }
            return ["success": true, "message": "0"]
        }

        let repo = MobileRepository(session: env.session, writer: env.writer)

        let dataOff = await repo.setMobileDataEnabled(false)
        XCTAssertTrue(dataOff.isOk)
        let p222 = try XCTUnwrap(env.transport.sent(222, method: "POST").first)
        assertPayloadKeys(p222, exactDataKeys: ["dialMode"])
        XCTAssertEqual(p222.payload["dialMode"] as? String, "0")
        XCTAssertGreaterThanOrEqual(env.transport.sent(218, method: "GET").count, 1)

        let flightOn = await repo.setFlightModeEnabled(true)
        XCTAssertTrue(flightOn.isOk)
        let p226 = try XCTUnwrap(env.transport.sent(226, method: "POST").first)
        assertPayloadKeys(p226, exactDataKeys: ["flightMode"])
        XCTAssertEqual(p226.payload["flightMode"] as? String, "1")
    }

    func testNetworkModePlmnLockLteCaNrCaAndRoamingPayloads() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var state218: [String: Any] = [
            "success": true,
            "dialMode": "1",
            "flightMode": "0",
            "lteCA": "1",
            "nrCA": "0",
            "roamingEnable": "0",
            "networkMode": "1C",
            "mode5g": "0"
        ]
        var state256: [String: Any] = [
            "success": true,
            "networkMode": "1C",
            "display_mode": "1C"
        ]
        env.transport.on(218, method: "GET") { _ in state218 }
        env.transport.on(256) { req in
            if req.method == "POST" {
                if let m = req.payload["networkMode"] {
                    state256["networkMode"] = m
                    state218["networkMode"] = m
                }
                return ["success": true, "message": "0"]
            }
            return state256
        }
        env.transport.on(226, method: "POST") { req in
            if let v = req.payload["lteCA"] { state218["lteCA"] = v }
            if let v = req.payload["nrCA"] { state218["nrCA"] = v }
            return ["success": true, "message": "0"]
        }
        env.transport.on(220, method: "POST") { req in
            if let v = req.payload["roamingEnable"] { state218["roamingEnable"] = v }
            return ["success": true, "message": "0"]
        }

        let repo = MobileRepository(session: env.session, writer: env.writer)

        // PLMN locked rejects network mode write locally
        let locked = await repo.setNetworkMode("4", flags: RouterFeatureFlags(lockPlmnFlag: true))
        XCTAssertFalse(locked.isOk)
        XCTAssertEqual(env.transport.sent(256, method: "POST").count, 0)

        let modeRes = await repo.setNetworkMode("4", flags: RouterFeatureFlags(lockPlmnFlag: false))
        XCTAssertEqual(modeRes.value?.networkMode, "4")
        let p256 = try XCTUnwrap(env.transport.sent(256, method: "POST").first)
        assertPayloadKeys(p256, exactDataKeys: ["networkMode"])
        XCTAssertEqual(p256.payload["networkMode"] as? String, "4")

        let lteCaRes = await repo.setLteCa(false)
        XCTAssertEqual(lteCaRes.value?.lteCa, false)
        let pLteCa = try XCTUnwrap(env.transport.sent(226, method: "POST").first)
        assertPayloadKeys(pLteCa, exactDataKeys: ["lteCA"])
        XCTAssertEqual(pLteCa.payload["lteCA"] as? String, "0")

        let nrCaRes = await repo.setNrCa(true)
        XCTAssertEqual(nrCaRes.value?.nrCa, true)
        let pNrCa = try XCTUnwrap(env.transport.sent(226, method: "POST").last)
        assertPayloadKeys(pNrCa, exactDataKeys: ["nrCA"])
        XCTAssertEqual(pNrCa.payload["nrCA"] as? String, "1")

        let roamRes = await repo.setRoaming(true)
        XCTAssertEqual(roamRes.value?.roamingEnable, true)
        let pRoam = try XCTUnwrap(env.transport.sent(220, method: "POST").first)
        assertPayloadKeys(pRoam, exactDataKeys: ["roamingEnable"])
        XCTAssertEqual(pRoam.payload["roamingEnable"] as? String, "1")
    }

    func testTrafficLimitCalibrateAndClearExactPayloads() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var state337: [String: Any] = [
            "success": true,
            "limitSwitch": "0",
            "limitSize": "0",
            "flow_limit_unit": "1",
            "startDate": "1",
            "warn_percentage": "85",
            "ul_mon_flow": "4096",
            "dl_mon_flow": "1024"
        ]
        env.transport.on(337) { req in
            if req.method == "POST" {
                if (req.payload["statisticsReset"] as? String) == "1" {
                    if let used = req.payload["usedFlow"] as? String {
                        state337["ul_mon_flow"] = used
                        state337["dl_mon_flow"] = "0"
                    } else {
                        state337["ul_mon_flow"] = "0"
                        state337["dl_mon_flow"] = "0"
                    }
                    return ["success": true, "message": "0"]
                }
                for key in ["limitSwitch", "limitSize", "flow_limit_unit", "startDate", "warn_percentage"] {
                    if let val = req.payload[key] { state337[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return state337
        }

        let repo = MobileRepository(session: env.session, writer: env.writer)

        // Validation guards
        let writeRes1 = await repo.saveTrafficLimit(enabled: true, size: "100000", unitCode: "1", startDate: 1)
        XCTAssertFalse(writeRes1.isOk)
        let writeRes2 = await repo.saveTrafficLimit(enabled: true, size: "50", unitCode: "1", startDate: 29)
        XCTAssertFalse(writeRes2.isOk)
        let writeRes3 = await repo.calibrateTrafficStatistics(targetUsedMb: -1)
        XCTAssertFalse(writeRes3.isOk)
        XCTAssertEqual(env.transport.sent(337, method: "POST").count, 0)

        // Save enabled TB limit preserves warn_percentage from pre-read
        let saveOn = await repo.saveTrafficLimit(enabled: true, size: "2", unitCode: "TB", startDate: 15)
        XCTAssertEqual(saveOn.value?.limitEnabled, true)
        XCTAssertEqual(saveOn.value?.unitCode, "2")
        XCTAssertEqual(saveOn.value?.startDate, 15)
        let pSaveOn = try XCTUnwrap(env.transport.sent(337, method: "POST").first)
        assertPayloadKeys(pSaveOn, exactDataKeys: [
            "limitSwitch", "limitSize", "flow_limit_unit", "startDate", "warn_percentage"
        ])
        XCTAssertEqual(pSaveOn.payload["flow_limit_unit"] as? String, "2")
        XCTAssertEqual(pSaveOn.payload["warn_percentage"] as? String, "85")

        // Disable forces limitSize to "0"
        env.transport.clearRequests()
        let saveOff = await repo.saveTrafficLimit(enabled: false, size: "500", unitCode: "0", startDate: 1)
        XCTAssertEqual(saveOff.value?.limitEnabled, false)
        let pSaveOff = try XCTUnwrap(env.transport.sent(337, method: "POST").first)
        XCTAssertEqual(pSaveOff.payload["limitSwitch"] as? String, "0")
        XCTAssertEqual(pSaveOff.payload["limitSize"] as? String, "0")

        // Calibrate sets decimal MB usedFlow
        env.transport.clearRequests()
        let calRes = await repo.calibrateTrafficStatistics(targetUsedMb: 1234.5)
        XCTAssertEqual(try XCTUnwrap(calRes.value?.totalUsedMb), 1234.5, accuracy: 0.1)
        let pCal = try XCTUnwrap(env.transport.sent(337, method: "POST").first)
        assertPayloadKeys(pCal, exactDataKeys: ["statisticsReset", "usedFlow"])
        XCTAssertEqual(pCal.payload["usedFlow"] as? String, "1234.5")

        // Clear statistics
        env.transport.clearRequests()
        let clearRes = await repo.clearTrafficStatistics()
        XCTAssertEqual(try XCTUnwrap(clearRes.value?.totalUsedMb), 0.0, accuracy: 0.01)
        let pClr = try XCTUnwrap(env.transport.sent(337, method: "POST").first)
        assertPayloadKeys(pClr, exactDataKeys: ["statisticsReset"])
        XCTAssertEqual(pClr.payload["statisticsReset"] as? String, "1")
    }

    func testApnBasicMtuValidationAndProfilesWrite() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var state213: [String: Any] = [
            "success": true,
            "selectType": "IP",
            "apnMTU": "1500",
            "apnNatName": "1"
        ]
        var storedProfiles: [[String: Any]] = []

        env.transport.on(213) { req in
            if req.method == "POST" {
                for key in ["selectType", "apnMTU", "apnNatName"] {
                    if let val = req.payload[key] { state213[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return state213
        }
        env.transport.on(248) { req in
            if req.method == "POST" {
                storedProfiles = req.payload["apn_list"] as? [[String: Any]] ?? []
                return ["success": true, "message": "0"]
            }
            return ["success": true, "apn_list": storedProfiles]
        }

        let repo = MobileRepository(session: env.session, writer: env.writer)

        // IPV6 MTU < 1280 rejected locally
        let writeRes1 = await repo.saveApnBasic(selectType: "IPV6", mtu: 1200, natEnabled: true)
        XCTAssertFalse(writeRes1.isOk)
        XCTAssertEqual(env.transport.sent(213, method: "POST").count, 0)

        let basicRes = await repo.saveApnBasic(selectType: "IP", mtu: 1492, natEnabled: false)
        XCTAssertEqual(basicRes.value?.mtu, 1492)
        XCTAssertEqual(basicRes.value?.natEnabled, false)
        let p213 = try XCTUnwrap(env.transport.sent(213, method: "POST").first)
        assertPayloadKeys(p213, exactDataKeys: ["subcmd", "selectType", "apnMTU", "apnNatName"])
        XCTAssertEqual(p213.payload["subcmd"] as? Int, 3)
        XCTAssertEqual(p213.payload["apnMTU"] as? String, "1492")
        XCTAssertEqual(p213.payload["apnNatName"] as? String, "0")

        // Profiles validation & write with selectAuthtication typo key
        let writeRes2 = await repo.saveApnProfiles([ApnProfile(name: "Bad", apnName: "auto")])
        XCTAssertFalse(writeRes2.isOk)
        XCTAssertEqual(env.transport.sent(248, method: "POST").count, 0)

        let profile = ApnProfile(
            name: "Airtel NG",
            apnName: "internet.ng.airtel.com",
            selectType: "IP",
            username: "wap",
            password: "wap",
            authType: "1",
            mtu: 1500,
            natEnabled: true,
            isDefault: true
        )
        let profRes = await repo.saveApnProfiles([profile])
        XCTAssertEqual(profRes.value?.count, 1)
        let p248 = try XCTUnwrap(env.transport.sent(248, method: "POST").first)
        assertPayloadKeys(p248, exactDataKeys: ["apn_list"])
        let row = try XCTUnwrap((p248.payload["apn_list"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(row.keys), [
            "name", "apnName", "selectAuthtication", "apnUserName",
            "apnUserPassword", "edit_flag", "default_flag"
        ])
        XCTAssertEqual(row["apnName"] as? String, "internet.ng.airtel.com")
        XCTAssertEqual(row["selectAuthtication"] as? String, "1")
        XCTAssertEqual(row["default_flag"] as? String, "1")
    }

    func testSignalBandLockAndCellLockValidationAndPayloads() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var state161 = RouterFixtures.json161()
        var state160 = RouterFixtures.json160Ca()

        env.transport.on(161) { req in
            if req.method == "POST" {
                state161["band_4g_switch"] = req.payload["band4gRadio"]
                state161["band_5g_switch"] = req.payload["band5gRadio"]
                state161["band_4g_mask"] = req.payload["lock4gBand"]
                state161["band_5g_mask"] = req.payload["lock5gBand"]
                return ["success": true, "message": "0"]
            }
            return state161
        }
        env.transport.on(160) { req in
            if req.method == "POST" {
                let sub = req.payload["subcmd"] as? Int ?? 0
                if sub == 0 {
                    state160["lte_lock_sw"] = req.payload["lte_lock_sw"]
                    state160["lte_lock_freq"] = req.payload["lte_lock_freq"]
                    state160["lte_lock_pci"] = req.payload["lte_lock_pci"]
                } else {
                    state160["nr_lock_sw"] = req.payload["nr_lock_sw"]
                    state160["nr_lock_freq"] = req.payload["nr_lock_freq"]
                    state160["nr_lock_pci"] = req.payload["nr_lock_pci"]
                    state160["nr_lock_cell_band"] = req.payload["nr_lock_cell_band"]
                }
                return ["success": true, "message": "0"]
            }
            return state160
        }

        let repo = SignalRepository(session: env.session, writer: env.writer)

        // Band lock preflight guards: empty or unsupported bands blocked
        let writeRes1 = await repo.saveBandLock(lock4g: true, bands4g: [], lock5g: false, bands5g: [])
        XCTAssertFalse(writeRes1.isOk)
        let writeRes2 = await repo.saveBandLock(lock4g: false, bands4g: [], lock5g: true, bands5g: [77])
        XCTAssertFalse(writeRes2.isOk)
        XCTAssertEqual(env.transport.sent(161, method: "POST").count, 0)

        let bandOk = await repo.saveBandLock(lock4g: true, bands4g: [1, 3], lock5g: true, bands5g: [78])
        XCTAssertEqual(bandOk.value?.locked4g, [1, 3])
        XCTAssertEqual(bandOk.value?.locked5g, [78])
        let p161 = try XCTUnwrap(env.transport.sent(161, method: "POST").first)
        assertPayloadKeys(p161, exactDataKeys: ["band4gRadio", "band5gRadio", "lock4gBand", "lock5gBand"])
        XCTAssertEqual(p161.payload["lock4gBand"] as? String, BandMask.encode([1, 3]))
        XCTAssertEqual(p161.payload["lock5gBand"] as? String, BandMask.encode([78]))

        // LTE cell lock validation & multi-cell lock
        let writeRes3 = await repo.saveLteCellLock(enabled: true, cells: [])
        XCTAssertFalse(writeRes3.isOk)
        let writeRes4 = await repo.saveLteCellLock(enabled: true, cells: [LteLockCell(earfcn: "1650", pci: "504")])
        XCTAssertFalse(writeRes4.isOk)
        let writeRes5 = await repo.saveLteCellLock(enabled: true, cells: [
            LteLockCell(earfcn: "1650", pci: "12"),
            LteLockCell(earfcn: "1650", pci: "12")
        ])
        XCTAssertFalse(writeRes5.isOk)
        XCTAssertEqual(env.transport.sent(160, method: "POST").count, 0)

        let lteCells = [LteLockCell(earfcn: "1650", pci: "12"), LteLockCell(earfcn: "3050", pci: "44")]
        let lteOk = await repo.saveLteCellLock(enabled: true, cells: lteCells)
        XCTAssertEqual(lteOk.value?.lteCells, lteCells)
        let p160Lte = try XCTUnwrap(env.transport.sent(160, method: "POST").first)
        assertPayloadKeys(p160Lte, exactDataKeys: ["subcmd", "lte_lock_sw", "lte_lock_freq", "lte_lock_pci"])
        XCTAssertEqual(p160Lte.payload["subcmd"] as? Int, 0)
        XCTAssertEqual(p160Lte.payload["lte_lock_freq"] as? String, "1650,3050")
        XCTAssertEqual(p160Lte.payload["lte_lock_pci"] as? String, "12,44")

        // NR cell lock validation & unlock
        env.transport.clearRequests()
        let writeRes6 = await repo.saveNrCellLock(enabled: true, cells: [NrLockCell(band: "78", arfcn: "633984", pci: "1008")])
        XCTAssertFalse(writeRes6.isOk)
        let nrOk = await repo.saveNrCellLock(enabled: true, cells: [NrLockCell(band: "78", arfcn: "633984", pci: "201")])
        XCTAssertEqual(nrOk.value?.nrEnabled, true)
        let p160Nr = try XCTUnwrap(env.transport.sent(160, method: "POST").first)
        assertPayloadKeys(p160Nr, exactDataKeys: [
            "subcmd", "nr_lock_sw", "nr_lock_freq", "nr_lock_pci", "nr_lock_cell_band"
        ])
        XCTAssertEqual(p160Nr.payload["subcmd"] as? Int, 1)
        XCTAssertEqual(p160Nr.payload["nr_lock_cell_band"] as? String, "78")

        env.transport.clearRequests()
        let nrUnlock = await repo.saveNrCellLock(enabled: false, cells: [])
        XCTAssertEqual(nrUnlock.value?.nrEnabled, false)
        let p160Unlock = try XCTUnwrap(env.transport.sent(160, method: "POST").first)
        XCTAssertEqual(p160Unlock.payload["nr_lock_sw"] as? String, "0")
        XCTAssertEqual(p160Unlock.payload["nr_lock_freq"] as? String, "")
        XCTAssertEqual(p160Unlock.payload["nr_lock_cell_band"] as? String, "")
    }

    func testSmsLoadSend502MarkReadDeleteAndSettingsSmscOmission() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let row1 = CodecHelpers.encodeBase64Utf8("10 0 +2348011111111 2026-11-14 09:30 Your OTP is 4821")
        let row2 = CodecHelpers.encodeBase64Utf8("11 1 AirtelNG 2026-11-14 08:00 Welcome to 5G")
        var inboxList = "\(row1),\(row2)"
        var unreadCount = "1"
        var sendReplyCode = "0"
        var settings16: [String: Any] = [
            "success": true,
            "smsSw": "1",
            "smscSw": "0",
            "dmCsca": "+2348020000000",
            "maxSize": "200"
        ]

        env.transport.on(12) { req in
            if req.method == "POST" {
                unreadCount = "0"
                return ["success": true, "message": "0"]
            }
            return ["success": true, "sms_total": inboxList.isEmpty ? "0" : "2", "sms_list": inboxList, "receive_full": "0", "send_full": "0"]
        }
        env.transport.on(394, method: "GET") { _ in ["success": true, "sms_unread": unreadCount] }
        env.transport.on(14, method: "POST") { _ in
            ["success": true, "message": sendReplyCode]
        }
        env.transport.on(13, method: "POST") { req in
            if (req.payload["index"] as? String) == "10" {
                inboxList = row2
            }
            return ["success": true, "message": "0"]
        }
        env.transport.on(15, method: "POST") { _ in
            inboxList = ""
            return ["success": true, "message": "0"]
        }
        env.transport.on(16) { req in
            if req.method == "POST" {
                for key in ["smsSw", "maxSize", "dmCsca"] {
                    if let val = req.payload[key] { settings16[key] = val }
                }
                return ["success": true, "message": "0"]
            }
            return settings16
        }

        let unreadUpdates = TestBox<[Int]>([])
        let repo = SmsRepository(
            session: env.session,
            writer: env.writer,
            onUnreadCountSync: { count in
                unreadUpdates.withLock { $0.append(count) }
            }
        )

        let page = await repo.loadPage(folder: .inbox, page: 1)
        XCTAssertEqual(page.value?.messages.count, 2)
        XCTAssertEqual(page.value?.messages[0].isRead, false)
        XCTAssertEqual(page.value?.messages[1].isRead, true)
        XCTAssertEqual(repo.unreadCount, 1)
        XCTAssertEqual(unreadUpdates.get(), [1])

        // SMS send validation & 502 full handling
        let writeRes1 = await repo.sendSms(phoneNumber: "abc", body: "Hi")
        XCTAssertFalse(writeRes1.isOk)
        let writeRes2 = await repo.sendSms(phoneNumber: "+234800", body: "")
        XCTAssertFalse(writeRes2.isOk)
        sendReplyCode = "502"
        let fullRes = await repo.sendSms(phoneNumber: "+234800", body: "Hello")
        XCTAssertTrue((fullRes.errorMessage ?? "").contains("full"))

        sendReplyCode = "0"
        env.transport.clearRequests()
        let sendOk = await repo.sendSms(phoneNumber: "+2348001234", body: "Status check")
        XCTAssertTrue(sendOk.isOk)
        let p14 = try XCTUnwrap(env.transport.sent(14, method: "POST").first)
        assertPayloadKeys(p14, exactDataKeys: ["phone", "content"])
        XCTAssertEqual(p14.payload["phone"] as? String, "+2348001234")
        XCTAssertEqual(p14.payload["content"] as? String, CodecHelpers.encodeBase64Utf8("Status check"))

        // Mark read & mark all read
        env.transport.clearRequests()
        let writeRes3 = await repo.markRead(indices: ["10"])
        XCTAssertTrue(writeRes3.isOk)
        let pMark = try XCTUnwrap(env.transport.sent(12, method: "POST").first)
        assertPayloadKeys(pMark, exactDataKeys: ["subcmd", "index"])
        XCTAssertEqual(pMark.payload["subcmd"] as? Int, 0)
        XCTAssertEqual(pMark.payload["index"] as? String, "10")

        env.transport.clearRequests()
        let writeRes4 = await repo.markAllRead()
        XCTAssertTrue(writeRes4.isOk)
        let pMarkAll = try XCTUnwrap(env.transport.sent(12, method: "POST").first)
        XCTAssertEqual(pMarkAll.payload["index"] as? String, "READ ALL")

        // Delete selected & delete all
        env.transport.clearRequests()
        let writeRes5 = await repo.deleteMessages(folder: .inbox, indices: ["10"])
        XCTAssertTrue(writeRes5.isOk)
        let pDel = try XCTUnwrap(env.transport.sent(13, method: "POST").first)
        assertPayloadKeys(pDel, exactDataKeys: ["subcmd", "index"])
        XCTAssertEqual(pDel.payload["subcmd"] as? Int, 0)
        XCTAssertEqual(pDel.payload["index"] as? String, "10")

        env.transport.clearRequests()
        let writeRes6 = await repo.deleteAll(folder: .outbox)
        XCTAssertTrue(writeRes6.isOk)
        let pDelAll = try XCTUnwrap(env.transport.sent(15, method: "POST").first)
        assertPayloadKeys(pDelAll, exactDataKeys: ["subcmd"])
        XCTAssertEqual(pDelAll.payload["subcmd"] as? Int, 1)

        // Settings with smscSw == "0" omits dmCsca; smscSw == "1" validates & sends dmCsca
        env.transport.clearRequests()
        let savedNoSmsc = await repo.saveSettings(smsEnabled: false, smscNumber: "", maxSize: 300)
        XCTAssertEqual(savedNoSmsc.value?.smsEnabled, false)
        let p16NoSmsc = try XCTUnwrap(env.transport.sent(16, method: "POST").first)
        assertPayloadKeys(p16NoSmsc, exactDataKeys: ["smsSw", "maxSize"])

        settings16["smscSw"] = "1"
        _ = await repo.loadSettings()
        let writeRes7 = await repo.saveSettings(smsEnabled: true, smscNumber: "invalid", maxSize: 300)
        XCTAssertFalse(writeRes7.isOk)

        env.transport.clearRequests()
        let savedWithSmsc = await repo.saveSettings(smsEnabled: true, smscNumber: "+2348021234567", maxSize: 400)
        XCTAssertEqual(savedWithSmsc.value?.smscNumber, "+2348021234567")
        let p16WithSmsc = try XCTUnwrap(env.transport.sent(16, method: "POST").first)
        assertPayloadKeys(p16WithSmsc, exactDataKeys: ["smsSw", "maxSize", "dmCsca"])
        XCTAssertEqual(p16WithSmsc.payload["dmCsca"] as? String, "+2348021234567")
    }

    func testUssdSendHexDecodeReplyTimeout507Error506CancelAndClearHistory() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var nextUssdReply: [String: Any] = ["success": true, "message": "004F004B", "need_reply": "1"]
        env.transport.on(350, method: "POST") { _ in nextUssdReply }

        let repo = UssdRepository(session: env.session, writer: env.writer)

        // Blank rejected locally
        let writeRes1 = await repo.send(input: "   ")
        XCTAssertFalse(writeRes1.isOk)
        XCTAssertNotNil(repo.sessionState.lastError)
        XCTAssertEqual(env.transport.sent(350, method: "POST").count, 0)

        // UCS-2 hex decoded and session awaits reply
        let first = await repo.send(input: "*123#")
        XCTAssertEqual(first.value?.text, "OK")
        XCTAssertEqual(first.value?.needReply, true)
        XCTAssertTrue(repo.sessionState.needReply)
        let p350 = try XCTUnwrap(env.transport.sent(350, method: "POST").first)
        assertPayloadKeys(p350, exactDataKeys: ["subcmd", "ussd_code"])
        XCTAssertEqual(p350.payload["subcmd"] as? String, "0")
        XCTAssertEqual(p350.payload["ussd_code"] as? String, "*123#")

        // 507 timeout closes session
        nextUssdReply = ["success": true, "message": "507", "need_reply": "0"]
        let timeoutRes = await repo.send(input: "1")
        XCTAssertEqual(timeoutRes.value?.text, "USSD request timed out")
        XCTAssertFalse(repo.sessionState.needReply)

        // 506 carrier error
        nextUssdReply = ["success": true, "message": "506", "need_reply": "0"]
        let err506 = await repo.send(input: "*141#")
        XCTAssertEqual(err506.value?.text, "Carrier returned a USSD error")

        // Cancel session sends subcmd "1"
        env.transport.clearRequests()
        nextUssdReply = ["success": true, "message": "0"]
        let writeRes2 = await repo.cancelSession()
        XCTAssertTrue(writeRes2.isOk)
        let cancelPost = try XCTUnwrap(env.transport.sent(350, method: "POST").first)
        assertPayloadKeys(cancelPost, exactDataKeys: ["subcmd"])
        XCTAssertEqual(cancelPost.payload["subcmd"] as? String, "1")

        XCTAssertFalse(repo.sessionState.history.isEmpty)
        repo.clearHistory()
        XCTAssertTrue(repo.sessionState.history.isEmpty)
    }

    func testValidatorsParityWifiLanSmsSmscApnWpsPortIpUrlTime() {
        // SSID & Wi-Fi password
        XCTAssertNotNil(WifiAndLanValidators.validateSsid(""))
        XCTAssertNotNil(WifiAndLanValidators.validateSsid(String(repeating: "a", count: 33)))
        XCTAssertNotNil(WifiAndLanValidators.validateSsid("Blocked", forbiddenSsids: ["Blocked"]))
        XCTAssertNil(WifiAndLanValidators.validateSsid("Airtel 5G"))
        XCTAssertNotNil(WifiAndLanValidators.validateWifiPassword("1234567", authenticationType: "2"))
        XCTAssertNil(WifiAndLanValidators.validateWifiPassword("anything", authenticationType: "0"))

        // Router password rules
        XCTAssertNotNil(SensitiveFormatters.validateRouterPassword(oldPassword: "", newPassword: "Password1", confirmPassword: "Password1"))
        XCTAssertNotNil(SensitiveFormatters.validateRouterPassword(oldPassword: "oldpass1", newPassword: "bad pass1", confirmPassword: "bad pass1"))
        XCTAssertNotNil(SensitiveFormatters.validateRouterPassword(oldPassword: "Password1", newPassword: "Password1", confirmPassword: "Password1"))
        XCTAssertNotNil(SensitiveFormatters.validateRouterPassword(oldPassword: "oldpass1", newPassword: "Password1", confirmPassword: "Password2"))
        XCTAssertNil(SensitiveFormatters.validateRouterPassword(oldPassword: "oldpass1", newPassword: "Password1!", confirmPassword: "Password1!"))
        XCTAssertNotNil(SensitiveFormatters.validateLogSizeKb("0"))
        XCTAssertNil(SensitiveFormatters.validateLogSizeKb("200"))

        // SMS weighted length & SMSC
        XCTAssertEqual(SmsValidators.weightedSmsLength("Hi😀"), 6) // 2 ASCII + 2 UTF-16 units * 2
        XCTAssertNotNil(SmsValidators.validateSmsMaxSize("49"))
        XCTAssertNotNil(SmsValidators.validateSmscNumber("", required: true))
        XCTAssertNil(SmsValidators.validateSmscNumber("", required: false))

        // Port normalization & conflicts + IPv4/IPv6/URL/Time window
        XCTAssertEqual(Phase71Validators.normalizePortRange("8000-8080", separator: ":"), "8000:8080")
        XCTAssertEqual(Phase71Validators.normalizePortRange("8000:8080", separator: "-"), "8000-8080")
        XCTAssertEqual(Phase71Validators.findPortConflict(portExpr: "440-450", reservedPorts: [80, 443]), 443)
        XCTAssertNil(Phase71Validators.validateIpOrRange("192.168.1.10-192.168.1.20", ippro: "IPV4", allowRange: true))
        XCTAssertNotNil(Phase71Validators.validateIpOrRange("192.168.1.30-192.168.1.20", ippro: "IPV4", allowRange: true))
        XCTAssertTrue(Phase71Validators.isIpv6Literal("fe80::1"))
        XCTAssertNil(Phase71Validators.validateWpsPin("12345670"))
        XCTAssertNotNil(Phase71Validators.validateWpsPin("12345671"))
        XCTAssertEqual(Phase71Validators.stripTimezoneSuffix("UTC+1_2"), "UTC+1")
        XCTAssertNotNil(Phase71Validators.validateFilterUrl("bad host name"))
        XCTAssertNil(Phase71Validators.validateFilterUrl("example.com"))
        XCTAssertNil(Phase71Validators.validateTimeWindow(startHhMm: "08:00", endHhMm: "21:30"))
        XCTAssertNotNil(Phase71Validators.validateTimeWindow(startHhMm: "21:30", endHhMm: "08:00"))
    }
}
