import SwiftUI

/// Wi-Fi radios card, same layout and wording as Android's WifiRadiosCard.
public struct WifiRadiosCardView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var pendingBand: Band?

    enum Band { case ghz24, ghz5 }

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    private var busy: Bool { viewModel.isWifiLoading || viewModel.isWifiSaving }

    public var body: some View {
        let wifi = viewModel.wifiRadios
        VStack(alignment: .leading, spacing: 14) {
            AppCardHeader(title: "Wi-Fi Radios", subtitle: "2.4 GHz and 5 GHz broadcast control") {
                if viewModel.isWifiLoading {
                    ProgressView()
                } else {
                    GlassIconButton(systemImage: "arrow.clockwise", label: "Reload Wi-Fi status") {
                        Task { await viewModel.loadWifiRadios() }
                    }
                    .disabled(busy)
                }
            }

            RadioRow(title: "2.4 GHz Network", ssid: wifi.ssid24g, channel: wifi.channel24g,
                     enabled: wifi.enabled24g, canToggle: !busy) { pendingBand = .ghz24 }
            Divider().opacity(0.4)
            RadioRow(title: "5 GHz Network", ssid: wifi.ssid5g, channel: wifi.channel5g,
                     enabled: wifi.enabled5g, canToggle: !busy) { pendingBand = .ghz5 }

            if viewModel.isWifiSaving {
                HStack(spacing: 10) {
                    ProgressView()
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Applying radio changes...").font(.caption.weight(.semibold))
                        Text("Wi-Fi will momentarily drop (~5-10s) as the router reloads.")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .liquidGlassPill()
            } else if let msg = viewModel.wifiStatusMessage {
                Text(msg)
                    .font(.caption.monospaced())
                    .foregroundStyle(msg.hasPrefix("Error") ? StatusColors.error : Color.accentColor)
            }
        }
        .liquidGlassCard()
        .alert(alertTitle, isPresented: Binding(get: { pendingBand != nil }, set: { if !$0 { pendingBand = nil } })) {
            if blocked {
                Button("OK", role: .cancel) { pendingBand = nil }
            } else {
                Button("Cancel", role: .cancel) { pendingBand = nil }
                Button(targetEnabled ? "Turn On" : "Turn Off", role: targetEnabled ? nil : .destructive) {
                    let band = pendingBand
                    let target = targetEnabled
                    pendingBand = nil
                    Task {
                        if band == .ghz24 {
                            await viewModel.toggleWifi24g(enabled: target)
                        } else {
                            await viewModel.toggleWifi5g(enabled: target)
                        }
                    }
                }
            }
        } message: {
            Text(alertMessage)
        }
    }

    // MARK: Confirmation (mirrors the Android dialog)

    private var is24g: Bool { pendingBand == .ghz24 }
    private var bandLabel: String { is24g ? "2.4 GHz" : "5 GHz" }
    private var otherLabel: String { is24g ? "5 GHz" : "2.4 GHz" }
    private var targetEnabled: Bool { !(is24g ? viewModel.wifiRadios.enabled24g : viewModel.wifiRadios.enabled5g) }
    private var otherEnabled: Bool { is24g ? viewModel.wifiRadios.enabled5g : viewModel.wifiRadios.enabled24g }
    /// Turning off the only broadcasting band would disconnect this phone for good.
    private var blocked: Bool { !targetEnabled && !otherEnabled }

    private var alertTitle: String {
        blocked ? "Can't turn off \(bandLabel)" : "\(targetEnabled ? "Turn On" : "Turn Off") \(bandLabel) Wi-Fi?"
    }

    private var alertMessage: String {
        if blocked {
            return "\(otherLabel) is off, so \(bandLabel) is your only Wi-Fi. Turning it off would disconnect every wireless device, including this phone, and the app couldn't turn it back on. Turn on \(otherLabel) first."
        }
        let action = targetEnabled
            ? "The router will enable the \(bandLabel) radio broadcast."
            : "The router will disable the \(bandLabel) radio broadcast. Connected devices on this band will disconnect."
        return action + "\n\nNotice: The router wireless subsystem will restart immediately. Wi-Fi connections will briefly drop for 5–10 seconds."
    }
}

private struct RadioRow: View {
    let title: String
    let ssid: String
    let channel: String
    let enabled: Bool
    let canToggle: Bool
    let onToggleRequested: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            IconCircle(systemImage: enabled ? "wifi" : "wifi.slash", active: enabled)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(title).font(.subheadline.weight(.semibold))
                    AppBadge(text: enabled ? "Active" : "Off", color: enabled ? StatusColors.success : .secondary)
                }
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            Toggle("", isOn: Binding(get: { enabled }, set: { _ in onToggleRequested() }))
                .labelsHidden()
                .disabled(!canToggle)
        }
    }

    private var subtitle: String {
        var s = ssid.isEmpty ? (enabled ? "Broadcasting" : "Disabled") : ssid
        if !channel.isEmpty { s += " • Ch \(channel)" }
        return s
    }
}
