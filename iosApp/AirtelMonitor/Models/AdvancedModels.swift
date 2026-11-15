import Foundation

// MARK: - LAN & DHCP

public struct LanConfig: Equatable, Sendable {
    public let lanIp: String
    public let netMask: String
    public let dhcpEnabled: Bool
    public let ipBegin: String
    public let ipEnd: String
    public let expireTime: String
    public let primaryDns: String
    public let secondaryDns: String
    public let ipv6PrimaryDns: String
    public let ipv6SecondaryDns: String

    public init(
        lanIp: String = "192.168.1.1",
        netMask: String = "255.255.255.0",
        dhcpEnabled: Bool = true,
        ipBegin: String = "192.168.1.100",
        ipEnd: String = "192.168.1.200",
        expireTime: String = "24h",
        primaryDns: String = "",
        secondaryDns: String = "",
        ipv6PrimaryDns: String = "",
        ipv6SecondaryDns: String = ""
    ) {
        self.lanIp = lanIp
        self.netMask = netMask
        self.dhcpEnabled = dhcpEnabled
        self.ipBegin = ipBegin
        self.ipEnd = ipEnd
        self.expireTime = expireTime
        self.primaryDns = primaryDns
        self.secondaryDns = secondaryDns
        self.ipv6PrimaryDns = ipv6PrimaryDns
        self.ipv6SecondaryDns = ipv6SecondaryDns
    }
}

public struct StaticDhcpLease: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { "\(ip)-\(mac)" }
    public let ip: String
    public let mac: String

    public init(ip: String, mac: String) {
        self.ip = ip
        self.mac = mac
    }
}

// MARK: - Wi-Fi

public enum WifiBand: String, CaseIterable, Sendable {
    case band24g
    case band5g

    public var cmd: Int {
        switch self {
        case .band24g: return 2
        case .band5g: return 211
        }
    }

    public var advancedCmd: Int {
        switch self {
        case .band24g: return 208
        case .band5g: return 231
        }
    }

    public var label: String {
        switch self {
        case .band24g: return "2.4 GHz"
        case .band5g: return "5 GHz"
        }
    }
}

public struct WifiBandStatus: Equatable, Sendable {
    public let enabled: Bool
    public let ssid: String
    public let key: String
    public let authenticationType: String
    public let visibleBroadcast: Bool
    public let channel: String
    public let wifiSames: Bool
    public let maxStaNum: String

    public init(
        enabled: Bool = true,
        ssid: String = "",
        key: String = "",
        authenticationType: String = "2",
        visibleBroadcast: Bool = true,
        channel: String = "0",
        wifiSames: Bool = false,
        maxStaNum: String = ""
    ) {
        self.enabled = enabled
        self.ssid = ssid
        self.key = key
        self.authenticationType = authenticationType
        self.visibleBroadcast = visibleBroadcast
        self.channel = channel
        self.wifiSames = wifiSames
        self.maxStaNum = maxStaNum
    }

    public var encryptionLabel: String {
        switch authenticationType {
        case "0": return "Open"
        case "2": return "WPA2-PSK"
        case "4": return "WPA2/WPA3-PSK"
        case "5": return "WPA3-SAE"
        case "1": return "WPA-PSK"
        case "3": return "WPA/WPA2-PSK"
        default: return "WPA2-PSK"
        }
    }
}

public struct WifiBandDetails: Equatable, Sendable {
    public let band24g: WifiBandStatus
    public let band5g: WifiBandStatus
    public let forbidSsids: [String]
    public let forbidKeys: [String]

    public init(
        band24g: WifiBandStatus = WifiBandStatus(),
        band5g: WifiBandStatus = WifiBandStatus(),
        forbidSsids: [String] = [],
        forbidKeys: [String] = []
    ) {
        self.band24g = band24g
        self.band5g = band5g
        self.forbidSsids = forbidSsids
        self.forbidKeys = forbidKeys
    }
}

public struct GuestWifiBandConfig: Equatable, Sendable {
    public let wifiOpen: Bool
    public let ssid: String
    public let key: String
    public let authenticationType: String
    public let encryption: String
    public let broadcast: Bool
    public let wmm: Bool
    public let mainWifiOpen: Bool
    public let customPort: String

    public init(
        wifiOpen: Bool = false,
        ssid: String = "",
        key: String = "",
        authenticationType: String = "2",
        encryption: String = "1",
        broadcast: Bool = true,
        wmm: Bool = true,
        mainWifiOpen: Bool = true,
        customPort: String = ""
    ) {
        self.wifiOpen = wifiOpen
        self.ssid = ssid
        self.key = key
        self.authenticationType = authenticationType
        self.encryption = encryption
        self.broadcast = broadcast
        self.wmm = wmm
        self.mainWifiOpen = mainWifiOpen
        self.customPort = customPort
    }
}

public struct GuestWifiSnapshot: Equatable, Sendable {
    public let band24g: GuestWifiBandConfig
    public let band5g: GuestWifiBandConfig

