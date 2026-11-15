import Foundation

public enum PollingMode: Equatable, Sendable {
    /// Standard dashboard polling (`StatusRepository.pollFast` + 20s slow cache).
    case normal
    /// Antenna alignment mode (2s `cmd 205` only).
    case alignment
}

@MainActor
public final class PollingCoordinator: ObservableObject {
    private let pollIntervalProvider: () -> TimeInterval
    private let onNormalTick: (_ forceSlow: Bool) async -> Void
    private let onAlignmentTick: () async -> Void

    @Published public private(set) var isPaused = false
    @Published public private(set) var isAppInForeground = false
    @Published public private(set) var mode: PollingMode = .normal

    private var loopTask: Task<Void, Never>?
    private var forceSlowNext = true

    public init(
        pollIntervalProvider: @escaping () -> TimeInterval,
        onNormalTick: @escaping (_ forceSlow: Bool) async -> Void,
        onAlignmentTick: @escaping () async -> Void = {}
    ) {
        self.pollIntervalProvider = pollIntervalProvider
        self.onNormalTick = onNormalTick
        self.onAlignmentTick = onAlignmentTick
    }

    public func onForegroundChanged(_ inForeground: Bool) {
        guard isAppInForeground != inForeground else { return }
        isAppInForeground = inForeground
        syncLoop()
    }

    public func setPaused(_ paused: Bool) {
        guard isPaused != paused else { return }
        isPaused = paused
        syncLoop()
    }

    @discardableResult
    public func togglePause() -> Bool {
        let next = !isPaused
        setPaused(next)
        return next
    }

    public func setMode(_ newMode: PollingMode) {
        guard mode != newMode else { return }
        mode = newMode
        restartLoop()
    }

    public func onIntervalChanged() {
        restartLoop()
    }

    public func triggerImmediateRefresh(includeSlow: Bool = true) {
        if includeSlow { forceSlowNext = true }
        Task { [weak self] in
            await self?.runOneTick()
        }
    }

    public func shutdown() {
        loopTask?.cancel()
        loopTask = nil
    }

    private func syncLoop() {
        let shouldRun = isAppInForeground && !isPaused
        if shouldRun {
            if loopTask == nil || loopTask?.isCancelled == true {
                startLoop()
            }
        } else {
            loopTask?.cancel()
            loopTask = nil
        }
    }

    private func restartLoop() {
        loopTask?.cancel()
        loopTask = nil
        syncLoop()
    }

    private func startLoop() {
        loopTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled && self.isAppInForeground && !self.isPaused {
                await self.runOneTick()
                let delaySec: TimeInterval = {
                    switch self.mode {
                    case .alignment: return 2.0
                    case .normal: return max(0.5, self.pollIntervalProvider())
                    }
                }()
                try? await Task.sleep(nanoseconds: UInt64(delaySec * 1_000_000_000))
            }
        }
    }

    private func runOneTick() async {
        switch mode {
        case .alignment:
            await onAlignmentTick()
        case .normal:
            let slow = forceSlowNext
            forceSlowNext = false
            await onNormalTick(slow)
        }
    }
}
