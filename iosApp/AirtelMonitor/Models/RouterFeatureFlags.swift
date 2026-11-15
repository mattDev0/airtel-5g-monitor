import Foundation

/// Web UI capability & visibility flags parsed from `cmd 80` (`INIT_PAGE` / `js/main.js`).
public struct RouterFeatureFlags: Equatable {
    public let isLoaded: Bool
    public let webPageHideHex: String
    public let pageFlags: [Character]
    public let accountLevel: String
    public let aeraId: String
    public let networkMode: String
    public let fwSwitch: Bool
    public let parentControlSwitch: Bool
    public let lockPlmnFlag: Bool
    public let ipPassthrough: Bool
    public let smsEnabled: Bool
    public let iadReady: Bool
    public let wifiSsidForbid: [String]
    public let wifiPwdForbid: [String]

    public init(
        isLoaded: Bool = false,
        webPageHideHex: String = "",
        pageFlags: [Character] = ["0", "0"],
        accountLevel: String = "3",
        aeraId: String = "",
        networkMode: String = "",
        fwSwitch: Bool = false,
        parentControlSwitch: Bool = false,
        lockPlmnFlag: Bool = false,
        ipPassthrough: Bool = false,
        smsEnabled: Bool = true,
        iadReady: Bool = true,
        wifiSsidForbid: [String] = [],
        wifiPwdForbid: [String] = []
    ) {
        self.isLoaded = isLoaded
        self.webPageHideHex = webPageHideHex
        self.pageFlags = pageFlags
        self.accountLevel = accountLevel
        self.aeraId = aeraId
        self.networkMode = networkMode
        self.fwSwitch = fwSwitch
        self.parentControlSwitch = parentControlSwitch
        self.lockPlmnFlag = lockPlmnFlag
        self.ipPassthrough = ipPassthrough
        self.smsEnabled = smsEnabled
        self.iadReady = iadReady
        self.wifiSsidForbid = wifiSsidForbid
        self.wifiPwdForbid = wifiPwdForbid
    }

    public func pageHideCheck(_ index: Int) -> Bool {
        let v = (0..<pageFlags.count).contains(index) ? pageFlags[index] : nil
        switch v {
        case "1": return accountLevel == "1" || accountLevel == "2"
        case "2": return accountLevel == "1" || accountLevel == "2" || accountLevel == "3"
        case "3": return true
        default: return accountLevel == "1"
        }
    }

    public func pageHideCheck2(_ index: Int) -> Bool {
        let v = (0..<pageFlags.count).contains(index) ? pageFlags[index] : nil
        switch v {
        case "1": return accountLevel == "1" || accountLevel == "2"
        case "2": return accountLevel == "1" || accountLevel == "2" || accountLevel == "3"
        case "3": return accountLevel == "1"
        default: return true
        }
    }

    public func pageHideCheck3(_ index: Int) -> Bool {
        let v = (0..<pageFlags.count).contains(index) ? pageFlags[index] : nil
        switch v {
        case "1": return true
        case "2": return accountLevel == "1" || accountLevel == "2" || accountLevel == "3"
        case "3": return accountLevel == "1"
        default: return accountLevel == "1" || accountLevel == "2"
        }
    }

    public var ussdAvailable: Bool { pageHideCheck(131) }
    public var networkSetAvailable: Bool { pageHideCheck2(84) }
    public var apnAvailable: Bool { pageHideCheck2(366) }
    public var trafficLimitAvailable: Bool { pageHideCheck2(94) }

    public var wifiPowerCmd411Available: Bool { aeraId == "NG0131" }
    public var guestWifi24gAvailable: Bool { pageHideCheck(19) }
    public var guestWifi5gAvailable: Bool { pageHideCheck(23) }
    public var wpsAvailable: Bool { pageHideCheck2(211) }
    public var wifiScheduleAvailable: Bool { pageHideCheck(255) }

    public var parentalControlAvailable: Bool { pageHideCheck(153) }
    public var parentSpeedVisible: Bool { pageHideCheck2(238) }
    public var parentUrlVisible: Bool { pageHideCheck2(239) }
    public var parentTimeVisible: Bool { pageHideCheck2(240) }

    public var dmzAvailable: Bool { pageHideCheck2(61) && !ipPassthrough && networkMode != "7" }
    public var portForwardAvailable: Bool { pageHideCheck2(171) && !ipPassthrough && networkMode != "7" }
    public var firewallRuleAvailable: Bool { pageHideCheck2(62) }
    public var urlFilterAvailable: Bool { pageHideCheck2(63) }
    public var ipMacBindingAvailable: Bool { pageHideCheck2(64) }
    public var ddosAvailable: Bool { pageHideCheck2(67) }
    public var upnpAvailable: Bool { pageHideCheck(39) }
    public var trustedIpAvailable: Bool { pageHideCheck2(118) && !ipPassthrough && networkMode != "7" }
    public var dhcpIpMacBindingSync: Bool { pageHideCheck2(11) }
    public var wanDetectRescanAvailable: Bool {
        pageHideCheck(186) && ["1", "5", "7", "9"].contains(networkMode)
    }

    private static let nibbleToPair = [
        "00", "01", "02", "03",
        "10", "11", "12", "13",
        "20", "21", "22", "23",
        "30", "31", "32", "33"
    ]

    public static func parseHexPageHide(_ hex: String?) -> [Character] {
        let clean = (hex ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return ["0", "0"] }
        var out = ""
        out.reserveCapacity(clean.count * 2)
        for ch in clean {
            guard let nibble = ch.hexDigitValue else { continue }
            out.append(nibbleToPair[nibble])
        }
        guard !out.isEmpty else { return ["0", "0"] }
        return Array(out.reversed())
    }

    public static func fromJson80(_ json: [String: Any]) -> RouterFeatureFlags {
        func str(_ key: String, _ defaultVal: String = "") -> String {
            guard let v = json[key], !(v is NSNull) else { return defaultVal }
            let s = "\(v)".trimmingCharacters(in: .whitespacesAndNewlines)
            return s.isEmpty ? defaultVal : s
        }
        let rawHide = str("web_page_hide")
        let ssidForbid = str("wifi_ssid_forbid")
            .components(separatedBy: CharacterSet(charactersIn: ",;"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let pwdForbid = str("wifi_pwd_forbid")
            .components(separatedBy: CharacterSet(charactersIn: ",;"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        return RouterFeatureFlags(
            isLoaded: true,
            webPageHideHex: rawHide,
            pageFlags: parseHexPageHide(rawHide),
            accountLevel: str("account_level", "3"),
            aeraId: str("aeraId"),
            networkMode: str("network_mode"),
            fwSwitch: str("fw_switch") == "1",
            parentControlSwitch: str("parentControlSwitch") == "1",
            lockPlmnFlag: str("lock_plmn_flag") == "1",
            ipPassthrough: str("ip_passthrough_sw") == "1",
            smsEnabled: str("smsSw", "1") == "1",
            iadReady: str("iad_ready_status", "1") != "0",
            wifiSsidForbid: ssidForbid,
            wifiPwdForbid: pwdForbid
        )
    }
}
