import SwiftUI

/// Main screen: four tabs (Home, Network, Tools, Devices) under a Liquid Glass tab bar, each
/// with the header bar and offline banner on top. Mirrors the Android app's bottom bar.
public struct DashboardView: View {
    @StateObject private var viewModel = MonitorViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var showRebootAlert = false
    @State private var showLocks = false
    @State private var selection: Tab = .home
    @State private var pendingDnsScroll = false

    enum Tab: Hashable { case home, network, tools, devices }

    public init() {}

    public var body: some View {
        // On iOS 26 the system draws this as the floating Liquid Glass tab bar: the selection
        // is a glass lens that morphs between tabs, and the bar shrinks as you scroll.
        TabView(selection: $selection) {
            tabPage {
                HeroMetricsView(wan: viewModel.state.wan)
                CellularCardView(
                    cellular: viewModel.state.cellular,
                    cellLock: viewModel.state.cellLock,
                    hardware: viewModel.state.hardware,
                    onEditLocks: { showLocks = true },
                    networkBusy: viewModel.isNetworkBusy,
                    onMobileData: { on in Task { await viewModel.setMobileData(on) } },
                    onFlightMode: { on in Task { await viewModel.setFlightMode(on) } },
                    onRescan: { Task { await viewModel.rescanNetwork() } }
                )
            }
            .tabItem { Label("Home", systemImage: "house.fill") }
            .tag(Tab.home)

            tabPage(scrollsToDns: true) {
                WifiRadiosCardView(viewModel: viewModel)
                LanDnsCardView(viewModel: viewModel).id("dns")
            }
            .tabItem { Label("Network", systemImage: "wifi") }
            .tag(Tab.network)

            tabPage {
                DiagnosisCardView(viewModel: viewModel)
            }
            .tabItem { Label("Tools", systemImage: "network") }
            .tag(Tab.tools)

            tabPage {
                DeviceFleetView(viewModel: viewModel)
            }
            .tabItem { Label("Devices", systemImage: "laptopcomputer.and.iphone") }
            .tag(Tab.devices)
        }
        .modifier(MinimizingTabBar())
        .overlay(alignment: .bottom) {
            if let toast = viewModel.toast {
                Text(toast)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .liquidGlassPanel(cornerRadius: 14)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 64)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { viewModel.toast = nil }
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: viewModel.toast)
        .sheet(isPresented: $showSettings) {
            SettingsSheetView { viewModel.settingsChanged() }
        }
        .sheet(isPresented: $showLocks) {
            LockEditorView(viewModel: viewModel)
        }
        .alert("Reboot Router?", isPresented: $showRebootAlert) {
            Button("Cancel", role: .cancel) {}
            Button("Reboot", role: .destructive) {
                Task {
                    await viewModel.reboot()
                    if let msg = viewModel.rebootStatusMessage { viewModel.showToast(msg) }
                }
            }
        } message: {
            Text("This restarts the ZLT X17M 5G router. Internet connectivity will drop for 1–2 minutes.")
        }
        .onAppear {
            viewModel.startPolling()
            Task {
                await viewModel.loadDns()
                await viewModel.loadWifiRadios()
            }
        }
        .onChange(of: scenePhase) { newPhase in
            switch newPhase {
            case .active: viewModel.startPolling()
            case .inactive, .background: viewModel.stopPolling()
            @unknown default: break
            }
        }
    }

    /// One scrollable tab: the header and offline banner, then the tab's own cards.
    private func tabPage<Content: View>(
        scrollsToDns: Bool = false,
        @ViewBuilder content: () -> Content
    ) -> some View {
        let inner = content()
        return ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 16) {
                    HeaderBarView(
                        viewModel: viewModel,
                        onOpenDns: {
                            Task { await viewModel.loadDns() }
                            if scrollsToDns {
                                withAnimation { proxy.scrollTo("dns", anchor: .top) }
                            } else {
                                pendingDnsScroll = true
                                selection = .network
                            }
                        },
                        onOpenSettings: { showSettings = true },
                        onOpenReboot: { showRebootAlert = true }
                    )

                    if viewModel.connectionStatus == .reconnecting {
                        offlineBanner
                    }

                    inner
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .padding(.bottom, 24)
            }
            .background { LiquidGlassBackground() }
            .refreshable { await viewModel.reloadAll() }
            .onAppear {
                guard scrollsToDns, pendingDnsScroll else { return }
                pendingDnsScroll = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    withAnimation { proxy.scrollTo("dns", anchor: .top) }
                }
            }
        }
    }

    private var offlineBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(StatusColors.error)
            VStack(alignment: .leading, spacing: 2) {
                Text("Cannot reach router at \(viewModel.routerHost)").font(.subheadline.weight(.semibold))
                Text("Ensure your phone is connected to the router's Wi-Fi network.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .liquidGlassPanel(cornerRadius: 16, padding: 14)
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(StatusColors.error.opacity(0.4), lineWidth: 1))
    }
}

/// Collapses the iOS 26 tab bar while scrolling down; a no-op on earlier versions.
private struct MinimizingTabBar: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            content
        }
    }
}
