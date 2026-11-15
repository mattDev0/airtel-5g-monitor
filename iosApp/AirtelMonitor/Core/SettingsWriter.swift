import Foundation

/// Specification for a verified router settings mutation: read current -> preflight -> fetch token -> POST -> verify read-back.
public struct WriteSpec<T> {
    public let cmd: Int
    public let readCmd: Int
    public let readExtra: [String: Any]
    public let preflight: ([String: Any]) -> RouterResult<Unit>?
    public let buildPayload: ([String: Any]) -> [String: Any]
    public let verifyPost: ([String: Any]) -> RouterResult<Unit>?
    public let verify: ([String: Any]) -> Bool
    public let parseResult: ([String: Any]) -> T
    public let writeTimeoutSeconds: TimeInterval
    public let verifyRetries: Int
    public let verifyDelaySeconds: TimeInterval
    public let allowEmptyWriteReply: Bool
    public let skipPreRead: Bool
    public let skipVerifyRead: Bool
    public let postAccepted: ([String: Any]) -> Bool

    public init(
        cmd: Int,
        readCmd: Int? = nil,
        readExtra: [String: Any] = [:],
        preflight: @escaping ([String: Any]) -> RouterResult<Unit>? = { _ in nil },
        buildPayload: @escaping ([String: Any]) -> [String: Any],
        verifyPost: @escaping ([String: Any]) -> RouterResult<Unit>? = { _ in nil },
        verify: @escaping ([String: Any]) -> Bool,
        parseResult: @escaping ([String: Any]) -> T,
        writeTimeoutSeconds: TimeInterval = 20.0,
        verifyRetries: Int = 1,
        verifyDelaySeconds: TimeInterval = 0.0,
        allowEmptyWriteReply: Bool = false,
        skipPreRead: Bool = false,
        skipVerifyRead: Bool = false,
        postAccepted: @escaping ([String: Any]) -> Bool = { _ in false }
    ) {
        self.cmd = cmd
        self.readCmd = readCmd ?? cmd
        self.readExtra = readExtra
        self.preflight = preflight
        self.buildPayload = buildPayload
        self.verifyPost = verifyPost
        self.verify = verify
        self.parseResult = parseResult
        self.writeTimeoutSeconds = writeTimeoutSeconds
        self.verifyRetries = verifyRetries
        self.verifyDelaySeconds = verifyDelaySeconds
        self.allowEmptyWriteReply = allowEmptyWriteReply
        self.skipPreRead = skipPreRead
        self.skipVerifyRead = skipVerifyRead
        self.postAccepted = postAccepted
    }
}

/// Single write gateway enforcing read-merge-POST-verify without blind POST retries.
public final class SettingsWriter: @unchecked Sendable {
    private let session: RouterSession
    private let sleepFn: @Sendable (TimeInterval) async -> Void

    public init(
        session: RouterSession,
        sleepFn: @escaping @Sendable (TimeInterval) async -> Void = { seconds in
            if seconds > 0 {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            }
        }
    ) {
        self.session = session
        self.sleepFn = sleepFn
    }

    public func write<T>(_ spec: WriteSpec<T>) async -> RouterResult<T> {
        let current: [String: Any]
        if spec.skipPreRead {
            current = [:]
        } else {
            let currentRes = await session.execute(cmd: spec.readCmd, method: "GET", extra: spec.readExtra)
            switch currentRes {
            case .ok(let json):
                current = json
            default:
                return currentRes.castFailure()
            }
        }

        if let preFailure = spec.preflight(current), !preFailure.isOk {
            return preFailure.castFailure()
        }

        var payload = spec.buildPayload(current)
        let tokenRes = await session.fetchWriteToken()
        guard case .ok(let token) = tokenRes else {
            return tokenRes.castFailure()
        }
        payload["token"] = token

        let postRes = await session.execute(
            cmd: spec.cmd,
            method: "POST",
            extra: payload,
            timeoutSeconds: spec.writeTimeoutSeconds,
            allowEmptyReply: spec.allowEmptyWriteReply,
            retryOnNetworkFailure: false
        )
        guard case .ok(let postJson) = postRes else {
            return postRes.castFailure()
        }

        if !postJson.isEmpty || !spec.allowEmptyWriteReply {
            let msg = RouterSession.str(postJson, "message")
            let flag = RouterSession.str(postJson, "flag")
            let accepted = spec.postAccepted(postJson) ||
                (postJson["success"] as? Bool == true) ||
                msg == "0" ||
                msg == "success" ||
                flag == "0"
            if !accepted {
                return .badResponse(
                    message: msg.isEmpty ? "Router rejected changes for cmd \(spec.cmd)" : msg,
                    rawBody: Self.jsonString(postJson)
                )
            }
            if let postFailure = spec.verifyPost(postJson), !postFailure.isOk {
                return postFailure.castFailure()
            }
        }

        if spec.skipVerifyRead {
            return .ok(spec.parseResult(postJson))
        }

        var lastReadBack: [String: Any]?
        var lastError: RouterResult<T>?
        let checks = max(1, spec.verifyRetries)

        for _ in 0..<checks {
            if spec.verifyDelaySeconds > 0 {
                await sleepFn(spec.verifyDelaySeconds)
            }
            let readRes = await session.execute(cmd: spec.readCmd, method: "GET", extra: spec.readExtra)
            switch readRes {
            case .ok(let readJson):
                lastReadBack = readJson
                if spec.verify(readJson) {
                    return .ok(spec.parseResult(readJson))
                }
            case .notLoggedIn, .limitedAccess:
                return readRes.castFailure()
            case .unreachable, .badResponse:
                lastError = readRes.castFailure()
            }
        }

        if let lastReadBack {
            return .badResponse(
                message: "Router did not confirm the updated settings for cmd \(spec.cmd)",
                rawBody: Self.jsonString(lastReadBack)
            )
        }
        return lastError ?? .badResponse(
            message: "Router did not answer verification read for cmd \(spec.cmd)",
            rawBody: Self.jsonString(postJson)
        )
    }

    /// Commits staged firewall, DHCP IP-MAC, or parental control tables (`cmd 20 APPLY_FILTER`).
    public func applyFilter() async -> RouterResult<Unit> {
        await write(
            WriteSpec<Unit>(
                cmd: 20,
                buildPayload: { _ in [:] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
    }

    private static func jsonString(_ dict: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let str = String(data: data, encoding: .utf8) else {
            return ""
        }
        return str
    }
}
