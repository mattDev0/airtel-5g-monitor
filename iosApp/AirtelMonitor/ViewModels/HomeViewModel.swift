import Foundation
import Combine

@MainActor
public final class HomeViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []
    private var initialWifiLoaded = false

    @Published public private(set) var routerState: RouterState?
    @Published public private(set) var wifiRadios = WifiRadioConfig()
    @Published public private(set) var lockSettings: LockSettings?
    @Published public private(set) var speedUnit: SpeedUnitPreference = .mbps
    @Published public private(set) var unreadSmsCount: Int = 0
    @Published public private(set) var networkActionBusy: Bool = false
    @Published public private(set) var lockLoading: Bool = false
    @Published public private(set) var lockSaving: Bool = false
    @Published public var showLockEditor: Bool = false
    @Published public var lockMessage: String?

    public init(container: AppContainer) {
        self.container = container
        sync()

        container.statusRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.wifiRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.signalRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.preferences.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
    }

    private func sync() {
        routerState = container.statusRepository.routerState
        wifiRadios = container.wifiRepository.config
        lockSettings = container.signalRepository.lockSettings
        speedUnit = container.preferences.speedUnitPreference
        unreadSmsCount = container.statusRepository.routerState?.unreadSmsCount ?? container.smsRepository.unreadCount
    }

    public func ensureWifiRadiosLoaded() {
        guard !initialWifiLoaded else { return }
        initialWifiLoaded = true
        Task {
            _ = await container.wifiRepository.loadWifiConfig()
        }
    }

    public func setMobileData(_ on: Bool) {
        runAction(label: on ? "Mobile data enabled" : "Mobile data disabled") {
            await self.container.mobileRepository.setMobileDataEnabled(on)
        }
    }

    public func setFlightMode(_ on: Bool) {
        runAction(label: on ? "Flight mode enabled" : "Flight mode disabled") {
            await self.container.mobileRepository.setFlightModeEnabled(on)
        }
    }

    public func setWifi24g(_ on: Bool) {
        runWifiToggle(band: .band24g, enabled: on)
    }

    public func setWifi5g(_ on: Bool) {
        runWifiToggle(band: .band5g, enabled: on)
    }

    public func rescanNetwork() {
        runAction(label: "Network rescan triggered") {
            await self.container.signalRepository.rescanNetwork()
        }
    }

    public func openLockEditor() {
        showLockEditor = true
        lockLoading = true
        lockMessage = nil
        Task {
            let res = await container.signalRepository.loadLocks()
            lockLoading = false
            if !res.isOk {
                lockMessage = res.errorMessage
            }
        }
    }

    public func saveBandLock(lock4g: Bool, bands4g: Set<Int>, lock5g: Bool, bands5g: Set<Int>) {
        guard !lockSaving else { return }
        lockSaving = true
        lockMessage = nil
        Task {
            let res = await container.signalRepository.saveBandLock(
                lock4g: lock4g,
                bands4g: bands4g,
                lock5g: lock5g,
                bands5g: bands5g
            )
            lockSaving = false
            if res.isOk {
                container.emitStatusMessage("Band lock updated")
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
            } else {
                lockMessage = res.errorMessage
            }
        }
    }

    public func saveLteCellLock(enabled: Bool, cells: [LteLockCell]) {
        guard !lockSaving else { return }
        lockSaving = true
        lockMessage = nil
        Task {
            let res = await container.signalRepository.saveLteCellLock(enabled: enabled, cells: cells)
            lockSaving = false
            if res.isOk {
                container.emitStatusMessage(enabled ? "4G cell lock updated" : "4G cell lock unlocked")
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
            } else {
                lockMessage = res.errorMessage
            }
        }
    }

    public func saveNrCellLock(enabled: Bool, cells: [NrLockCell]) {
        guard !lockSaving else { return }
        lockSaving = true
        lockMessage = nil
        Task {
            let res = await container.signalRepository.saveNrCellLock(enabled: enabled, cells: cells)
            lockSaving = false
            if res.isOk {
                container.emitStatusMessage(enabled ? "5G cell lock updated" : "5G cell lock unlocked")
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
            } else {
                lockMessage = res.errorMessage
            }
        }
    }

    private func runWifiToggle(band: WifiBand, enabled: Bool) {
        guard !networkActionBusy else { return }
        networkActionBusy = true
        Task {
            let res = await container.wifiRepository.setWifiEnabled(band: band, enabled: enabled)
            networkActionBusy = false
            if case .ok(let msg) = res {
                container.emitStatusMessage(msg)
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
            } else {
                container.emitStatusMessage(res.errorMessage ?? "Failed to update \(band.label)")
            }
        }
    }

    private func runAction<T>(label: String, block: @escaping () async -> RouterResult<T>) {
        guard !networkActionBusy else { return }
        networkActionBusy = true
        Task {
            let res = await block()
            networkActionBusy = false
            if res.isOk {
                container.emitStatusMessage(label)
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
            } else {
                container.emitStatusMessage(res.errorMessage ?? "Action failed")
            }
        }
    }
}
