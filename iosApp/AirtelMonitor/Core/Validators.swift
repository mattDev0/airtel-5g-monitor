import Foundation

public enum BandMask {
    public static func decode(_ hex: String?) -> Set<Int> {
        var bands = Set<Int>()
        let digits = Array((hex ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased().reversed())
        for (i, ch) in digits.enumerated() {
            guard let nibble = ch.hexDigitValue else { return [] }
            for bit in 0..<4 where nibble & (1 << bit) != 0 {
                bands.insert(i * 4 + bit + 1)
            }
        }
        return bands
    }

    public static func encode(_ bands: Set<Int>) -> String {
        guard let top = bands.filter({ $0 >= 1 }).max() else { return "" }
        var nibbles = [Int](repeating: 0, count: (top - 1) / 4 + 1)
        for band in bands where band >= 1 {
            nibbles[(band - 1) / 4] |= 1 << ((band - 1) % 4)
        }
        let hex = nibbles.reversed().map { String($0, radix: 16) }.joined()
        let trimmed = hex.drop(while: { $0 == "0" })
        return String(trimmed)
    }
}

public enum NetworkFormatters {
    public static func formatThroughput(_ mbps: Double, unit: SpeedUnitPreference) -> (String, String) {
        switch unit {
        case .mbps:
            return (String(format: "%.2f", mbps), "Mbps")
        case .mbs:
            return (String(format: "%.2f", mbps / 8.0), "MB/s")
        }
    }

    public static func formatPeakThroughput(_ mbps: Double, unit: SpeedUnitPreference) -> String {
        switch unit {
        case .mbps:
            return String(format: "Peak %.1fM", mbps)
        case .mbs:
            return String(format: "Peak %.2f MB/s", mbps / 8.0)
        }
    }

    public static func formatDataMb(_ mb: Double) -> String {
        let clamped = max(0.0, mb)
        if clamped >= 1_048_576.0 {
            return String(format: "%.2f TB", clamped / 1_048_576.0)
        } else if clamped >= 1024.0 {
            return String(format: "%.2f GB", clamped / 1024.0)
        } else {
            return String(format: "%.1f MB", clamped)
        }
    }

    public static func isIpv4(_ value: String) -> Bool {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4 else { return false }
        for p in parts {
            guard let n = Int(p), (0...255).contains(n), !(p.count > 1 && p.hasPrefix("0")) else {
                return false
            }
        }
        return true
    }

    public static func ipv4ToUInt32(_ ip: String) -> UInt32 {
        var out: UInt32 = 0
        for part in ip.split(separator: ".") {
            out = (out << 8) | (UInt32(part) ?? 0)
        }
        return out
    }

    public static func maskMac(_ mac: String) -> String {
        let clean = mac.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = clean.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 6 else { return clean }
        return "\(parts[0]):\(parts[1]):**:**:\(parts[4]):\(parts[5])"
    }

    public static func formatPollInterval(_ seconds: Double) -> String {
        if seconds.truncatingRemainder(dividingBy: 1.0) == 0 {
            return "\(Int(seconds))s"
        }
        return String(format: "%.1fs", seconds)
    }
}

public enum SensitiveFormatters {
    public static func maskIdentifier(_ raw: String, visibleSuffix: Int = 4) -> String {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty || clean == "-" || clean.caseInsensitiveCompare("REDACTED") == .orderedSame {
            return clean
        }
        let suffixCount = max(0, visibleSuffix)
        if clean.count <= suffixCount {
            return String(repeating: "•", count: max(4, clean.count))
        }
        let maskedCount = max(4, clean.count - suffixCount)
        return String(repeating: "•", count: maskedCount) + clean.suffix(suffixCount)
    }

