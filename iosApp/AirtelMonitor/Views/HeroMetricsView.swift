import SwiftUI

public struct HeroMetricsView: View {
    public let wan: WanMetrics

    public init(wan: WanMetrics) {
        self.wan = wan
    }

    public var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                // Download Speed Card
                SpeedCard(
                    title: "DOWNLOAD",
                    speed: wan.downloadMbps,
                    unit: "Mbps",
                    peak: wan.peakDlMbps,
                    icon: "arrow.down.circle.fill",
                    accentColor: .blue
                )

                // Upload Speed Card
                SpeedCard(
                    title: "UPLOAD",
                    speed: wan.uploadMbps,
                    unit: "Mbps",
                    peak: wan.peakUlMbps,
                    icon: "arrow.up.circle.fill",
                    accentColor: .green
                )
            }

            // Data Flow Row
            HStack {
                HStack(spacing: 4) {
                    Image(systemName: "arrow.down")
                        .foregroundColor(.blue)
                        .font(.caption2)
                    Text("\(wan.dlFlowMb) MB")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                HStack(spacing: 4) {
                    Image(systemName: "arrow.up")
                        .foregroundColor(.green)
                        .font(.caption2)
                    Text("\(wan.ulFlowMb) MB")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                HStack(spacing: 4) {
                    Image(systemName: "chart.bar.fill")
                        .foregroundColor(.purple)
                        .font(.caption2)
                    Text("Total: \(wan.totalFlowMb) MB")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.primary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .liquidGlassPanel()
        }
    }
}

private struct SpeedCard: View {
    let title: String
    let speed: Double
    let unit: String
    let peak: Double
    let icon: String
    let accentColor: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: icon)
                    .foregroundColor(accentColor)
                    .font(.subheadline)
                    .shadow(color: accentColor.opacity(0.5), radius: 4)
                Text(title)
                    .font(.caption2)
                    .fontWeight(.bold)
                    .foregroundColor(.secondary)
                Spacer()
            }

            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(String(format: "%.2f", speed))
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundColor(.primary)
                Text(unit)
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundColor(.secondary)
            }

            if peak > 0 {
                Text("Peak: \(String(format: "%.1f", peak)) \(unit)")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .liquidGlassCard(tint: accentColor)
    }
}
