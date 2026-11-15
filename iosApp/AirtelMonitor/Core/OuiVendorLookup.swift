import Foundation

public struct OuiMatch: Equatable {
    public let vendor: String?
    public let isPrivateMac: Bool

    public init(vendor: String?, isPrivateMac: Bool) {
        self.vendor = vendor
        self.isPrivateMac = isPrivateMac
    }
}

/// Offline IEEE OUI MAC vendor lookup backed by bundled `Resources/oui.csv`.
public final class OuiVendorLookup {
    public static let empty = OuiVendorLookup(prefixMap: [:])

    private let prefixMap: [String: String]

    public var count: Int { prefixMap.count }

    public init(prefixMap: [String: String]) {
        self.prefixMap = prefixMap
    }

    public static func fromBundle(_ bundle: Bundle = .main, resourceName: String = "oui") -> OuiVendorLookup {
        guard let url = bundle.url(forResource: resourceName, withExtension: "csv"),
              let content = try? String(contentsOf: url, encoding: .utf8) else {
            return .empty
        }
        return fromLines(content.split(whereSeparator: \.isNewline).map(String.init))
    }

    public static func fromLines(_ lines: [String]) -> OuiVendorLookup {
        OuiVendorLookup(prefixMap: parseLines(lines))
    }

    public func lookup(_ mac: String) -> OuiMatch {
        let clean = Self.stripHex(mac)
        guard clean.count >= 6 else {
            return OuiMatch(vendor: nil, isPrivateMac: false)
        }
        let prefix = String(clean.prefix(6))
        let vendor = prefixMap[prefix]
        let isPrivate = vendor == nil && Self.isLocallyAdministeredUnicast(clean)
        return OuiMatch(vendor: vendor, isPrivateMac: isPrivate)
    }

    public static func parseLines(_ lines: [String]) -> [String: String] {
        var map: [String: String] = [:]
        map.reserveCapacity(lines.count)
        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.hasPrefix("#") { continue }
            guard let commaIdx = line.firstIndex(of: ",") else { continue }
            let prefix = String(line[..<commaIdx]).uppercased()
            let vendor = String(line[line.index(after: commaIdx)...]).trimmingCharacters(in: .whitespacesAndNewlines)
            if prefix.count == 6, prefix.allSatisfy({ $0.hexDigitValue != nil }), !vendor.isEmpty {
                map[prefix] = vendor
            }
        }
        return map
    }

    public static func isLocallyAdministeredUnicast(_ mac: String) -> Bool {
        let clean = stripHex(mac)
        guard clean.count >= 2, let firstOctet = Int(clean.prefix(2), radix: 16) else {
            return false
        }
        return (firstOctet & 0x03) == 0x02
    }

    private static func stripHex(_ mac: String) -> String {
        String(mac.uppercased().filter { $0.hexDigitValue != nil })
    }
}
