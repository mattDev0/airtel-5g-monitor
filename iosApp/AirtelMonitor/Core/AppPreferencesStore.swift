import Foundation
import Combine

public enum ThemePreference: String, CaseIterable, Codable, Identifiable {
    case system
    case light
    case dark

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }
}

public enum SpeedUnitPreference: String, CaseIterable, Codable, Identifiable {
    case mbps
    case mbs

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .mbps: return "Mbps"
        case .mbs: return "MB/s"
        }
    }
}

public struct RouterCredentials: Sendable, Equatable {
    public let host: String
    public let username: String
    public let password: String

    public init(host: String, username: String, password: String) {
        self.host = host
        self.username = username
        self.password = password
    }
}

/// Persisted settings + Keychain credential storage + legacy SettingsStore migration.
@MainActor
public final class AppPreferencesStore: ObservableObject {
    public static let shared = AppPreferencesStore()

    private enum Keys {
        static let routerHost = "settings_router_host"
        static let username = "settings_username"
        static let legacyPassword = "settings_password"
        static let pollIntervalSeconds = "settings_poll_interval_seconds"
        static let deviceAliases = "settings_device_aliases"
        static let themePreference = "settings_theme_preference"
        static let speedUnit = "settings_speed_unit"
        static let maskSensitive = "settings_mask_sensitive"
        static let onboardingCompleted = "settings_onboarding_completed"
        static let hasSavedPassword = "settings_has_saved_password"
        static let dailyUsageJson = "settings_daily_usage_json"
        static let signalThresholdDbm = "settings_signal_threshold_dbm"
        static let alertMonthlyLimitPct = "settings_alert_monthly_limit_pct"
        static let keychainAccount = "router_admin_password"
    }

    private let defaults: UserDefaults
    private let keychain: KeychainStoring

    @Published public private(set) var routerHost: String
    @Published public private(set) var username: String
    @Published public private(set) var password: String
    @Published public private(set) var hasSavedPassword: Bool
    @Published public private(set) var onboardingCompleted: Bool
    @Published public private(set) var pollIntervalSeconds: Double
    @Published public private(set) var themePreference: ThemePreference
    @Published public private(set) var speedUnitPreference: SpeedUnitPreference
    @Published public private(set) var maskSensitiveInfo: Bool
    @Published public private(set) var signalThresholdDbm: Int
    @Published public private(set) var alertMonthlyLimitPct: Int
    @Published public private(set) var deviceAliases: [String: String]
    @Published public private(set) var dailyUsageEntries: [DailyUsageEntry]

    public init(
        defaults: UserDefaults = .standard,
        keychain: KeychainStoring = SystemKeychainStore()
    ) {
        self.defaults = defaults
        self.keychain = keychain

        let hostRaw = defaults.string(forKey: Keys.routerHost)
        self.routerHost = Self.normalizeHost(hostRaw ?? "192.168.1.1")
        let userRaw = defaults.string(forKey: Keys.username)
        self.username = (userRaw?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
            ? userRaw!.trimmingCharacters(in: .whitespacesAndNewlines)
            : "user"

        // Migrate any legacy UserDefaults plaintext password into Keychain on init.
        let legacyPw = defaults.string(forKey: Keys.legacyPassword)
        var keychainPw = keychain.readPassword(account: Keys.keychainAccount)
        var migratedLegacy = false
        if let legacyPw, !legacyPw.isEmpty {
            if keychainPw == nil || keychainPw?.isEmpty == true {
                keychain.savePassword(legacyPw, account: Keys.keychainAccount)
                keychainPw = legacyPw
            }
            defaults.removeObject(forKey: Keys.legacyPassword)
            migratedLegacy = true
        }

        let resolvedPw = keychainPw ?? ""
        self.password = resolvedPw

        let storedHasPw = defaults.object(forKey: Keys.hasSavedPassword) as? Bool
        let effectiveHasPw = storedHasPw ?? !resolvedPw.isEmpty
        self.hasSavedPassword = effectiveHasPw
        if storedHasPw == nil && effectiveHasPw {
            defaults.set(true, forKey: Keys.hasSavedPassword)
        }

        // Phase 7.2 parity: upgrading installs with saved credentials skip onboarding.
        let storedOnboarding = defaults.object(forKey: Keys.onboardingCompleted) as? Bool
        let hadLegacyInstall = hostRaw != nil || userRaw != nil || migratedLegacy
        let effectiveOnboarding = storedOnboarding ?? (effectiveHasPw || hadLegacyInstall)
        self.onboardingCompleted = effectiveOnboarding
        if storedOnboarding == nil && effectiveOnboarding {
            defaults.set(true, forKey: Keys.onboardingCompleted)
        }

        let interval = defaults.double(forKey: Keys.pollIntervalSeconds)
        self.pollIntervalSeconds = interval > 0 ? min(max(interval, 0.5), 10.0) : 2.0

        self.themePreference = ThemePreference(rawValue: defaults.string(forKey: Keys.themePreference) ?? "") ?? .system
        self.speedUnitPreference = SpeedUnitPreference(rawValue: defaults.string(forKey: Keys.speedUnit) ?? "") ?? .mbps
        self.maskSensitiveInfo = defaults.object(forKey: Keys.maskSensitive) as? Bool ?? false
        let threshold = defaults.object(forKey: Keys.signalThresholdDbm) as? Int ?? -100
        self.signalThresholdDbm = min(max(threshold, -130), -60)
        let pct = defaults.object(forKey: Keys.alertMonthlyLimitPct) as? Int ?? 90
        self.alertMonthlyLimitPct = min(max(pct, 50), 100)

        self.deviceAliases = defaults.dictionary(forKey: Keys.deviceAliases) as? [String: String] ?? [:]

        if let jsonStr = defaults.string(forKey: Keys.dailyUsageJson),
           let data = jsonStr.data(using: .utf8),
           let decoded = try? JSONDecoder().decode([DailyUsageEntry].self, from: data) {
            self.dailyUsageEntries = decoded.sorted { $0.dateIso < $1.dateIso }
        } else {
            self.dailyUsageEntries = []
        }
    }

    public var currentCredentials: RouterCredentials {
        RouterCredentials(
            host: routerHost,
            username: username,
            password: password.isEmpty ? "admin" : password
        )
    }

    public func updateConnectionSettings(
        host: String,
        username: String,
        password: String?,
        pollIntervalSeconds: Double? = nil,
        markOnboardingCompleted: Bool? = nil
    ) {
        let cleanHost = Self.normalizeHost(host)
        let cleanUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        self.routerHost = cleanHost
        self.username = cleanUser.isEmpty ? "root" : cleanUser
        defaults.set(self.routerHost, forKey: Keys.routerHost)
        defaults.set(self.username, forKey: Keys.username)

        if let pw = password {
            self.password = pw
            if pw.isEmpty {
                keychain.deletePassword(account: Keys.keychainAccount)
                self.hasSavedPassword = false
                defaults.set(false, forKey: Keys.hasSavedPassword)
            } else {
                keychain.savePassword(pw, account: Keys.keychainAccount)
                self.hasSavedPassword = true
                defaults.set(true, forKey: Keys.hasSavedPassword)
            }
        }

        if let poll = pollIntervalSeconds {
            let clamped = min(max(poll, 0.5), 10.0)
            self.pollIntervalSeconds = clamped
            defaults.set(clamped, forKey: Keys.pollIntervalSeconds)
        }

        if let done = markOnboardingCompleted {
            setOnboardingCompleted(done)
        }
    }

    public func setPollInterval(_ seconds: Double) {
        let clamped = min(max(seconds, 0.5), 10.0)
        self.pollIntervalSeconds = clamped
        defaults.set(clamped, forKey: Keys.pollIntervalSeconds)
    }

    public func setOnboardingCompleted(_ completed: Bool) {
        self.onboardingCompleted = completed
        defaults.set(completed, forKey: Keys.onboardingCompleted)
    }

    public func markOnboardingCompletedIfSavedCredentials() {
        if !onboardingCompleted && hasSavedPassword {
            setOnboardingCompleted(true)
        }
    }

    public func setThemePreference(_ pref: ThemePreference) {
        self.themePreference = pref
        defaults.set(pref.rawValue, forKey: Keys.themePreference)
    }

    public func setSpeedUnitPreference(_ unit: SpeedUnitPreference) {
        self.speedUnitPreference = unit
        defaults.set(unit.rawValue, forKey: Keys.speedUnit)
    }

    public func setMaskSensitiveInfo(_ mask: Bool) {
        self.maskSensitiveInfo = mask
        defaults.set(mask, forKey: Keys.maskSensitive)
    }

    public func getAlias(for identifier: String, fallback: String) -> String {
        let key = identifier.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !key.isEmpty else { return fallback }
        if let custom = deviceAliases[key], !custom.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return custom
        }
        return fallback
    }

