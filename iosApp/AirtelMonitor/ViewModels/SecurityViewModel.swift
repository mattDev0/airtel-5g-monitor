import Foundation
import Combine

public enum ActiveSecuritySheet: String, Identifiable {
    case dmz
    case portForward
    case ipPortFilter
    case portFilter
    case urlFilter
    case macFilter
    case trustedIp
    public var id: String { rawValue }
}

@MainActor
public final class SecurityViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []

    @Published public private(set) var isLoading = false
    @Published public private(set) var isSaving = false
    @Published public private(set) var errorMessage: String?

    @Published public private(set) var featureFlags = RouterFeatureFlags()
    @Published public private(set) var firewallEnabled = false
    @Published public private(set) var firewallLevel: FirewallLevel = .low
    @Published public private(set) var alg = AlgSettings()
    @Published public private(set) var dmz = DmzConfig()
    @Published public private(set) var ddos = DdosConfig()
    @Published public private(set) var upnp = UpnpConfig()
    @Published public private(set) var systemPorts: Set<Int> = []
    @Published public private(set) var portForwards: [PortForwardRule] = []
    @Published public private(set) var ipPortFilters: [IpPortFilterRule] = []
    @Published public private(set) var portFilters: [PortFilterRule] = []
    @Published public private(set) var urlFilters: [UrlFilterRule] = []
    @Published public private(set) var urlDefaultAcceptAll = true
    @Published public private(set) var macFilters: [MacFilterRule] = []
    @Published public private(set) var trustedIp = TrustedIpConfig()

    @Published public var activeSheet: ActiveSecuritySheet?
    @Published public var sheetError: String?

    public init(container: AppContainer) {
        self.container = container
        sync()

        container.securityRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.featureFlagRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
    }

    private func sync() {
        let s = container.securityRepository
        featureFlags = container.featureFlagRepository.flags
        firewallEnabled = s.firewallEnabled
        firewallLevel = s.firewallLevel
        alg = s.alg
        dmz = s.dmz
        ddos = s.ddos
        upnp = s.upnp
        systemPorts = s.systemPorts
        portForwards = s.portForwards
        ipPortFilters = s.ipPortFilters
        portFilters = s.portFilters
        urlFilters = s.urlFilters
        urlDefaultAcceptAll = s.defaultPolicy.acceptAllV4
        macFilters = s.macFilters
        trustedIp = s.trustedIp
    }

    public func loadAll() {
        isLoading = true
        errorMessage = nil
        Task {
            _ = await container.featureFlagRepository.load()
            let res = await container.securityRepository.loadAll()
            isLoading = false
            if !res.isOk { errorMessage = res.errorMessage }
        }
    }

    public func saveFirewall(enabled: Bool, level: FirewallLevel) {
        runSave(successMsg: "Firewall settings saved") {
            await self.container.securityRepository.saveFirewallSwitch(enabled: enabled, level: level)
        }
    }

    public func saveAlg(_ next: AlgSettings) {
        runSave(successMsg: "ALG passthrough settings saved") {
            await self.container.securityRepository.saveAlg(next)
        }
    }

    public func saveDdos(_ enabled: Bool) {
        runSave(successMsg: "DDoS protection \(enabled ? "enabled" : "disabled")") {
            await self.container.securityRepository.saveDdos(enabled: enabled)
        }
    }

    public func saveUpnp(_ enabled: Bool) {
        runSave(successMsg: "UPnP \(enabled ? "enabled" : "disabled")") {
            await self.container.securityRepository.saveUpnp(enabled: enabled)
        }
    }

    public func saveDmz(enabled: Bool, ip: String) {
        runSheetSave(successMsg: "DMZ updated", dismiss: true) {
            await self.container.securityRepository.saveDmz(enabled: enabled, ip: ip)
        }
    }

    public func savePortForwards(_ rules: [PortForwardRule]) {
        runSheetSave(successMsg: "Port forwarding rules saved") {
            await self.container.securityRepository.savePortForwards(rules)
        }
    }

    public func saveIpPortFilters(_ rules: [IpPortFilterRule]) {
        runSheetSave(successMsg: "IP/Port filter rules saved") {
            await self.container.securityRepository.saveIpPortFilters(rules)
        }
    }

    public func savePortFilters(_ rules: [PortFilterRule]) {
        runSheetSave(successMsg: "Port filter rules saved") {
            await self.container.securityRepository.savePortFilters(rules)
        }
    }

    public func saveUrlFilters(_ rules: [UrlFilterRule]) {
        runSheetSave(successMsg: "URL filter rules saved") {
            await self.container.securityRepository.saveUrlFilters(rules)
        }
    }

    public func saveMacFilters(_ rules: [MacFilterRule]) {
        runSheetSave(successMsg: "MAC filter rules saved") {
            await self.container.securityRepository.saveMacFilters(rules)
        }
    }

    public func saveDefaultPolicy(acceptAll: Bool) {
        runSheetSave(successMsg: acceptAll ? "Default policy: Blacklist (Allow all except blocked)" : "Default policy: Whitelist (Block all except allowed)") {
            await self.container.securityRepository.saveDefaultPolicy(acceptAll: acceptAll)
        }
    }

    public func saveTrustedIps(enabled: Bool, ips: [String]) {
        runSheetSave(successMsg: "Trusted IPs saved", dismiss: true) {
            await self.container.securityRepository.saveTrustedIps(enabled: enabled, ips: ips)
        }
    }

    private func runSave<T>(successMsg: String, _ work: @escaping () async -> RouterResult<T>) {
        guard !isSaving else { return }
        isSaving = true
        errorMessage = nil
        Task {
            let res = await work()
            isSaving = false
            if res.isOk {
                container.emitStatusMessage(successMsg)
            } else {
                errorMessage = res.errorMessage
                container.emitStatusMessage(res.errorMessage ?? "Save failed")
            }
        }
    }

    private func runSheetSave<T>(successMsg: String, dismiss: Bool = false, _ work: @escaping () async -> RouterResult<T>) {
        guard !isSaving else { return }
        isSaving = true
        sheetError = nil
        Task {
            let res = await work()
            isSaving = false
            if res.isOk {
                container.emitStatusMessage(successMsg)
                if dismiss { activeSheet = nil }
            } else {
                sheetError = res.errorMessage
            }
        }
    }
}
