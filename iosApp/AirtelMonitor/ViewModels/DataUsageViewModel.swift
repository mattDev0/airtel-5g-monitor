import Foundation
import Combine

@MainActor
public final class DataUsageViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []
    private var initialLoaded = false

    @Published public private(set) var monthlyUsage = MonthlyUsageSummary()
    @Published public private(set) var dailyHistory: [DailyUsageEntry] = []
    @Published public private(set) var limitSettings: TrafficLimitSettings?
    @Published public private(set) var quotaEditable: Bool = true
    @Published public private(set) var isLoading = false
    @Published public private(set) var isSaving = false
    @Published public var statusMessage: String?

    @Published public var showQuotaSheet = false
    @Published public var showCalibrateSheet = false
    @Published public var showClearRouterConfirm = false

    public init(container: AppContainer) {
        self.container = container
        sync()

        container.statusRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.preferences.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.mobileRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.featureFlagRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
    }

    private func sync() {
        monthlyUsage = container.statusRepository.routerState?.monthlyUsage ?? MonthlyUsageSummary()
        dailyHistory = container.preferences.dailyUsageEntries
        limitSettings = container.mobileRepository.trafficLimit
        let flags = container.featureFlagRepository.flags
        quotaEditable = !flags.isLoaded || flags.trafficLimitAvailable
    }

    public func ensureLoaded() {
        guard !initialLoaded else { return }
        initialLoaded = true
        loadTrafficLimit()
    }

    public func loadTrafficLimit() {
        isLoading = true
        Task {
            let res = await container.mobileRepository.loadTrafficLimit()
            isLoading = false
            if !res.isOk {
                statusMessage = res.errorMessage
            }
        }
    }

    public func saveQuota(enabled: Bool, size: String, unitCode: String, startDay: Int) {
        guard !isSaving else { return }
        isSaving = true
        statusMessage = nil
        Task {
            let res = await container.mobileRepository.saveTrafficLimit(
                enabled: enabled,
                size: size,
                unitCode: unitCode,
                startDate: startDay
            )
            isSaving = false
            if res.isOk {
                showQuotaSheet = false
                container.emitStatusMessage("Monthly data limit saved")
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: true)
            } else {
                statusMessage = res.errorMessage
            }
        }
    }

    public func calibrateUsage(targetUsedMb: Double) {
        guard !isSaving else { return }
        isSaving = true
        statusMessage = nil
        Task {
            let res = await container.mobileRepository.calibrateTrafficStatistics(targetUsedMb: targetUsedMb)
            isSaving = false
            if res.isOk {
                showCalibrateSheet = false
                container.emitStatusMessage("Router traffic counter calibrated")
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: true)
            } else {
                statusMessage = res.errorMessage
            }
        }
    }

    public func clearRouterTrafficStatistics() {
        guard !isSaving else { return }
        isSaving = true
        statusMessage = nil
        Task {
            let res = await container.mobileRepository.clearTrafficStatistics()
            isSaving = false
            showClearRouterConfirm = false
            if res.isOk {
                container.emitStatusMessage("Router monthly traffic counters cleared")
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: true)
            } else {
                statusMessage = res.errorMessage
            }
        }
    }

    public func clearLocalHistory() {
        container.preferences.clearDailyUsageHistory()
        container.emitStatusMessage("Cleared local 31-day history")
    }
}
