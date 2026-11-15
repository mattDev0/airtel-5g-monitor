import Foundation
import XCTest
@testable import AirtelMonitor

struct RecordedRouterRequest {
    let cmd: Int
    let method: String
    let sessionId: String
    let payload: [String: Any]
}

enum MockRouterReply {
    case ok([String: Any])
    case raw(Int, String)
    case networkError
}

actor MockRouterTransport: RouterTransport {
    private var requests: [RecordedRouterRequest] = []
    private var handlers: [String: @Sendable (RecordedRouterRequest) async -> MockRouterReply] = [:]
    private var authChallenge = "challenge-token"
    private var assignedSessionId = "sid-mock-1"
    private var expectedPassword: String?
    private var writeTokens: [String] = ["w-tok-1", "w-tok-2", "w-tok-3", "w-tok-4", "w-tok-5"]
    private var tokenIdx = 0

    func configureAuth(
        sessionId: String = "sid-mock-1",
        challenge: String = "challenge-token",
        expectedPassword: String? = nil
    ) {
        self.assignedSessionId = sessionId
        self.authChallenge = challenge
        self.expectedPassword = expectedPassword
    }

    func setHandler(
        cmd: Int,
        method: String? = nil,
        handler: @escaping @Sendable (RecordedRouterRequest) async -> MockRouterReply
    ) {
        let key = Self.routeKey(cmd: cmd, method: method)
        handlers[key] = handler
    }

    func recordedRequests() -> [RecordedRouterRequest] {
        requests
    }

    func resetRequests() {
        requests.removeAll()
    }

    func post(url: URL, jsonBody: [String: Any], timeoutSeconds: TimeInterval) async throws -> RouterTransportResponse {
        let cmd = (jsonBody["cmd"] as? Int) ?? Int("\(jsonBody["cmd"] ?? "")") ?? -1
        let method = "\(jsonBody["method"] ?? "GET")".uppercased()
        let sid = "\(jsonBody["sessionId"] ?? "")"
        let req = RecordedRouterRequest(cmd: cmd, method: method, sessionId: sid, payload: jsonBody)
        requests.append(req)

        if let exact = handlers[Self.routeKey(cmd: cmd, method: method)] ?? handlers[Self.routeKey(cmd: cmd, method: nil)] {
            switch await exact(req) {
            case .ok(let dict):
                return RouterTransportResponse(statusCode: 200, rawBody: Self.encodeJson(dict))
            case .raw(let code, let body):
                return RouterTransportResponse(statusCode: code, rawBody: body)
            case .networkError:
                throw URLError(.cannotConnectToHost)
            }
        }

        switch cmd {
        case 232:
            return RouterTransportResponse(
                statusCode: 200,
                rawBody: Self.encodeJson(["token": authChallenge, "success": true])
            )
        case 100:
            if let expectedPassword {
                let expectedHash = RouterSession.sha256Hex(authChallenge + expectedPassword)
                if "\(jsonBody["passwd"] ?? "")" != expectedHash {
                    return RouterTransportResponse(
                        statusCode: 200,
                        rawBody: Self.encodeJson(["success": false, "message": "Invalid username or password"])
                    )
                }
            }
            return RouterTransportResponse(
                statusCode: 200,
                rawBody: Self.encodeJson(["success": true, "sessionId": assignedSessionId, "account_level": "1"])
            )
        case 233:
            let tok = writeTokens[tokenIdx % writeTokens.count]
            tokenIdx += 1
            return RouterTransportResponse(statusCode: 200, rawBody: Self.encodeJson(["token": tok, "success": true]))
        case 20:
            return RouterTransportResponse(statusCode: 200, rawBody: Self.encodeJson(["success": true]))
        default:
            return RouterTransportResponse(statusCode: 200, rawBody: Self.encodeJson(["success": true]))
        }
    }

    private static func routeKey(cmd: Int, method: String?) -> String {
        if let method {
            return "\(cmd):\(method.uppercased())"
        }
        return "\(cmd):*"
    }

    private static func encodeJson(_ dict: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }
}

final class RouterSessionAndWriterTests: XCTestCase {
    func testSha256AuthReloginAndLimitedAccess() async {
        let transport = MockRouterTransport()
        await transport.configureAuth(sessionId: "sid-v3", challenge: "chal-abc", expectedPassword: "secret")

        let expireCountBox = LockBox(value: 0)
        await transport.setHandler(cmd: 18, method: "GET") { _ in
            let current = expireCountBox.increment()
            if current == 1 {
                return .raw(200, "{\"success\":false,\"message\":\"NO_AUTH\"}")
            }
            return .ok(["rxBytes": "1024", "txBytes": "512"])
        }
        await transport.setHandler(cmd: 367, method: "GET") { _ in
            .ok(["success": false, "message": "LIMITED_ACCESS"])
        }

        let session = RouterSession(
            transport: transport,
            credentialsProvider: { RouterCredentials(host: "192.168.1.1", username: "user", password: "secret") }
        )

        let res18 = await session.execute(cmd: 18, method: "GET")
        guard case .ok(let json18) = res18 else {
            return XCTFail("Expected cmd 18 to recover via single relogin")
        }
        XCTAssertEqual("\(json18["rxBytes"] ?? "")", "1024")

        let reqs = await transport.recordedRequests()
        XCTAssertEqual(reqs.filter { $0.cmd == 232 }.count, 2)
        XCTAssertEqual(reqs.filter { $0.cmd == 100 }.count, 2)
        let cmd100 = reqs.first(where: { $0.cmd == 100 })
        XCTAssertEqual(cmd100?.payload["passwd"] as? String, RouterSession.sha256Hex("chal-abc" + "secret"))

        let res367 = await session.execute(cmd: 367, method: "GET")
        if case .limitedAccess(let c, _) = res367 {
            XCTAssertEqual(c, 367)
        } else {
            XCTFail("Expected .limitedAccess for cmd 367")
        }
    }

