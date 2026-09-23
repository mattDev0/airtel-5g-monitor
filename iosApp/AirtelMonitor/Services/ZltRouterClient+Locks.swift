import Foundation

/// Band sets as the router encodes them: a hex bitmask where bit 0 is band 1
/// (e.g. B1+B3 = "5", n41+n78 = "20000000010000000000").
public enum BandMask {
    public static func decode(_ hex: String?) -> Set<Int> {
        var bands = Set<Int>()
        let digits = Array((hex ?? "").trimmingCharacters(in: .whitespaces).lowercased().reversed())
        for (i, ch) in digits.enumerated() {
            guard let nibble = ch.hexDigitValue else { return [] }
            for bit in 0..<4 where nibble & (1 << bit) != 0 {
                bands.insert(i * 4 + bit + 1)
            }
        }
        return bands
    }

    public static func encode(_ bands: Set<Int>) -> String {
        guard let top = bands.filter({ $0 >= 1 }).max() else { return "" }
        var nibbles = [Int](repeating: 0, count: (top - 1) / 4 + 1)
        for band in bands where band >= 1 {
            nibbles[(band - 1) / 4] |= 1 << ((band - 1) % 4)
        }
        let hex = nibbles.reversed().map { String($0, radix: 16) }.joined()
        let trimmed = hex.drop(while: { $0 == "0" })
        return String(trimmed)
    }
}

/// Pure parsing/validation for the lock commands, kept static so tests can reach it.
public enum LockLogic {
    /// 5G bands that are supplementary uplink only; a lock can't consist of just these.
    static let sulOnly5gBands: Set<Int> = [75, 76, 80, 81, 82, 83, 84, 86]

    static func str(_ dict: [String: Any], _ key: String) -> String {
        guard let v = dict[key] else { return "" }
        return "\(v)"
    }

    public static func parseBandLock(_ r: [String: Any]) -> BandLockConfig {
        var c = BandLockConfig()
        c.lock4gEnabled = str(r, "band_4g_switch") == "1"
        c.supported4g = BandMask.decode(str(r, "all_band_4g")).sorted()
        c.locked4g = BandMask.decode(str(r, "band_4g_mask"))
        c.lock5gEnabled = str(r, "band_5g_switch") == "1"
        c.supported5g = BandMask.decode(str(r, "all_band_5g")).sorted()
        c.locked5g = BandMask.decode(str(r, "band_5g_mask"))
        return c
    }

    public static func parseCellLock(_ r: [String: Any]) -> CellLockConfig {
        func list(_ key: String) -> [String] {
            str(r, key).split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        }
        func state(_ key: String) -> LockState {
            switch str(r, key) {
            case "1": return .locked
            case "-1": return .failed
            default: return .unlocked
            }
        }
        func at(_ a: [String], _ i: Int) -> String { i < a.count ? a[i] : "" }

        var c = CellLockConfig()
        c.lteEnabled = str(r, "lte_lock_sw") == "1"
        let lteFreqs = list("lte_lock_freq").filter { !$0.isEmpty }
        let ltePcis = list("lte_lock_pci")
        c.lteCells = lteFreqs.enumerated().map { LteLockCell(earfcn: $1, pci: at(ltePcis, $0)) }
        c.lteState = state("lock_4g_flag")

        c.nrEnabled = str(r, "nr_lock_sw") == "1"
        let nrFreqs = list("nr_lock_freq").filter { !$0.isEmpty }
        let nrPcis = list("nr_lock_pci")
        let nrBands = list("nr_lock_cell_band")
        c.nrCells = nrFreqs.enumerated().map { NrLockCell(band: at(nrBands, $0), arfcn: $1, pci: at(nrPcis, $0)) }
        c.nrState = state("lock_5g_flag")

        if !str(r, "FREQ").isEmpty { c.current4g = LteLockCell(earfcn: str(r, "FREQ"), pci: str(r, "PCI")) }
        if !str(r, "FREQ_5G").isEmpty {
            c.current5g = NrLockCell(band: str(r, "current_band_5g"), arfcn: str(r, "FREQ_5G"), pci: str(r, "PCI_5G"))
        }
        return c
    }

