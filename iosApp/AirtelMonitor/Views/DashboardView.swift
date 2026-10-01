import SwiftUI

/// Main screen, laid out like the Android app: header card, offline banner, then the
/// speed, cellular, Wi-Fi, DNS, diagnosis and device cards in the same order.
public struct DashboardView: View {
    @StateObject private var viewModel = MonitorViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var showRebootAlert = false
    @State private var showLocks = false

    public init() {}

    public var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 16) {
                    HeaderBarView(
                        viewModel: viewModel,
                        onOpenDns: {
                            Task { await viewModel.loadDns() }
                            withAnimation { proxy.scrollTo("dns", anchor: .top) }
                        },
                        onOpenSettings: { showSettings = true },
                        onOpenReboot: { showRebootAlert = true }
                    )

                    if viewModel.connectionStatus == .reconnecting {
                        offlineBanner
                    }

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
                    WifiRadiosCardView(viewModel: viewModel)
                    LanDnsCardView(viewModel: viewModel).id("dns")
                    DiagnosisCardView(viewModel: viewModel)
                    DeviceFleetView(viewModel: viewModel)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .padding(.bottom, 24)
            }
            .background { LiquidGlassBackground() }
            .refreshable { await viewModel.reloadAll() }
        }
        .overlay(alignment: .bottom) {
            if let toast = viewModel.toast {
                Text(toast)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .liquidGlassPanel(cornerRadius: 14)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
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
