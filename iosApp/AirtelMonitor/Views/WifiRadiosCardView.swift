import SwiftUI

public struct WifiRadiosCardView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var pendingBand: String? = nil
    @State private var showConfirmToggle: Bool = false
    @State private var targetToggleValue: Bool = false

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "wifi")
                    .foregroundColor(.accentColor)
                    .font(.subheadline)
                Text("Wi-Fi Radios")
                    .font(.subheadline)
                    .fontWeight(.bold)
                Spacer()

                if viewModel.isWifiLoading {
                    ProgressView()
                        .scaleEffect(0.8)
                }
            }

            VStack(spacing: 8) {
                // 2.4 GHz Radio
                RadioRow(
                    label: "2.4 GHz Wi-Fi",
                    ssid: viewModel.wifiRadios.ssid24g,
                    channel: viewModel.wifiRadios.channel24g,
                    isOn: viewModel.wifiRadios.enabled24g,
                    isLoading: viewModel.isWifiLoading
                ) { newValue in
                    confirmToggle(band: "2.4 GHz", newValue: newValue)
                }

                Divider()

                // 5 GHz Radio
                RadioRow(
                    label: "5 GHz Wi-Fi (Wi-Fi 6)",
                    ssid: viewModel.wifiRadios.ssid5g,
                    channel: viewModel.wifiRadios.channel5g,
                    isOn: viewModel.wifiRadios.enabled5g,
                    isLoading: viewModel.isWifiLoading
                ) { newValue in
                    confirmToggle(band: "5 GHz", newValue: newValue)
                }
            }

            if let msg = viewModel.wifiStatusMessage {
                Text(msg)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .padding(.top, 2)
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.04), radius: 3, x: 0, y: 1)
        .alert("Toggle Wi-Fi Radio?", isPresented: $showConfirmToggle) {
            Button("Cancel", role: .cancel) { }
            Button(targetToggleValue ? "Turn On" : "Turn Off", role: targetToggleValue ? .none : .destructive) {
                executeToggle()
            }
        } message: {
            Text("Changing Wi-Fi radio state restarts the wireless subsystem for 5–10 seconds. Devices on this radio will disconnect.")
        }
    }

    private func confirmToggle(band: String, newValue: Bool) {
        self.pendingBand = band
        self.targetToggleValue = newValue
        self.showConfirmToggle = true
    }

    private func executeToggle() {
        guard let band = pendingBand else { return }
        Task {
            if band == "2.4 GHz" {
                await viewModel.toggleWifi24g(enabled: targetToggleValue)
            } else {
                await viewModel.toggleWifi5g(enabled: targetToggleValue)
            }
        }
    }
}

private struct RadioRow: View {
    let label: String
    let ssid: String
    let channel: String
    let isOn: Bool
    let isLoading: Bool
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.subheadline)
                    .fontWeight(.medium)
                HStack(spacing: 4) {
                    Text(ssid.isEmpty ? "Hidden / Disabled" : ssid)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    if !channel.isEmpty {
                        Text("• Ch \(channel)")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }

            Spacer()

            Toggle("", isOn: Binding(
                get: { isOn },
                set: { onToggle($0) }
            ))
            .labelsHidden()
            .disabled(isLoading)
        }
    }
}
