import Foundation
import Combine

@MainActor
public final class AppContainer: ObservableObject {
    public let preferences: AppPreferencesStore
    public let ouiLookup: OuiVendorLookup
    public let transport: RouterTransport
    public let session: RouterSession
    public let writer: SettingsWriter

    public let featureFlagRepository: FeatureFlagRepository
    public let statusRepository: StatusRepository
    public let signalRepository: SignalRepository
    public let deviceRepository: DeviceRepository
    public let wifiRepository: WifiRepository
    public let mobileRepository: MobileRepository
    public let simRepository: SimRepository
    public let lanRepository: LanRepository
    public let smsRepository: SmsRepository
    public let securityRepository: SecurityRepository
    public let parentalControlRepository: ParentalControlRepository
    public let systemRepository: SystemRepository
    public let ussdRepository: UssdRepository

    public let speedTestRunner: SpeedTestRunner
    public let gatewayDetector: NetworkGatewayDetector
    public let alignmentTonePlayer: AlignmentTonePlayer
    public let pollingCoordinator: PollingCoordinator

    @Published public private(set) var statusMessage: String?
    private var toastDismissTask: Task<Void, Never>?
    private var cancellables: Set<AnyCancellable> = []

    public init(
        preferences: AppPreferencesStore? = nil,
        transport: RouterTransport = URLSessionRouterTransport()
    ) {
        let prefs = preferences ?? AppPreferencesStore()
        self.preferences = prefs
        self.ouiLookup = OuiVendorLookup.fromBundle()
        self.transport = transport

        let session = RouterSession(
            transport: transport,
            credentialsProvider: { @MainActor in
                prefs.currentCredentials
            },
            onLoginSuccess: {
                await MainActor.run {
                    prefs.markOnboardingCompletedIfSavedCredentials()
                }
            }
        )
        self.session = session
        self.writer = SettingsWriter(session: session)

        let flagRepo = FeatureFlagRepository(session: session)
        let statusRepo = StatusRepository(
            session: session,
            preferences: prefs,
            ouiLookup: ouiLookup
        )
        self.featureFlagRepository = flagRepo
        self.statusRepository = statusRepo
        self.signalRepository = SignalRepository(session: session, writer: writer)
        self.deviceRepository = DeviceRepository(preferences: prefs)
        self.wifiRepository = WifiRepository(session: session, writer: writer)
        self.mobileRepository = MobileRepository(session: session, writer: writer)
        self.simRepository = SimRepository(session: session, writer: writer)
        self.lanRepository = LanRepository(session: session, writer: writer)
        self.smsRepository = SmsRepository(
            session: session,
            writer: writer,
            onUnreadCountSync: { [weak statusRepo] count in
                statusRepo?.setUnreadSmsCount(count)
            }
        )
        self.securityRepository = SecurityRepository(session: session, writer: writer)
        self.parentalControlRepository = ParentalControlRepository(session: session, writer: writer)
        self.systemRepository = SystemRepository(session: session, writer: writer, preferences: prefs)
        self.ussdRepository = UssdRepository(session: session, writer: writer)

        self.speedTestRunner = SpeedTestRunner()
        self.gatewayDetector = NetworkGatewayDetector()
        self.alignmentTonePlayer = AlignmentTonePlayer()

        self.pollingCoordinator = PollingCoordinator(
            pollIntervalProvider: { prefs.pollIntervalSeconds },
            onNormalTick: { forceSlow in
                let res = await statusRepo.pollFast(forceSlow: forceSlow)
                if res.isOk && !flagRepo.flags.isLoaded {
                    _ = await flagRepo.load()
                }
            },
            onAlignmentTick: {
                _ = await statusRepo.pollAlignment()
            }
        )

        // Relay objectWillChange from child observables needed by app root UI
        prefs.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)
    }

    public func emitStatusMessage(_ message: String) {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        statusMessage = trimmed
        toastDismissTask?.cancel()
        toastDismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_200_000_000)
            guard !Task.isCancelled else { return }
            self?.statusMessage = nil
        }
    }

    public func clearStatusMessage() {
        toastDismissTask?.cancel()
        toastDismissTask = nil
        statusMessage = nil
    }
}
