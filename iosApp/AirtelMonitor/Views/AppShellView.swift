import SwiftUI

public enum AppTab: Int, Hashable, CaseIterable {
    case home
    case signal
    case devices
    case settings
}

public enum AppRoute: Hashable {
    case alignment
    case messages
    case usage
    case security
    case system
}

private struct TabBarMinimizeModifier: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            content
        }
    }
}

public struct AppShellView: View {
    @ObservedObject public var container: AppContainer
    @StateObject private var shellVM: ShellViewModel

    @State private var selectedTab: AppTab = .home
    @State private var homePath: [AppRoute] = []
    @State private var signalPath: [AppRoute] = []
    @State private var devicesPath: [AppRoute] = []
    @State private var settingsPath: [AppRoute] = []
    @State private var openConnectionSheetInSettings = false

    public init(container: AppContainer) {
        self.container = container
        _shellVM = StateObject(wrappedValue: ShellViewModel(container: container))
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                NavigationStack(path: $homePath) {
                    HomeView(
                        container: container,
                        shellVM: shellVM,
                        onNavigate: { route in push(route, on: .home) },
                        onSwitchTab: { tab in selectedTab = tab }
                    )
                    .navigationDestination(for: AppRoute.self) { route in
                        destinationView(for: route)
                    }
                }
                .tabItem {
                    Label("Home", systemImage: "house.fill")
                }
                .tag(AppTab.home)

                NavigationStack(path: $signalPath) {
                    SignalView(
                        container: container,
                        shellVM: shellVM,
                        onNavigate: { route in push(route, on: .signal) }
                    )
                    .navigationDestination(for: AppRoute.self) { route in
                        destinationView(for: route)
                    }
                }
                .tabItem {
                    Label("Signal", systemImage: "antenna.radiowaves.left.and.right")
                }
                .tag(AppTab.signal)

                NavigationStack(path: $devicesPath) {
                    DevicesView(
                        container: container,
                        shellVM: shellVM
                    )
                    .navigationDestination(for: AppRoute.self) { route in
                        destinationView(for: route)
                    }
                }
                .tabItem {
                    Label("Devices", systemImage: "laptopcomputer.and.iphone")
                }
                .tag(AppTab.devices)

                NavigationStack(path: $settingsPath) {
                    SettingsHubView(
                        container: container,
                        shellVM: shellVM,
                        openConnectionTrigger: $openConnectionSheetInSettings,
                        onNavigate: { route in push(route, on: .settings) }
                    )
                    .navigationDestination(for: AppRoute.self) { route in
                        destinationView(for: route)
                    }
                }
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
                .badge(shellVM.unreadSmsCount > 0 ? shellVM.unreadSmsCount : 0)
                .tag(AppTab.settings)
            }
            .tint(AirtelTheme.accent)
            .modifier(TabBarMinimizeModifier())

            if let toast = container.statusMessage, !toast.isEmpty {
                toastBanner(toast)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 64)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: container.statusMessage)
        .fullScreenCover(isPresented: Binding(
            get: { !shellVM.onboardingCompleted },
            set: { _ in }
        )) {
            OnboardingView(container: container)
        }
        .sheet(isPresented: $shellVM.showRebootConfirm) {
            ConfirmSheet(
                request: ConfirmPromptRequest(
                    title: "Reboot Router?",
                    impactMessage: "All Wi-Fi, LAN, and cellular connections will drop for ~60–90 seconds while the ZLT X17M restarts.",
                    confirmLabel: "Reboot Router",
                    tier: .destructive,
                    requiredConfirmationText: "ZLT X17M",
                    onConfirm: {
                        shellVM.confirmReboot()
                    }
                )
            )
            .presentationDetents([.medium])
        }
    }

    private func push(_ route: AppRoute, on tab: AppTab) {
        selectedTab = tab
        switch tab {
        case .home:
            homePath.append(route)
        case .signal:
            signalPath.append(route)
        case .devices:
            devicesPath.append(route)
        case .settings:
            settingsPath.append(route)
        }
    }

    @ViewBuilder
    private func destinationView(for route: AppRoute) -> some View {
        switch route {
        case .alignment:
            AlignmentView(container: container)
        case .messages:
            MessagesView(container: container)
        case .usage:
            DataUsageView(container: container)
        case .security:
            SecurityView(container: container)
        case .system:
            SystemToolsView(container: container, shellVM: shellVM)
        }
    }

    private func toastBanner(_ message: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "info.circle.fill")
                .foregroundStyle(AirtelTheme.accent)
            Text(message)
                .font(.caption.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(2)
            Spacer(minLength: 4)
            Button {
                container.clearStatusMessage()
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .liquidGlassCapsule()
    }
}

