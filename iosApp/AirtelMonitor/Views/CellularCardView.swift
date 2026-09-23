import SwiftUI

public struct CellularCardView: View {
    public let cellular: CellularMetrics
    public let cellLock: CellLockInfo
    public var onEditLocks: (() -> Void)?
    @State private var isExpanded: Bool = false

    public init(cellular: CellularMetrics, cellLock: CellLockInfo, onEditLocks: (() -> Void)? = nil) {
        self.cellular = cellular
        self.cellLock = cellLock
        self.onEditLocks = onEditLocks
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

                if cellular.signalLvl > 0 {
                    HStack(spacing: 4) {
                        Image(systemName: "cellularbars", variableValue: Double(cellular.signalLvl) / 5.0)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.primary)
                        Text("\(cellular.signalLvl)/5")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .liquidGlassPill()
                }

                Text(cellular.networkType)
                    .font(.caption2)
                    .fontWeight(.bold)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.18))
                    .foregroundColor(.accentColor)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

                Button {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.75)) {
                        isExpanded.toggle()
                    }
                } label: {
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.secondary)
                }
                .liquidGlassSmallButton()
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
                    .opacity(0.4)

                VStack(spacing: 8) {
                    DetailRow(label: "Carrier", value: cellular.operatorName)
                    DetailRow(label: "Hardware", value: "\(cellular.boardType) / \(cellular.iduType)")
                    DetailRow(label: "CQI / QAM", value: "5G: \(cellular.nrCqi) (\(cellular.nrQamDl)) • 4G: \(cellular.lteCqi)")
                    DetailRow(label: "5G Band & PCI", value: "Band: \(cellLock.currentBand5g) • PCI: \(cellLock.servingPci5g) (ARFCN \(cellLock.servingFreq5g))")
                    DetailRow(label: "4G Band & PCI", value: "Band: \(cellLock.currentBands4g) • PCI: \(cellLock.servingPci4g) (EARFCN \(cellLock.servingFreq4g))")

                    HStack {
                        Text("Cell Lock Status")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Spacer()
                        Text(cellLock.nrLockEnabled && cellLock.lteLockEnabled ? "4G + 5G Locked"
                             : cellLock.nrLockEnabled ? "5G Locked"
                             : cellLock.lteLockEnabled ? "4G Locked" : "Auto (Unlocked)")
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(cellLock.nrLockEnabled || cellLock.lteLockEnabled ? .orange : .green)
                    }
                }
                .padding(10)
                .liquidGlassPill()
            }

            if let onEditLocks {
                Button(action: onEditLocks) {
                    Label("Band & Cell Lock", systemImage: "lock.shield")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .liquidGlassActionButton()
            }
        }
        .liquidGlassCard()
    }
}

private struct MetricPill: View {
    let title: String
    let value: String
    let unit: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
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
        .liquidGlassPill()
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

