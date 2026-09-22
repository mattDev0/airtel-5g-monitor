import SwiftUI

public struct HeaderBarView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @Binding var showSettings: Bool
    @State private var showRebootAlert: Bool = false

    public init(viewModel: MonitorViewModel, showSettings: Binding<Bool>) {
        self.viewModel = viewModel
        self._showSettings = showSettings
    }

    public var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Airtel 5G Monitor")
                    .font(.headline)
                    .fontWeight(.bold)

                HStack(spacing: 6) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 8, height: 8)
                        .shadow(color: statusColor.opacity(0.8), radius: 4)

                    Text(statusText)
                        .font(.caption)
                        .foregroundColor(statusColor)
                        .fontWeight(.bold)

                    if viewModel.isConnected {
                        Text("• \(viewModel.state.timeFormatted)")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Spacer()

            HStack(spacing: 10) {
                Button {
                    Task { await viewModel.reloadAll() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 15, weight: .semibold))
                        .padding(9)
                        .background(.thinMaterial)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 0.8))
                }
                .disabled(viewModel.isRefreshing)

                Button {
                    showRebootAlert = true
                } label: {
                    Image(systemName: "power")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.red)
                        .padding(9)
                        .background(.thinMaterial)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 0.8))
                }

                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .padding(9)
                        .background(.thinMaterial)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 0.8))
                }
            }
        }
        .liquidGlassCard()
        .alert("Reboot Router?", isPresented: $showRebootAlert) {
            Button("Cancel", role: .cancel) { }
            Button("Reboot", role: .destructive) {
                Task { await viewModel.reboot() }
            }
        } message: {
            Text("This will send a hardware reboot command to the router at \(viewModel.state.cellular.boardType). Connection will drop temporarily.")
        }
    }

    private var statusColor: Color {
        if viewModel.isConnected {
            return .green
        } else if viewModel.isRefreshing {
            return .orange
        } else {
            return .red
        }
    }

    private var statusText: String {
        if viewModel.isConnected {
            return "LIVE"
        } else if viewModel.isRefreshing {
            return "CONNECTING"
        } else {
            return "OFFLINE"
        }
    }
}