public struct RouterShellToolbarControls: ToolbarContent {
    @ObservedObject public var shellVM: ShellViewModel
    public var onFixCredentials: () -> Void

    public init(shellVM: ShellViewModel, onFixCredentials: @escaping () -> Void) {
        self.shellVM = shellVM
        self.onFixCredentials = onFixCredentials
    }

    public var body: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            HStack(spacing: 8) {
                Button {
                    shellVM.togglePause()
                } label: {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(statusColor)
                            .frame(width: 7, height: 7)
                        Text(shellVM.isPaused ? "Paused" : statusLabel)
                            .font(.caption.weight(.semibold))
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(statusColor.opacity(0.14), in: Capsule())
                }
                .buttonStyle(.plain)

                Menu {
                    Button {
                        shellVM.forceRefresh()
                    } label: {
                        Label("Refresh Now", systemImage: "arrow.clockwise")
                    }
                    Button {
                        shellVM.togglePause()
                    } label: {
                        Label(shellVM.isPaused ? "Resume Polling" : "Pause Polling", systemImage: shellVM.isPaused ? "play.fill" : "pause.fill")
                    }
                    if shellVM.isAuthError || shellVM.connectionStatus == .disconnected {
                        Button {
                            onFixCredentials()
                        } label: {
                            Label("Connection Settings", systemImage: "key.fill")
                        }
                    }
                    Divider()
                    Button(role: .destructive) {
                        shellVM.showRebootConfirm = true
                    } label: {
                        Label("Reboot Router", systemImage: "power")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
    }

    private var statusColor: Color {
        if shellVM.isPaused { return .secondary }
        switch shellVM.connectionStatus {
        case .connected: return AirtelTheme.success
        case .connecting, .reconnecting: return AirtelTheme.warning
        case .disconnected: return AirtelTheme.error
        }
    }

    private var statusLabel: String {
        switch shellVM.connectionStatus {
        case .connected: return "Live"
        case .connecting: return "Connecting"
        case .reconnecting: return "Reconnecting"
        case .disconnected: return shellVM.isAuthError ? "Auth Error" : "Offline"
        }
    }
}

public struct RouterConnectivityBanner: View {
    @ObservedObject public var shellVM: ShellViewModel
    public var onFixCredentials: () -> Void

    public init(shellVM: ShellViewModel, onFixCredentials: @escaping () -> Void) {
        self.shellVM = shellVM
        self.onFixCredentials = onFixCredentials
    }

    public var body: some View {
        if shellVM.isAuthError || shellVM.connectionStatus == .disconnected {
            HStack(spacing: 10) {
                Image(systemName: shellVM.isAuthError ? "lock.trianglebadge.exclamationmark" : "wifi.exclamationmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AirtelTheme.error)
                VStack(alignment: .leading, spacing: 2) {
                    Text(shellVM.isAuthError ? "Router Authentication Required" : "Router Unreachable (\(shellVM.routerHost))")
                        .font(.caption.weight(.bold))
                    if let msg = shellVM.lastErrorMessage, !msg.isEmpty {
                        Text(msg)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                Spacer()
                Button(shellVM.isAuthError ? "Fix" : "Retry") {
                    if shellVM.isAuthError {
                        onFixCredentials()
                    } else {
                        shellVM.forceRefresh()
                    }
                }
                .font(.caption.weight(.semibold))
                .liquidGlassSmallButton()
            }
            .padding(12)
            .background(AirtelTheme.error.opacity(0.10), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(AirtelTheme.error.opacity(0.35), lineWidth: 0.75)
            )
        }
    }
}
