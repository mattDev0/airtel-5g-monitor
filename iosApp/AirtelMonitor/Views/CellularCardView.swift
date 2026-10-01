import SwiftUI

/// Cellular & cell-lock card, same layout as Android's CellularCellLockCard.
public struct CellularCardView: View {
    public let cellular: CellularMetrics
    public let cellLock: CellLockInfo
    public let hardware: HardwareMetrics
    public var onEditLocks: (() -> Void)?
    public var networkBusy: Bool
    public var onMobileData: ((Bool) -> Void)?
    public var onFlightMode: ((Bool) -> Void)?
    public var onRescan: (() -> Void)?
    @State private var isExpanded = false
    /// Actions that cut the connection wait for a confirmation.
    @State private var pending: PendingAction?

    private struct PendingAction {
        let title: String
        let message: String
        let run: () -> Void
    }

    public init(cellular: CellularMetrics, cellLock: CellLockInfo, hardware: HardwareMetrics,
                onEditLocks: (() -> Void)? = nil, networkBusy: Bool = false,
                onMobileData: ((Bool) -> Void)? = nil, onFlightMode: ((Bool) -> Void)? = nil,
                onRescan: (() -> Void)? = nil) {
        self.cellular = cellular
        self.cellLock = cellLock
        self.hardware = hardware
        self.onEditLocks = onEditLocks
        self.networkBusy = networkBusy
        self.onMobileData = onMobileData
        self.onFlightMode = onFlightMode
        self.onRescan = onRescan
    }

    private var isLocked: Bool {
        cellLock.lteLockEnabled || cellLock.nrLockEnabled || cellLock.bandLock4gEnabled || cellLock.bandLock5gEnabled
    }

    private func value(_ s: String) -> String? { s.isEmpty || s == "-" ? nil : s }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Network badge, operator, signal
            HStack(spacing: 10) {
                Text(value(cellular.networkType) ?? "No Signal")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.accentColor)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.18), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                Text(value(cellular.operatorName) ?? "Airtel").font(.subheadline.weight(.medium))
                Spacer()
                SignalBars(level: cellular.signalLvl, rsrp: cellular.rsrp5g)
            }

            // Band / Cell / Temp / CPU (SINR when hardware stats are missing)
            HStack(spacing: 8) {
                AppTelemetryPill(label: "Band", value: value(cellLock.currentBand5g).map { "n\($0)" } ?? "-")
                AppTelemetryPill(label: "Cell", value: value(cellLock.servingPci5g).map { "PCI \($0)" } ?? "-")
                if let temp = value(hardware.temperature), temp != "--" {
                    AppTelemetryPill(label: "Temp", value: "\(temp)°C")
                    AppTelemetryPill(label: "CPU", value: "\(Int(hardware.cpuUsage))%")
                } else {
                    AppTelemetryPill(label: "SINR", value: value(cellular.sinr5g).map { "\($0) dB" } ?? "-")
                }
            }

            // Cell lock status and details toggle
            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Circle().fill(isLocked ? StatusColors.warning : StatusColors.success).frame(width: 8, height: 8)
                    Text(isLocked ? "Cell Lock Active" : "Cell Lock: Auto (Default)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(isExpanded ? "Hide details" : "Details").font(.caption.weight(.semibold))
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down").font(.system(size: 11, weight: .bold))
                }
                .foregroundStyle(Color.accentColor)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                VStack(spacing: 8) {
                    AppDetailRow(title: "5G Serving EARFCN/ARFCN", value: cellLock.servingFreq5g)
                    AppDetailRow(title: "4G Serving PCI / EARFCN", value: "\(cellLock.servingPci4g) / \(cellLock.servingFreq4g)")
                    AppDetailRow(title: "4G Active Bands",
                                 value: value(cellLock.currentBands4g).map { "B" + $0.replacingOccurrences(of: "+", with: " + B") } ?? "-")
                    AppDetailRow(title: "4G Signal (RSRP / SINR)", value: "\(cellular.rsrp4g) dBm / \(cellular.sinr4g) dB")
                    AppDetailRow(title: "5G CQI / 4G CQI", value: "\(cellular.nrCqi) / \(cellular.lteCqi)")
                    AppDetailRow(title: "4G Cell Lock",
                                 value: cellLock.lteLockEnabled ? "PCI \(cellLock.lteLockPci) @ \(cellLock.lteLockFreq)" : "Auto")
                    AppDetailRow(title: "5G Cell Lock",
                                 value: cellLock.nrLockEnabled ? "PCI \(cellLock.nrLockPci) @ \(cellLock.nrLockFreq)" : "Auto")
                }
                .padding(12)
                .liquidGlassPill()
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            if let onMobileData, let onFlightMode {
                HStack(spacing: 8) {
                    NetworkToggle(title: "Mobile data", isOn: cellLock.mobileDataOn, disabled: networkBusy) { on in
                        if on {
                            onMobileData(true)
                        } else {
                            pending = PendingAction(
                                title: "Turn off mobile data?",
                                message: "The internet stops for every device until you turn it back on. Wi-Fi and this app keep working.",
                                run: { onMobileData(false) })
                        }
                    }
                    NetworkToggle(title: "Flight mode", isOn: cellLock.flightModeOn, disabled: networkBusy) { on in
                        if !on {
                            onFlightMode(false)
                        } else {
                            pending = PendingAction(
                                title: "Turn on flight mode?",
                                message: "The modem's radio switches off, so there's no internet until you turn it off again. Wi-Fi and this app keep working.",
                                run: { onFlightMode(true) })
                        }
                    }
                }
            }

            HStack(spacing: 8) {
                if let onEditLocks {
                    Button(action: onEditLocks) {
                        Text("Band & Cell Lock")
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity)
                    }
                    .liquidGlassActionButton()
                }
                if let onRescan {
                    Button {
                        pending = PendingAction(
                            title: "Search for the network again?",
                            message: "The connection drops for up to a minute while the modem searches.",
                            run: onRescan)
                    } label: {
                        Group {
                            if networkBusy { ProgressView() } else { Text("Re-search") }
                        }
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                    }
                    .liquidGlassActionButton()
                    .disabled(networkBusy || cellLock.flightModeOn == true)
                }
            }
        }
        .liquidGlassCard()
        .alert(pending?.title ?? "", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })) {
            Button("Cancel", role: .cancel) { pending = nil }
            Button("Continue", role: .destructive) {
                pending?.run()
                pending = nil
            }
        } message: {
            Text(pending?.message ?? "")
        }
    }
}

/// "-95 dBm" plus four bars, like Android's signal indicator.
private struct SignalBars: View {
    static let heights: [CGFloat] = [4, 7, 10, 14]
    let level: Int
    let rsrp: String

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            if !rsrp.isEmpty && rsrp != "-" {
                Text("\(rsrp) dBm").font(.caption2.monospaced()).foregroundStyle(.secondary)
            }
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<4, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(i < level ? Color.accentColor : Color.secondary.opacity(0.3))
                        .frame(width: 3, height: Self.heights[i])
                }
            }
        }
    }
}

/// Compact labelled switch; greyed out until the router has reported the state.
private struct NetworkToggle: View {
    let title: String
    let isOn: Bool?
    let disabled: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn == true }, set: { onChange($0) })) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .disabled(disabled || isOn == nil)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .liquidGlassPill()
    }
}
