import XCTest
@testable import AirtelMonitor

final class LockLogicTests: XCTestCase {
    func testBandMaskDecodeAndEncodeRoundTrip() {
        XCTAssertEqual(BandMask.decode("0005"), [1, 3])
        XCTAssertEqual(BandMask.decode("84"), [3, 8])
        XCTAssertEqual(BandMask.decode(""), [])
        XCTAssertEqual(BandMask.decode("INVALID"), [])

        XCTAssertEqual(BandMask.encode([1, 3]), "5")
        XCTAssertEqual(BandMask.encode([3, 8]), "84")
        XCTAssertEqual(BandMask.encode([78]), "20000000000000000000")
        XCTAssertEqual(BandMask.decode("20000000000000000000"), [78])
    }

    func testParsesBandLockAndCellLock() {
        let bandCfg = RouterParsers.parseBandLock([
            "band_4g_switch": "1",
            "all_band_4g": "84",
            "band_4g_mask": "4",
            "band_5g_switch": "0",
            "all_band_5g": "20000000000000000000",
            "band_5g_mask": ""
        ])
        XCTAssertTrue(bandCfg.lock4gEnabled)
        XCTAssertEqual(bandCfg.supported4g, [3, 8])
        XCTAssertEqual(bandCfg.locked4g, [3])
        XCTAssertFalse(bandCfg.lock5gEnabled)
        XCTAssertEqual(bandCfg.supported5g, [78])

        let cellCfg = RouterParsers.parseCellLock([
            "lte_lock_sw": "1",
            "lte_lock_freq": "1650,3050",
            "lte_lock_pci": "12,44",
            "lock_4g_flag": "1",
            "nr_lock_sw": "1",
            "nr_lock_band": "78",
            "nr_lock_freq": "633984",
            "nr_lock_pci": "201",
            "lock_5g_flag": "1"
        ])
        XCTAssertTrue(cellCfg.lteEnabled)
        XCTAssertEqual(cellCfg.lteCells, [
            LteLockCell(earfcn: "1650", pci: "12"),
            LteLockCell(earfcn: "3050", pci: "44")
        ])
        XCTAssertEqual(cellCfg.lteState, .locked)
        XCTAssertTrue(cellCfg.nrEnabled)
        XCTAssertEqual(cellCfg.nrCells, [
            NrLockCell(band: "78", arfcn: "633984", pci: "201")
        ])
        XCTAssertEqual(cellCfg.nrState, .locked)
    }

    func testCellValidationRules() {
        XCTAssertNotNil(LockLogic.validateLteCells([]))
        XCTAssertNotNil(LockLogic.validateLteCells([LteLockCell(earfcn: "999999", pci: "1")]))
        XCTAssertNotNil(LockLogic.validateLteCells([LteLockCell(earfcn: "1650", pci: "504")]))
        XCTAssertNil(LockLogic.validateLteCells([LteLockCell(earfcn: "1650", pci: "")]))
        XCTAssertNotNil(LockLogic.validateLteCells([
            LteLockCell(earfcn: "1650", pci: "12"),
            LteLockCell(earfcn: "1650", pci: "12")
        ]))

        XCTAssertNotNil(LockLogic.validateNrCells([]))
        XCTAssertNotNil(LockLogic.validateNrCells([NrLockCell(band: "300", arfcn: "633984", pci: "10")]))
        XCTAssertNotNil(LockLogic.validateNrCells([NrLockCell(band: "78", arfcn: "0", pci: "10")]))
        XCTAssertNotNil(LockLogic.validateNrCells([NrLockCell(band: "78", arfcn: "633984", pci: "1008")]))
        XCTAssertNil(LockLogic.validateNrCells([NrLockCell(band: "78", arfcn: "633984", pci: "201")]))
    }

    @MainActor
    func testSignalRepositoryBandAndCellLockWriteFlow() async {
        let transport = MockRouterTransport()
        await transport.configureAuth(sessionId: "sid-lock-1", challenge: "tok-1")
        await transport.setHandler(cmd: 161, method: "GET") { _ in
            .ok([
                "band_4g_switch": "0",
                "all_band_4g": "84",
                "band_4g_mask": "",
                "band_5g_switch": "0",
                "all_band_5g": "20000000000000000000",
                "band_5g_mask": ""
            ])
        }
        await transport.setHandler(cmd: 161, method: "POST") { _ in
            await transport.setHandler(cmd: 161, method: "GET") { _ in
                .ok([
                    "band_4g_switch": "1",
                    "all_band_4g": "84",
                    "band_4g_mask": "4",
                    "band_5g_switch": "0",
                    "all_band_5g": "20000000000000000000",
                    "band_5g_mask": ""
                ])
            }
            return .ok(["success": true])
        }

        let session = RouterSession(
            transport: transport,
            credentialsProvider: { RouterCredentials(host: "192.168.1.1", username: "user", password: "pw") }
        )
        let writer = SettingsWriter(session: session, sleepFn: { _ in })
        let repo = SignalRepository(session: session, writer: writer)

        let unsupported = await repo.saveBandLock(lock4g: true, bands4g: [41], lock5g: false, bands5g: [])
        XCTAssertFalse(unsupported.isOk)

        let saved = await repo.saveBandLock(lock4g: true, bands4g: [3], lock5g: false, bands5g: [])
        guard case .ok(let cfg) = saved else {
            return XCTFail("Expected saveBandLock to succeed")
        }
        XCTAssertTrue(cfg.lock4gEnabled)
        XCTAssertEqual(cfg.locked4g, [3])

        let writes = await transport.recordedRequests().filter { $0.cmd == 161 && $0.method == "POST" }
        XCTAssertEqual(writes.count, 1)
        XCTAssertEqual(writes.first?.payload["lock4gBand"] as? String, "4")
    }
}
