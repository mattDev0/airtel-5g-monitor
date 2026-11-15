import Foundation
import Combine

@MainActor
public final class SignalViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []
    private var initialNeighborsLoaded = false
    private var alignmentActive = false
    private var lastReadingSnapshot: AlignmentSnapshot?

    @Published public private(set) var routerState: RouterState?
    @Published public private(set) var lockSettings: LockSettings?
    @Published public private(set) var signalHistory: [SignalHistoryPoint] = []
    @Published public private(set) var neighborCells: [NeighborCell] = []
    @Published public private(set) var alignmentSnapshot: AlignmentSnapshot?

    @Published public private(set) var neighborsLoading = false
    @Published public private(set) var rescanBusy = false
    @Published public var showLockEditor = false
    @Published public private(set) var lockLoading = false
    @Published public private(set) var lockSaving = false
    @Published public var lockMessage: String?

    @Published public private(set) var alignmentSoundEnabled = true
    @Published public private(set) var bestSinr: Double?
    @Published public private(set) var bestRsrp: Double?
    @Published public private(set) var alignmentSinrHistory: [Double] = []
    @Published public private(set) var alignmentRsrpHistory: [Double] = []
    @Published public private(set) var alignmentLastUpdatedAt: Date?

    public init(container: AppContainer) {
        self.container = container
        sync()

        container.statusRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.onStatusChanged() } }
            .store(in: &cancellables)
        container.signalRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
    }

    private func sync() {
        routerState = container.statusRepository.routerState
        signalHistory = container.statusRepository.signalHistory
        alignmentSnapshot = container.statusRepository.alignmentSnapshot
        lockSettings = container.signalRepository.lockSettings
        neighborCells = container.signalRepository.neighborCells
    }

    private func onStatusChanged() {
        sync()
        guard alignmentActive, let snap = alignmentSnapshot else { return }
        if Self.hasAlignmentValuesChanged(prev: lastReadingSnapshot, next: snap) {
            lastReadingSnapshot = snap
            alignmentLastUpdatedAt = Date(timeIntervalSince1970: TimeInterval(snap.timestampMs) / 1000.0)
        }
        if let sinr = snap.primarySinr {
            bestSinr = max(bestSinr ?? sinr, sinr)
            alignmentSinrHistory = (alignmentSinrHistory + [sinr]).suffix(45)
        }
        if let rsrp = snap.primaryRsrp {
            bestRsrp = max(bestRsrp ?? rsrp, rsrp)
            alignmentRsrpHistory = (alignmentRsrpHistory + [rsrp]).suffix(45)
        }
        if alignmentSoundEnabled {
            container.alignmentTonePlayer.playPip(sinrDb: snap.primarySinr)
        }
    }

    public func ensureNeighborsLoaded() {
        guard !initialNeighborsLoaded else { return }
        initialNeighborsLoaded = true
        refreshNeighborCells()
    }

    public func refreshNeighborCells() {
        guard !neighborsLoading else { return }
        neighborsLoading = true
        Task {
            let res = await container.signalRepository.loadNeighborCells()
            neighborsLoading = false
            if !res.isOk {
                container.emitStatusMessage(res.errorMessage ?? "Could not load neighbor cells")
            }
        }
    }

    public func rescanNetwork() {
        guard !rescanBusy else { return }
        rescanBusy = true
        Task {
            let res = await container.signalRepository.rescanNetwork()
            rescanBusy = false
            if res.isOk {
                container.emitStatusMessage("Network rescan triggered")
                container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
            } else {
                container.emitStatusMessage(res.errorMessage ?? "Rescan failed")
            }
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

    public func startAlignmentMode() {
        alignmentActive = true
        bestSinr = nil
        bestRsrp = nil
        alignmentSinrHistory = []
        alignmentRsrpHistory = []
        lastReadingSnapshot = nil
        alignmentLastUpdatedAt = nil
        container.pollingCoordinator.setMode(.alignment)
        container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
    }

    public func stopAlignmentMode() {
        alignmentActive = false
        container.alignmentTonePlayer.stop()
        container.pollingCoordinator.setMode(.normal)
    }

    public func toggleAlignmentSound() {
        alignmentSoundEnabled.toggle()
        container.alignmentTonePlayer.setMuted(!alignmentSoundEnabled)
    }

    public func resetAlignmentPeaks() {
        bestSinr = alignmentSnapshot?.primarySinr
        bestRsrp = alignmentSnapshot?.primaryRsrp
    }

    public static func hasAlignmentValuesChanged(prev: AlignmentSnapshot?, next: AlignmentSnapshot) -> Bool {
        if next.primaryRsrp == nil && next.primarySinr == nil { return false }
        guard let prev else { return true }
        return prev.rsrp5g != next.rsrp5g ||
            prev.sinr5g != next.sinr5g ||
            prev.rsrp4g != next.rsrp4g ||
            prev.sinr4g != next.sinr4g
    }
}
