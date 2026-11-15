import Foundation
import XCTest
@testable import AirtelMonitor

@MainActor
final class RouterSessionAndWriterParityTests: XCTestCase {

    func testLoginSendsSha256OfChallengePlusPasswordAndFiresOnLoginSuccess() async {
        let callbackCount = TestBox(0)
        let env = makeTestEnv(onLoginSuccess: {
            callbackCount.withLock { $0 += 1 }
        })
        defer { env.cleanup() }

        let res = await env.session.login()
        XCTAssertTrue(res.isOk)
        XCTAssertEqual(callbackCount.get(), 1)

        let lvl = await env.session.currentAccountLevel()
        XCTAssertEqual(lvl, "1")

        let authReqs = env.transport.sent(100, method: "POST")
        XCTAssertEqual(authReqs.count, 1)
        let req = authReqs[0]
        XCTAssertEqual(req.payload["username"] as? String, "root")
        XCTAssertEqual(req.payload["isAutoUpgrade"] as? String, "0")
        XCTAssertEqual(req.payload["subcmd"] as? Int, 0)
        XCTAssertEqual(
            req.payload["passwd"] as? String,
            RouterSession.sha256Hex("CHALLENGE-1admin")
        )
    }

    func testLimitedAccessWriteDoesNotReloginOrRetryPost() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        env.transport.on(256, method: "POST") { _ in
            ["success": false, "message": "LIMITED_ACCESS"]
        }

        let res = await env.session.execute(
            cmd: 256,
            method: "POST",
            extra: ["networkMode": "4", "token": "T1"]
        )
        guard case .limitedAccess(let cmd, _) = res else {
            return XCTFail("Expected .limitedAccess, got \(res)")
        }
        XCTAssertEqual(cmd, 256)
        XCTAssertEqual(env.transport.sent(100, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(256, method: "POST").count, 1)
    }

    func testExpiredSessionOnGetTriggersSingleReloginAndRetryForNoAuthAndLoginTimeout() async {
        for expiredToken in ["NO_AUTH", "LOGIN_TIMEOUT"] {
            let env = makeTestEnv()
            defer { env.cleanup() }
            var calls = 0
            env.transport.on(3, method: "GET") { _ in
                calls += 1
                if calls == 1 {
                    return ["success": false, "message": expiredToken]
                }
                return RouterFixtures.json3()
            }

            let res = await env.session.execute(cmd: 3, method: "GET")
            guard case .ok(let json) = res else {
                return XCTFail("Expected retry success after \(expiredToken), got \(res)")
            }
            XCTAssertEqual(CodecHelpers.str(json, "main_dns"), "1.1.1.1")
            XCTAssertEqual(env.transport.sent(3, method: "GET").count, 2)
            XCTAssertEqual(env.transport.sent(100, method: "POST").count, 2)
        }
    }

    func testExpiredSessionOnPostRefreshesWriteTokenAndResendsOnce() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var posts = 0
        env.transport.on(256, method: "POST") { _ in
            posts += 1
            if posts == 1 {
                return ["success": false, "message": "NO_AUTH"]
            }
            return ["success": true, "message": "0"]
        }

        let res = await env.session.execute(
            cmd: 256,
            method: "POST",
            extra: ["networkMode": "4", "token": "OLD_TOKEN"]
        )
        XCTAssertTrue(res.isOk)

