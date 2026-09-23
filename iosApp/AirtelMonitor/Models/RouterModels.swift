import Foundation

public enum DeviceType: String, CaseIterable, Identifiable, Codable {
    case phone
    case tablet
    case pc
    case tv
    case gaming
    case router
    case device

    public var id: String { rawValue }

    public var sfSymbol: String {
        switch self {
        case .phone: return "iphone"
        case .tablet: return "ipad"
        case .pc: return "laptopcomputer"
        case .tv: return "tv"
        case .gaming: return "gamecontroller"
        case .router: return "wifi.router"
        case .device: return "network"
        }
    }
}

public struct DeviceItem: Identifiable, Equatable, Hashable {
    public var id: String { mac.isEmpty ? ip : mac }
    public let mac: String
    public let ip: String
    public let hostname: String
    public var alias: String
    public let type: DeviceType
    public let band: String
    public let ssid: String
    public let rssi: String
    public let signalPercent: Int
    public let txRateMbps: String
    public let rxRateMbps: String
    public let interfaceName: String
    public let expires: String
    public let ipv6: String
    public let isRouter: Bool

    public var displayName: String {
        alias.isEmpty ? hostname : alias
    }

    public init(
        mac: String = "",
        ip: String = "",
        hostname: String = "",
        alias: String = "",
        type: DeviceType = .device,
        band: String = "",
        ssid: String = "",
        rssi: String = "",
        signalPercent: Int = 0,
        txRateMbps: String = "-",
        rxRateMbps: String = "-",
        interfaceName: String = "wlan",
        expires: String = "",
        ipv6: String = "",
        isRouter: Bool = false
    ) {
        self.mac = mac
        self.ip = ip
        self.hostname = hostname
        self.alias = alias
        self.type = type
        self.band = band
        self.ssid = ssid
        self.rssi = rssi
        self.signalPercent = signalPercent
        self.txRateMbps = txRateMbps
        self.rxRateMbps = rxRateMbps
        self.interfaceName = interfaceName
        self.expires = expires
        self.ipv6 = ipv6
        self.isRouter = isRouter
    }
}

public struct BandwidthPoint: Identifiable, Equatable {
    public let id = UUID()
    public let timeFormatted: String
    public let downloadMbps: Double
    public let uploadMbps: Double
    public let rxKbps: Double
    public let txKbps: Double

    public init(
        timeFormatted: String,
        downloadMbps: Double,
        uploadMbps: Double,
        rxKbps: Double,
        txKbps: Double
    ) {
        self.timeFormatted = timeFormatted
        self.downloadMbps = downloadMbps
        self.uploadMbps = uploadMbps
        self.rxKbps = rxKbps
        self.txKbps = txKbps
    }
}

public struct WanMetrics: Equatable {
    public let downloadKbps: Double
    public let uploadKbps: Double
    public let downloadMbps: Double
    public let uploadMbps: Double
    public let dlFlowMb: String
    public let ulFlowMb: String
    public let totalFlowMb: String
    public let peakDlMbps: Double
    public let peakUlMbps: Double

    public init(
        downloadKbps: Double = 0.0,
        uploadKbps: Double = 0.0,
        downloadMbps: Double = 0.0,
        uploadMbps: Double = 0.0,
        dlFlowMb: String = "-",
        ulFlowMb: String = "-",
        totalFlowMb: String = "-",
        peakDlMbps: Double = 0.0,
        peakUlMbps: Double = 0.0
    ) {
        self.downloadKbps = downloadKbps
        self.uploadKbps = uploadKbps
        self.downloadMbps = downloadMbps
        self.uploadMbps = uploadMbps
        self.dlFlowMb = dlFlowMb
        self.ulFlowMb = ulFlowMb
        self.totalFlowMb = totalFlowMb
        self.peakDlMbps = peakDlMbps
        self.peakUlMbps = peakUlMbps
    }
}

public struct CellularMetrics: Equatable {
    public let networkType: String
    public let signalLvl: Int
    public let rsrp5g: String
    public let sinr5g: String
    public let rsrp4g: String
    public let sinr4g: String
    public let operatorName: String
    public let boardType: String
    public let iduType: String
    public let nrCqi: String
    public let lteCqi: String
    public let nrQamDl: String

    public init(
        networkType: String = "-",
        signalLvl: Int = 0,
        rsrp5g: String = "-",
        sinr5g: String = "-",
        rsrp4g: String = "-",
        sinr4g: String = "-",
        operatorName: String = "Airtel",
        boardType: String = "ZLT X17M",
        iduType: String = "ZLT W304VA PRO",
        nrCqi: String = "-",
        lteCqi: String = "-",
        nrQamDl: String = "-"
    ) {
        self.networkType = networkType
        self.signalLvl = signalLvl
        self.rsrp5g = rsrp5g
        self.sinr5g = sinr5g
        self.rsrp4g = rsrp4g
        self.sinr4g = sinr4g
        self.operatorName = operatorName
        self.boardType = boardType
        self.iduType = iduType
        self.nrCqi = nrCqi
        self.lteCqi = lteCqi
        self.nrQamDl = nrQamDl
    }
}

public struct CellLockInfo: Equatable {
    public let servingPci4g: String
    public let servingFreq4g: String
    public let servingPci5g: String
    public let servingFreq5g: String
    public let currentBands4g: String
    public let currentBand5g: String
    public let lteLockEnabled: Bool
    public let lteLockPci: String
    public let lteLockFreq: String
    public let nrLockEnabled: Bool
    public let nrLockPci: String
    public let nrLockFreq: String
    public let bandLock4gEnabled: Bool
    public let bandLock5gEnabled: Bool