    public static func validateRouterPassword(oldPassword: String, newPassword: String, confirmPassword: String) -> String? {
        if oldPassword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Current password is required"
        }
        if newPassword.isEmpty {
            return "New password cannot be blank"
        }
        if newPassword.contains(where: { $0.isWhitespace }) {
            return "Password cannot contain spaces"
        }
        if newPassword.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value > 0x7E }) {
            return "Password must use printable ASCII characters only"
        }
        if !(8...63).contains(newPassword.count) {
            return "Password must be 8 to 63 characters"
        }
        if newPassword == oldPassword {
            return "New password must differ from current password"
        }
        let hasUpper = newPassword.contains(where: { ("A"..."Z").contains($0) })
        let hasLower = newPassword.contains(where: { ("a"..."z").contains($0) })
        let hasDigit = newPassword.contains(where: { ("0"..."9").contains($0) })
        let hasSymbol = newPassword.contains(where: { !$0.isLetter && !$0.isNumber })
        if !hasUpper || !hasLower || !hasDigit || !hasSymbol {
            return "Include uppercase, lowercase, number, and special character"
        }
        if newPassword != confirmPassword {
            return "New password confirmation does not match"
        }
        return nil
    }

    public static func validateLogSizeKb(_ raw: String) -> String? {
        guard let v = Int(raw.trimmingCharacters(in: .whitespaces)) else {
            return "Log size must be a whole number (KB)"
        }
        if !(1...2000).contains(v) {
            return "Log size must be between 1 and 2000 KB"
        }
        return nil
    }

    public static func formatUptimeSeconds(_ raw: String) -> String {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "--" }
        guard let total = Int(clean), total >= 0 else { return clean }
        let days = total / 86_400
        let hours = (total % 86_400) / 3_600
        let minutes = (total % 3_600) / 60
        if days > 0 {
            return String(format: "%dd %02dh %02dm", days, hours, minutes)
        } else if hours > 0 {
            return String(format: "%dh %02dm", hours, minutes)
        } else {
            return String(format: "%dm %02ds", minutes, total % 60)
        }
    }
}

public enum WifiAndLanValidators {
    public static func validateSsid(_ ssid: String, forbiddenSsids: [String] = []) -> String? {
        if ssid.isEmpty { return "SSID cannot be blank" }
        if ssid.first?.isWhitespace == true || ssid.last?.isWhitespace == true {
            return "SSID cannot start or end with whitespace"
        }
        if ssid.contains(";") || ssid.contains("\\") {
            return "SSID cannot contain ';' or '\\'"
        }
        let utf8Bytes = ssid.utf8.count
        if utf8Bytes > 32 {
            return "SSID must be 32 UTF-8 bytes or fewer (currently \(utf8Bytes))"
        }
        if forbiddenSsids.contains(where: { !$0.isEmpty && $0 == ssid }) {
            return "SSID is not allowed by the router"
        }
        return nil
    }

    public static func validateWifiPassword(
        _ key: String,
        authenticationType: String,
        forbiddenKeys: [String] = []
    ) -> String? {
        if authenticationType == "0" { return nil }
        if !(8...63).contains(key.count) {
            return "Wi-Fi password must be 8–63 characters"
        }
        if key.contains(where: { $0.isWhitespace || $0 == "\"" || $0 == ";" || $0 == "\\" }) {
            return "Wi-Fi password cannot contain spaces, '\"', ';', or '\\'"
        }
        if forbiddenKeys.contains(where: { !$0.isEmpty && $0 == key }) {
            return "Password is too common or forbidden by the router"
        }
        return nil
    }

