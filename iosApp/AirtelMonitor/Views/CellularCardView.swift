import SwiftUI

public struct CellularCardView: View {
    public let cellular: CellularMetrics
    public let cellLock: CellLockInfo
    @State private var isExpanded: Bool = false

    public init(cellular: CellularMetrics, cellLock: CellLockInfo) {
        self.cellular = cellular
        self.cellLock = cellLock
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundColor(.accentColor)
                    .font(.subheadline)
                Text("Cellular & RF")
                    .font(.subheadline)
                    .fontWeight(.bold)
                Spacer()

                Text(cellular.networkType)
                    .font(.caption2)
                    .fontWeight(.bold)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.15))
                    .foregroundColor(.accentColor)
                    .cornerRadius(6)

                Button {
                    withAnimation { isExpanded.toggle() }
                } label: {
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            // Radio Grid
            HStack(spacing: 8) {
                MetricPill(title: "5G RSRP", value: cellular.rsrp5g, unit: "dBm")
                MetricPill(title: "5G SINR", value: cellular.sinr5g, unit: "dB")
                MetricPill(title: "4G RSRP", value: cellular.rsrp4g, unit: "dBm")
                MetricPill(title: "4G SINR", value: cellular.sinr4g, unit: "dB")
            }

            if isExpanded {
                Divider()

                VStack(spacing: 8) {
                    DetailRow(label: "Carrier", value: cellular.operatorName)
                    DetailRow(label: "Hardware", value: "\(cellular.boardType) / \(cellular.iduType)")
                    DetailRow(label: "CQI / QAM", value: "5G: \(cellular.nrCqi) (\(cellular.nrQamDl)) • 4G: \(cellular.lteCqi)")
                    DetailRow(label: "5G Band & PCI", value: "Band: \(cellLock.currentBand5g) • PCI: \(cellLock.servingPci5g) (\(cellLock.servingFreq5g) MHz)")
                    DetailRow(label: "4G Band & PCI", value: "Band: \(cellLock.currentBands4g) • PCI: \(cellLock.servingPci4g) (\(cellLock.servingFreq4g) MHz)")

                    HStack {
                        Text("Cell Lock Status")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Spacer()
                        Text(cellLock.nrLockEnabled ? "5G Locked" : (cellLock.lteLockEnabled ? "4G Locked" : "Auto (Unlocked)"))
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(cellLock.nrLockEnabled || cellLock.lteLockEnabled ? .orange : .green)
                    }
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.04), radius: 3, x: 0, y: 1)
    }
}

private struct MetricPill: View {
    let title: String
    let value: String
    let unit: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(.secondary)
            HStack(spacing: 2) {
                Text(value)
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundColor(.primary)
                if !value.contains("-") && !value.isEmpty {
                    Text(unit)
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(8)
    }
}

private struct DetailRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.caption)
                .fontWeight(.medium)
                .foregroundColor(.primary)
        }
    }
}
