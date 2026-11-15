import Foundation

public enum LockLogic {
    public static func validateLteCells(_ cells: [LteLockCell]) -> String? {
        guard !cells.isEmpty else { return "Add at least one 4G cell" }
        for cell in cells {
            guard let earfcn = Int(cell.earfcn), (0...262_143).contains(earfcn) else {
                return "4G EARFCN must be a number from 0 to 262143"
            }
            if !cell.pci.isEmpty {
                guard let pci = Int(cell.pci), (0...503).contains(pci) else {
                    return "4G PCI must be from 0 to 503, or blank"
                }
            }
        }
        if Set(cells).count != cells.count {
            return "The same 4G cell is listed twice"
        }
        return nil
    }

    public static func validateNrCells(_ cells: [NrLockCell]) -> String? {
        guard !cells.isEmpty else { return "Add at least one 5G cell" }
        for cell in cells {
            guard let band = Int(cell.band), (1...264).contains(band) else {
                return "5G band must be a number from 1 to 264 (e.g. 78 for n78)"
            }
            guard let arfcn = Int(cell.arfcn), (1...3_279_165).contains(arfcn) else {
                return "5G ARFCN must be a number from 1 to 3279165"
            }
            if !cell.pci.isEmpty {
                guard let pci = Int(cell.pci), (0...1007).contains(pci) else {
                    return "5G PCI must be from 0 to 1007, or blank"
                }
            }
        }
        if Set(cells).count != cells.count {
            return "The same 5G cell is listed twice"
        }
        return nil
    }
}

