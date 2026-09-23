import XCTest
@testable import AirtelMonitor

final class LockLogicTests: XCTestCase {

    func testBandMaskMatchesRouterEncoding() {
        XCTAssertEqual(BandMask.decode("120080800C5"), [1, 3, 7, 8, 20, 28, 38, 41])
        XCTAssertEqual(BandMask.decode("20000000010000000000"), [41, 78])
        XCTAssertEqual(BandMask.encode([1, 3, 7, 8, 20, 28, 38, 41]), "120080800c5")
        XCTAssertEqual(BandMask.encode([3, 7]), "44")
        XCTAssertEqual(BandMask.encode([78]), "20000000000000000000")
        XCTAssertEqual(BandMask.encode([]), "")
        XCTAssertEqual(BandMask.decode(""), [])
        XCTAssertEqual(BandMask.decode("zz"), [])
    }

    func testParsesBandAndCellLock() {
        let bands = LockLogic.parseBandLock([
            "all_band_4g": "120080800C5", "band_4g_switch": "1", "band_4g_mask": "5",
            "all_band_5g": "20000000010000000000", "band_5g_switch": "0", "band_5g_mask": "",
        ])
        XCTAssertEqual(bands.supported4g, [1, 3, 7, 8, 20, 28, 38, 41])
        XCTAssertEqual(bands.locked4g, [1, 3])
        XCTAssertTrue(bands.lock4gEnabled)
        XCTAssertFalse(bands.lock5gEnabled)

        let cells = LockLogic.parseCellLock([
            "lte_lock_sw": "1", "lte_lock_freq": "225,1850", "lte_lock_pci": "456,", "lock_4g_flag": "1",
            "nr_lock_sw": "1", "nr_lock_freq": "627264", "nr_lock_pci": "916", "nr_lock_cell_band": "78", "lock_5g_flag": "-1",
            "FREQ": "225", "PCI": "456", "FREQ_5G": "627264", "PCI_5G": "916", "current_band_5g": "78",
        ])
        XCTAssertEqual(cells.lteCells, [LteLockCell(earfcn: "225", pci: "456"), LteLockCell(earfcn: "1850", pci: "")])
        XCTAssertEqual(cells.lteState, .locked)
        XCTAssertEqual(cells.nrCells, [NrLockCell(band: "78", arfcn: "627264", pci: "916")])
        XCTAssertEqual(cells.nrState, .failed)
        XCTAssertEqual(cells.current5g, NrLockCell(band: "78", arfcn: "627264", pci: "916"))
    }

    func testValidation() {
        var supported = BandLockConfig()
        supported.supported4g = [1, 3, 7]
        supported.supported5g = [41, 78]
        XCTAssertNil(LockLogic.validateBands(lock4g: true, bands4g: [3], lock5g: true, bands5g: [78], supported: supported))
        XCTAssertNotNil(LockLogic.validateBands(lock4g: true, bands4g: [], lock5g: false, bands5g: [], supported: supported))
        XCTAssertNotNil(LockLogic.validateBands(lock4g: true, bands4g: [5], lock5g: false, bands5g: [], supported: supported))

        XCTAssertNil(LockLogic.validateLte([LteLockCell(earfcn: "225", pci: "456")]))
        XCTAssertNil(LockLogic.validateLte([LteLockCell(earfcn: "225", pci: "")]))
        XCTAssertNotNil(LockLogic.validateLte([LteLockCell(earfcn: "225", pci: "504")]))
        XCTAssertNotNil(LockLogic.validateLte([LteLockCell(earfcn: "x", pci: "1")]))
        XCTAssertNotNil(LockLogic.validateLte([]))
        XCTAssertNotNil(LockLogic.validateLte([LteLockCell(earfcn: "225", pci: "1"), LteLockCell(earfcn: "225", pci: "1")]))

        XCTAssertNil(LockLogic.validateNr([NrLockCell(band: "78", arfcn: "627264", pci: "916")]))
        XCTAssertNotNil(LockLogic.validateNr([NrLockCell(band: "300", arfcn: "627264", pci: "1")]))
        XCTAssertNotNil(LockLogic.validateNr([NrLockCell(band: "78", arfcn: "627264", pci: "1008")]))
    }
}