    public init(
        servingPci4g: String = "-",
        servingFreq4g: String = "-",
        servingPci5g: String = "-",
        servingFreq5g: String = "-",
        currentBands4g: String = "-",
        currentBand5g: String = "-",
        lteLockEnabled: Bool = false,
        lteLockPci: String = "-",
        lteLockFreq: String = "-",
        nrLockEnabled: Bool = false,
        nrLockPci: String = "-",
        nrLockFreq: String = "-",
        bandLock4gEnabled: Bool = false,
        bandLock5gEnabled: Bool = false
    ) {
        self.servingPci4g = servingPci4g
        self.servingFreq4g = servingFreq4g
        self.servingPci5g = servingPci5g
        self.servingFreq5g = servingFreq5g
        self.currentBands4g = currentBands4g
        self.currentBand5g = currentBand5g
        self.lteLockEnabled = lteLockEnabled
        self.lteLockPci = lteLockPci
        self.lteLockFreq = lteLockFreq
        self.nrLockEnabled = nrLockEnabled
        self.nrLockPci = nrLockPci
        self.nrLockFreq = nrLockFreq
        self.bandLock4gEnabled = bandLock4gEnabled
        self.bandLock5gEnabled = bandLock5gEnabled
    }
}

public struct HardwareMetrics: Equatable {
    public let cpuUsage: Double
    public let temperature: String
    public let memory: String

    public init(cpuUsage: Double = 0.0, temperature: String = "--", memory: String = "--") {
        self.cpuUsage = cpuUsage
        self.temperature = temperature
        self.memory = memory
    }
}

public struct RouterState: Equatable {
    public let timestamp: Int64
    public let timeFormatted: String
    public let wan: WanMetrics
    public let cellular: CellularMetrics
    public let cellLock: CellLockInfo
    public let hardware: HardwareMetrics
    public let devices: [DeviceItem]
    public let history: [BandwidthPoint]

    public init(
        timestamp: Int64 = 0,
        timeFormatted: String = "--:--:--",
        wan: WanMetrics = WanMetrics(),
        cellular: CellularMetrics = CellularMetrics(),
        cellLock: CellLockInfo = CellLockInfo(),
        hardware: HardwareMetrics = HardwareMetrics(),
        devices: [DeviceItem] = [],
        history: [BandwidthPoint] = []
    ) {
        self.timestamp = timestamp
        self.timeFormatted = timeFormatted
        self.wan = wan
        self.cellular = cellular
        self.cellLock = cellLock
        self.hardware = hardware
        self.devices = devices
        self.history = history
    }
}

public struct DnsConfig: Equatable {
    public var primary: String
    public var secondary: String
    public var dhcpEnabled: Bool

    public init(primary: String = "", secondary: String = "", dhcpEnabled: Bool = true) {
        self.primary = primary
        self.secondary = secondary
        self.dhcpEnabled = dhcpEnabled
    }
}

public struct WifiRadioConfig: Equatable {
    public var enabled24g: Bool
    public var ssid24g: String
    public var channel24g: String
    public var enabled5g: Bool
    public var ssid5g: String
    public var channel5g: String

    public init(
        enabled24g: Bool = true,
        ssid24g: String = "",
        channel24g: String = "",
        enabled5g: Bool = true,
        ssid5g: String = "",
        channel5g: String = ""
    ) {
        self.enabled24g = enabled24g
        self.ssid24g = ssid24g
        self.channel24g = channel24g
        self.enabled5g = enabled5g
        self.ssid5g = ssid5g
        self.channel5g = channel5g
    }
}

public enum DiagnosisMode: String, CaseIterable, Identifiable {
    case ping = "Ping"
    case traceroute = "Traceroute"
    public var id: String { rawValue }
}

public struct DiagnosisState: Equatable {
    public var mode: DiagnosisMode
    public var target: String
    public var pingCount: Int
    public var isRunning: Bool
    public var output: String
    public var statusMessage: String?

    public init(
        mode: DiagnosisMode = .ping,
        target: String = "8.8.8.8",
        pingCount: Int = 4,
        isRunning: Bool = false,
        output: String = "",
        statusMessage: String? = nil
    ) {
        self.mode = mode
        self.target = target
        self.pingCount = pingCount
        self.isRunning = isRunning
        self.output = output
        self.statusMessage = statusMessage
    }
}

// MARK: - Band lock (cmd 161) & local physical cell lock (cmd 160)

/// Which LTE/NR bands the modem may use. Band numbers, e.g. 3 = B3, 78 = n78.
public struct BandLockConfig: Hashable {
    public var lock4gEnabled = false
    public var supported4g: [Int] = []
    public var locked4g: Set<Int> = []
    public var lock5gEnabled = false
    public var supported5g: [Int] = []
    public var locked5g: Set<Int> = []
    public init() {}
}

public struct LteLockCell: Equatable, Hashable {
    public var earfcn: String
    public var pci: String
    public init(earfcn: String, pci: String) { self.earfcn = earfcn; self.pci = pci }
}

public struct NrLockCell: Equatable, Hashable {
    public var band: String
    public var arfcn: String
    public var pci: String
    public init(band: String, arfcn: String, pci: String) { self.band = band; self.arfcn = arfcn; self.pci = pci }
}

public enum LockState: Hashable { case unlocked, locked, failed }

public struct CellLockConfig: Hashable {
    public var lteEnabled = false
    public var lteCells: [LteLockCell] = []
    public var lteState: LockState = .unlocked
    public var nrEnabled = false
    public var nrCells: [NrLockCell] = []
    public var nrState: LockState = .unlocked
    public var current4g: LteLockCell?
    public var current5g: NrLockCell?
    public init() {}
}

public struct LockSettings: Equatable {
    public var bands = BandLockConfig()
    public var cells = CellLockConfig()
    public init() {}
}