    /// Returns an error message, or nil when the band lock request is valid.
    public static func validateBands(lock4g: Bool, bands4g: Set<Int>, lock5g: Bool, bands5g: Set<Int>, supported: BandLockConfig) -> String? {
        if lock4g && bands4g.isEmpty { return "Pick at least one 4G band" }
        if lock5g && bands5g.isEmpty { return "Pick at least one 5G band" }
        if let b = bands4g.subtracting(supported.supported4g).min() { return "This router doesn't support 4G band B\(b)" }
        if let b = bands5g.subtracting(supported.supported5g).min() { return "This router doesn't support 5G band n\(b)" }
        if lock5g && bands5g.isSubset(of: sulOnly5gBands) { return "A 5G lock can't use supplementary uplink bands only" }
        return nil
    }

    private static func inRange(_ s: String, _ range: ClosedRange<Int>) -> Bool {
        guard let v = Int(s) else { return false }
        return range.contains(v)
    }

    public static func validateLte(_ cells: [LteLockCell]) -> String? {
        if cells.isEmpty { return "Add at least one 4G cell" }
        for c in cells {
            if !inRange(c.earfcn, 0...262_143) { return "4G EARFCN must be a number from 0 to 262143" }
            if !c.pci.isEmpty && !inRange(c.pci, 0...503) { return "4G PCI must be from 0 to 503, or blank" }
        }
        if Set(cells).count != cells.count { return "The same 4G cell is listed twice" }
        return nil
    }

    public static func validateNr(_ cells: [NrLockCell]) -> String? {
        if cells.isEmpty { return "Add at least one 5G cell" }
        for c in cells {
            if !inRange(c.band, 1...264) { return "5G band must be a number from 1 to 264 (e.g. 78 for n78)" }
            if !inRange(c.arfcn, 1...3_279_165) { return "5G ARFCN must be a number from 1 to 3279165" }
            if !c.pci.isEmpty && !inRange(c.pci, 0...1007) { return "5G PCI must be from 0 to 1007, or blank" }
        }
        if Set(cells).count != cells.count { return "The same 5G cell is listed twice" }
        return nil
    }
}

private func lockError(_ message: String) -> Error {
    NSError(domain: "AirtelMonitor", code: 20, userInfo: [NSLocalizedDescriptionKey: message])
}

extension ZltRouterClient {
    public func getLockSettings() async -> (Bool, LockSettings) {
        let band = await query(cmdId: 161)
        let cell = await query(cmdId: 160)
        let bandOk = band["success"] as? Bool == true || band["all_band_4g"] != nil
        let cellOk = cell["success"] as? Bool == true || cell["lte_lock_sw"] != nil
        guard bandOk || cellOk else { return (false, LockSettings()) }
        var s = LockSettings()
        s.bands = LockLogic.parseBandLock(band)
        s.cells = LockLogic.parseCellLock(cell)
        return (true, s)
    }

    /// Saves which bands the modem may use. The modem re-registers, so the link can drop briefly.
    public func setBandLock(lock4g: Bool, bands4g: Set<Int>, lock5g: Bool, bands5g: Set<Int>) async -> Result<String, Error> {
        let current = await query(cmdId: 161)
        guard current["success"] as? Bool == true || current["all_band_4g"] != nil else {
            return .failure(lockError(LockLogic.str(current, "message").isEmpty ? "Could not read band lock settings" : LockLogic.str(current, "message")))
        }
        if let problem = LockLogic.validateBands(lock4g: lock4g, bands4g: bands4g, lock5g: lock5g, bands5g: bands5g,
                                                 supported: LockLogic.parseBandLock(current)) {
            return .failure(lockError(problem))
        }
        let token = LockLogic.str(await query(cmdId: 233), "token")
        let payload: [String: Any] = [
            "band4gRadio": lock4g ? "1" : "0",
            "band5gRadio": lock5g ? "1" : "0",
            "lock4gBand": BandMask.encode(bands4g),
            "lock5gBand": BandMask.encode(bands5g),
            "token": token,
        ]
        let res = await query(cmdId: 161, method: "POST", extra: payload, timeout: 20)
        guard res["success"] as? Bool == true else {
            return .failure(lockError(LockLogic.str(res, "message").isEmpty ? "Router rejected the band lock" : LockLogic.str(res, "message")))
        }
        return await confirmLock("Band lock") {
            let b = LockLogic.parseBandLock(await self.query(cmdId: 161))
            return b.lock4gEnabled == lock4g && b.lock5gEnabled == lock5g
                && (!lock4g || b.locked4g == bands4g) && (!lock5g || b.locked5g == bands5g)
        }
    }