    public init(
        band24g: GuestWifiBandConfig = GuestWifiBandConfig(),
        band5g: GuestWifiBandConfig = GuestWifiBandConfig()
    ) {
        self.band24g = band24g
        self.band5g = band5g
    }
}

public struct WifiAdvancedBandConfig: Equatable, Sendable {
    public let wifiOpen: Bool
    public let countryCode: String
    public let workMode: String
    public let channel: String
    public let bandWidth: String
    public let txPower: String
    public let maxNum: Int
    public let maxNumLimit: Int
    public let apIsolate: Bool
    public let dfsSwitch: Bool
    public let clientStaReadOnly: Bool
    public let staLimitPrompt: String

    public init(
        wifiOpen: Bool = true,
        countryCode: String = "",
        workMode: String = "0",
        channel: String = "0",
        bandWidth: String = "0",
        txPower: String = "15",
        maxNum: Int = 32,
        maxNumLimit: Int = 64,
        apIsolate: Bool = false,
        dfsSwitch: Bool = false,
        clientStaReadOnly: Bool = false,
        staLimitPrompt: String = ""
    ) {
        self.wifiOpen = wifiOpen
        self.countryCode = countryCode
        self.workMode = workMode
        self.channel = channel
        self.bandWidth = bandWidth
        self.txPower = txPower
        self.maxNum = maxNum
        self.maxNumLimit = maxNumLimit
        self.apIsolate = apIsolate
        self.dfsSwitch = dfsSwitch
        self.clientStaReadOnly = clientStaReadOnly
        self.staLimitPrompt = staLimitPrompt
    }
}

public struct WifiPower411Config: Equatable, Sendable {
    public let txPower24g: String
    public let txPower5g: String

    public init(txPower24g: String = "15", txPower5g: String = "15") {
        self.txPower24g = txPower24g
        self.txPower5g = txPower5g
    }
}

public struct WpsBandStatus: Equatable, Sendable {
    public let enabled: Bool
    public let wifiOpen: Bool
    public let authType: String
    public let visibleBroadcast: Bool

    public init(
        enabled: Bool = false,
        wifiOpen: Bool = true,
        authType: String = "2",
        visibleBroadcast: Bool = true
    ) {
        self.enabled = enabled
        self.wifiOpen = wifiOpen
        self.authType = authType
        self.visibleBroadcast = visibleBroadcast
    }

    public var isPbcEligible: Bool {
        wifiOpen && visibleBroadcast && authType != "0" && authType != "1" && authType != "5"
    }
}

public struct WpsSnapshot: Equatable, Sendable {
    public let band24g: WpsBandStatus
    public let band5g: WpsBandStatus

    public init(
        band24g: WpsBandStatus = WpsBandStatus(),
        band5g: WpsBandStatus = WpsBandStatus()
    ) {
        self.band24g = band24g
        self.band5g = band5g
    }
}

public struct WifiScheduleConfig: Equatable, Sendable {
    public let enabled: Bool
    public let onHour: Int
    public let onMinute: Int
    public let offHour: Int
    public let offMinute: Int

    public init(
        enabled: Bool = false,
        onHour: Int = 7,
        onMinute: Int = 0,
        offHour: Int = 23,
        offMinute: Int = 0
    ) {
        self.enabled = enabled
        self.onHour = onHour
        self.onMinute = onMinute
        self.offHour = offHour
        self.offMinute = offMinute
    }
}

// MARK: - Mobile & SIM

public struct SimPinState: Equatable, Sendable {
    public let simStatusCode: String
    public let cardType: String
    public let pinLockEnabled: Bool
    public let pinRequired: Bool
    public let pukRequired: Bool
    public let pinAttemptsLeft: Int
    public let pukAttemptsLeft: Int

    public init(
        simStatusCode: String = "",
        cardType: String = "",
        pinLockEnabled: Bool = false,
        pinRequired: Bool = false,
        pukRequired: Bool = false,
        pinAttemptsLeft: Int = 3,
        pukAttemptsLeft: Int = 10
    ) {
        self.simStatusCode = simStatusCode
        self.cardType = cardType
        self.pinLockEnabled = pinLockEnabled
        self.pinRequired = pinRequired
        self.pukRequired = pukRequired
        self.pinAttemptsLeft = pinAttemptsLeft
        self.pukAttemptsLeft = pukAttemptsLeft
    }

    public var statusLabel: String {
        if pukRequired { return "PUK required" }
        if pinRequired { return "PIN required" }
        if simStatusCode == "1" {
            return pinLockEnabled ? "Ready · PIN lock on" : "Ready"
        }
        if simStatusCode.isEmpty { return "Unknown" }
        return "Status \(simStatusCode)"
    }
}

public struct TrafficLimitSettings: Equatable, Sendable {
    public let limitEnabled: Bool
    public let limitSize: String
    public let unitCode: String
    public let startDate: Int
    public let dlMonthMb: Double
    public let ulMonthMb: Double

