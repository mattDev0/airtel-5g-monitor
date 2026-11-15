import Foundation
import Combine

@MainActor
public final class ShellViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []

    @Published public private(set) var connectionStatus: ConnectionStatus = .connecting
    @Published public private(set) var isPaused: Bool = false
    @Published public private(set) var pollIntervalSeconds: Double = 2.0
    @Published public private(set) var routerHost: String = "192.168.1.1"
    @Published public private(set) var unreadSmsCount: Int = 0
    @Published public private(set) var isAuthError: Bool = false
    @Published public private(set) var lastErrorMessage: String?
    @Published public private(set) var onboardingCompleted: Bool = true

    @Published public var showRebootConfirm: Bool = false
    @Published public private(set) var isRebooting: Bool = false

    public init(container: AppContainer) {
        self.container = container
        syncFromContainer()

        container.statusRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
        container.pollingCoordinator.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
        container.preferences.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.syncFromContainer() } }
            .store(in: &cancellables)
    }

    private func syncFromContainer() {
        connectionStatus = container.statusRepository.connectionStatus
        isAuthError = container.statusRepository.isAuthError
        lastErrorMessage = container.statusRepository.lastErrorMessage
        unreadSmsCount = container.statusRepository.routerState?.unreadSmsCount ?? container.smsRepository.unreadCount
        isPaused = container.pollingCoordinator.isPaused
        pollIntervalSeconds = container.preferences.pollIntervalSeconds
        routerHost = container.preferences.routerHost
        onboardingCompleted = container.preferences.onboardingCompleted
    }

    public func togglePause() {
        let paused = container.pollingCoordinator.togglePause()
        container.emitStatusMessage(paused ? "Telemetry paused" : "Telemetry resumed")
        if !paused {
            container.pollingCoordinator.triggerImmediateRefresh(includeSlow: false)
        }
    }

    public func forceRefresh() {
        container.pollingCoordinator.triggerImmediateRefresh(includeSlow: true)
    }

    public func setPollInterval(_ seconds: Double) {
        container.preferences.setPollInterval(seconds)
        container.pollingCoordinator.onIntervalChanged()
    }

    public func confirmReboot() {
        guard !isRebooting else { return }
        isRebooting = true
        Task {
            let res = await container.systemRepository.rebootRouter()
            isRebooting = false
            showRebootConfirm = false
            if res.isOk {
                container.emitStatusMessage("Router is rebooting...")
                container.statusRepository.resetState()
            } else {
                container.emitStatusMessage(res.errorMessage ?? "Reboot failed")
            }
        }
    }
}
