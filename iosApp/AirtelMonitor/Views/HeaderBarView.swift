import SwiftUI

/// Slim app bar, same layout as Android's HeaderBar: name and live status on the left,
/// a pause/resume button and an overflow menu on the right. Refresh, poll interval,
/// DNS, settings and reboot live in the menu so the bar stays out of the way.
public struct HeaderBarView: View {
    @ObservedObject var viewModel: MonitorViewModel
    let onOpenDns: () -> Void
    let onOpenSettings: () -> Void
    let onOpenReboot: () -> Void
    @State private var pulse = false

    private let pollOptions: [Double] = [1.0, 1.5, 2.0, 3.0, 5.0]

    public var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "wifi.router")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 40, height: 40)
                .background(Color.accentColor.opacity(0.18), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text("Airtel 5G")
                    .font(.title2.weight(.bold))
                    .lineLimit(1)
                statusLine
            }

            Spacer(minLength: 8)

            Button {
                viewModel.togglePause()
            } label: {
                Image(systemName: viewModel.isPaused ? "play.fill" : "pause.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(viewModel.isPaused ? StatusColors.warning : .secondary)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(viewModel.isPaused ? "Resume" : "Pause")

            Menu {
                Button {
                    Task { await viewModel.reloadAll() }
                } label: {
                    Label("Refresh now", systemImage: "arrow.clockwise")
                }

                Section("Update every") {
                    ForEach(pollOptions, id: \.self) { interval in
                        Button {
                            viewModel.setPollInterval(interval)
                        } label: {
                            if interval == viewModel.pollInterval {
                                Label(Self.intervalLabel(interval), systemImage: "checkmark")
                            } else {
                                Text(Self.intervalLabel(interval))
                            }
                        }
                    }
                }

                Section {
                    Button(action: onOpenDns) { Label("DNS", systemImage: "server.rack") }
                    Button(action: onOpenSettings) { Label("Settings", systemImage: "gearshape") }
                    Button(role: .destructive, action: onOpenReboot) { Label("Reboot router", systemImage: "power") }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("More")
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
        .onAppear { pulse = true }
    }

    private var statusLine: some View {
        let (text, color): (String, Color) = {
            if viewModel.isPaused { return ("Paused", StatusColors.warning) }
            switch viewModel.connectionStatus {
            case .connected: return ("Live", StatusColors.success)
            case .connecting: return ("Connecting", .accentColor)
            case .reconnecting: return ("Reconnecting", StatusColors.error)
            }
        }()
        let live = viewModel.connectionStatus == .connected && !viewModel.isPaused
        return HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 7, height: 7)
                .scaleEffect(live && pulse ? 1.2 : 0.8)
                .animation(live ? .easeInOut(duration: 1.2).repeatForever(autoreverses: true) : .default, value: pulse)
            Text(text)
                .font(.caption.weight(.semibold))
                .foregroundStyle(color)
            Text("· \(Self.intervalLabel(viewModel.pollInterval)) · \(viewModel.routerHost)")
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.tail)
        }
    }

    static func intervalLabel(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(value))s" : String(format: "%.1fs", value)
    }
}