    public static func validateDhcpSettings(
        lanIp: String,
        netMask: String,
        dhcpEnabled: Bool,
        ipBegin: String,
        ipEnd: String
    ) -> String? {
        if !NetworkFormatters.isIpv4(lanIp) { return "Invalid router LAN IP address" }
        if !isValidSubnetMask(netMask) { return "Invalid subnet mask" }
        if !dhcpEnabled { return nil }
        if !NetworkFormatters.isIpv4(ipBegin) { return "Invalid DHCP start IP" }
        if !NetworkFormatters.isIpv4(ipEnd) { return "Invalid DHCP end IP" }

        let lanNum = NetworkFormatters.ipv4ToUInt32(lanIp)
        let maskNum = NetworkFormatters.ipv4ToUInt32(netMask)
        let beginNum = NetworkFormatters.ipv4ToUInt32(ipBegin)
        let endNum = NetworkFormatters.ipv4ToUInt32(ipEnd)

        if beginNum > endNum { return "DHCP start IP must be <= end IP" }
        if (beginNum & maskNum) != (lanNum & maskNum) || (endNum & maskNum) != (lanNum & maskNum) {
            return "DHCP range must be in the same subnet as \(lanIp)"
        }
        if (beginNum...endNum).contains(lanNum) {
            return "DHCP pool cannot include the router IP (\(lanIp))"
        }
        return nil
    }

    public static func isValidSubnetMask(_ mask: String) -> Bool {
        guard NetworkFormatters.isIpv4(mask) else { return false }
        let value = NetworkFormatters.ipv4ToUInt32(mask)
        if value == 0 || value == UInt32.max { return false }
        let inverted = ~value
        return ((inverted &+ 1) & inverted) == 0
    }

    public static func validatePin(_ pin: String) -> String? {
        if pin.isEmpty { return "PIN cannot be blank" }
        if !pin.allSatisfy({ ("0"..."9").contains($0) }) { return "PIN must contain digits only" }
        if !(4...8).contains(pin.count) { return "PIN must be 4–8 digits" }
        return nil
    }

    public static func validatePuk(_ puk: String) -> String? {
        if puk.isEmpty { return "PUK cannot be blank" }
        if !puk.allSatisfy({ ("0"..."9").contains($0) }) { return "PUK must contain digits only" }
        if puk.count != 8 { return "PUK must be exactly 8 digits" }
        return nil
    }
}

public enum SmsValidators {
    private static let phoneRegex = try! NSRegularExpression(pattern: #"^[\d+]\d{1,30}$"#)

    public static func validatePhoneNumber(_ rawPhone: String) -> String? {
        let clean = rawPhone.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "Phone number cannot be blank" }
        let range = NSRange(clean.startIndex..., in: clean)
        if phoneRegex.firstMatch(in: clean, range: range) == nil {
            return "Enter a valid recipient number (optional '+' followed by digits)"
        }
        return nil
    }

    public static func weightedSmsLength(_ content: String) -> Int {
        var total = 0
        for codeUnit in content.utf16 {
            total += codeUnit < 0x007F ? 1 : 2
        }
        return total
    }

    public static func validateSmsBody(_ content: String, maxWeightedLength: Int = 4096) -> String? {
        if content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Message body cannot be empty"
        }
        if content.unicodeScalars.contains(where: { $0.value > 0xFFFF }) {
            return "Emoji and 4-byte Unicode characters are not supported by the router"
        }
        let effectiveLimit = min(max(maxWeightedLength, 1), 4096)
        let weight = weightedSmsLength(content)
        if weight > effectiveLimit {
            return "Message is too long (\(weight) / \(effectiveLimit) units)"
        }
        return nil
    }

    public static func validateSmsMaxSize(_ raw: String) -> String? {
        guard let v = Int(raw.trimmingCharacters(in: .whitespaces)) else {
            return "Capacity must be a whole number"
        }
        if !(50...1000).contains(v) { return "Storage capacity must be between 50 and 1000" }
        return nil
    }

    public static func validateSmscNumber(_ raw: String, required: Bool) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            return required ? "SMSC number cannot be blank" : nil
        }
        let range = NSRange(clean.startIndex..., in: clean)
        if phoneRegex.firstMatch(in: clean, range: range) == nil {
            return "Enter a valid SMSC number (optional '+' followed by digits)"
        }
        return nil
    }
}

public enum UssdDecodedMessage: Equatable {
    case empty
    case timeout
    case carrierError
    case text(String)
}