    public init(
        limitEnabled: Bool = false,
        limitSize: String = "0",
        unitCode: String = "1",
        startDate: Int = 1,
        dlMonthMb: Double = 0,
        ulMonthMb: Double = 0
    ) {
        self.limitEnabled = limitEnabled
        self.limitSize = limitSize
        self.unitCode = unitCode
        self.startDate = startDate
        self.dlMonthMb = dlMonthMb
        self.ulMonthMb = ulMonthMb
    }

    public var totalUsedMb: Double { dlMonthMb + ulMonthMb }
}

public struct ApnProfile: Identifiable, Equatable, Hashable, Sendable {
    public let id = UUID()
    public var name: String
    public var apnName: String
    public var selectType: String
    public var username: String
    public var password: String
    public var authType: String
    public var mtu: Int
    public var natEnabled: Bool
    public var isDefault: Bool

    public init(
        name: String = "",
        apnName: String = "",
        selectType: String = "IP",
        username: String = "",
        password: String = "",
        authType: String = "0",
        mtu: Int = 1500,
        natEnabled: Bool = true,
        isDefault: Bool = false
    ) {
        self.name = name
        self.apnName = apnName
        self.selectType = selectType
        self.username = username
        self.password = password
        self.authType = authType
        self.mtu = mtu
        self.natEnabled = natEnabled
        self.isDefault = isDefault
    }

    public static func == (lhs: ApnProfile, rhs: ApnProfile) -> Bool {
        lhs.name == rhs.name &&
            lhs.apnName == rhs.apnName &&
            lhs.selectType == rhs.selectType &&
            lhs.username == rhs.username &&
            lhs.password == rhs.password &&
            lhs.authType == rhs.authType &&
            lhs.mtu == rhs.mtu &&
            lhs.natEnabled == rhs.natEnabled &&
            lhs.isDefault == rhs.isDefault
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(name)
        hasher.combine(apnName)
        hasher.combine(selectType)
        hasher.combine(username)
        hasher.combine(password)
        hasher.combine(authType)
        hasher.combine(mtu)
        hasher.combine(natEnabled)
        hasher.combine(isDefault)
    }
}

public struct ApnConfigSnapshot: Equatable, Sendable {
    public let selectType: String
    public let mtu: Int
    public let natEnabled: Bool
    public let apnSwitch: Bool
    public let customApnName: String
    public let username: String
    public let password: String
    public let authType: String
    public let profiles: [ApnProfile]

    public init(
        selectType: String = "IP",
        mtu: Int = 1500,
        natEnabled: Bool = true,
        apnSwitch: Bool = false,
        customApnName: String = "",
        username: String = "",
        password: String = "",
        authType: String = "0",
        profiles: [ApnProfile] = []
    ) {
        self.selectType = selectType
        self.mtu = mtu
        self.natEnabled = natEnabled
        self.apnSwitch = apnSwitch
        self.customApnName = customApnName
        self.username = username
        self.password = password
        self.authType = authType
        self.profiles = profiles
    }
}

public struct NetworkModeOption: Identifiable, Equatable, Sendable {
    public var id: String { code }
    public let code: String
    public let label: String

    public init(code: String, label: String) {
        self.code = code
        self.label = label
    }
}

public enum NetworkModeCatalog {
    public static let options: [NetworkModeOption] = [
        NetworkModeOption(code: "1E", label: "Auto (5G SA+NSA/4G/3G)"),
        NetworkModeOption(code: "1C", label: "5G SA+NSA / 4G"),
        NetworkModeOption(code: "10", label: "5G SA Only"),
        NetworkModeOption(code: "C", label: "5G NSA / 4G"),
        NetworkModeOption(code: "4", label: "4G Only"),
        NetworkModeOption(code: "6", label: "4G / 3G"),
        NetworkModeOption(code: "7", label: "4G / 3G / 2G"),
        NetworkModeOption(code: "2", label: "3G Only"),
        NetworkModeOption(code: "1", label: "2G Only")
    ]

    public static let mode5gOptions: [NetworkModeOption] = [
        NetworkModeOption(code: "0", label: "SA + NSA"),
        NetworkModeOption(code: "1", label: "SA Only"),
        NetworkModeOption(code: "2", label: "NSA Only")
    ]

    public static func labelFor(_ code: String) -> String {
        let up = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return options.first(where: { $0.code.uppercased() == up })?.label ?? (up.isEmpty ? "Unknown" : up)
    }

    public static func label5gFor(_ code: String) -> String {
        let c = code.trimmingCharacters(in: .whitespacesAndNewlines)
        return mode5gOptions.first(where: { $0.code == c })?.label ?? (c.isEmpty ? "Default" : c)
    }
}

