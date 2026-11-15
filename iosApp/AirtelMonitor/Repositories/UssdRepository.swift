import Foundation

@MainActor
public final class UssdRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var sessionState = UssdSessionState()

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    @discardableResult
    public func send(input: String) async -> RouterResult<UssdResult> {
        let clean = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if let err = Phase71Validators.validateUssdInput(clean, isReply: sessionState.needReply) {
            sessionState = UssdSessionState(
                isBusy: false,
                needReply: sessionState.needReply,
                history: sessionState.history,
                lastError: err
            )
            return .badResponse(message: err, rawBody: "")
        }

        sessionState = UssdSessionState(
            isBusy: true,
            needReply: sessionState.needReply,
            history: sessionState.history,
            lastError: nil
        )

        let res = await writer.write(
            WriteSpec<UssdResult>(
                cmd: 350,
                buildPayload: { _ in
                    ["subcmd": "0", "ussd_code": clean]
                },
                verify: { _ in true },
                parseResult: { Self.parseUssdResponse($0) },
                writeTimeoutSeconds: 25.0,
                skipPreRead: true,
                skipVerifyRead: true,
                postAccepted: { _ in true }
            )
        )

        switch res {
        case .ok(let decoded):
            var updated = sessionState.history
            updated.append((outgoing: clean, response: decoded.text))
            sessionState = UssdSessionState(
                isBusy: false,
                needReply: decoded.needReply,
                history: updated,
                lastError: nil
            )
            return .ok(decoded)
        default:
            let errText = res.errorMessage ?? "USSD request failed"
            sessionState = UssdSessionState(
                isBusy: false,
                needReply: sessionState.needReply,
                history: sessionState.history,
                lastError: errText
            )
            return res
        }
    }

    @discardableResult
    public func cancelSession() async -> RouterResult<Unit> {
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 350,
                buildPayload: { _ in ["subcmd": "1"] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                writeTimeoutSeconds: 10.0,
                skipPreRead: true,
                skipVerifyRead: true,
                postAccepted: { _ in true }
            )
        )
        sessionState = UssdSessionState(
            isBusy: false,
            needReply: false,
            history: sessionState.history,
            lastError: nil
        )
        return res
    }

    public func clearHistory() {
        sessionState = UssdSessionState()
    }

    public static func parseUssdResponse(_ json: [String: Any]) -> UssdResult {
        let errorCode = CodecHelpers.str(json, "error")
        let messageRaw = CodecHelpers.str(json, "message")
        let needReply = CodecHelpers.str(json, "need_reply") == "1"

        if messageRaw == "507" || errorCode == "507" {
            return UssdResult(text: "USSD request timed out", needReply: false)
        }

        switch Phase71Validators.decodeUssdResponse(messageRaw) {
        case .timeout:
            return UssdResult(text: "USSD request timed out", needReply: false)
        case .carrierError:
            return UssdResult(text: "Carrier returned a USSD error", needReply: false)
        case .empty:
            let fallback = CodecHelpers.firstNonBlank(json, keys: ["ussd_str", "content"], defaultValue: "Empty response from carrier")
            return UssdResult(text: fallback, needReply: needReply)
        case .text(let decoded):
            return UssdResult(text: decoded, needReply: needReply)
        }
    }
}