    public func setLteCellLock(enabled: Bool, cells: [LteLockCell]) async -> Result<String, Error> {
        let clean = cells.map { LteLockCell(earfcn: $0.earfcn.trimmingCharacters(in: .whitespaces), pci: $0.pci.trimmingCharacters(in: .whitespaces)) }
        if enabled, let problem = LockLogic.validateLte(clean) { return .failure(lockError(problem)) }
        let on = enabled && !clean.isEmpty
        let token = LockLogic.str(await query(cmdId: 233), "token")
        let payload: [String: Any] = [
            "subcmd": 0,
            "lte_lock_sw": on ? "1" : "0",
            "lte_lock_freq": on ? clean.map(\.earfcn).joined(separator: ",") : "",
            "lte_lock_pci": on ? clean.map(\.pci).joined(separator: ",") : "",
            "token": token,
        ]
        let res = await query(cmdId: 160, method: "POST", extra: payload, timeout: 20)
        guard res["success"] as? Bool == true else {
            return .failure(lockError(LockLogic.str(res, "message").isEmpty ? "Router rejected the 4G cell lock" : LockLogic.str(res, "message")))
        }
        return await confirmLock(on ? "4G cell lock" : "4G cell unlock") {
            let c = LockLogic.parseCellLock(await self.query(cmdId: 160))
            return c.lteEnabled == on && (!on || c.lteCells == clean)
        }
    }

    public func setNrCellLock(enabled: Bool, cells: [NrLockCell]) async -> Result<String, Error> {
        let clean = cells.map {
            NrLockCell(band: $0.band.trimmingCharacters(in: .whitespaces),
                       arfcn: $0.arfcn.trimmingCharacters(in: .whitespaces),
                       pci: $0.pci.trimmingCharacters(in: .whitespaces))
        }
        if enabled, let problem = LockLogic.validateNr(clean) { return .failure(lockError(problem)) }
        let on = enabled && !clean.isEmpty
        let token = LockLogic.str(await query(cmdId: 233), "token")
        let payload: [String: Any] = [
            "subcmd": 1,
            "nr_lock_sw": on ? "1" : "0",
            "nr_lock_freq": on ? clean.map(\.arfcn).joined(separator: ",") : "",
            "nr_lock_pci": on ? clean.map(\.pci).joined(separator: ",") : "",
            "nr_lock_cell_band": on ? clean.map(\.band).joined(separator: ",") : "",
            "token": token,
        ]
        let res = await query(cmdId: 160, method: "POST", extra: payload, timeout: 20)
        guard res["success"] as? Bool == true else {
            return .failure(lockError(LockLogic.str(res, "message").isEmpty ? "Router rejected the 5G cell lock" : LockLogic.str(res, "message")))
        }
        return await confirmLock(on ? "5G cell lock" : "5G cell unlock") {
            let c = LockLogic.parseCellLock(await self.query(cmdId: 160))
            return c.nrEnabled == on && (!on || c.nrCells == clean)
        }
    }

    /// Reads the setting back until it matches; the modem may be re-registering meanwhile.
    private func confirmLock(_ what: String, matches: () async -> Bool) async -> Result<String, Error> {
        for _ in 0..<8 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if await matches() { return .success("\(what) saved and confirmed by router") }
        }
        return .failure(lockError("\(what) was sent, but the router still reports the old setting"))
    }
}