public struct MobileAdvancedConfig: Equatable, Sendable {
    public let networkMode: String
    public let displayMode: String
    public let mode5g: String
    public let lteCa: Bool
    public let nrCa: Bool
    public let roamingEnable: Bool
    public let mobileDataOn: Bool
    public let flightModeOn: Bool

    public init(
        networkMode: String = "1C",
        displayMode: String = "1C",
        mode5g: String = "0",
        lteCa: Bool = true,
        nrCa: Bool = true,
        roamingEnable: Bool = false,
        mobileDataOn: Bool = true,
        flightModeOn: Bool = false
    ) {
        self.networkMode = networkMode
        self.displayMode = displayMode
        self.mode5g = mode5g
        self.lteCa = lteCa
        self.nrCa = nrCa
        self.roamingEnable = roamingEnable
        self.mobileDataOn = mobileDataOn
        self.flightModeOn = flightModeOn
    }
}

// MARK: - SMS & USSD

public enum SmsFolder: String, CaseIterable, Identifiable, Sendable {
    case inbox
    case outbox
    public var id: String { rawValue }
    public var subcmd: Int { self == .inbox ? 0 : 1 }
    public var label: String { self == .inbox ? "Inbox" : "Outbox" }
}

public struct SmsMessage: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { "\(folder.rawValue)-\(index)" }
    public let index: String
    public let folder: SmsFolder
    public let phoneNumber: String
    public let date: String
    public let text: String
    public let isRead: Bool

    public init(
        index: String,
        folder: SmsFolder,
        phoneNumber: String,
        date: String,
        text: String,
        isRead: Bool
    ) {
        self.index = index
        self.folder = folder
        self.phoneNumber = phoneNumber
        self.date = date
        self.text = text
        self.isRead = isRead
    }
}

public struct SmsPage: Equatable, Sendable {
    public let folder: SmsFolder
    public let page: Int
    public let totalCount: Int
    public let isReceiveFull: Bool
    public let isSendFull: Bool
    public let messages: [SmsMessage]

    public init(
        folder: SmsFolder = .inbox,
        page: Int = 1,
        totalCount: Int = 0,
        isReceiveFull: Bool = false,
        isSendFull: Bool = false,
        messages: [SmsMessage] = []
    ) {
        self.folder = folder
        self.page = page
        self.totalCount = totalCount
        self.isReceiveFull = isReceiveFull
        self.isSendFull = isSendFull
        self.messages = messages
    }
}

public struct SmsSettings: Equatable, Sendable {
    public let smsEnabled: Bool
    public let smscEditable: Bool
    public let smscNumber: String
    public let maxSize: Int
    public let maxWeightedLength: Int

    public init(
        smsEnabled: Bool = true,
        smscEditable: Bool = false,
        smscNumber: String = "",
        maxSize: Int = 100,
        maxWeightedLength: Int = 640
    ) {
        self.smsEnabled = smsEnabled
        self.smscEditable = smscEditable
        self.smscNumber = smscNumber
        self.maxSize = maxSize
        self.maxWeightedLength = maxWeightedLength
    }
}

public struct UssdResult: Equatable, Sendable {
    public let text: String
    public let needReply: Bool
    public let cancelled: Bool

    public init(text: String, needReply: Bool = false, cancelled: Bool = false) {
        self.text = text
        self.needReply = needReply
        self.cancelled = cancelled
    }
}

public struct UssdSessionState: Equatable, Sendable {
    public let isBusy: Bool
    public let needReply: Bool
    public let history: [(outgoing: String?, response: String)]
    public let lastError: String?

    public init(
        isBusy: Bool = false,
        needReply: Bool = false,
        history: [(outgoing: String?, response: String)] = [],
        lastError: String? = nil
    ) {
        self.isBusy = isBusy
        self.needReply = needReply
        self.history = history
        self.lastError = lastError
    }

    public static func == (lhs: UssdSessionState, rhs: UssdSessionState) -> Bool {
        lhs.isBusy == rhs.isBusy &&
            lhs.needReply == rhs.needReply &&
            lhs.lastError == rhs.lastError &&
            lhs.history.count == rhs.history.count &&
            zip(lhs.history, rhs.history).allSatisfy { $0.outgoing == $1.outgoing && $0.response == $1.response }
    }
}

// MARK: - Security & Parental Control

public struct MacFilterRule: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { "\(mac)-\(ippro)" }
    public let mac: String
    public let remark: String
    public let ippro: String
    public let enableRule: Bool
    public let enableLink: Bool

    public init(
        mac: String,
        remark: String = "",
        ippro: String = "IPV4",
        enableRule: Bool = true,
        enableLink: Bool = false
    ) {
        self.mac = mac
        self.remark = remark
        self.ippro = ippro
        self.enableRule = enableRule
        self.enableLink = enableLink
    }
}

