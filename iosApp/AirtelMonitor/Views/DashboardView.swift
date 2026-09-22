import SwiftUI

public struct DashboardView: View {
    @StateObject private var viewModel = MonitorViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings: Bool = false

    public init() {}

    public var body: some View {
        NavigationView {
            ZStack {
                Color(.systemGroupedBackground)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 16) {
                        HeaderBarView(viewModel: viewModel, showSettings: $showSettings)

                        if let err = viewModel.errorMessage, !viewModel.isConnected {
                            HStack {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.red)
                                Text(err)
                                    .font(.caption)
                                    .foregroundColor(.red)
                                Spacer()
                            }
                            .padding(10)
                            .background(Color.red.opacity(0.1))
                            .cornerRadius(8)
                            .padding(.horizontal)
                        }

                        // Main sections
                        VStack(spacing: 14) {
                            HeroMetricsView(wan: viewModel.state.wan)
                            CellularCardView(cellular: viewModel.state.cellular, cellLock: viewModel.state.cellLock)
                            WifiRadiosCardView(viewModel: viewModel)
                            LanDnsCardView(viewModel: viewModel)
                            DiagnosisCardView(viewModel: viewModel)
                            DeviceFleetView(viewModel: viewModel)
                        }
                        .padding(.horizontal)
                        .padding(.bottom, 24)
                    }
                }
                .refreshable {
                    await viewModel.reloadAll()
                }
            }
            .navigationBarHidden(true)
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .sheet(isPresented: $showSettings) {
            SettingsSheetView()
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