        let sentPosts = env.transport.sent(256, method: "POST")
        XCTAssertEqual(sentPosts.count, 2)
        XCTAssertEqual(sentPosts[0].payload["token"] as? String, "OLD_TOKEN")
        XCTAssertEqual(sentPosts[1].payload["token"] as? String, "T1")
        XCTAssertEqual(env.transport.sent(100, method: "POST").count, 2)
    }

    func testGetNetworkErrorRetriesOnceAndRecovers() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var attempts = 0
        env.transport.onReply(205, method: "GET") { _ in
            attempts += 1
            if attempts == 1 {
                return .error(TestRouterError.network("stale socket"))
            }
            return .json(RouterFixtures.json205Ca())
        }

        let res = await env.session.execute(cmd: 205, method: "GET")
        XCTAssertTrue(res.isOk)
        XCTAssertEqual(env.transport.sent(205, method: "GET").count, 2)
    }

    func testPostNetworkErrorIsNeverRetriedAndReturnsUnreachable() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        env.transport.onError(226, method: "POST", message: "connection reset by peer")

        let res = await env.session.execute(
            cmd: 226,
            method: "POST",
            extra: ["flightMode": "1", "token": "T0"]
        )
        guard case .unreachable(let msg) = res else {
            return XCTFail("Expected .unreachable, got \(res)")
        }
        XCTAssertTrue(msg.contains("connection reset"))
        XCTAssertEqual(env.transport.sent(226, method: "POST").count, 1)
    }

    func testEmptyReplyAllowedOnlyWhenRequested() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        env.transport.onText(211, method: "POST") { _ in "" }

        let strict = await env.session.execute(cmd: 211, method: "POST", allowEmptyReply: false)
        guard case .badResponse(let strictMsg, _) = strict else {
            return XCTFail("Expected .badResponse for empty reply when disallowed, got \(strict)")
        }
        XCTAssertTrue(strictMsg.contains("Empty"))

        let lenient = await env.session.execute(cmd: 211, method: "POST", allowEmptyReply: true)
        guard case .ok(let emptyDict) = lenient else {
            return XCTFail("Expected .ok([:]) when empty reply allowed, got \(lenient)")
        }
        XCTAssertTrue(emptyDict.isEmpty)
    }

    func testSettingsWriterFailsWithRawBodyWhenPostRejectedAndNeverRetriesPost() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        env.transport.on(367, method: "GET") { _ in ["success": true, "fw_switch": "0", "level": "low"] }
        env.transport.on(367, method: "POST") { _ in
            ["success": false, "message": "502", "detail": "firewall table full"]
        }

        let res: RouterResult<AirtelMonitor.Unit> = await env.writer.write(
            WriteSpec<AirtelMonitor.Unit>(
                cmd: 367,
                buildPayload: { _ in ["fw_switch": "1", "level": "high"] },
                verify: { _ in true },
                parseResult: { _ in AirtelMonitor.Unit() }
            )
        )
        guard case .badResponse(let msg, let rawBody) = res else {
            return XCTFail("Expected .badResponse, got \(res)")
        }
        XCTAssertEqual(msg, "502")
        XCTAssertTrue(rawBody.contains("firewall table full"))
        XCTAssertEqual(env.transport.sent(367, method: "POST").count, 1)
        XCTAssertEqual(env.transport.sent(233, method: "GET").count, 1)
    }

    func testSettingsWriterVerifyRetriesAndIncludesRawReadBackOnPersistentMismatch() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        // First verify succeeds on 3rd attempt
        var readAttempts = 0
        env.transport.on(256, method: "GET") { _ in
            readAttempts += 1
            return ["success": true, "networkMode": readAttempts >= 4 ? "4" : "1C"]
        }
        env.transport.on(256, method: "POST") { _ in ["success": true, "message": "0"] }

        let okRes: RouterResult<String> = await env.writer.write(
            WriteSpec<String>(
                cmd: 256,
                buildPayload: { _ in ["networkMode": "4"] },
                verify: { CodecHelpers.str($0, "networkMode") == "4" },
                parseResult: { CodecHelpers.str($0, "networkMode") },
                verifyRetries: 3
            )
        )
        XCTAssertEqual(okRes.value, "4")
        XCTAssertEqual(env.transport.sent(256, method: "GET").count, 4) // 1 pre-read + 3 verify reads

        // Persistent mismatch returns raw read-back JSON body
        env.transport.on(256, method: "GET") { _ in ["success": true, "networkMode": "1E", "reason": "kept_old"] }
        let failRes: RouterResult<String> = await env.writer.write(
            WriteSpec<String>(
                cmd: 256,
                buildPayload: { _ in ["networkMode": "4"] },
                verify: { CodecHelpers.str($0, "networkMode") == "4" },
                parseResult: { CodecHelpers.str($0, "networkMode") },
                verifyRetries: 2,
                skipPreRead: true
            )
        )
        guard case .badResponse(let failMsg, let raw) = failRes else {
            return XCTFail("Expected verification failure, got \(failRes)")
        }
        XCTAssertTrue(failMsg.contains("did not confirm"))
        XCTAssertTrue(raw.contains("kept_old"))
    }

    func testSettingsWriterApplyFilterPostsCmd20WithFreshToken() async throws {
        let env = makeTestEnv()
        defer { env.cleanup() }

        let res = await env.writer.applyFilter()
        XCTAssertTrue(res.isOk)

        let post = try XCTUnwrap(env.transport.sent(20, method: "POST").first)
        assertPayloadKeys(post, exactDataKeys: [])
        XCTAssertEqual(env.transport.sent(20, method: "GET").count, 0)
    }

    func testFeatureFlagRepositoryCachesUntilForcedAndResetsCleanly() async {
        let env = makeTestEnv()
        defer { env.cleanup() }

        var area = "NG0131"
        env.transport.on(80, method: "GET") { _ in
            [
                "success": true,
                "aeraId": area,
                "account_level": "1",
                "web_page_hide": "0",
                "lock_plmn_flag": "0"
            ]
        }

        let repo = FeatureFlagRepository(session: env.session)
        let first = await repo.load()
        XCTAssertEqual(first.value?.wifiPowerCmd411Available, true)
        XCTAssertEqual(env.transport.sent(80, method: "GET").count, 1)

        // Cached when not forced
        area = "NG0002"
        let cached = await repo.load(force: false)
        XCTAssertEqual(cached.value?.aeraId, "NG0131")
        XCTAssertEqual(env.transport.sent(80, method: "GET").count, 1)

        // Forced reload picks up change
        let forced = await repo.load(force: true)
        XCTAssertEqual(forced.value?.aeraId, "NG0002")
        XCTAssertEqual(forced.value?.wifiPowerCmd411Available, false)
        XCTAssertEqual(env.transport.sent(80, method: "GET").count, 2)

        repo.reset()
        XCTAssertFalse(repo.flags.isLoaded)
    }

    func testPreferencesStoreNormalizesHostClampsPollIntervalAndTracksDailyUsage() {
        let env = makeTestEnv()
        defer { env.cleanup() }

        XCTAssertEqual(AppPreferencesStore.normalizeHost(" https://192.168.8.1/ "), "192.168.8.1")
        XCTAssertEqual(AppPreferencesStore.normalizeHost(""), "192.168.1.1")

        env.preferences.setPollInterval(0.1)
        XCTAssertEqual(env.preferences.pollIntervalSeconds, 0.5, accuracy: 0.001)
        env.preferences.setPollInterval(99.0)
        XCTAssertEqual(env.preferences.pollIntervalSeconds, 10.0, accuracy: 0.001)

        let cal = Calendar(identifier: .gregorian)
        let day1 = cal.date(from: DateComponents(year: 2026, month: 11, day: 10, hour: 9))!
        let day2 = cal.date(from: DateComponents(year: 2026, month: 11, day: 11, hour: 9))!
        let day3Reset = cal.date(from: DateComponents(year: 2026, month: 11, day: 12, hour: 9))!

        env.preferences.recordDailyUsageSample(monthlyUsedMb: 1000.0, date: day1)
        env.preferences.recordDailyUsageSample(monthlyUsedMb: 1250.0, date: day1)
        env.preferences.recordDailyUsageSample(monthlyUsedMb: 1600.0, date: day2)
        env.preferences.recordDailyUsageSample(monthlyUsedMb: 400.0, date: day3Reset)

        let entries = env.preferences.dailyUsageEntries
        XCTAssertEqual(entries.count, 3)
        XCTAssertEqual(entries[0].dateIso, "2026-11-10")
        XCTAssertEqual(entries[0].dailyMb, 250.0, accuracy: 0.01)
        XCTAssertEqual(entries[0].sampleCount, 2)
        XCTAssertEqual(entries[1].dateIso, "2026-11-11")
        XCTAssertEqual(entries[1].dailyMb, 350.0, accuracy: 0.01)
        XCTAssertEqual(entries[2].dateIso, "2026-11-12")
        XCTAssertEqual(entries[2].dailyMb, 400.0, accuracy: 0.01)
    }
}