public enum FirewallLevel: String, CaseIterable, Identifiable, Sendable {
    case low = "low"
    case medium = "medium"
    case high = "high"

    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .low: return "Low"
        case .medium: return "Medium"
        case .high: return "High"
        }
    }

    public static func fromRaw(_ raw: String) -> FirewallLevel {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "high", "2": return .high
        case "medium", "1": return .medium
        default: return .low
        }
    }
}

public struct AlgSettings: Equatable, Sendable {
    public var l2tp: Bool
    public var ipsec: Bool
    public var h323: Bool
    public var rtsp: Bool
    public var sip: Bool
    public var ftp: Bool
    public var pptp: Bool

    public init(
        l2tp: Bool = true,
        ipsec: Bool = true,
        h323: Bool = true,
        rtsp: Bool = true,
        sip: Bool = true,
        ftp: Bool = true,
        pptp: Bool = true
    ) {
        self.l2tp = l2tp
        self.ipsec = ipsec
        self.h323 = h323
        self.rtsp = rtsp
        self.sip = sip
        self.ftp = ftp
        self.pptp = pptp
    }
}

public struct DmzConfig: Equatable, Sendable {
    public let enabled: Bool
    public let ip: String

    public init(enabled: Bool = false, ip: String = "") {
        self.enabled = enabled
        self.ip = ip
    }
}

public struct DdosConfig: Equatable, Sendable {
    public let enabled: Bool
    public init(enabled: Bool = false) { self.enabled = enabled }
}

public struct UpnpConfig: Equatable, Sendable {
    public let enabled: Bool
    public init(enabled: Bool = false) { self.enabled = enabled }
}

public struct PortForwardRule: Identifiable, Equatable, Hashable, Sendable {
    public let id = UUID()
    public let port: String
    public let mappingPort: String
    public let mappingIp: String
    public let protocolName: String
    public let ippro: String
    public let remark: String
    public let enableRule: Bool
    public let interfaceName: String

    public init(
        port: String,
        mappingPort: String,
        mappingIp: String,
        protocolName: String = "TCP",
        ippro: String = "IPV4",
        remark: String = "",
        enableRule: Bool = true,
        interfaceName: String = ""
    ) {
        self.port = port
        self.mappingPort = mappingPort
        self.mappingIp = mappingIp
        self.protocolName = protocolName
        self.ippro = ippro
        self.remark = remark
        self.enableRule = enableRule
        self.interfaceName = interfaceName
    }

    public static func == (lhs: PortForwardRule, rhs: PortForwardRule) -> Bool {
        lhs.port == rhs.port &&
            lhs.mappingPort == rhs.mappingPort &&
            lhs.mappingIp == rhs.mappingIp &&
            lhs.protocolName == rhs.protocolName &&
            lhs.ippro == rhs.ippro &&
            lhs.remark == rhs.remark &&
            lhs.enableRule == rhs.enableRule &&
            lhs.interfaceName == rhs.interfaceName
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(port)
        hasher.combine(mappingPort)
        hasher.combine(mappingIp)
        hasher.combine(protocolName)
        hasher.combine(ippro)
        hasher.combine(remark)
        hasher.combine(enableRule)
        hasher.combine(interfaceName)
    }
}

public struct IpPortFilterRule: Identifiable, Equatable, Hashable, Sendable {
    public let id = UUID()
    public let ip: String
    public let destIp: String
    public let srcPort: String
    public let destPort: String
    public let protocolName: String
    public let ippro: String
    public let remark: String
    public let enableRule: Bool
    public let enableLink: Bool

    public init(
        ip: String = "",
        destIp: String = "",
        srcPort: String = "",
        destPort: String = "",
        protocolName: String = "All",
        ippro: String = "IPV4",
        remark: String = "",
        enableRule: Bool = true,
        enableLink: Bool = false
    ) {
        self.ip = ip
        self.destIp = destIp
        self.srcPort = srcPort
        self.destPort = destPort
        self.protocolName = protocolName
        self.ippro = ippro
        self.remark = remark
        self.enableRule = enableRule
        self.enableLink = enableLink
    }

    public static func == (lhs: IpPortFilterRule, rhs: IpPortFilterRule) -> Bool {
        lhs.ip == rhs.ip &&
            lhs.destIp == rhs.destIp &&
            lhs.srcPort == rhs.srcPort &&
            lhs.destPort == rhs.destPort &&
            lhs.protocolName == rhs.protocolName &&
            lhs.ippro == rhs.ippro &&
            lhs.remark == rhs.remark &&
            lhs.enableRule == rhs.enableRule &&
            lhs.enableLink == rhs.enableLink
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(ip)
        hasher.combine(destIp)
        hasher.combine(srcPort)
        hasher.combine(destPort)
        hasher.combine(protocolName)
        hasher.combine(ippro)
        hasher.combine(remark)
        hasher.combine(enableRule)
        hasher.combine(enableLink)
    }
}

public struct PortFilterRule: Identifiable, Equatable, Hashable, Sendable {
    public let id = UUID()
    public let port: String
    public let protocolName: String
    public let ippro: String
    public let remark: String
    public let enableRule: Bool
    public let enableLink: Bool

