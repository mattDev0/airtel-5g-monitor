import Foundation
import Security

public protocol KeychainStoring: AnyObject {
    func readPassword(account: String) -> String?
    @discardableResult
    func savePassword(_ password: String, account: String) -> Bool
    @discardableResult
    func deletePassword(account: String) -> Bool
}

/// Keychain Services store for router admin credentials, keeping secrets out of UserDefaults.
public final class SystemKeychainStore: KeychainStoring {
    private let service: String

    public init(service: String = "dev.mattdev0.airtelmonitor.credentials") {
        self.service = service
    }

    public func readPassword(account: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    public func savePassword(_ password: String, account: String) -> Bool {
        let encoded = Data(password.utf8)
        let baseQuery: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account
        ]
        let attributes: [CFString: Any] = [
            kSecValueData: encoded,
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return true
        }
        var addQuery = baseQuery
        addQuery[kSecValueData] = encoded
        addQuery[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess
    }

    @discardableResult
    public func deletePassword(account: String) -> Bool {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}

public final class InMemoryKeychainStore: KeychainStoring {
    private var storage: [String: String] = [:]

    public init() {}

    public func readPassword(account: String) -> String? {
        storage[account]
    }

    @discardableResult
    public func savePassword(_ password: String, account: String) -> Bool {
        storage[account] = password
        return true
    }

    @discardableResult
    public func deletePassword(account: String) -> Bool {
        storage.removeValue(forKey: account)
        return true
    }
}
