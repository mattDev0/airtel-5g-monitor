import Foundation

@MainActor
public final class FeatureFlagRepository: ObservableObject {
    private let session: RouterSession

    @Published public private(set) var flags = RouterFeatureFlags()

    public init(session: RouterSession) {
        self.session = session
    }

    @discardableResult
    public func load(force: Bool = false) async -> RouterResult<RouterFeatureFlags> {
        if !force && flags.isLoaded {
            return .ok(flags)
        }
        let res = await session.execute(cmd: 80, method: "GET", timeoutSeconds: 8.0)
        switch res {
        case .ok(let json):
            let parsed = RouterFeatureFlags.fromJson80(json)
            self.flags = parsed
            return .ok(parsed)
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

    public func reset() {
        flags = RouterFeatureFlags()
    }
}
