import Foundation
import CryptoKit

public struct RouterTransportResponse: Sendable {
    public let statusCode: Int
    public let rawBody: String

    public init(statusCode: Int, rawBody: String) {
        self.statusCode = statusCode
        self.rawBody = rawBody
    }
}

public protocol RouterTransport: Sendable {
    func post(
        url: URL,
        jsonBody: [String: Any],
        timeoutSeconds: TimeInterval
    ) async throws -> RouterTransportResponse
}

public final class URLSessionRouterTransport: RouterTransport, @unchecked Sendable {
    private let session: URLSession

    public init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 25.0
            config.timeoutIntervalForResource = 35.0
            config.waitsForConnectivity = false
            self.session = URLSession(configuration: config)
        }
    }

    public func post(
        url: URL,
        jsonBody: [String: Any],
        timeoutSeconds: TimeInterval
    ) async throws -> RouterTransportResponse {
        let data = try JSONSerialization.data(withJSONObject: jsonBody)
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = data
        req.timeoutInterval = timeoutSeconds
        req.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")

        let (respData, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let body = String(data: respData, encoding: .utf8) ?? ""
        return RouterTransportResponse(statusCode: code, rawBody: body)
    }
}

/// Manages sessionId, cmd 232->100 login, cmd 233 write token, and automatic session recovery.
public actor RouterSession {
    private let transport: RouterTransport
    private let credentialsProvider: @Sendable () async -> RouterCredentials
    private let onLoginSuccess: (@Sendable () async -> Void)?

    private var sessionId: String?
    private var accountLevelValue: String = "3"
    private var lastAuthError: String?
    private var loginTask: Task<RouterResult<Unit>, Never>?

    public init(
        transport: RouterTransport = URLSessionRouterTransport(),
        credentialsProvider: @escaping @Sendable () async -> RouterCredentials,
        onLoginSuccess: (@Sendable () async -> Void)? = nil
    ) {
        self.transport = transport
        self.credentialsProvider = credentialsProvider
        self.onLoginSuccess = onLoginSuccess
    }

    public func currentAccountLevel() -> String {
        accountLevelValue
    }

    public func resetSession() {
        sessionId = nil
        lastAuthError = nil
        accountLevelValue = "3"
    }

    public func testConnection() async -> RouterResult<Unit> {
        resetSession()
        return await login()
    }

    public func fetchWriteToken() async -> RouterResult<String> {
        let res = await execute(cmd: 233, method: "GET", timeoutSeconds: 5.0)
        switch res {
        case .ok(let json):
            let token = Self.str(json, "token")
            if token.isEmpty {
                return .badResponse(message: "Router did not issue a write token", rawBody: "")
            }
            return .ok(token)
        case .notLoggedIn(let msg):
            return .notLoggedIn(msg)
        case .limitedAccess(let cmd, let msg):
            return .limitedAccess(cmd: cmd, message: msg)
        case .unreachable(let msg):
            return .unreachable(msg)
        case .badResponse(let msg, let raw):
            return .badResponse(message: msg, rawBody: raw)
        }
    }

    public func execute(
        cmd: Int,
        method: String = "GET",
        extra: [String: Any] = [:],
        timeoutSeconds: TimeInterval = 5.0,
        allowEmptyReply: Bool = false,
        retryOnNetworkFailure: Bool = true
    ) async -> RouterResult<[String: Any]> {
        switch await executeRaw(
            cmd: cmd,
            method: method,
            extra: extra,
            timeoutSeconds: timeoutSeconds,
            retryOnNetworkFailure: retryOnNetworkFailure
        ) {
        case .ok(let raw):
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                if allowEmptyReply {
                    return .ok([:])
                }
                return .badResponse(message: "Empty response from router for cmd \(cmd)", rawBody: "")
            }
            guard let data = trimmed.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return .badResponse(message: "Malformed JSON for cmd \(cmd)", rawBody: trimmed)
            }
            let message = Self.str(json, "message")
            if message == "LIMITED_ACCESS" {
                return .limitedAccess(cmd: cmd, message: "Router account has limited access for cmd \(cmd)")
            }
            if cmd == 80 {
                let lvl = Self.str(json, "account_level")
                if !lvl.isEmpty { accountLevelValue = lvl }
            }
            return .ok(json)
        case .notLoggedIn(let msg):
            return .notLoggedIn(msg)
        case .limitedAccess(let c, let msg):
            return .limitedAccess(cmd: c, message: msg)
        case .unreachable(let msg):
            return .unreachable(msg)
        case .badResponse(let msg, let raw):
            return .badResponse(message: msg, rawBody: raw)
        }
    }

    public func executeRaw(
        cmd: Int,
        method: String = "GET",
        extra: [String: Any] = [:],
        timeoutSeconds: TimeInterval = 5.0,
        retryOnNetworkFailure: Bool = true
    ) async -> RouterResult<String> {
        if sessionId == nil {
            let loginRes = await login()
            if case .ok = loginRes {
                // continue
            } else {
                return loginRes.castFailure()
            }
        }

        let creds = await credentialsProvider()
        guard let url = Self.cgiUrl(host: creds.host) else {
            return .unreachable("Invalid router host: \(creds.host)")
        }

        var payload: [String: Any] = [
            "cmd": cmd,
            "method": method,
            "sessionId": sessionId ?? ""
        ]
        for (k, v) in extra {
            payload[k] = v
        }

        let isWrite = method.uppercased() == "POST"
        let firstTry: RouterTransportResponse
        do {
            firstTry = try await sendPost(
                url: url,
                payload: payload,
                timeout: timeoutSeconds,
                retryNetwork: !isWrite && retryOnNetworkFailure
            )
        } catch {
            return .unreachable(error.localizedDescription)
        }

        let sessionExpired = Self.isAuthExpired(firstTry.rawBody) ||
            (!isWrite && Self.isGetAuthFailure(firstTry.rawBody))

        if !sessionExpired {
            return .ok(firstTry.rawBody)
        }

        // Session expired -> force fresh login (coalesced) and retry once.
        sessionId = nil
        let relogin = await login()
        guard case .ok = relogin else {
            return relogin.castFailure()
        }

        payload["sessionId"] = sessionId ?? ""
        if isWrite && payload["token"] != nil {
            if case .ok(let freshToken) = await fetchWriteToken() {
                payload["token"] = freshToken
            }
        }

        do {
            let secondTry = try await sendPost(
                url: url,
                payload: payload,
                timeout: timeoutSeconds,
                retryNetwork: false
            )
            if Self.isAuthExpired(secondTry.rawBody) {
                return .notLoggedIn(lastAuthError ?? "Router session expired")
            }
            return .ok(secondTry.rawBody)
        } catch {
            return .unreachable(error.localizedDescription)
        }
    }

    public func login() async -> RouterResult<Unit> {
        if let existing = loginTask {
            return await existing.value
        }
        let task = Task<RouterResult<Unit>, Never> { [self] in
            await self.performLoginLocked()
        }
        loginTask = task
        let result = await task.value
        loginTask = nil
        return result
    }

    private func performLoginLocked() async -> RouterResult<Unit> {
        let creds = await credentialsProvider()
        guard let url = Self.cgiUrl(host: creds.host) else {
            return .unreachable("Invalid router host: \(creds.host)")
        }

        do {
            let tokenResp = try await transport.post(
                url: url,
                jsonBody: [
                    "cmd": 232,
                    "method": "GET",
                    "sessionId": ""
                ],
                timeoutSeconds: 5.0
            )
            guard (200...299).contains(tokenResp.statusCode),
                  let tokenData = tokenResp.rawBody.data(using: .utf8),
                  let tokenJson = try? JSONSerialization.jsonObject(with: tokenData) as? [String: Any],
                  let challenge = tokenJson["token"] as? String,
                  !challenge.isEmpty else {
                return .badResponse(message: "Router did not return login challenge token", rawBody: tokenResp.rawBody)
            }

            let pwdHash = Self.sha256Hex(challenge + creds.password)
            let clientSessionId = Self.generateSessionId()
            let authResp = try await transport.post(
                url: url,
                jsonBody: [
                    "cmd": 100,
                    "method": "POST",
                    "sessionId": clientSessionId,
                    "username": creds.username,
                    "passwd": pwdHash,
                    "isAutoUpgrade": "0",
                    "subcmd": 0
                ],
                timeoutSeconds: 5.0
            )
            guard (200...299).contains(authResp.statusCode),
                  let authData = authResp.rawBody.data(using: .utf8),
                  let authJson = try? JSONSerialization.jsonObject(with: authData) as? [String: Any] else {
                return .badResponse(message: "Malformed login response from router", rawBody: authResp.rawBody)
            }

            let ok = (authJson["success"] as? Bool == true) ||
                (Self.str(authJson, "message") == "success") ||
                (authJson["sessionId"] as? String)?.isEmpty == false

            if ok {
                let serverSid = Self.str(authJson, "sessionId")
                sessionId = serverSid.isEmpty ? clientSessionId : serverSid
                let lvl = Self.str(authJson, "account_level")
                if !lvl.isEmpty { accountLevelValue = lvl }
                lastAuthError = nil
                if let cb = onLoginSuccess {
                    await cb()
                }
                return .ok(Unit())
            } else {
                let err = Self.str(authJson, "message")
                let friendly = err.isEmpty ? "Invalid router username or password" : err
                lastAuthError = friendly
                sessionId = nil
                return .notLoggedIn(friendly)
            }
        } catch {
            return .unreachable(error.localizedDescription)
        }
    }

    private func sendPost(
        url: URL,
        payload: [String: Any],
        timeout: TimeInterval,
        retryNetwork: Bool
    ) async throws -> RouterTransportResponse {
        do {
            return try await transport.post(url: url, jsonBody: payload, timeoutSeconds: timeout)
        } catch {
            if retryNetwork {
                return try await transport.post(url: url, jsonBody: payload, timeoutSeconds: timeout)
            }
            throw error
        }
    }

    public static func sha256Hex(_ input: String) -> String {
        let digest = SHA256.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func generateSessionId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased() +
            UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    private static func cgiUrl(host: String) -> URL? {
        let clean = AppPreferencesStore.normalizeHost(host)
        return URL(string: "http://\(clean)/cgi-bin/http.cgi")
    }

    private static func isAuthExpired(_ body: String) -> Bool {
        body.contains("\"NO_AUTH\"") || body.contains("\"LOGIN_TIMEOUT\"")
    }

    private static func isGetAuthFailure(_ body: String) -> Bool {
        guard let data = body.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        return json["success"] as? Bool == false && str(json, "message") != "LIMITED_ACCESS"
    }

    public static func str(_ dict: [String: Any], _ key: String) -> String {
        guard let v = dict[key], !(v is NSNull) else { return "" }
        return "\(v)"
    }
}

public struct Unit: Equatable, Sendable {
    public init() {}
}