    public init(
        port: String,
        protocolName: String = "tcp",
        ippro: String = "IPV4",
        remark: String = "",
        enableRule: Bool = true,
        enableLink: Bool = false
    ) {
        self.port = port
        self.protocolName = protocolName
        self.ippro = ippro
        self.remark = remark
        self.enableRule = enableRule
        self.enableLink = enableLink
    }

    public static func == (lhs: PortFilterRule, rhs: PortFilterRule) -> Bool {
        lhs.port == rhs.port &&
            lhs.protocolName == rhs.protocolName &&
            lhs.ippro == rhs.ippro &&
            lhs.remark == rhs.remark &&
            lhs.enableRule == rhs.enableRule &&
            lhs.enableLink == rhs.enableLink
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(port)
        hasher.combine(protocolName)
        hasher.combine(ippro)
        hasher.combine(remark)
        hasher.combine(enableRule)
        hasher.combine(enableLink)
    }
}

public struct UrlFilterRule: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { "\(url)-\(ippro)" }
    public let url: String
    public let ippro: String
    public let remark: String
    public let enableRule: Bool
    public let enableLink: Bool

    public init(
        url: String,
        ippro: String = "IPV4",
        remark: String = "",
        enableRule: Bool = true,
        enableLink: Bool = false
    ) {
        self.url = url
        self.ippro = ippro
        self.remark = remark
        self.enableRule = enableRule
        self.enableLink = enableLink
    }
}

public struct TrustedIpConfig: Equatable, Sendable {
    public let enabled: Bool
    public let ips: [String]

    public init(enabled: Bool = false, ips: [String] = []) {
        self.enabled = enabled
        self.ips = ips
    }
}

public struct DefaultFilterPolicy: Equatable, Sendable {
    public let acceptAllV4: Bool
    public let acceptAllV6: Bool
    public let enableRuleV4: Bool
    public let enableRuleV6: Bool

    public init(
        acceptAllV4: Bool = true,
        acceptAllV6: Bool = true,
        enableRuleV4: Bool = true,
        enableRuleV6: Bool = true
    ) {
        self.acceptAllV4 = acceptAllV4
        self.acceptAllV6 = acceptAllV6
        self.enableRuleV4 = enableRuleV4
        self.enableRuleV6 = enableRuleV6
    }

    public var isWhitelistMode: Bool { !acceptAllV4 }
}

public struct ParentalTimeRule: Identifiable, Equatable, Hashable, Sendable {
    public let id = UUID()
    public let mac: String
    public let startTime: String
    public let endTime: String
    public let week: String
    public let enableRule: Bool

    public init(
        mac: String,
        startTime: String,
        endTime: String,
        week: String = "1,2,3,4,5",
        enableRule: Bool = true
    ) {
        self.mac = mac
        self.startTime = startTime
        self.endTime = endTime
        self.week = week
        self.enableRule = enableRule
    }

    public static func == (lhs: ParentalTimeRule, rhs: ParentalTimeRule) -> Bool {
        lhs.mac == rhs.mac &&
            lhs.startTime == rhs.startTime &&
            lhs.endTime == rhs.endTime &&
            lhs.week == rhs.week &&
            lhs.enableRule == rhs.enableRule
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(mac)
        hasher.combine(startTime)
        hasher.combine(endTime)
        hasher.combine(week)
        hasher.combine(enableRule)
    }
}

public struct ParentalSpeedRule: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { ip }
    public let ip: String
    public let speedKbps: Int
    public let enableRule: Bool

    public init(ip: String, speedKbps: Int, enableRule: Bool = true) {
        self.ip = ip
        self.speedKbps = speedKbps
        self.enableRule = enableRule
    }
}

public struct ParentalUrlRule: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { "\(mac)-\(url)" }
    public let mac: String
    public let url: String
    public let enableRule: Bool

    public init(mac: String, url: String, enableRule: Bool = true) {
        self.mac = mac
        self.url = url
        self.enableRule = enableRule
    }
}

public struct ParentalControlSnapshot: Equatable, Sendable {
    public let enabled: Bool
    public let timeRules: [ParentalTimeRule]
    public let speedRules: [ParentalSpeedRule]
    public let urlRules: [ParentalUrlRule]

    public init(
        enabled: Bool = false,
        timeRules: [ParentalTimeRule] = [],
        speedRules: [ParentalSpeedRule] = [],
        urlRules: [ParentalUrlRule] = []
    ) {
        self.enabled = enabled
        self.timeRules = timeRules
        self.speedRules = speedRules
        self.urlRules = urlRules
    }
}

// MARK: - System, Diagnostics & Alerts

