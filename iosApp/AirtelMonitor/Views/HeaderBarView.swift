import SwiftUI

/// Top card, same content as Android's HeaderBar: name, live status, model and address,
/// poll rate, and the pause / refresh / DNS / settings / reboot buttons.
public struct HeaderBarView: View {
    @ObservedObject var viewModel: MonitorViewModel
    let onOpenDns: () -> Void
    let onOpenSettings: () -> Void
    let onOpenReboot: () -> Void
    @State private var pulse = false

    private let pollOptions: [Double] = [1.0, 1.5, 2.0, 3.0, 5.0]

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(systemName: "wifi")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 42, height: 42)
                    .background(Color.accentColor.opacity(0.18), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text("Airtel 5G").font(.headline.weight(.bold))
                        statusBadge
                    }
                    Text("\(modelName) • \(viewModel.routerHost)")
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }

            Divider().opacity(0.5)

            HStack {
                Menu {
                    ForEach(pollOptions, id: \.self) { interval in
                        Button {
                            viewModel.setPollInterval(interval)
                        } label: {
                            if interval == viewModel.pollInterval {
                                Label(Self.pollLabel(interval), systemImage: "checkmark")
                            } else {
                                Text(Self.pollLabel(interval))
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text("Poll: \(Self.seconds(viewModel.pollInterval))s")
                        Image(systemName: "chevron.down").font(.system(size: 10, weight: .bold))
                    }
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .liquidGlassPill(cornerRadius: 50)
                }

                Spacer(minLength: 8)

                HStack(spacing: 6) {
                    GlassIconButton(systemImage: viewModel.isPaused ? "play.fill" : "pause.fill",
                                    tint: viewModel.isPaused ? .orange : .primary,
                                    label: viewModel.isPaused ? "Resume" : "Pause") {
                        viewModel.togglePause()
                    }
                    GlassIconButton(systemImage: "arrow.clockwise", label: "Refresh") {
                        Task { await viewModel.reloadAll() }
                    }
                    GlassIconButton(systemImage: "server.rack", label: "DNS", action: onOpenDns)
                    GlassIconButton(systemImage: "gearshape.fill", label: "Settings", action: onOpenSettings)
                    GlassIconButton(systemImage: "power", tint: .red, label: "Reboot", action: onOpenReboot)
                }
            }
        }
        .liquidGlassCard()
        .onAppear { pulse = true }
    }

    private var modelName: String {
        let board = viewModel.state.cellular.boardType
        return board.isEmpty || board == "-" ? "ZLT X17M" : board
    }

    private var statusBadge: some View {
        let (text, color): (String, Color) = {
            if viewModel.isPaused { return ("PAUSED", StatusColors.warning) }
            switch viewModel.connectionStatus {
            case .connected: return ("LIVE", StatusColors.success)
            case .connecting: return ("CONNECTING", .accentColor)
            case .reconnecting: return ("RECONNECTING", StatusColors.error)
            }
        }()
        let live = viewModel.connectionStatus == .connected && !viewModel.isPaused
        return HStack(spacing: 5) {
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
                .scaleEffect(live && pulse ? 1.15 : 0.85)
                .animation(live ? .easeInOut(duration: 1.2).repeatForever(autoreverses: true) : .default, value: pulse)
            Text(text).font(.system(size: 10, weight: .bold)).kerning(0.5)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(color.opacity(0.15), in: Capsule())
    }

    static func seconds(_ value: Double) -> String {
        String(format: "%.1f", value)
    }

    static func pollLabel(_ interval: Double) -> String {
        let suffix = interval == 1.0 ? " (Fast)" : interval == 2.0 ? " (Balanced)" : ""
        return "\(seconds(interval))s\(suffix)"
    }
}
