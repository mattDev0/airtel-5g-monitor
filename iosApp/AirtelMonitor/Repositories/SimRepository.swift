import Foundation

@MainActor
public final class SimRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var state: SimPinState?

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    @discardableResult
    public func loadStatus() async -> RouterResult<SimPinState> {
        let r113 = await session.execute(cmd: 113, method: "GET")
        let r134 = await session.execute(cmd: 134, method: "GET")
        if case .notLoggedIn(let msg) = r113 { return .notLoggedIn(msg) }
        if case .notLoggedIn(let msg) = r134 { return .notLoggedIn(msg) }
        guard case .ok(let j134) = r134 else { return r134.castFailure() }
        let j113 = r113.getOrNull() ?? [:]
        let parsed = Self.parseState(j113: j113, j134: j134)
        self.state = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func setPinLockEnabled(enable: Bool, pin: String) async -> RouterResult<SimPinState> {
        let cleanPin = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        if let err = WifiAndLanValidators.validatePin(cleanPin) {
            return .badResponse(message: err, rawBody: "")
        }
        let target = enable ? "1" : "0"
        let res = await writer.write(
            WriteSpec<SimPinState>(
                cmd: 51,
                readCmd: 134,
                buildPayload: { _ in
                    ["subcmd": "1", "enable": target, "pin": cleanPin]
                },
                verifyPost: { postJson in
                    if CodecHelpers.str(postJson, "message") != "0" {
                        return .badResponse(message: "Wrong SIM PIN or router rejected PIN change", rawBody: "")
                    }
                    return nil
                },
                verify: { rb in
                    (CodecHelpers.str(rb, "lock_pin_switch") == "1") == enable
                },
                parseResult: { j134 in
                    Self.parseState(j113: [:], j134: j134, prev: self.state)
                },
                skipPreRead: true,
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        _ = await loadStatus()
        if case .ok(let next) = res { return .ok(self.state ?? next) }
        return res
    }

    @discardableResult
    public func verifyPin(_ pin: String) async -> RouterResult<SimPinState> {
        let cleanPin = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        if let err = WifiAndLanValidators.validatePin(cleanPin) {
            return .badResponse(message: err, rawBody: "")
        }
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 51,
                buildPayload: { _ in ["subcmd": "2", "pin": cleanPin] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        let refreshed = await loadStatus()
        if !res.isOk { return res.castFailure() }
        return refreshed
    }

    @discardableResult
    public func changePin(oldPin: String, newPin: String) async -> RouterResult<SimPinState> {
        let cleanOld = oldPin.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanNew = newPin.trimmingCharacters(in: .whitespacesAndNewlines)
        if let err = WifiAndLanValidators.validatePin(cleanOld) {
            return .badResponse(message: "Current \(err)", rawBody: "")
        }
        if let err = WifiAndLanValidators.validatePin(cleanNew) {
            return .badResponse(message: "New \(err)", rawBody: "")
        }
        if cleanOld == cleanNew {
            return .badResponse(message: "New SIM PIN must differ from current PIN", rawBody: "")
        }

        // Phase 7.2: cmd 51 subcmd 4 expects `pin` + `pinPasswordChange` + `enable: "2"` only.
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 51,
                buildPayload: { _ in
                    [
                        "subcmd": "4",
                        "enable": "2",
                        "pin": cleanOld,
                        "pinPasswordChange": cleanNew
                    ]
                },
                verifyPost: { postJson in
                    if CodecHelpers.str(postJson, "message") != "0" {
                        return .badResponse(message: "SIM PIN update rejected — check current PIN", rawBody: "")
                    }
                    return nil
                },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        let refreshed = await loadStatus()
        if !res.isOk { return res.castFailure() }
        return refreshed
    }

    @discardableResult
    public func unlockWithPuk(puk: String, newPin: String) async -> RouterResult<SimPinState> {
        let cleanPuk = puk.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPin = newPin.trimmingCharacters(in: .whitespacesAndNewlines)
        if let err = WifiAndLanValidators.validatePuk(cleanPuk) {
            return .badResponse(message: err, rawBody: "")
        }
        if let err = WifiAndLanValidators.validatePin(cleanPin) {
            return .badResponse(message: err, rawBody: "")
        }
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 51,
                buildPayload: { _ in ["subcmd": "3", "puk": cleanPuk, "pin": cleanPin] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        let refreshed = await loadStatus()
        if !res.isOk { return res.castFailure() }
        return refreshed
    }

    private static func parseState(j113: [String: Any], j134: [String: Any], prev: SimPinState? = nil) -> SimPinState {
        SimPinState(
            simStatusCode: CodecHelpers.nonBlankStr(j113, "sim_status") ?? prev?.simStatusCode ?? "",
            cardType: CodecHelpers.nonBlankStr(j113, "current_card_type") ?? prev?.cardType ?? "",
            pinLockEnabled: CodecHelpers.str(j134, "lock_pin_switch") == "1",
            pinRequired: CodecHelpers.str(j134, "lock_pin_flag") == "1",
            pukRequired: CodecHelpers.str(j134, "lock_puk_flag") == "1",
            pinAttemptsLeft: Int(CodecHelpers.str(j134, "pin_left_times")) ?? prev?.pinAttemptsLeft ?? 3,
            pukAttemptsLeft: Int(CodecHelpers.str(j134, "puk_left_times")) ?? prev?.pukAttemptsLeft ?? 10
        )
    }
}
