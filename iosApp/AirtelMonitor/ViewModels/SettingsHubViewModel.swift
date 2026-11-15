import Foundation
import Combine

public enum ActiveSettingsSheet: String, Identifiable {
    case wifi24g
    case wifi5g
    case wifiQr
    case guestWifi
    case wifiAdvanced
    case wifiPower
    case wifiWps
    case wifiSchedule
    case mobileNetwork
    case apn
    case simPin
    case lanDhcp
    case diagnosis
    case connection
    public var id: String { rawValue }
}

@MainActor
public final class SettingsHubViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []
    private var diagTask: Task<Void, Never>?
    private var randomWpsTask: Task<Void, Never>?

    @Published public private(set) var routerHost: String = "192.168.1.1"
    @Published public private(set) var username: String = "user"
    @Published public private(set) var hasSavedPassword: Bool = false
    @Published public private(set) var pollIntervalSeconds: Double = 2.0
    @Published public private(set) var themePreference: ThemePreference = .system
    @Published public private(set) var speedUnit: SpeedUnitPreference = .mbps
    @Published public private(set) var maskSensitiveInfo: Bool = true
    @Published public private(set) var unreadSmsCount: Int = 0

    @Published public private(set) var featureFlags = RouterFeatureFlags()
    @Published public private(set) var wifiDetails = WifiBandDetails()
    @Published public private(set) var guestWifi = GuestWifiSnapshot()
    @Published public private(set) var adv24g: WifiAdvancedBandConfig?
    @Published public private(set) var adv5g: WifiAdvancedBandConfig?
    @Published public private(set) var power411: WifiPower411Config?
    @Published public private(set) var wps = WpsSnapshot()
    @Published public private(set) var wifiSchedule = WifiScheduleConfig()

    @Published public private(set) var mobileAdvanced = MobileAdvancedConfig()
    @Published public private(set) var apnConfig = ApnConfigSnapshot()
    @Published public private(set) var networkModeSummary: String = "--"
    @Published public private(set) var networkModeLabel: String = "--"
    @Published public private(set) var simState = SimPinState()
    @Published public private(set) var lanConfig = LanConfig()
    @Published public private(set) var diagnosisState = DiagnosisState()

    @Published public private(set) var randomWpsPin: String?
    @Published public private(set) var sheetLoading = false
    @Published public private(set) var sheetSaving = false
    @Published public var sheetError: String?
    @Published public var activeSheet: ActiveSettingsSheet?

    public init(container: AppContainer) {
        self.container = container
        sync()

        container.preferences.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.statusRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.featureFlagRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.wifiRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.mobileRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.simRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.lanRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
    }

    private func sync() {
        let p = container.preferences
        routerHost = p.routerHost
        username = p.username
        hasSavedPassword = p.hasSavedPassword
        pollIntervalSeconds = p.pollIntervalSeconds
        themePreference = p.themePreference
        speedUnit = p.speedUnitPreference
        maskSensitiveInfo = p.maskSensitiveInfo

        featureFlags = container.featureFlagRepository.flags
        wifiDetails = container.wifiRepository.bandDetails
        guestWifi = container.wifiRepository.guestWifi
        adv24g = container.wifiRepository.adv24g
        adv5g = container.wifiRepository.adv5g
        power411 = container.wifiRepository.power411
        wps = container.wifiRepository.wps
        wifiSchedule = container.wifiRepository.schedule

        mobileAdvanced = container.mobileRepository.mobileAdvanced
        apnConfig = container.mobileRepository.apnConfig
        simState = container.simRepository.state ?? SimPinState()
        lanConfig = container.lanRepository.lanConfig
        unreadSmsCount = container.statusRepository.routerState?.unreadSmsCount ?? container.smsRepository.unreadCount

        if let r = container.statusRepository.routerState {
            networkModeSummary = Self.buildNetworkModeSummary(r)
            let code = r.networkModeSnapshot.networkMode
            networkModeLabel = code.isEmpty ? NetworkModeCatalog.labelFor(mobileAdvanced.networkMode) : NetworkModeCatalog.labelFor(code)
        } else {
            networkModeLabel = NetworkModeCatalog.labelFor(mobileAdvanced.networkMode)
        }
    }

    public func ensureLoaded() {
        Task {
            if !container.featureFlagRepository.flags.isLoaded {
                _ = await container.featureFlagRepository.load()
            }
            _ = await container.wifiRepository.loadWifiBandDetails()
            _ = await container.lanRepository.loadLanConfig()
            _ = await container.simRepository.loadStatus()
        }
    }

    public func storedPassword() -> String {
        container.preferences.password
    }

    public func saveConnection(host: String, username: String, password: String) {
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "https://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        container.preferences.updateConnectionSettings(
            host: cleanHost.isEmpty ? "192.168.1.1" : cleanHost,
            username: username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "user" : username.trimmingCharacters(in: .whitespacesAndNewlines),
            password: password
        )
        Task {
            await container.session.resetSession()
            container.statusRepository.resetState()
            container.featureFlagRepository.reset()
            container.pollingCoordinator.triggerImmediateRefresh(includeSlow: true)
            container.emitStatusMessage("Router connection settings updated")
            activeSheet = nil
        }
    }

    public func setPollInterval(_ seconds: Double) {
        container.preferences.setPollInterval(seconds)
        container.pollingCoordinator.onIntervalChanged()
    }

    public func setThemePreference(_ pref: ThemePreference) {
        container.preferences.setThemePreference(pref)
    }

    public func setSpeedUnitPreference(_ unit: SpeedUnitPreference) {
        container.preferences.setSpeedUnitPreference(unit)
    }

    public func setMaskSensitiveInfo(_ mask: Bool) {
        container.preferences.setMaskSensitiveInfo(mask)
    }

    public func openSheet(_ sheet: ActiveSettingsSheet) {
        sheetError = nil
        activeSheet = sheet
        switch sheet {
        case .wifi24g, .wifi5g, .wifiQr:
            runLoad { _ = await self.container.wifiRepository.loadWifiBandDetails() }
        case .guestWifi:
            runLoad {
                _ = await self.container.featureFlagRepository.load()
                let f = self.container.featureFlagRepository.flags
                return await self.container.wifiRepository.loadGuestWifi(
                    load24g: f.guestWifi24gAvailable,
                    load5g: f.guestWifi5gAvailable
                )
            }
        case .wifiAdvanced:
            runLoad {
                _ = await self.container.wifiRepository.loadWifiAdvanced(band: .band24g)
                return await self.container.wifiRepository.loadWifiAdvanced(band: .band5g)
            }
        case .wifiPower:
            runLoad { await self.container.wifiRepository.loadWifiPower411() }
        case .wifiWps:
            runLoad { await self.container.wifiRepository.loadWpsStatus() }
        case .wifiSchedule:
            runLoad { await self.container.wifiRepository.loadWifiSchedule() }
        case .mobileNetwork:
            runLoad { await self.container.mobileRepository.loadMobileAdvanced() }
        case .apn:
            runLoad { await self.container.mobileRepository.loadApnConfig() }
        case .simPin:
            runLoad { await self.container.simRepository.loadStatus() }
        case .lanDhcp:
            runLoad { await self.container.lanRepository.loadLanConfig() }
        case .diagnosis, .connection:
            break
        }
    }

    public func toggleWifiBandEnabled(_ band: WifiBand, enabled: Bool) {
        runSave(successMsg: nil) {
            let res = await self.container.wifiRepository.setWifiEnabled(band: band, enabled: enabled)
            if case .ok(let msg) = res {
                self.container.emitStatusMessage(msg)
                return .ok(Unit())
            }
            return res.castFailure()
        }
    }

    public func saveWifiBand(
        band: WifiBand,
        ssid: String,
        key: String,
        authType: String,
        hidden: Bool,
        sameSsidBothBands: Bool
    ) {
        runSave(successMsg: "\(band.label) Wi-Fi settings saved", dismiss: true) {
            await self.container.wifiRepository.saveWifiBandConfig(
                band: band,
                ssid: ssid,
                key: key,
                authenticationType: authType,
                visibleBroadcast: !hidden,
                wifiSames: sameSsidBothBands,
                applySameToBothBands: sameSsidBothBands
            )
        }
    }

    public func saveGuestWifi(band: WifiBand, config: GuestWifiBandConfig) {
        runSave(successMsg: "Guest Wi-Fi (\(band.label)) saved") {
            await self.container.wifiRepository.saveGuestWifi(band: band, config: config)
        }
    }

    public func saveWifiAdvanced(band: WifiBand, config: WifiAdvancedBandConfig) {
        runSave(successMsg: "\(band.label) radio settings saved") {
            await self.container.wifiRepository.saveWifiAdvanced(band: band, config: config)
        }
    }

    public func saveWifiPower411(_ level: String) {
        runSave(successMsg: "Wi-Fi TX power updated") {
            await self.container.wifiRepository.saveWifiPower411(level)
        }
    }

    public func setWpsEnabled(band: WifiBand, enabled: Bool) {
        runSave(successMsg: "WPS \(enabled ? "enabled" : "disabled") on \(band.label)") {
            await self.container.wifiRepository.setWpsEnabled(band: band, enabled: enabled)
        }
    }

    public func triggerWpsPbc(band: WifiBand) {
        runSave(successMsg: "WPS pairing started on \(band.label) (2 min window)") {
            await self.container.wifiRepository.triggerWpsPbc(band: band)
        }
    }

    public func submitWpsPin(band: WifiBand, pin: String) {
        runSave(successMsg: "WPS PIN submitted for \(band.label)") {
            await self.container.wifiRepository.submitWpsPin(band: band, pin: pin)
        }
    }

    public func generateRandomWpsPin(band: WifiBand) {
        guard !sheetSaving else { return }
        sheetSaving = true
        sheetError = nil
        randomWpsTask?.cancel()
        Task {
            let res = await container.wifiRepository.openRandomWpsPin(band: band)
            sheetSaving = false
            switch res {
            case .ok(let pin):
                randomWpsPin = pin
                container.emitStatusMessage("Router WPS PIN active: \(pin)")
                randomWpsTask = Task { [weak self] in
                    for _ in 0..<24 {
                        try? await Task.sleep(nanoseconds: 5_000_000_000)
                        if Task.isCancelled { return }
                        if case .ok(true) = await self?.container.wifiRepository.pollRandomWpsSuccess() {
                            await MainActor.run {
                                self?.randomWpsPin = nil
                                self?.container.emitStatusMessage("WPS client connected")
                            }
                            return
                        }
                    }
                }
            default:
                sheetError = res.errorMessage
            }
        }
    }

    public func saveWifiSchedule(_ cfg: WifiScheduleConfig) {
        runSave(successMsg: "Wi-Fi schedule saved", dismiss: true) {
            await self.container.wifiRepository.saveWifiSchedule(cfg)
        }
    }

    public func setNetworkMode(_ code: String) {
        runSave(successMsg: "Preferred network mode updated") {
            await self.container.mobileRepository.setNetworkMode(code, flags: self.featureFlags)
        }
    }

    public func setLteCa(_ enabled: Bool) {
        runSave(successMsg: "LTE Carrier Aggregation \(enabled ? "enabled" : "disabled")") {
            await self.container.mobileRepository.setLteCa(enabled)
        }
    }

    public func setNrCa(_ enabled: Bool) {
        runSave(successMsg: "5G NR Carrier Aggregation \(enabled ? "enabled" : "disabled")") {
            await self.container.mobileRepository.setNrCa(enabled)
        }
    }

    public func setRoaming(_ enabled: Bool) {
        runSave(successMsg: "Data roaming \(enabled ? "enabled" : "disabled")") {
            await self.container.mobileRepository.setRoaming(enabled)
        }
    }

    public func saveApnBasic(selectType: String, mtu: Int, natEnabled: Bool) {
        runSave(successMsg: "APN basic settings saved") {
            await self.container.mobileRepository.saveApnBasic(selectType: selectType, mtu: mtu, natEnabled: natEnabled)
        }
    }

    public func saveApnProfiles(_ profiles: [ApnProfile]) {
        runSave(successMsg: "APN profiles saved") {
            await self.container.mobileRepository.saveApnProfiles(profiles)
        }
    }

    public func setSimPinLock(enabled: Bool, pin: String) {
        runSave(successMsg: "SIM PIN lock \(enabled ? "enabled" : "disabled")") {
            await self.container.simRepository.setPinLockEnabled(enable: enabled, pin: pin)
        }
    }

    public func verifySimPin(_ pin: String) {
        runSave(successMsg: "SIM PIN verified") {
            await self.container.simRepository.verifyPin(pin)
        }
    }

    public func changeSimPin(oldPin: String, newPin: String) {
        runSave(successMsg: "SIM PIN changed") {
            await self.container.simRepository.changePin(oldPin: oldPin, newPin: newPin)
        }
    }

    public func unlockWithPuk(puk: String, newPin: String) {
        runSave(successMsg: "SIM unlocked with PUK") {
            await self.container.simRepository.unlockWithPuk(puk: puk, newPin: newPin)
        }
    }

    public func saveLanDhcp(_ cfg: LanConfig) {
        runSave(successMsg: "LAN & DHCP settings saved", dismiss: true) {
            await self.container.lanRepository.saveLanDhcpSettings(
                lanIp: cfg.lanIp,
                netMask: cfg.netMask,
                dhcpEnabled: cfg.dhcpEnabled,
                ipBegin: cfg.ipBegin,
                ipEnd: cfg.ipEnd,
                expireTime: cfg.expireTime,
                primaryDns: cfg.primaryDns,
                secondaryDns: cfg.secondaryDns
            )
        }
    }

    public func runDiagnosis(mode: DiagnosisMode, target: String, pingCount: Int) {
        stopDiagnosis()
        let toolMode: NetworkToolMode = mode == .ping ? .ping : .traceroute
        diagnosisState = DiagnosisState(mode: mode, target: target, pingCount: pingCount, isRunning: true, output: "Starting \(mode.rawValue)...")
        diagTask = Task { [weak self] in
            guard let self else { return }
            let startRes = await container.systemRepository.startNetworkTool(
                mode: toolMode,
                target: target,
                pingCount: pingCount
            )
            if !startRes.isOk {
                diagnosisState.isRunning = false
                diagnosisState.statusMessage = startRes.errorMessage
                diagnosisState.output = startRes.errorMessage ?? "Failed to start test"
                return
            }

            for _ in 0..<45 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                let readRes = await container.systemRepository.readNetworkToolOutput(mode: toolMode)
                if case .ok(let text) = readRes {
                    if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !text.contains("\"success\"") {
                        diagnosisState.output = text
                    }
                    if text.contains("statistic") || text.contains("Network is unreachable") || text.contains("Resume") {
                        _ = await container.systemRepository.stopNetworkTool(mode: toolMode)
                        diagnosisState.isRunning = false
                        return
                    }
                }
            }
            _ = await container.systemRepository.stopNetworkTool(mode: toolMode)
            diagnosisState.isRunning = false
        }
    }

    public func stopDiagnosis() {
        diagTask?.cancel()
        diagTask = nil
        if diagnosisState.isRunning {
            let mode: NetworkToolMode = diagnosisState.mode == .ping ? .ping : .traceroute
            diagnosisState.isRunning = false
            Task { _ = await container.systemRepository.stopNetworkTool(mode: mode) }
        }
    }

    private func runLoad<T>(_ work: @escaping () async -> RouterResult<T>) {
        sheetLoading = true
        sheetError = nil
        Task {
            let res = await work()
            sheetLoading = false
            if !res.isOk {
                sheetError = res.errorMessage
            }
        }
    }

    private func runSave<T>(successMsg: String?, dismiss: Bool = false, _ work: @escaping () async -> RouterResult<T>) {
        guard !sheetSaving else { return }
        sheetSaving = true
        sheetError = nil
        Task {
            let res = await work()
            sheetSaving = false
            if res.isOk {
                if let successMsg { container.emitStatusMessage(successMsg) }
                if dismiss { activeSheet = nil }
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
            } else {
                sheetError = res.errorMessage
            }
        }
    }

    public static func buildNetworkModeSummary(_ router: RouterState) -> String {
        let modeRaw = router.cellular.networkType.trimmingCharacters(in: .whitespacesAndNewlines)
        let mode = modeRaw.isEmpty ? router.networkModeSnapshot.networkMode : modeRaw
        var parts: [String] = [mode.isEmpty ? "--" : mode]
        let is5gActive = mode.range(of: "5G", options: .caseInsensitive) != nil
        if is5gActive {
            switch router.networkModeSnapshot.mode5g {
            case "0": parts.append("5G mode: SA + NSA")
            case "1": parts.append("5G mode: SA")
            case "2": parts.append("5G mode: NSA")
            default: break
            }
        }
        var ca: [String] = []
        if router.networkModeSnapshot.lteCa { ca.append("LTE CA") }
        if router.networkModeSnapshot.nrCa { ca.append("NR CA") }
        if !ca.isEmpty { parts.append(ca.joined(separator: " + ")) }
        if router.networkModeSnapshot.roamingEnable { parts.append("Roaming") }
        return parts.joined(separator: " • ")
    }
}