public struct RouterAboutInfo: Equatable, Sendable {
    public let oduModel: String
    public let iduModel: String
    public let hardwareVersion: String
    public let firmwareVersion: String
    public let buildDate: String
    public let uptimeText: String
    public let cpuUsage: String
    public let temperature: String
    public let memoryText: String
    public let imei: String
    public let imsi: String
    public let iccid: String
    public let serialNumber: String
    public let msisdn: String
    public let eid: String
    public let iduMac: String
    public let lanMac: String
    public let linkRates: [String]

    public init(
        oduModel: String = "ZLT X17M",
        iduModel: String = "",
        hardwareVersion: String = "",
        firmwareVersion: String = "",
        buildDate: String = "",
        uptimeText: String = "--",
        cpuUsage: String = "",
        temperature: String = "",
        memoryText: String = "",
        imei: String = "",
        imsi: String = "",
        iccid: String = "",
        serialNumber: String = "",
        msisdn: String = "",
        eid: String = "",
        iduMac: String = "",
        lanMac: String = "",
        linkRates: [String] = []
    ) {
        self.oduModel = oduModel
        self.iduModel = iduModel
        self.hardwareVersion = hardwareVersion
        self.firmwareVersion = firmwareVersion
        self.buildDate = buildDate
        self.uptimeText = uptimeText
        self.cpuUsage = cpuUsage
        self.temperature = temperature
        self.memoryText = memoryText
        self.imei = imei
        self.imsi = imsi
        self.iccid = iccid
        self.serialNumber = serialNumber
        self.msisdn = msisdn
        self.eid = eid
        self.iduMac = iduMac
        self.lanMac = lanMac
        self.linkRates = linkRates
    }
}

public enum LogGrade: String, CaseIterable, Identifiable, Sendable {
    case defaultLevel = "0"
    case err = "1"
    case warning = "2"
    case debug = "3"
    case info = "4"

    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .defaultLevel: return "Default"
        case .err: return "Error"
        case .warning: return "Warning"
        case .debug: return "Debug"
        case .info: return "Info"
        }
    }

    public static func fromRaw(_ raw: String) -> LogGrade {
        LogGrade(rawValue: raw.trimmingCharacters(in: .whitespacesAndNewlines)) ?? .defaultLevel
    }
}

public struct LogEntry: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public let time: String
    public let type: String
    public let level: String
    public let message: String

    public init(time: String = "", type: String = "", level: String = "", message: String) {
        self.time = time
        self.type = type
        self.level = level
        self.message = message
    }

    public static func == (lhs: LogEntry, rhs: LogEntry) -> Bool {
        lhs.time == rhs.time &&
            lhs.type == rhs.type &&
            lhs.level == rhs.level &&
            lhs.message == rhs.message
    }
}

public struct LogSettings: Equatable, Sendable {
    public let saveLogEnabled: Bool
    public let logSizeKb: Int
    public let logGrade: LogGrade

    public init(saveLogEnabled: Bool = false, logSizeKb: Int = 200, logGrade: LogGrade = .info) {
        self.saveLogEnabled = saveLogEnabled
        self.logSizeKb = logSizeKb
        self.logGrade = logGrade
    }
}

public struct ScheduledRebootConfig: Equatable, Sendable {
    public let enabled: Bool
    public let timeHhMm: String
    public let weekDays: String

    public init(enabled: Bool = false, timeHhMm: String = "03:00", weekDays: String = "0") {
        self.enabled = enabled
        self.timeHhMm = timeHhMm
        self.weekDays = weekDays
    }
}

public struct RouterTimeConfig: Equatable, Sendable {
    public let currentSystemTime: String
    public let timezone: String
    public let ntpEnabled: Bool
    public let server1: String
    public let server2: String

    public init(
        currentSystemTime: String = "",
        timezone: String = "UTC+1",
        ntpEnabled: Bool = true,
        server1: String = "pool.ntp.org",
        server2: String = ""
    ) {
        self.currentSystemTime = currentSystemTime
        self.timezone = timezone
        self.ntpEnabled = ntpEnabled
        self.server1 = server1
        self.server2 = server2
    }
}

public struct WanLinkDetectConfig: Equatable, Sendable {
    public let enabled: Bool
    public let networkMode: String
    public let isMixMode: Bool
    public let checkTimeSec: Int
    public let mode: String
    public let ip1: String
    public let ip2: String
    public let ipv6Ip1: String
    public let ipv6Ip2: String
    public let action: String
    public let rebootWaitMinutes: Int
    public let dialWaitSec: Int

    public init(
        enabled: Bool = false,
        networkMode: String = "1",
        isMixMode: Bool = false,
        checkTimeSec: Int = 10,
        mode: String = "0",
        ip1: String = "8.8.8.8",
        ip2: String = "",
        ipv6Ip1: String = "",
        ipv6Ip2: String = "",
        action: String = "0",
        rebootWaitMinutes: Int = 3,
        dialWaitSec: Int = 10
    ) {
        self.enabled = enabled
        self.networkMode = networkMode
        self.isMixMode = isMixMode
        self.checkTimeSec = checkTimeSec
        self.mode = mode
        self.ip1 = ip1
        self.ip2 = ip2
        self.ipv6Ip1 = ipv6Ip1
        self.ipv6Ip2 = ipv6Ip2
        self.action = action
        self.rebootWaitMinutes = rebootWaitMinutes
        self.dialWaitSec = dialWaitSec
    }
}

