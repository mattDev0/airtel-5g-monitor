import Foundation

public enum CarrierTech: String, Equatable {
    case lte = "LTE"
    case nr = "NR"
}

public struct CarrierComponent: Identifiable, Equatable {
    public var id: String { "\(tech.rawValue)-\(band)-\(earfcn)-\(pci)-\(isPrimary)" }
    public let tech: CarrierTech
    public let band: String
    public let earfcn: String
    public let pci: String
    public let bandwidthMHz: Int?
    public let isPrimary: Bool

    public init(
        tech: CarrierTech,
        band: String,
        earfcn: String,
        pci: String,
        bandwidthMHz: Int?,
        isPrimary: Bool
    ) {
        self.tech = tech
        self.band = band
        self.earfcn = earfcn
        self.pci = pci
        self.bandwidthMHz = bandwidthMHz
        self.isPrimary = isPrimary
    }
}

public struct LinkDetails: Equatable {
    public let dlMcs4g: String
    public let ulMcs4g: String
    public let dlMcs5g: String
    public let ulMcs5g: String
    public let maxDlQam4g: String
    public let maxUlQam4g: String
    public let maxDlQam5g: String
    public let maxUlQam5g: String
    public let rank4g: String
    public let rank5g: String
    public let mimoStatus: String
    public let bler4g: String
    public let bler5g: String

    public init(
        dlMcs4g: String = "-",
        ulMcs4g: String = "-",
        dlMcs5g: String = "-",
        ulMcs5g: String = "-",
        maxDlQam4g: String = "-",
        maxUlQam4g: String = "-",
        maxDlQam5g: String = "-",
        maxUlQam5g: String = "-",
        rank4g: String = "-",
        rank5g: String = "-",
        mimoStatus: String = "-",
        bler4g: String = "-",
        bler5g: String = "-"
    ) {
        self.dlMcs4g = dlMcs4g
        self.ulMcs4g = ulMcs4g
        self.dlMcs5g = dlMcs5g
        self.ulMcs5g = ulMcs5g
        self.maxDlQam4g = maxDlQam4g
        self.maxUlQam4g = maxUlQam4g
        self.maxDlQam5g = maxDlQam5g
        self.maxUlQam5g = maxUlQam5g
        self.rank4g = rank4g
        self.rank5g = rank5g
        self.mimoStatus = mimoStatus
        self.bler4g = bler4g
        self.bler5g = bler5g
    }

    public var has5gDetails: Bool {
        [dlMcs5g, ulMcs5g, maxDlQam5g, maxUlQam5g, rank5g, bler5g].contains { !$0.isEmpty && $0 != "-" }
    }
}

public struct CellIdentity: Equatable {
    public let plmn: String
    public let enbId4g: String
    public let cellId4g: String
    public let tac4g: String
    public let ecgi: String
    public let enbId5g: String
    public let cellId5g: String
    public let tac5g: String
    public let ncgi: String

    public init(
        plmn: String = "-",
        enbId4g: String = "-",
        cellId4g: String = "-",
        tac4g: String = "-",
        ecgi: String = "-",
        enbId5g: String = "-",
        cellId5g: String = "-",
        tac5g: String = "-",
        ncgi: String = "-"
    ) {
        self.plmn = plmn
        self.enbId4g = enbId4g
        self.cellId4g = cellId4g
        self.tac4g = tac4g
        self.ecgi = ecgi
        self.enbId5g = enbId5g
        self.cellId5g = cellId5g
        self.tac5g = tac5g
        self.ncgi = ncgi
    }

    public var has5gIdentity: Bool {
        [enbId5g, cellId5g, tac5g, ncgi].contains { !$0.isEmpty && $0 != "-" }
    }
}

public struct NeighborCell: Identifiable, Equatable {
    public var id: String { "\(tech.rawValue)-\(earfcn)-\(pci)" }
    public let tech: CarrierTech
    public let earfcn: String
    public let pci: String
    public let rsrp: String
    public let rsrq: String
    public let sinr: String

    public init(tech: CarrierTech, earfcn: String, pci: String, rsrp: String, rsrq: String, sinr: String) {
        self.tech = tech
        self.earfcn = earfcn
        self.pci = pci
        self.rsrp = rsrp
        self.rsrq = rsrq
        self.sinr = sinr
    }
}

public struct SignalHistoryPoint: Identifiable, Equatable {
    public let id = UUID()
    public let timeLabel: String
    public let rsrp4g: Double?
    public let sinr4g: Double?
    public let rsrp5g: Double?
    public let sinr5g: Double?
    public let pci: String

