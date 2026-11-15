import Foundation
import Combine

@MainActor
public final class DeviceRepository: ObservableObject {
    private let preferences: AppPreferencesStore
    @Published public private(set) var aliasVersion: Int = 0

    public init(preferences: AppPreferencesStore) {
        self.preferences = preferences
    }

    public func saveAlias(_ alias: String, for identifier: String) {
        preferences.setAlias(alias, for: identifier)
        aliasVersion &+= 1
    }

    public func clearAlias(for identifier: String) {
        preferences.setAlias("", for: identifier)
        aliasVersion &+= 1
    }

    public func enrichDevices(_ devices: [DeviceItem]) -> [DeviceItem] {
        devices.map { dev in
            let id = dev.mac.isEmpty ? dev.ip : dev.mac
            var copy = dev
            copy.hasCustomAlias = preferences.hasAlias(for: id)
            copy.alias = preferences.getAlias(for: id, fallback: dev.hostname)
            return copy
        }
    }
}