    func testWrongPasswordReturnsNotLoggedIn() async {
        let transport = MockRouterTransport()
        await transport.configureAuth(sessionId: "sid", challenge: "chal-1", expectedPassword: "correct-password")

        let session = RouterSession(
            transport: transport,
            credentialsProvider: { RouterCredentials(host: "192.168.1.1", username: "user", password: "wrong") }
        )

        let res = await session.execute(cmd: 18, method: "GET")
        if case .notLoggedIn = res {
            // expected
        } else {
            XCTFail("Expected .notLoggedIn on wrong password")
        }
    }

    func testSettingsWriterPreflightTokenVerifyRetriesAndNoPostRetry() async {
        let transport = MockRouterTransport()
        let readCounter = LockBox(value: 0)
        await transport.setHandler(cmd: 3, method: "GET") { _ in
            let n = readCounter.increment()
            return .ok(["dns1": n >= 4 ? "1.1.1.1" : "8.8.8.8"])
        }
        await transport.setHandler(cmd: 3, method: "POST") { _ in
            .ok(["success": true])
        }

        let session = RouterSession(
            transport: transport,
            credentialsProvider: { RouterCredentials(host: "192.168.1.1", username: "user", password: "pw") }
        )
        let writer = SettingsWriter(session: session, sleepFn: { _ in })

        // Preflight fail -> should not invoke cmd 233 or POST
        let preflightErr = await writer.write(
            WriteSpec<String>(
                cmd: 3,
                preflight: { _ in .badResponse(message: "bad input", rawBody: "") },
                buildPayload: { _ in ["dns1": "bad"] },
                verify: { _ in true },
                parseResult: { _ in "" }
            )
        )
        XCTAssertFalse(preflightErr.isOk)
        let afterPreflight = await transport.recordedRequests()
        XCTAssertEqual(afterPreflight.filter { $0.cmd == 233 }.count, 0)

        // Reset read counter cleanly for verify retry test (1 pre-read + 3 verify reads)
        readCounter.set(0)
        await transport.resetRequests()

        let okRes = await writer.write(
            WriteSpec<String>(
                cmd: 3,
                buildPayload: { _ in ["dns1": "1.1.1.1"] },
                verify: { "\($0["dns1"] ?? "")" == "1.1.1.1" },
                parseResult: { "\($0["dns1"] ?? "")" },
                verifyRetries: 4,
                verifyDelaySeconds: 0.5
            )
        )
        XCTAssertEqual(okRes.getOrNull(), "1.1.1.1")

        let writeReqs = await transport.recordedRequests().filter { $0.cmd == 3 && $0.method == "POST" }
        XCTAssertEqual(writeReqs.count, 1)
        XCTAssertEqual(writeReqs.first?.payload["token"] as? String, "w-tok-1")

        // applyFilter -> cmd 20 POST
        let filterRes = await writer.applyFilter()
        XCTAssertTrue(filterRes.isOk)
        let cmd20Reqs = await transport.recordedRequests().filter { $0.cmd == 20 }
        XCTAssertEqual(cmd20Reqs.count, 1)
    }

    @MainActor
    func testAppPreferencesStoreDefaultsKeychainAndDailyUsage() {
        let suiteName = "AppPreferencesStoreTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let keychain = InMemoryKeychainStore()
        let store = AppPreferencesStore(defaults: defaults, keychain: keychain)

        XCTAssertEqual(store.host, "192.168.1.1")
        XCTAssertEqual(store.username, "user")
        XCTAssertFalse(store.onboardingCompleted)

        store.updateConnectionSettings(host: "http://192.168.8.1/", username: "admin", password: "pw123")
        XCTAssertEqual(store.host, "192.168.8.1")
        XCTAssertEqual(store.username, "admin")
        XCTAssertEqual(store.password, "pw123")
        XCTAssertEqual(keychain.readPassword(account: "admin"), "pw123")

        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        let d1 = cal.date(from: DateComponents(year: 2026, month: 5, day: 1, hour: 12))!
        let d2 = cal.date(from: DateComponents(year: 2026, month: 5, day: 2, hour: 12))!
        store.recordDailyUsageSample(monthlyUsedMb: 1200, date: d1)
        store.recordDailyUsageSample(monthlyUsedMb: 1750, date: d2)
        // Cycle rollover (counter drops sharply -> positive usedMb on new day)
        let d3 = cal.date(from: DateComponents(year: 2026, month: 5, day: 3, hour: 12))!
        store.recordDailyUsageSample(monthlyUsedMb: 300, date: d3)

        XCTAssertEqual(store.dailyUsageEntries.count, 3)
        XCTAssertEqual(store.dailyUsageEntries[1].deltaMb, 550, accuracy: 0.01)
        XCTAssertEqual(store.dailyUsageEntries[2].deltaMb, 300, accuracy: 0.01)
    }
}

private final class LockBox: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Int

    init(value: Int) {
        self.storage = value
    }

    @discardableResult
    func increment() -> Int {
        lock.lock()
        defer { lock.unlock() }
        storage += 1
        return storage
    }

    func set(_ next: Int) {
        lock.lock()
        defer { lock.unlock() }
        storage = next
    }
}