    public init(
        timeLabel: String,
        rsrp4g: Double? = nil,
        sinr4g: Double? = nil,
        rsrp5g: Double? = nil,
        sinr5g: Double? = nil,
        pci: String = ""
    ) {
        self.timeLabel = timeLabel
        self.rsrp4g = rsrp4g
        self.sinr4g = sinr4g
        self.rsrp5g = rsrp5g
        self.sinr5g = sinr5g
        self.pci = pci
    }
}

public struct AlignmentSnapshot: Equatable {
    public let networkType: String
    public let carrierSummary: String
    public let rsrp5g: Double?
    public let sinr5g: Double?
    public let rsrp4g: Double?
    public let sinr4g: Double?
    public let timestampMs: Int64

    public init(
        networkType: String = "-",
        carrierSummary: String = "-",
        rsrp5g: Double? = nil,
        sinr5g: Double? = nil,
        rsrp4g: Double? = nil,
        sinr4g: Double? = nil,
        timestampMs: Int64 = 0
    ) {
        self.networkType = networkType
        self.carrierSummary = carrierSummary
        self.rsrp5g = rsrp5g
        self.sinr5g = sinr5g
        self.rsrp4g = rsrp4g
        self.sinr4g = sinr4g
        self.timestampMs = timestampMs
    }

    public var activeTechLabel: String {
        (rsrp5g != nil || sinr5g != nil) ? "5G NR" : "4G LTE"
    }

    public var primaryRsrp: Double? { rsrp5g ?? rsrp4g }
    public var primarySinr: Double? { sinr5g ?? sinr4g }
}

public struct MonthlyUsageSummary: Equatable {
    public let usedMb: Double
    public let currentFlowMb: Double?
    public let dlMonthMb: Double
    public let ulMonthMb: Double
    public let roamDlMb: Double
    public let roamUlMb: Double
    public let limitEnabled: Bool
    public let quotaMb: Double?
    public let warnPercent: Int?
    public let billingStartDay: Int?
    public let daysLeftInCycle: Int?
    public let lastResetLabel: String?

    public init(
        usedMb: Double = 0,
        currentFlowMb: Double? = nil,
        dlMonthMb: Double = 0,
        ulMonthMb: Double = 0,
        roamDlMb: Double = 0,
        roamUlMb: Double = 0,
        limitEnabled: Bool = false,
        quotaMb: Double? = nil,
        warnPercent: Int? = nil,
        billingStartDay: Int? = nil,
        daysLeftInCycle: Int? = nil,
        lastResetLabel: String? = nil
    ) {
        self.usedMb = usedMb
        self.currentFlowMb = currentFlowMb
        self.dlMonthMb = dlMonthMb
        self.ulMonthMb = ulMonthMb
        self.roamDlMb = roamDlMb
        self.roamUlMb = roamUlMb
        self.limitEnabled = limitEnabled
        self.quotaMb = quotaMb
        self.warnPercent = warnPercent
        self.billingStartDay = billingStartDay
        self.daysLeftInCycle = daysLeftInCycle
        self.lastResetLabel = lastResetLabel
    }

    public var limitMb: Double? { quotaMb }
    public var warnPercentage: Int? { warnPercent }
    public var cycleStartDay: Int? { billingStartDay }

    public var usedFraction: Double? {
        guard limitEnabled, let quota = quotaMb, quota > 0 else { return nil }
        return min(max(usedMb / quota, 0), 1.5)
    }

    public var progressFraction: Double? {
        guard limitEnabled, let quota = quotaMb, quota > 0 else { return nil }
        return min(max(usedMb / quota, 0), 1.0)
    }

    public var percentUsed: Int? {
        guard let quota = quotaMb, quota > 0 else { return nil }
        return Int((usedMb / quota) * 100.0)
    }

    public var isOverWarning: Bool {
        guard limitEnabled, let warn = warnPercent, warn > 0, let pct = percentUsed else { return false }
        return pct >= warn
    }
}

public struct DailyUsageEntry: Codable, Equatable, Identifiable {
    public var id: String { dateIso }
    public let dateIso: String
    public let dailyMb: Double
    public let endTotalMb: Double
    public let baselineMb: Double
    public let sampleCount: Int

    public init(
        dateIso: String,
        dailyMb: Double,
        endTotalMb: Double,
        baselineMb: Double,
        sampleCount: Int = 1
    ) {
        self.dateIso = dateIso
        self.dailyMb = dailyMb
        self.endTotalMb = endTotalMb
        self.baselineMb = baselineMb
        self.sampleCount = sampleCount
    }
}
