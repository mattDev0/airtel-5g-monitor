import Foundation
import Combine

public enum OnboardingTestStatus: Equatable {
    case idle
    case testing
    case connected(deviceLabel: String)
    case wrongPassword(String)
    case unreachable(String)

    public var isTesting: Bool {
        if case .testing = self { return true }
        return false
    }

    public var isVerified: Bool {
        if case .connected = self { return true }
        return false
    }
}

@MainActor
public final class OnboardingViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []

    @Published public var routerHost: String
    @Published public var username: String
    @Published public var password: String
    @Published public private(set) var isWifiConnected: Bool
    @Published public private(set) var detectedGatewayIp: String?
    @Published public private(set) var testStatus: OnboardingTestStatus = .idle

    public init(container: AppContainer) {
        self.container = container
        let prefs = container.preferences
        let gw = container.gatewayDetector.info

        let prefilledHost: String
        if gw.isWifiConnected, let suggested = gw.suggestedGatewayIp, !suggested.isEmpty,
           (prefs.routerHost.isEmpty || prefs.routerHost == "192.168.1.1") {
            prefilledHost = suggested
        } else {
            prefilledHost = prefs.routerHost.isEmpty ? "192.168.1.1" : prefs.routerHost
        }

        self.routerHost = prefilledHost
        self.username = prefs.username.isEmpty ? "user" : prefs.username
        self.password = prefs.password
        self.isWifiConnected = gw.isWifiConnected
        self.detectedGatewayIp = gw.suggestedGatewayIp

        container.gatewayDetector.objectWillChange
            .sink { [weak self] _ in
                Task { @MainActor in
                    guard let self else { return }
                    let info = self.container.gatewayDetector.info
                    self.isWifiConnected = info.isWifiConnected
                    self.detectedGatewayIp = info.suggestedGatewayIp
                    if info.isWifiConnected, let suggested = info.suggestedGatewayIp,
                       (self.routerHost.isEmpty || self.routerHost == "192.168.1.1") {
                        self.routerHost = suggested
                    }
                }
            }
            .store(in: &cancellables)
    }

    public func updateHost(_ value: String) {
        routerHost = value
        testStatus = .idle
    }

    public func updateUsername(_ value: String) {
        username = value
        testStatus = .idle
    }

    public func updatePassword(_ value: String) {
        password = value
        testStatus = .idle
    }

    public func applyDetectedGateway() {
        guard let gw = detectedGatewayIp else { return }
        routerHost = gw
        testStatus = .idle
    }

    public func testConnection() {
        let cleanHost = Self.sanitizeHost(routerHost)
        let cleanUser = username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "user" : username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanHost.isEmpty else {
            testStatus = .unreachable("Enter the router IP or hostname")
            return
        }

        testStatus = .testing
        let pwd = password
        let transport = container.transport

        Task {
            let tempSession = RouterSession(
                transport: transport,
                credentialsProvider: {
                    RouterCredentials(host: cleanHost, username: cleanUser, password: pwd)
                }
            )
            let loginRes = await tempSession.login()
            switch loginRes {
            case .ok:
                let r113 = await tempSession.execute(cmd: 113, method: "GET")
                let label = r113.getOrNull().flatMap { CodecHelpers.nonBlankStr($0, "board_type") } ?? "Airtel ODU"
                testStatus = .connected(deviceLabel: label)
            case .limitedAccess:
                testStatus = .connected(deviceLabel: "Airtel ODU")
            case .notLoggedIn(let msg):
                testStatus = .wrongPassword(msg.isEmpty ? "Incorrect router username or password" : msg)
            case .unreachable(let msg), .badResponse(let msg, _):
                testStatus = .unreachable(msg.isEmpty ? "Cannot reach router at \(cleanHost)" : msg)
            }
        }
    }

    public func saveAndContinue() {
        applyAndDismiss()
    }

    public func skipForNow() {
        applyAndDismiss()
    }

    private func applyAndDismiss() {
        let cleanHost = Self.sanitizeHost(routerHost)
        let finalHost = cleanHost.isEmpty ? "192.168.1.1" : cleanHost
        let cleanUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalUser = cleanUser.isEmpty ? "user" : cleanUser

        container.preferences.updateConnectionSettings(
            host: finalHost,
            username: finalUser,
            password: password,
            onboardingCompleted: true
        )
        Task {
            await container.session.resetSession()
            container.statusRepository.resetState()
            container.featureFlagRepository.reset()
            container.pollingCoordinator.triggerImmediateRefresh(includeSlow: true)
        }
    }

    public static func sanitizeHost(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "https://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
}