@MainActor
public final class SignalRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var lockSettings: LockSettings?
    @Published public private(set) var neighborCells: [NeighborCell] = []

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    @discardableResult
    public func loadLocks() async -> RouterResult<LockSettings> {
        let res161 = await session.execute(cmd: 161, method: "GET")
        let res160 = await session.execute(cmd: 160, method: "GET")

        if case .notLoggedIn(let msg) = res161 { return .notLoggedIn(msg) }
        if case .notLoggedIn(let msg) = res160 { return .notLoggedIn(msg) }

        guard case .ok(let json161) = res161 else { return res161.castFailure() }
        guard case .ok(let json160) = res160 else { return res160.castFailure() }

        var settings = LockSettings()
        settings.bands = RouterParsers.parseBandLock(json161)
        settings.cells = RouterParsers.parseCellLock(json160)
        self.lockSettings = settings
        return .ok(settings)
    }

    @discardableResult
    public func saveBandLock(
        lock4g: Bool,
        bands4g: Set<Int>,
        lock5g: Bool,
        bands5g: Set<Int>
    ) async -> RouterResult<BandLockConfig> {
        let res = await writer.write(
            WriteSpec<BandLockConfig>(
                cmd: 161,
                preflight: { current in
                    let cfg = RouterParsers.parseBandLock(current)
                    if lock4g && bands4g.isEmpty {
                        return .badResponse(message: "Select at least one 4G band", rawBody: "")
                    }
                    if lock5g && bands5g.isEmpty {
                        return .badResponse(message: "Select at least one 5G band", rawBody: "")
                    }
                    let unsupported4 = bands4g.subtracting(cfg.supported4g)
                    if lock4g && !cfg.supported4g.isEmpty && !unsupported4.isEmpty {
                        return .badResponse(message: "Unsupported 4G bands selected", rawBody: "")
                    }
                    let unsupported5 = bands5g.subtracting(cfg.supported5g)
                    if lock5g && !cfg.supported5g.isEmpty && !unsupported5.isEmpty {
                        return .badResponse(message: "Unsupported 5G bands selected", rawBody: "")
                    }
                    return nil
                },
                buildPayload: { _ in
                    [
                        "band4gRadio": lock4g ? "1" : "0",
                        "band5gRadio": lock5g ? "1" : "0",
                        "lock4gBand": BandMask.encode(bands4g),
                        "lock5gBand": BandMask.encode(bands5g)
                    ]
                },
                verify: { readBack in
                    let parsed = RouterParsers.parseBandLock(readBack)
                    return parsed.lock4gEnabled == lock4g &&
                        parsed.lock5gEnabled == lock5g &&
                        (!lock4g || parsed.locked4g == bands4g) &&
                        (!lock5g || parsed.locked5g == bands5g)
                },
                parseResult: { RouterParsers.parseBandLock($0) },
                verifyRetries: 8,
                verifyDelaySeconds: 2.0
            )
        )
        if case .ok(let cfg) = res {
            var next = lockSettings ?? LockSettings()
            next.bands = cfg
            lockSettings = next
        }
        return res
    }

    @discardableResult
    public func saveLteCellLock(enabled: Bool, cells: [LteLockCell]) async -> RouterResult<CellLockConfig> {
        let clean = cells.map {
            LteLockCell(
                earfcn: $0.earfcn.trimmingCharacters(in: .whitespacesAndNewlines),
                pci: $0.pci.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        }
        if enabled, let err = LockLogic.validateLteCells(clean) {
            return .badResponse(message: err, rawBody: "")
        }

        let on = enabled && !clean.isEmpty
        let res = await writer.write(
            WriteSpec<CellLockConfig>(
                cmd: 160,
                buildPayload: { _ in
                    [
                        "subcmd": 0,
                        "lte_lock_sw": on ? "1" : "0",
                        "lte_lock_freq": on ? clean.map(\.earfcn).joined(separator: ",") : "",
                        "lte_lock_pci": on ? clean.map(\.pci).joined(separator: ",") : ""
                    ]
                },
                verify: { readBack in
                    let parsed = RouterParsers.parseCellLock(readBack)
                    return parsed.lteEnabled == on && (!on || parsed.lteCells == clean)
                },
                parseResult: { RouterParsers.parseCellLock($0) },
                verifyRetries: 8,
                verifyDelaySeconds: 2.0
            )
        )
        if case .ok(let cfg) = res {
            var next = lockSettings ?? LockSettings()
            next.cells = cfg
            lockSettings = next
        }
        return res
    }

    @discardableResult
    public func saveNrCellLock(enabled: Bool, cells: [NrLockCell]) async -> RouterResult<CellLockConfig> {
        let clean = cells.map {
            NrLockCell(
                band: $0.band.trimmingCharacters(in: .whitespacesAndNewlines),
                arfcn: $0.arfcn.trimmingCharacters(in: .whitespacesAndNewlines),
                pci: $0.pci.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        }
        if enabled, let err = LockLogic.validateNrCells(clean) {
            return .badResponse(message: err, rawBody: "")
        }

        let on = enabled && !clean.isEmpty
        let res = await writer.write(
            WriteSpec<CellLockConfig>(
                cmd: 160,
                buildPayload: { _ in
                    [
                        "subcmd": 1,
                        "nr_lock_sw": on ? "1" : "0",
                        "nr_lock_freq": on ? clean.map(\.arfcn).joined(separator: ",") : "",
                        "nr_lock_pci": on ? clean.map(\.pci).joined(separator: ",") : "",
                        "nr_lock_cell_band": on ? clean.map(\.band).joined(separator: ",") : ""
                    ]
                },
                verify: { readBack in
                    let parsed = RouterParsers.parseCellLock(readBack)
                    return parsed.nrEnabled == on && (!on || parsed.nrCells == clean)
                },
                parseResult: { RouterParsers.parseCellLock($0) },
                verifyRetries: 8,
                verifyDelaySeconds: 2.0
            )
        )
        if case .ok(let cfg) = res {
            var next = lockSettings ?? LockSettings()
            next.cells = cfg
            lockSettings = next
        }
        return res
    }

    @discardableResult
    public func loadNeighborCells() async -> RouterResult<[NeighborCell]> {
        let res = await session.execute(cmd: 282, method: "GET", timeoutSeconds: 8.0)
        guard case .ok(let json) = res else {
            return res.castFailure()
        }
        let parsed = Phase1Parsers.parseNeighborCells(json)
        self.neighborCells = parsed
        return .ok(parsed)
    }

    public func rescanNetwork() async -> RouterResult<Unit> {
        await writer.write(
            WriteSpec<Unit>(
                cmd: 288,
                buildPayload: { _ in [:] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                writeTimeoutSeconds: 15.0,
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
    }
}
