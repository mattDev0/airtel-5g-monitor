import Foundation
import Combine

public enum DevicePolicySheet: String, Identifiable {
    case staticLeases
    case macFilter
    case parentalControl
    public var id: String { rawValue }
}

@MainActor
public final class DevicesViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []

    @Published public private(set) var allDevices: [DeviceItem] = []
    @Published public private(set) var filteredDevices: [DeviceItem] = []
    @Published public private(set) var totalCount: Int = 0
    @Published public private(set) var onlineCount: Int = 0
    @Published public private(set) var offlineCount: Int = 0
    @Published public private(set) var wifi5gCount: Int = 0
    @Published public private(set) var wifi24gCount: Int = 0
    @Published public private(set) var lanCount: Int = 0

    @Published public var presenceFilter: DevicePresenceFilter = .all { didSet { recomputeFiltered() } }
    @Published public var filterMode: DeviceFilter = .all { didSet { recomputeFiltered() } }
    @Published public var sortMode: DeviceSort = .speed { didSet { recomputeFiltered() } }
    @Published public var searchQuery: String = "" { didSet { recomputeFiltered() } }

    @Published public var selectedDevice: DeviceItem?
    @Published public var renameTarget: DeviceItem?
    @Published public var activePolicySheet: DevicePolicySheet?
    @Published public var prefillDevice: DeviceItem?

    @Published public private(set) var featureFlags = RouterFeatureFlags()
    @Published public private(set) var staticLeases: [StaticDhcpLease] = []
    @Published public private(set) var staticLeasesLoading = false
    @Published public private(set) var staticLeasesSaving = false

    @Published public private(set) var macFilterRules: [MacFilterRule] = []
    @Published public private(set) var macAcceptAll = true
    @Published public private(set) var macFilterLoading = false
    @Published public private(set) var macFilterSaving = false

    @Published public private(set) var parental = ParentalControlSnapshot()
    @Published public private(set) var parentalLoading = false
    @Published public private(set) var parentalSaving = false
    @Published public var policyErrorMessage: String?

    public init(container: AppContainer) {
        self.container = container
        syncFromContainer()

        container.statusRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
        container.deviceRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
        container.lanRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
        container.securityRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
        container.parentalControlRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
        container.featureFlagRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
    }

    private func syncFromContainer() {
        let raw = container.statusRepository.routerState?.devices ?? []
        allDevices = container.deviceRepository.enrichDevices(raw)
        featureFlags = container.featureFlagRepository.flags
        staticLeases = container.lanRepository.staticLeases
        macFilterRules = container.securityRepository.macFilters
        macAcceptAll = container.securityRepository.defaultPolicy.acceptAllV4
        parental = container.parentalControlRepository.snapshot
        if let sel = selectedDevice {
            selectedDevice = allDevices.first(where: { $0.id == sel.id }) ?? sel
        }
        recomputeFiltered()
    }

    private func recomputeFiltered() {
        totalCount = allDevices.count
        onlineCount = allDevices.filter(\.isOnline).count
        offlineCount = totalCount - onlineCount
        wifi5gCount = allDevices.filter { $0.band.contains("5") }.count
        wifi24gCount = allDevices.filter { $0.band.contains("2.4") }.count
        lanCount = allDevices.filter { $0.band.uppercased() == "LAN" }.count

        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let base = allDevices.filter { dev in
            let presenceMatch: Bool
            switch presenceFilter {
            case .all: presenceMatch = true
            case .online: presenceMatch = dev.isOnline
            case .offline: presenceMatch = !dev.isOnline
            }
            guard presenceMatch else { return false }

            let bandMatch: Bool
            switch filterMode {
            case .all: bandMatch = true
            case .wifi5g: bandMatch = dev.band.contains("5")
            case .wifi24g: bandMatch = dev.band.contains("2.4")
            case .lan: bandMatch = dev.band.uppercased() == "LAN"
            }
            guard bandMatch else { return false }

            if q.isEmpty { return true }
            return dev.displayName.lowercased().contains(q) ||
                dev.hostname.lowercased().contains(q) ||
                dev.ip.lowercased().contains(q) ||
                dev.mac.lowercased().contains(q) ||
                dev.vendor.lowercased().contains(q)
        }

        filteredDevices = base.sorted { a, b in
            if a.isOnline != b.isOnline { return a.isOnline && !b.isOnline }
            switch sortMode {
            case .speed:
                let sa = (Int(a.txRateMbps.filter(\.isNumber)) ?? 0) + (Int(a.rxRateMbps.filter(\.isNumber)) ?? 0)
                let sb = (Int(b.txRateMbps.filter(\.isNumber)) ?? 0) + (Int(b.rxRateMbps.filter(\.isNumber)) ?? 0)
                if sa != sb { return sa > sb }
                return a.displayName.localizedCaseInsensitiveCompare(b.displayName) == .orderedAscending
            case .name:
                return a.displayName.localizedCaseInsensitiveCompare(b.displayName) == .orderedAscending
            case .signal:
                if a.signalPercent != b.signalPercent { return a.signalPercent > b.signalPercent }
                return a.displayName.localizedCaseInsensitiveCompare(b.displayName) == .orderedAscending
            case .ip:
                return NetworkFormatters.ipv4ToUInt32(a.ip) < NetworkFormatters.ipv4ToUInt32(b.ip)
            }
        }
    }

    public func isMacReserved(_ mac: String) -> Bool {
        guard let norm = Phase71Validators.normalizeMac(mac) else { return false }
        return staticLeases.contains { $0.mac.caseInsensitiveCompare(norm) == .orderedSame }
    }

    public func isMacBlocked(_ mac: String) -> Bool {
        guard let norm = Phase71Validators.normalizeMac(mac) else { return false }
        if macAcceptAll {
            return macFilterRules.contains { $0.enableRule && !$0.enableLink && $0.mac.caseInsensitiveCompare(norm) == .orderedSame }
        } else {
            return !macFilterRules.contains { $0.enableRule && $0.enableLink && $0.mac.caseInsensitiveCompare(norm) == .orderedSame }
        }
    }

    public func saveAlias(_ alias: String, for device: DeviceItem) {
        container.deviceRepository.saveAlias(alias, for: device.id)
        container.statusRepository.reEnrichDevicesFromPreferences()
        container.emitStatusMessage("Saved label for \(device.hostname)")
        renameTarget = nil
    }

    public func clearAlias(for device: DeviceItem) {
        container.deviceRepository.clearAlias(for: device.id)
        container.statusRepository.reEnrichDevicesFromPreferences()
        container.emitStatusMessage("Cleared custom label")
        renameTarget = nil
    }

    public func openPolicySheet(_ sheet: DevicePolicySheet, prefill: DeviceItem? = nil) {
        prefillDevice = prefill
        policyErrorMessage = nil
        activePolicySheet = sheet
        switch sheet {
        case .staticLeases: loadStaticLeases()
        case .macFilter: loadMacFilter()
        case .parentalControl: loadParentalControl()
        }
    }

    public func loadStaticLeases() {
        staticLeasesLoading = true
        Task {
            _ = await container.featureFlagRepository.load()
            let res = await container.lanRepository.loadStaticLeases()
            staticLeasesLoading = false
            if !res.isOk { policyErrorMessage = res.errorMessage }
        }
    }

    public func saveStaticLeases(_ leases: [StaticDhcpLease]) {
        guard !staticLeasesSaving else { return }
        staticLeasesSaving = true
        policyErrorMessage = nil
        Task {
            let res = await container.lanRepository.saveStaticLeases(
                leases,
                syncIpMacBinding: container.featureFlagRepository.flags.dhcpIpMacBindingSync
            )
            staticLeasesSaving = false
            if res.isOk {
                container.emitStatusMessage("Static DHCP leases saved")
            } else {
                policyErrorMessage = res.errorMessage
            }
        }
    }

    public func loadMacFilter() {
        macFilterLoading = true
        Task {
            let res = await container.securityRepository.loadAll()
            macFilterLoading = false
            if !res.isOk { policyErrorMessage = res.errorMessage }
        }
    }

    public func toggleBlockMac(for device: DeviceItem) {
        guard let norm = Phase71Validators.normalizeMac(device.mac) else {
            container.emitStatusMessage("Invalid device MAC address")
            return
        }
        guard !macFilterSaving else { return }
        macFilterSaving = true
        Task {
            _ = await container.securityRepository.loadAll()
            let blocked = isMacBlocked(norm)
            var nextRules = container.securityRepository.macFilters
            if container.securityRepository.defaultPolicy.acceptAllV4 {
                if blocked {
                    nextRules.removeAll { $0.mac.caseInsensitiveCompare(norm) == .orderedSame }
                } else {
                    nextRules.append(MacFilterRule(mac: norm, remark: device.displayName, ippro: "IPV4", enableRule: true, enableLink: false))
                }
            } else {
                if blocked {
                    nextRules.append(MacFilterRule(mac: norm, remark: device.displayName, ippro: "IPV4", enableRule: true, enableLink: true))
                } else {
                    nextRules.removeAll { $0.mac.caseInsensitiveCompare(norm) == .orderedSame }
                }
            }
            let res = await container.securityRepository.saveMacFilters(nextRules)
            macFilterSaving = false
            if res.isOk {
                container.emitStatusMessage(blocked ? "Allowed \(device.displayName)" : "Blocked \(device.displayName)")
            } else {
                container.emitStatusMessage(res.errorMessage ?? "MAC filter update failed")
            }
        }
    }

    public func saveMacFilters(_ rules: [MacFilterRule]) {
        guard !macFilterSaving else { return }
        macFilterSaving = true
        policyErrorMessage = nil
        Task {
            let res = await container.securityRepository.saveMacFilters(rules)
            macFilterSaving = false
            if res.isOk {
                container.emitStatusMessage("MAC filter rules saved")
            } else {
                policyErrorMessage = res.errorMessage
            }
        }
    }

    public func loadParentalControl() {
        parentalLoading = true
        Task {
            _ = await container.featureFlagRepository.load()
            let res = await container.parentalControlRepository.loadAll()
            parentalLoading = false
            if !res.isOk { policyErrorMessage = res.errorMessage }
        }
    }

    public func setParentalEnabled(_ enabled: Bool) {
        guard !parentalSaving else { return }
        parentalSaving = true
        policyErrorMessage = nil
        Task {
            let res = await container.parentalControlRepository.setEnabled(enabled)
            parentalSaving = false
            if res.isOk {
                container.emitStatusMessage(enabled ? "Parental control enabled" : "Parental control disabled")
            } else {
                policyErrorMessage = res.errorMessage
            }
        }
    }

    public func saveParentalTimeRules(_ rules: [ParentalTimeRule]) {
        guard !parentalSaving else { return }
        parentalSaving = true
        policyErrorMessage = nil
        Task {
            let res = await container.parentalControlRepository.saveTimeRules(rules)
            parentalSaving = false
            if res.isOk {
                container.emitStatusMessage("Schedule rules saved")
            } else {
                policyErrorMessage = res.errorMessage
            }
        }
    }

    public func saveParentalSpeedRules(_ rules: [ParentalSpeedRule]) {
        guard !parentalSaving else { return }
        parentalSaving = true
        policyErrorMessage = nil
        Task {
            let res = await container.parentalControlRepository.saveSpeedRules(rules)
            parentalSaving = false
            if res.isOk {
                container.emitStatusMessage("Speed limit rules saved")
            } else {
                policyErrorMessage = res.errorMessage
            }
        }
    }

    public func saveParentalUrlRules(_ rules: [ParentalUrlRule]) {
        guard !parentalSaving else { return }
        parentalSaving = true
        policyErrorMessage = nil
        Task {
            let res = await container.parentalControlRepository.saveUrlRules(rules)
            parentalSaving = false
            if res.isOk {
                container.emitStatusMessage("Parental URL rules saved")
            } else {
                policyErrorMessage = res.errorMessage
            }
        }
    }
}