public enum Phase71Validators {
    private static let apnRegex = try! NSRegularExpression(
        pattern: #"^(?!rac|lac|sgsn|\.)(?!.*\.gprs$)(?!.*\.\.)(?!.*-\.)(?!.*\.-)[.a-zA-Z0-9-\s]{1,63}$"#
    )
    private static let ipv6GroupRegex = try! NSRegularExpression(pattern: #"^[0-9A-Fa-f]{1,4}$"#)

    public static func normalizeMac(_ raw: String) -> String? {
        let compact = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: ".", with: "")
        guard compact.count == 12, compact.allSatisfy({ $0.hexDigitValue != nil }) else {
            return nil
        }
        let upper = compact.uppercased()
        var parts: [String] = []
        var idx = upper.startIndex
        for _ in 0..<6 {
            let next = upper.index(idx, offsetBy: 2)
            parts.append(String(upper[idx..<next]))
            idx = next
        }
        return parts.joined(separator: ":")
    }

    public static func validateUnicastMac(_ raw: String) -> String? {
        guard let normalized = normalizeMac(raw) else {
            return "Enter a valid MAC address (AA:BB:CC:DD:EE:FF)"
        }
        if normalized == "00:00:00:00:00:00" { return "All-zero MAC address is not allowed" }
        if normalized == "FF:FF:FF:FF:FF:FF" { return "Broadcast MAC address is not allowed" }
        let firstOctet = Int(normalized.prefix(2), radix: 16) ?? 0
        if (firstOctet & 0x01) != 0 {
            return "Multicast MAC addresses are not allowed"
        }
        return nil
    }

    public static func isIpv6Literal(_ raw: String) -> Bool {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.contains(":"), !value.contains("%") else { return false }
        let doubleColonParts = value.components(separatedBy: "::")
        let doubleColonCount = doubleColonParts.count - 1
        if doubleColonCount > 1 { return false }

        let leftGroups = doubleColonParts[0].isEmpty ? [] : doubleColonParts[0].split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        let rightGroups: [String]
        if doubleColonCount == 1 && !doubleColonParts[1].isEmpty {
            rightGroups = doubleColonParts[1].split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        } else {
            rightGroups = []
        }

        let lastRight = rightGroups.last ?? leftGroups.last
        let hasEmbeddedV4 = lastRight?.contains(".") == true
        if hasEmbeddedV4, let v4 = lastRight, !NetworkFormatters.isIpv4(v4) {
            return false
        }

        let checkLeft = (hasEmbeddedV4 && rightGroups.isEmpty) ? Array(leftGroups.dropLast()) : leftGroups
        let checkRight = (hasEmbeddedV4 && !rightGroups.isEmpty) ? Array(rightGroups.dropLast()) : rightGroups
        let allGroupsOk = (checkLeft + checkRight).allSatisfy { grp in
            let r = NSRange(grp.startIndex..., in: grp)
            return ipv6GroupRegex.firstMatch(in: grp, range: r) != nil
        }
        if !allGroupsOk { return false }

        let groupUnits = checkLeft.count + checkRight.count + (hasEmbeddedV4 ? 2 : 0)
        return doubleColonCount == 1 ? groupUnits < 8 : groupUnits == 8
    }