    public func hasAlias(for identifier: String) -> Bool {
        let key = identifier.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !key.isEmpty else { return false }
        return deviceAliases[key]?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    public func setAlias(_ alias: String, for identifier: String) {
        let key = identifier.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !key.isEmpty else { return }
        let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
        var next = deviceAliases
        if trimmed.isEmpty {
            next.removeValue(forKey: key)
        } else {
            next[key] = trimmed
        }
        self.deviceAliases = next
        defaults.set(next, forKey: Keys.deviceAliases)
    }

    public func recordDailyUsageSample(monthlyUsedMb: Double, date: Date = Date()) {
        guard monthlyUsedMb.isFinite, monthlyUsedMb >= 0 else { return }
        let iso = Self.dayIsoString(date)
        var list = dailyUsageEntries
        if let idx = list.firstIndex(where: { $0.dateIso == iso }) {
            let prev = list[idx]
            let baseline = monthlyUsedMb < prev.baselineMb ? 0.0 : prev.baselineMb
            let delta = max(0.0, monthlyUsedMb - baseline)
            list[idx] = DailyUsageEntry(
                dateIso: iso,
                dailyMb: delta,
                endTotalMb: monthlyUsedMb,
                baselineMb: baseline,
                sampleCount: prev.sampleCount + 1
            )
        } else {
            let previousTotal = list.last?.endTotalMb ?? monthlyUsedMb
            let baseline = monthlyUsedMb < previousTotal ? 0.0 : previousTotal
            list.append(DailyUsageEntry(
                dateIso: iso,
                dailyMb: max(0.0, monthlyUsedMb - baseline),
                endTotalMb: monthlyUsedMb,
                baselineMb: baseline,
                sampleCount: 1
            ))
        }
        if list.count > 62 {
            list = Array(list.suffix(62))
        }
        persistDailyUsage(list)
    }

    public func clearDailyUsageHistory() {
        persistDailyUsage([])
    }

    private func persistDailyUsage(_ entries: [DailyUsageEntry]) {
        self.dailyUsageEntries = entries
        if let data = try? JSONEncoder().encode(entries),
           let str = String(data: data, encoding: .utf8) {
            defaults.set(str, forKey: Keys.dailyUsageJson)
        }
    }

    public static func normalizeHost(_ raw: String) -> String {
        let trimmed = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "http://", with: "", options: .caseInsensitive)
            .replacingOccurrences(of: "https://", with: "", options: .caseInsensitive)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return trimmed.isEmpty ? "192.168.1.1" : trimmed
    }

    private static func dayIsoString(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
}
