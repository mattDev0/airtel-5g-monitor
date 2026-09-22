import Foundation
import Combine

public final class SettingsStore: ObservableObject {
    public static let shared = SettingsStore()

    private enum Keys {
        static let routerHost = "settings_router_host"
        static let username = "settings_username"
        static let password = "settings_password"
        static let pollIntervalSeconds = "settings_poll_interval_seconds"
        static let deviceAliases = "settings_device_aliases"
    }

    private let defaults: UserDefaults

    @Published public var routerHost: String {
        didSet { defaults.set(routerHost, forKey: Keys.routerHost) }
    }

    @Published public var username: String {
        didSet { defaults.set(username, forKey: Keys.username) }
    }

    @Published public var password: String {
        didSet { defaults.set(password, forKey: Keys.password) }
    }

    @Published public var pollIntervalSeconds: Int {
        didSet { defaults.set(pollIntervalSeconds, forKey: Keys.pollIntervalSeconds) }
    }

    @Published public var deviceAliases: [String: String] {
        didSet { defaults.set(deviceAliases, forKey: Keys.deviceAliases) }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults

        self.routerHost = defaults.string(forKey: Keys.routerHost) ?? "192.168.1.1"
        self.username = defaults.string(forKey: Keys.username) ?? "root"
        self.password = defaults.string(forKey: Keys.password) ?? "admin"

        let storedInterval = defaults.integer(forKey: Keys.pollIntervalSeconds)
        self.pollIntervalSeconds = storedInterval > 0 ? storedInterval : 2

        self.deviceAliases = defaults.dictionary(forKey: Keys.deviceAliases) as? [String: String] ?? [:]
    }

    public func getAlias(for identifier: String, fallback: String) -> String {
        let key = identifier.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if let custom = deviceAliases[key], !custom.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return custom
        }
        return fallback
    }

    public func setAlias(_ alias: String, for identifier: String) {
        let key = identifier.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            deviceAliases.removeValue(forKey: key)
        } else {
            deviceAliases[key] = trimmed
        }
    }
}