    public static func validateIpOrRange(
        _ raw: String,
        ippro: String,
        allowRange: Bool = false,
        required: Bool = true
    ) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return required ? "IP address cannot be blank" : nil }
        if allowRange && clean.contains("-") {
            let parts = clean.split(separator: "-", omittingEmptySubsequences: false).map { String($0).trimmingCharacters(in: .whitespaces) }
            guard parts.count == 2 else { return "Enter IP range as start-end" }
            if let err = validateSingleIp(parts[0], ippro: ippro) { return err }
            if let err = validateSingleIp(parts[1], ippro: ippro) { return err }
            let startV4 = NetworkFormatters.isIpv4(parts[0])
            let endV4 = NetworkFormatters.isIpv4(parts[1])
            if startV4 != endV4 { return "Start and end IPs must use the same IP family" }
            if startV4 && NetworkFormatters.ipv4ToUInt32(parts[0]) > NetworkFormatters.ipv4ToUInt32(parts[1]) {
                return "Range start IP must be <= end IP"
            }
            return nil
        }
        return validateSingleIp(clean, ippro: ippro)
    }

    private static func validateSingleIp(_ clean: String, ippro: String) -> String? {
        switch ippro.uppercased() {
        case "IPV6":
            if !isIpv6Literal(clean) { return "Enter a valid IPv6 address" }
            if clean == "::" { return "Unspecified IPv6 address '::' is not allowed" }
            return nil
        case "IPV4V6":
            if NetworkFormatters.isIpv4(clean) {
                if clean == "0.0.0.0" || clean == "255.255.255.255" {
                    return "Enter a valid unicast IPv4 address"
                }
                return nil
            } else if isIpv6Literal(clean) {
                return clean == "::" ? "Unspecified IPv6 address '::' is not allowed" : nil
            } else {
                return "Enter a valid IPv4 or IPv6 address"
            }
        default:
            if !NetworkFormatters.isIpv4(clean) { return "Enter a valid IPv4 address" }
            if clean == "0.0.0.0" || clean == "255.255.255.255" {
                return "Enter a valid unicast IPv4 address"
            }
            return nil
        }
    }

    public static func validatePortOrRange(_ raw: String, required: Bool = false) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return required ? "Port cannot be blank" : nil }
        let sep: Character? = clean.contains(":") ? ":" : (clean.contains("-") ? "-" : nil)
        guard let sep else {
            guard let p = Int(clean), (1...65535).contains(p) else {
                return "Port must be between 1 and 65535"
            }
            return nil
        }
        let parts = clean.split(separator: sep, omittingEmptySubsequences: false).map { String($0).trimmingCharacters(in: .whitespaces) }
        guard parts.count == 2,
              let start = Int(parts[0]), let end = Int(parts[1]),
              (1...65535).contains(start), (1...65535).contains(end) else {
            return "Ports must be between 1 and 65535"
        }
        if start > end { return "Start port must be <= end port" }
        return nil
    }

    public static func normalizePortRange(_ raw: String, separator: Character) -> String {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "" }
        let sep: Character? = clean.contains(":") ? ":" : (clean.contains("-") ? "-" : nil)
        guard let sep else {
            return Int(clean).map(String.init) ?? clean
        }
        let parts = clean.split(separator: sep, omittingEmptySubsequences: false).map { String($0).trimmingCharacters(in: .whitespaces) }
        guard parts.count == 2 else { return clean }
        return parts[0] == parts[1] ? parts[0] : "\(parts[0])\(separator)\(parts[1])"
    }

    public static func findPortConflict(portExpr: String, reservedPorts: Set<Int>) -> Int? {
        guard !reservedPorts.isEmpty else { return nil }
        let clean = portExpr.trimmingCharacters(in: .whitespacesAndNewlines)
        let sep: Character? = clean.contains(":") ? ":" : (clean.contains("-") ? "-" : nil)
        guard let sep else {
            guard let p = Int(clean) else { return nil }
            return reservedPorts.contains(p) ? p : nil
        }
        let parts = clean.split(separator: sep, omittingEmptySubsequences: false).map { String($0).trimmingCharacters(in: .whitespaces) }
        guard parts.count == 2, let start = Int(parts[0]), let end = Int(parts[1]), start <= end else { return nil }
        return reservedPorts.first(where: { (start...end).contains($0) })
    }

    public static func validateWpsPin(_ raw: String) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count == 8, clean.allSatisfy({ ("0"..."9").contains($0) }) else {
            return "WPS PIN must be exactly 8 digits"
        }
        let digits = clean.compactMap { Int(String($0)) }
        let sum = 3 * (digits[0] + digits[2] + digits[4] + digits[6]) +
            digits[1] + digits[3] + digits[5] + digits[7]
        if sum % 10 != 0 {
            return "Invalid WPS PIN checksum"
        }
        return nil
    }

    public static func validateApnString(_ raw: String, fieldLabel: String) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "\(fieldLabel) cannot be blank" }
        let range = NSRange(clean.startIndex..., in: clean)
        if clean.caseInsensitiveCompare("auto") == .orderedSame || apnRegex.firstMatch(in: clean, range: range) == nil {
            return "Enter a valid \(fieldLabel) (1–63 letters, digits, '.' or '-')"
        }
        return nil
    }

    public static func validateApnCredential(_ raw: String, fieldLabel: String) -> String? {
        if raw.isEmpty { return nil }
        if raw.count > 63 { return "\(fieldLabel) must be 63 characters or fewer" }
        if raw.contains(where: { $0.isWhitespace }) || raw.unicodeScalars.contains(where: { $0.value > 0x7E }) {
            return "\(fieldLabel) cannot contain spaces or non-ASCII characters"
        }
        return nil
    }

    public static func validateApnMtu(_ raw: String, selectType: String) -> String? {
        guard let mtu = Int(raw.trimmingCharacters(in: .whitespaces)) else {
            return "MTU must be a whole number"
        }
        let minVal = selectType == "IP" ? 576 : 1280
        if !(minVal...1500).contains(mtu) {
            return "MTU for \(selectType) must be between \(minVal) and 1500"
        }
        return nil
    }

    public static func validateUssdInput(_ raw: String, isReply: Bool) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            return isReply ? "Reply cannot be blank" : "Enter a USSD code (e.g. *123#)"
        }
        if clean.count > 160 { return "USSD code is too long" }
        if clean.unicodeScalars.contains(where: { $0.value < 0x20 }) {
            return "Invalid control character in USSD input"
        }
        if !isReply && !clean.hasPrefix("*") && !clean.hasPrefix("#") {
            return "USSD dial code should start with * or #"
        }
        return nil
    }

    public static func decodeUssdResponse(_ rawMessage: String) -> UssdDecodedMessage {
        let clean = rawMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        switch clean {
        case "": return .empty
        case "507": return .timeout
        case "506": return .carrierError
        default:
            guard clean.count % 4 == 0, clean.allSatisfy({ $0.hexDigitValue != nil }) else {
                return .text(clean)
            }
            var chars: [Character] = []
            var idx = clean.startIndex
            while idx < clean.endIndex {
                let next = clean.index(idx, offsetBy: 4)
                let chunk = String(clean[idx..<next])
                idx = next
                if chunk == "0009" || chunk == "0000" { continue }
                if let code = UInt32(chunk, radix: 16), let scalar = UnicodeScalar(code) {
                    chars.append(Character(scalar))
                }
            }
            let text = String(chars).trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? .empty : .text(text)
        }
    }

    public static func stripTimezoneSuffix(_ timezone: String) -> String {
        timezone.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"_\d+$"#, with: "", options: .regularExpression)
    }

    public static func validateFilterUrl(_ raw: String) -> String? {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "URL / domain cannot be blank" }
        if clean.count > 128 || clean.contains(where: { $0.isWhitespace }) {
            return "URL must be 1–128 non-space characters"
        }
        return nil
    }

    public static func validateTimeWindow(startHhMm: String, endHhMm: String) -> String? {
        func parse(_ s: String) -> Int? {
            let parts = s.trimmingCharacters(in: .whitespaces).split(separator: ":")
            guard parts.count == 2, parts[0].count == 2, parts[1].count == 2,
                  let h = Int(parts[0]), let m = Int(parts[1]),
                  (0...23).contains(h), (0...59).contains(m) else {
                return nil
            }
            return h * 60 + m
        }
        guard let startMin = parse(startHhMm) else { return "Start time must use HH:mm (24h)" }
        guard let endMin = parse(endHhMm) else { return "End time must use HH:mm (24h)" }
        if startMin >= endMin { return "End time must be after start time" }
        return nil
    }
}