public struct SelfCheckResult: Equatable, Sendable {
    public let passed: Bool
    public let details: [(key: String, value: String)]

    public init(passed: Bool, details: [(key: String, value: String)]) {
        self.passed = passed
        self.details = details
    }

    public static func == (lhs: SelfCheckResult, rhs: SelfCheckResult) -> Bool {
        lhs.passed == rhs.passed &&
            lhs.details.count == rhs.details.count &&
            zip(lhs.details, rhs.details).allSatisfy { $0.key == $1.key && $0.value == $1.value }
    }
}

public enum SpeedTestPhase: String, Equatable, Sendable {
    case idle
    case measuringLatency
    case downloading
    case uploading
    case finished
    case failed
    case cancelled
}

public struct SpeedTestProgress: Equatable, Sendable {
    public let phase: SpeedTestPhase
    public let latencyMs: Double?
    public let jitterMs: Double?
    public let downloadMbps: Double
    public let uploadMbps: Double
    public let peakDownloadMbps: Double
    public let progressFraction: Double
    public let errorMessage: String?

    public init(
        phase: SpeedTestPhase = .idle,
        latencyMs: Double? = nil,
        jitterMs: Double? = nil,
        downloadMbps: Double = 0,
        uploadMbps: Double = 0,
        peakDownloadMbps: Double = 0,
        progressFraction: Double = 0,
        errorMessage: String? = nil
    ) {
        self.phase = phase
        self.latencyMs = latencyMs
        self.jitterMs = jitterMs
        self.downloadMbps = downloadMbps
        self.uploadMbps = uploadMbps
        self.peakDownloadMbps = peakDownloadMbps
        self.progressFraction = progressFraction
        self.errorMessage = errorMessage
    }

    public var isRunning: Bool {
        phase == .measuringLatency || phase == .downloading || phase == .uploading
    }
}

// MARK: - Notification / Monitor State Machine

public struct MonitorAlertConfig: Equatable, Sendable {
    public let notifyQuotaWarning: Bool
    public let notifyNewSms: Bool
    public let notifyNewDevice: Bool
    public let notifyUnreachable: Bool

    public init(
        notifyQuotaWarning: Bool = true,
        notifyNewSms: Bool = true,
        notifyNewDevice: Bool = false,
        notifyUnreachable: Bool = true
    ) {
        self.notifyQuotaWarning = notifyQuotaWarning
        self.notifyNewSms = notifyNewSms
        self.notifyNewDevice = notifyNewDevice
        self.notifyUnreachable = notifyUnreachable
    }
}

public struct MonitorState: Equatable, Sendable {
    public let lastQuotaAlertCycleKey: String
    public let lastNotifiedSmsUnreadCount: Int
    public let deviceBaselineInitialized: Bool
    public let knownDeviceMacs: Set<String>
    public let consecutiveUnreachableCount: Int
    public let isUnreachableAlertActive: Bool

    public init(
        lastQuotaAlertCycleKey: String = "",
        lastNotifiedSmsUnreadCount: Int = -1,
        deviceBaselineInitialized: Bool = false,
        knownDeviceMacs: Set<String> = [],
        consecutiveUnreachableCount: Int = 0,
        isUnreachableAlertActive: Bool = false
    ) {
        self.lastQuotaAlertCycleKey = lastQuotaAlertCycleKey
        self.lastNotifiedSmsUnreadCount = lastNotifiedSmsUnreadCount
        self.deviceBaselineInitialized = deviceBaselineInitialized
        self.knownDeviceMacs = knownDeviceMacs
        self.consecutiveUnreachableCount = consecutiveUnreachableCount
        self.isUnreachableAlertActive = isUnreachableAlertActive
    }
}

public enum MonitorObservation: Equatable, Sendable {
    case pollSuccess(
        usageUsedPercent: Int?,
        usageWarnPercent: Int?,
        cycleKey: String,
        smsUnreadCount: Int,
        activeDeviceMacs: Set<String>
    )
    case pollFailure
}

public enum MonitorAlert: Equatable, Sendable {
    case quotaWarning(usedPercent: Int, thresholdPercent: Int)
    case newSms(deltaCount: Int, totalUnread: Int)
    case newDevices(macs: Set<String>)
    case routerUnreachable
    case routerRecovered
}

public struct MonitorEvaluation: Equatable, Sendable {
    public let nextState: MonitorState
    public let alerts: [MonitorAlert]

    public init(nextState: MonitorState, alerts: [MonitorAlert]) {
        self.nextState = nextState
        self.alerts = alerts
    }
}
