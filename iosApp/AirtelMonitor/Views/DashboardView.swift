import SwiftUI

public struct DashboardView: View {
    @StateObject private var viewModel = MonitorViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings: Bool = false
    @State private var showRebootAlert: Bool = false
    @State private var showLocks: Bool = false

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    HeaderBarView(viewModel: viewModel)
                        .padding(.horizontal)

                    if let err = viewModel.errorMessage, !viewModel.isConnected {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.red)
                            Text(err)
                                .font(.caption)
                                .foregroundColor(.primary)
                            Spacer()
                        }
                        .liquidGlassPanel(padding: 12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(Color.red.opacity(0.4), lineWidth: 1)
                        )
                        .padding(.horizontal)
                    }

                    // Main sections
                    VStack(spacing: 14) {
                        HeroMetricsView(wan: viewModel.state.wan)
                        CellularCardView(cellular: viewModel.state.cellular, cellLock: viewModel.state.cellLock) { showLocks = true }
                        WifiRadiosCardView(viewModel: viewModel)
                        LanDnsCardView(viewModel: viewModel)
                        DiagnosisCardView(viewModel: viewModel)
                        DeviceFleetView(viewModel: viewModel)
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 24)
                }
            }
            .background { LiquidGlassBackground() }
            .refreshable {
                await viewModel.reloadAll()
            }
            .navigationTitle("Airtel 5G")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        Task { await viewModel.reloadAll() }
                    } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                    .disabled(viewModel.isRefreshing)

                    Button(role: .destructive) {
                        showRebootAlert = true
                    } label: {
                        Label("Reboot Router", systemImage: "power")
                    }
                    .tint(.red)

                    Button {
                        showSettings = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsSheetView()
        }
        .sheet(isPresented: $showLocks) {
            LockEditorView(viewModel: viewModel)
        }
        .alert("Reboot Router?", isPresented: $showRebootAlert) {
            Button("Cancel", role: .cancel) { }
            Button("Reboot", role: .destructive) {
                Task { await viewModel.reboot() }
            }
        } message: {
            Text("This will send a hardware reboot command to the router at \(viewModel.state.cellular.boardType). Connection will drop temporarily.")
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
            case .active:
                viewModel.startPolling()
            case .inactive, .background:
                viewModel.stopPolling()
            @unknown default:
                break
            }
        }
    }
}
