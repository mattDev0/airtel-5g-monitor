import SwiftUI

/// "Internet Speed" card, same layout as Android's MinimalWanSpeedCard.
public struct HeroMetricsView: View {
    public let wan: WanMetrics

    public init(wan: WanMetrics) {
        self.wan = wan
    }

    public var body: some View {
        VStack(spacing: 16) {
            HStack {
                Text("Internet Speed").font(.headline)
                Spacer()
                Text("Total: \(wan.totalFlowMb) MB")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            HStack(alignment: .top, spacing: 16) {
                SpeedColumn(label: "Download", icon: "arrow.down", tint: .accentColor,
                            mbps: wan.downloadMbps, kbps: wan.downloadKbps, peak: wan.peakDlMbps,
                            progress: min(max(wan.downloadMbps / 100, 0.01), 1))
                Rectangle()
                    .fill(Color.secondary.opacity(0.3))
                    .frame(width: 1, height: 72)
                SpeedColumn(label: "Upload", icon: "arrow.up", tint: .teal,
                            mbps: wan.uploadMbps, kbps: wan.uploadKbps, peak: wan.peakUlMbps,
                            progress: min(max(wan.uploadMbps / 50, 0.01), 1))
            }
        }
        .liquidGlassCard()
    }
}

private struct SpeedColumn: View {
    let label: String
    let icon: String
    let tint: Color
    let mbps: Double
    let kbps: Double
    let peak: Double
    let progress: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(tint)
                    .frame(width: 22, height: 22)
                    .background(tint.opacity(0.18), in: Circle())
                Text(label).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            }
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(String(format: "%.2f", mbps))
                    .font(.system(size: 28, weight: .bold, design: .monospaced))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .contentTransition(.numericText())
                Text("Mbps").font(.caption2).foregroundStyle(.secondary)
            }
            ProgressView(value: progress)
                .tint(tint)
                .animation(.easeOut(duration: 0.4), value: progress)
            HStack {
                Text("\(String(format: "%.0f", kbps)) kB/s")
                Spacer(minLength: 4)
                Text("Peak \(String(format: "%.1f", peak))M")
            }
            .font(.caption2.monospaced())
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
