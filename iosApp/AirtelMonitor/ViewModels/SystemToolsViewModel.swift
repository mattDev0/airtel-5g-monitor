import Foundation
import Combine

public enum ActiveSystemSheet: String, Identifiable {
    case scheduledReboot
    case logs
    case changePassword
    case routerTime
    case wanLinkDetect
    public var id: String { rawValue }
}

@MainActor
public final class SystemToolsViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []

    @Published public private(set) var isLoading = false
    @Published public private(set) var aboutInfo = RouterAboutInfo()
    @Published public private(set) var maskSensitive = true
    @Published public private(set) var revealedFields: Set<String> = []
    @Published public private(set) var featureFlags = RouterFeatureFlags()

    @Published public private(set) var selfCheck: SelfCheckResult?
    @Published public private(set) var selfCheckLoading = false
    @Published public private(set) var selfCheckError: String?

    @Published public private(set) var logs: [LogEntry] = []
    @Published public private(set) var logSettings = LogSettings()
    @Published public private(set) var logsLoading = false
    @Published public private(set) var scheduledReboot = ScheduledRebootConfig()
    @Published public private(set) var timeConfig = RouterTimeConfig()
    @Published public private(set) var wanLinkDetect = WanLinkDetectConfig()
    @Published public private(set) var speedTestProgress = SpeedTestProgress()

    @Published public private(set) var sheetBusy = false
    @Published public var sheetError: String?
    @Published public var activeSheet: ActiveSystemSheet?

    public init(container: AppContainer) {
        self.container = container
        sync()

        container.systemRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.speedTestRunner.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.preferences.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.featureFlagRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
    }

    private func sync() {
        aboutInfo = container.systemRepository.aboutInfo
        logs = container.systemRepository.logs
        logSettings = container.systemRepository.logSettings
        scheduledReboot = container.systemRepository.scheduledReboot
        timeConfig = container.systemRepository.timeConfig
        wanLinkDetect = container.systemRepository.wanLinkDetect
        selfCheck = container.systemRepository.lastSelfCheck
        speedTestProgress = container.speedTestRunner.progress
        maskSensitive = container.preferences.maskSensitiveInfo
        featureFlags = container.featureFlagRepository.flags
    }

    public func loadOverview() {
        isLoading = true
        Task {
            _ = await container.featureFlagRepository.load()
            _ = await container.systemRepository.loadAboutInfo()
            _ = await container.systemRepository.loadScheduledReboot()
            _ = await container.systemRepository.loadTimeConfig()
            isLoading = false
        }
    }

    public func toggleReveal(_ field: String) {
        if revealedFields.contains(field) {
            revealedFields.remove(field)
        } else {
            revealedFields.insert(field)
        }
    }

    public func runSelfCheck() {
        guard !selfCheckLoading else { return }
        selfCheckLoading = true
        selfCheckError = nil
        Task {
            let res = await container.systemRepository.runSelfCheck()
            selfCheckLoading = false
            if !res.isOk {
                selfCheckError = res.errorMessage
            }
        }
    }

    public func startSpeedTest() {
        container.speedTestRunner.start()
    }

    public func cancelSpeedTest() {
        container.speedTestRunner.cancel()
    }

    public func openSheet(_ sheet: ActiveSystemSheet) {
        sheetError = nil
        activeSheet = sheet
        switch sheet {
        case .logs:
            logsLoading = true
            Task {
                _ = await container.systemRepository.loadLogSettings()
                let res = await container.systemRepository.loadSystemLogs()
                logsLoading = false
                if !res.isOk { sheetError = res.errorMessage }
            }
        case .scheduledReboot:
            Task { _ = await container.systemRepository.loadScheduledReboot() }
        case .routerTime:
            Task { _ = await container.systemRepository.loadTimeConfig() }
        case .wanLinkDetect:
            Task { _ = await container.systemRepository.loadWanLinkDetect() }
        case .changePassword:
            break
        }
    }

    public func clearSystemLogs() {
        runSheetSave(successMsg: "Router logs cleared") {
            await self.container.systemRepository.clearSystemLogs()
        }
    }

    public func saveLogSettings(sizeKb: Int, grade: LogGrade) {
        runSheetSave(successMsg: "Log settings saved") {
            await self.container.systemRepository.saveLogSettings(logSizeKb: sizeKb, logGrade: grade)
        }
    }

    public func saveScheduledReboot(enabled: Bool, hour: Int, minute: Int, weekDays: String) {
        runSheetSave(successMsg: "Scheduled reboot updated", dismiss: true) {
            await self.container.systemRepository.saveScheduledReboot(enabled: enabled, hour: hour, minute: minute, weekDays: weekDays)
        }
    }

    public func saveTimeConfig(timezone: String, ntpEnabled: Bool, server1: String, server2: String) {
        runSheetSave(successMsg: "Router time settings saved", dismiss: true) {
            await self.container.systemRepository.saveTimeConfig(timezone: timezone, ntpEnabled: ntpEnabled, server1: server1, server2: server2)
        }
    }

    public func syncClientTime() {
        runSheetSave(successMsg: "Router clock synced to device") {
            await self.container.systemRepository.syncClientTime()
        }
    }

    public func saveWanLinkDetect(_ cfg: WanLinkDetectConfig) {
        runSheetSave(successMsg: "WAN link detection saved", dismiss: true) {
            await self.container.systemRepository.saveWanLinkDetect(cfg, flags: self.featureFlags)
        }
    }

    public func changeAdminPassword(oldPassword: String, newPassword: String, confirmPassword: String) {
        runSheetSave(successMsg: "Router admin password updated", dismiss: true) {
            await self.container.systemRepository.changeAdminPassword(
                oldPassword: oldPassword,
                newPassword: newPassword,
                confirmPassword: confirmPassword
            )
        }
    }

    public func rebootRouter() {
        sheetBusy = true
        Task {
            let res = await container.systemRepository.rebootRouter()
            sheetBusy = false
            if res.isOk {
                container.emitStatusMessage("Router is rebooting...")
                container.statusRepository.resetState()
            } else {
                container.emitStatusMessage(res.errorMessage ?? "Reboot failed")
            }
        }
    }

    public func factoryReset() {
        sheetBusy = true
        Task {
            let res = await container.systemRepository.factoryReset()
            sheetBusy = false
            if res.isOk {
                container.emitStatusMessage("Factory reset triggered")
                container.statusRepository.resetState()
            } else {
                container.emitStatusMessage(res.errorMessage ?? "Factory reset failed")
            }
        }
    }

    private func runSheetSave<T>(successMsg: String, dismiss: Bool = false, _ work: @escaping () async -> RouterResult<T>) {
        guard !sheetBusy else { return }
        sheetBusy = true
        sheetError = nil
        Task {
            let res = await work()
            sheetBusy = false
            if res.isOk {
                container.emitStatusMessage(successMsg)
                if dismiss { activeSheet = nil }
            } else {
                sheetError = res.errorMessage
            }
        }
    }
}
