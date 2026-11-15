import Foundation
import Combine

@MainActor
public final class MessagesViewModel: ObservableObject {
    private let container: AppContainer
    private var cancellables: Set<AnyCancellable> = []
    private var initialLoaded = false
    private let shortcutsKey = "v3_ussd_shortcuts"

    @Published public var activeFolder: SmsFolder = .inbox
    @Published public private(set) var inboxPage = SmsPage(folder: .inbox)
    @Published public private(set) var outboxPage = SmsPage(folder: .outbox)
    @Published public private(set) var settings = SmsSettings()
    @Published public private(set) var unreadCount: Int = 0

    @Published public private(set) var isLoading = false
    @Published public private(set) var isBusy = false
    @Published public private(set) var error: String?
    @Published public var selectedMessage: SmsMessage?

    @Published public var showComposeSheet = false
    @Published public var composeInitialPhone = ""
    @Published public var showSettingsSheet = false
    @Published public var showUssdSheet = false

    @Published public private(set) var ussdAvailable = true
    @Published public private(set) var ussdState = UssdSessionState()
    @Published public var ussdShortcuts: [String] = ["*121#", "*141#", "*123#", "*310#"]

    public var visiblePage: SmsPage {
        activeFolder == .inbox ? inboxPage : outboxPage
    }

    public init(container: AppContainer) {
        self.container = container
        if let saved = UserDefaults.standard.stringArray(forKey: shortcutsKey), !saved.isEmpty {
            self.ussdShortcuts = saved
        }
        sync()

        container.smsRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.ussdRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.featureFlagRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
        container.statusRepository.objectWillChange
            .sink { [weak self] _ in Task { @MainActor in self?.sync() } }
            .store(in: &cancellables)
    }

    private func sync() {
        inboxPage = container.smsRepository.inboxPage
        outboxPage = container.smsRepository.outboxPage
        settings = container.smsRepository.settings
        unreadCount = container.statusRepository.routerState?.unreadSmsCount ?? container.smsRepository.unreadCount
        ussdState = container.ussdRepository.sessionState
        let flags = container.featureFlagRepository.flags
        ussdAvailable = !flags.isLoaded || flags.ussdAvailable
    }

    public func ensureLoaded() {
        guard !initialLoaded else { return }
        initialLoaded = true
        refresh()
    }

    public func selectFolder(_ folder: SmsFolder) {
        activeFolder = folder
        refresh(folder: folder, page: 1)
    }

    public func refresh(folder: SmsFolder? = nil, page: Int? = nil) {
        let targetFolder = folder ?? activeFolder
        let targetPage = page ?? (targetFolder == .inbox ? inboxPage.page : outboxPage.page)
        isLoading = true
        error = nil
        Task {
            if !container.featureFlagRepository.flags.isLoaded {
                _ = await container.featureFlagRepository.load()
            }
            let res = await container.smsRepository.loadPage(folder: targetFolder, page: max(1, targetPage))
            _ = await container.smsRepository.loadSettings()
            isLoading = false
            if !res.isOk {
                error = res.errorMessage
            }
        }
    }

    public func openMessage(_ msg: SmsMessage) {
        selectedMessage = msg
        if msg.folder == .inbox && !msg.isRead {
            Task {
                _ = await container.smsRepository.markRead(indices: [msg.index])
            }
        }
    }

    public func markAllRead() {
        guard !isBusy else { return }
        isBusy = true
        Task {
            let res = await container.smsRepository.markAllRead()
            isBusy = false
            container.emitStatusMessage(res.isOk ? "All messages marked read" : (res.errorMessage ?? "Failed"))
        }
    }

    public func sendSms(to phone: String, body: String) {
        guard !isBusy else { return }
        isBusy = true
        error = nil
        Task {
            let res = await container.smsRepository.sendSms(phoneNumber: phone, body: body)
            isBusy = false
            if res.isOk {
                showComposeSheet = false
                composeInitialPhone = ""
                container.emitStatusMessage("SMS sent")
            } else {
                error = res.errorMessage
            }
        }
    }

    public func deleteMessage(_ msg: SmsMessage) {
        guard !isBusy else { return }
        isBusy = true
        Task {
            let res = await container.smsRepository.deleteMessages(folder: msg.folder, indices: [msg.index])
            isBusy = false
            if selectedMessage?.id == msg.id { selectedMessage = nil }
            container.emitStatusMessage(res.isOk ? "Message deleted" : (res.errorMessage ?? "Delete failed"))
        }
    }

    public func deleteAllInCurrentFolder() {
        guard !isBusy else { return }
        let folder = activeFolder
        isBusy = true
        Task {
            let res = await container.smsRepository.deleteAll(folder: folder)
            isBusy = false
            container.emitStatusMessage(res.isOk ? "Cleared \(folder.label)" : (res.errorMessage ?? "Delete failed"))
        }
    }

    public func saveSmsSettings(enabled: Bool, smsc: String, maxSize: Int) {
        guard !isBusy else { return }
        isBusy = true
        error = nil
        Task {
            let res = await container.smsRepository.saveSettings(smsEnabled: enabled, smscNumber: smsc, maxSize: maxSize)
            isBusy = false
            if res.isOk {
                showSettingsSheet = false
                container.emitStatusMessage("SMS settings saved")
            } else {
                error = res.errorMessage
            }
        }
    }

    public func sendUssd(_ codeOrReply: String) {
        let trimmed = codeOrReply.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            let res = await container.ussdRepository.send(input: trimmed)
            if res.isOk && trimmed.hasPrefix("*") && trimmed.hasSuffix("#") && !ussdShortcuts.contains(trimmed) {
                ussdShortcuts = ([trimmed] + ussdShortcuts).prefix(8).map { $0 }
                UserDefaults.standard.set(ussdShortcuts, forKey: shortcutsKey)
            }
        }
    }

    public func cancelUssdSession() {
        Task {
            _ = await container.ussdRepository.cancelSession()
        }
    }

    public func clearUssdHistory() {
        container.ussdRepository.clearHistory()
    }
}
