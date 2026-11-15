import SwiftUI

public struct SignalView: View {
    @ObservedObject public var container: AppContainer
    @ObservedObject public var shellVM: ShellViewModel
    public var onNavigate: (AppRoute) -> Void

    @StateObject private var viewModel: SignalViewModel
    @State private var chartMetric: SignalChartMetric = .rsrp
    @State private var openConnectionModal = false

    private enum SignalChartMetric: String, CaseIterable, Identifiable {
        case rsrp = "RSRP"
        case sinr = "SINR"
        var id: String { rawValue }
    }

    public init(
        container: AppContainer,
        shellVM: ShellViewModel,
        onNavigate: @escaping (AppRoute) -> Void
    ) {
        self.container = container
        self.shellVM = shellVM
        self.onNavigate = onNavigate
        _viewModel = StateObject(wrappedValue: SignalViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                RouterConnectivityBanner(shellVM: shellVM) {
                    openConnectionModal = true
                }

                actionBar

                if let state = viewModel.routerState {
                    signalMetersCard(state)
                    signalChartCard
                    carriersCard(state)
                    linkDetailsCard(state.linkDetails)
                    cellIdentityCard(state.cellIdentity)
                    neighborCellsCard
                } else {
                    SkeletonBox(height: 180)
                    SkeletonBox(height: 150)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Signal")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            RouterShellToolbarControls(shellVM: shellVM) {
                openConnectionModal = true
            }
        }
        .onAppear {
            viewModel.ensureNeighborsLoaded()
        }
        .sheet(isPresented: $viewModel.showLockEditor) {
            LockEditorSheet(
                lockSettings: viewModel.lockSettings,
                cellLock: viewModel.routerState?.cellLock,
                isLoading: viewModel.lockLoading,
                isSaving: viewModel.lockSaving,
                message: viewModel.lockMessage,
                onSaveBandLock: { l4, b4, l5, b5 in
                    viewModel.saveBandLock(lock4g: l4, bands4g: b4, lock5g: l5, bands5g: b5)
                },
                onSaveLteCellLock: { enabled, cells in
                    viewModel.saveLteCellLock(enabled: enabled, cells: cells)
                },
                onSaveNrCellLock: { enabled, cells in
                    viewModel.saveNrCellLock(enabled: enabled, cells: cells)
                }
            )
        }
        .sheet(isPresented: $openConnectionModal) {
            OnboardingView(container: container)
        }
    }

    private var actionBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                Button {
                    onNavigate(.alignment)
                } label: {
                    Label("Alignment Mode", systemImage: "scope")
                }
                .liquidGlassActionButton(tint: AirtelTheme.accent)

                Button {
                    viewModel.openLockEditor()
                } label: {
                    Label("Band & Cell Lock", systemImage: "lock.fill")
                }
                .liquidGlassSmallButton()

                Button {
                    viewModel.rescanNetwork()
                } label: {
                    if viewModel.rescanBusy {
                        ProgressView().controlSize(.small)
                    } else {
                        Label("Rescan", systemImage: "arrow.triangle.2.circlepath")
                    }
                }
                .disabled(viewModel.rescanBusy)
                .liquidGlassSmallButton()
            }
        }
    }

    private func signalMetersCard(_ state: RouterState) -> some View {
        let cell = state.cellular
        let rsrp5 = Double(cell.rsrp5g)
        let sinr5 = Double(cell.sinr5g)
        let rsrp4 = Double(cell.rsrp4g)
        let sinr4 = Double(cell.sinr4g)

        return SectionCard(
            title: "Radio Signal Metrics",
            subtitle: cell.networkType
        ) {
            VStack(alignment: .leading, spacing: 14) {
                if cell.has5gMetrics {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            BadgePill("5G NR", color: AirtelTheme.nr5g)
                            Spacer()
                            Text("RSRQ \(cell.rsrq5g) dB · CQI \(cell.nrCqi)")
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        if let v = rsrp5 {
                            SignalMeter(
                                title: "5G RSRP",
                                valueText: "\(cell.rsrp5g) dBm",
                                fraction: normalize(v, min: -140, max: -65),
                                tint: AirtelTheme.rsrpColor(v)
                            )
                        }
                        if let v = sinr5 {
                            SignalMeter(
                                title: "5G SINR",
                                valueText: "\(cell.sinr5g) dB",
                                fraction: normalize(v, min: -10, max: 30),
                                tint: AirtelTheme.sinrColor(v)
                            )
                        }
                    }
                    Divider()
                }

                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        BadgePill("4G LTE", color: AirtelTheme.lte4g)
                        Spacer()
                        Text("RSRQ \(cell.rsrq4g) dB · RSSI \(cell.rssi4g) · CQI \(cell.lteCqi)")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    SignalMeter(
                        title: "4G RSRP",
                        valueText: cell.rsrp4g == "-" ? "-" : "\(cell.rsrp4g) dBm",
                        fraction: rsrp4.map { normalize($0, min: -140, max: -65) } ?? 0,
                        tint: AirtelTheme.rsrpColor(rsrp4)
                    )
                    SignalMeter(
                        title: "4G SINR",
                        valueText: cell.sinr4g == "-" ? "-" : "\(cell.sinr4g) dB",
                        fraction: sinr4.map { normalize($0, min: -10, max: 30) } ?? 0,
                        tint: AirtelTheme.sinrColor(sinr4)
                    )
                }
            }
        }
    }

    private var signalChartCard: some View {
        let nrSeries: [Double]
        let lteSeries: [Double]
        switch chartMetric {
        case .rsrp:
            nrSeries = viewModel.signalHistory.compactMap(\.rsrp5g)
            lteSeries = viewModel.signalHistory.compactMap(\.rsrp4g)
        case .sinr:
            nrSeries = viewModel.signalHistory.compactMap(\.sinr5g)
            lteSeries = viewModel.signalHistory.compactMap(\.sinr4g)
        }

        return SectionCard(
            title: "Signal History",
            trailing: {
                Picker("Metric", selection: $chartMetric) {
                    ForEach(SignalChartMetric.allCases) { metric in
                        Text(metric.rawValue).tag(metric)
                    }
                }
                .pickerStyle(.segmented)
                .frame(width: 130)
            }
        ) {
            VStack(alignment: .leading, spacing: 8) {
                let series = buildChartSeries(nr: nrSeries, lte: lteSeries)
                if series.isEmpty {
                    Text("Collecting samples...")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 24)
                } else {
                    LineChart(series: series, showGrid: true)
                        .frame(height: 140)
                    HStack(spacing: 14) {
                        if nrSeries.count >= 2 {
                            legendDot(color: AirtelTheme.nr5g, label: "5G NR")
                        }
                        if lteSeries.count >= 2 {
                            legendDot(color: AirtelTheme.lte4g, label: "4G LTE")
                        }
                    }
                }
            }
        }
    }

    private func buildChartSeries(nr: [Double], lte: [Double]) -> [LineChartSeries] {
        var list: [LineChartSeries] = []
        if nr.count >= 2 {
            list.append(LineChartSeries(values: nr, color: AirtelTheme.nr5g, fillArea: true))
        }
        if lte.count >= 2 {
            list.append(LineChartSeries(values: lte, color: AirtelTheme.lte4g, fillArea: nr.count < 2))
        }
        return list
    }

    private func legendDot(color: Color, label: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
        }
    }

    private func carriersCard(_ state: RouterState) -> some View {
        SectionCard(
            title: "Active Carriers",
            subtitle: CarrierParser.formatCaSummary(state.carriers)
        ) {
            if state.carriers.isEmpty {
                Text("No active carrier aggregation components reported.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 6) {
                    ForEach(state.carriers) { carrier in
                        CarrierRow(carrier: carrier)
                        if carrier.id != state.carriers.last?.id {
                            Divider()
                        }
                    }
                }
            }
        }
    }

    private func linkDetailsCard(_ details: LinkDetails) -> some View {
        SectionCard(title: "Modulation & MIMO") {
            VStack(spacing: 8) {
                keyValue("4G MCS (DL / UL)", "\(details.dlMcs4g) / \(details.ulMcs4g)")
                keyValue("4G Max QAM (DL / UL)", "\(details.maxDlQam4g) / \(details.maxUlQam4g)")
                keyValue("4G Rank / BLER", "\(details.rank4g) / \(details.bler4g)")
                if details.has5gDetails {
                    Divider()
                    keyValue("5G MCS (DL / UL)", "\(details.dlMcs5g) / \(details.ulMcs5g)")
                    keyValue("5G Max QAM (DL / UL)", "\(details.maxDlQam5g) / \(details.maxUlQam5g)")
                    keyValue("5G Rank / BLER", "\(details.rank5g) / \(details.bler5g)")
                }
                if !details.mimoStatus.isEmpty && details.mimoStatus != "-" {
                    keyValue("MIMO Layers", details.mimoStatus)
                }
            }
        }
    }

    private func cellIdentityCard(_ cell: CellIdentity) -> some View {
        SectionCard(title: "Cell Identity") {
            VStack(spacing: 8) {
                keyValue("PLMN", cell.plmn)
                keyValue("4G eNB / Cell ID", "\(cell.enbId4g) / \(cell.cellId4g)")
                keyValue("4G TAC / ECGI", "\(cell.tac4g) / \(cell.ecgi)")
                if cell.has5gIdentity {
                    Divider()
                    keyValue("5G gNB / Cell ID", "\(cell.enbId5g) / \(cell.cellId5g)")
                    keyValue("5G TAC / NCGI", "\(cell.tac5g) / \(cell.ncgi)")
                }
            }
        }
    }

    private var neighborCellsCard: some View {
        SectionCard(
            title: "Neighbor Cells",
            trailing: {
                Button {
                    viewModel.refreshNeighborCells()
                } label: {
                    if viewModel.neighborsLoading {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(viewModel.neighborsLoading)
            }
        ) {
            if viewModel.neighborCells.isEmpty {
                EmptyStateView(
                    icon: "dot.radiowaves.left.and.right",
                    title: "No Neighbor Cells",
                    message: "Tap refresh to query intra/inter-frequency neighbor measurements from the modem.",
                    actionTitle: "Scan Neighbors"
                ) {
                    viewModel.refreshNeighborCells()
                }
            } else {
                VStack(spacing: 8) {
                    ForEach(viewModel.neighborCells) { n in
                        HStack {
                            BadgePill(n.tech.rawValue, color: n.tech == .nr ? AirtelTheme.nr5g : AirtelTheme.lte4g)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("PCI \(n.pci)")
                                    .font(.subheadline.weight(.semibold))
                                Text("ARFCN \(n.earfcn)")
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text("RSRP \(n.rsrp) dBm")
                                    .font(.caption.monospacedDigit().weight(.medium))
                                Text("RSRQ \(n.rsrq) · SINR \(n.sinr)")
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                        if n.id != viewModel.neighborCells.last?.id {
                            Divider()
                        }
                    }
                }
            }
        }
    }

    private func keyValue(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.caption.weight(.semibold).monospacedDigit())
                .multilineTextAlignment(.trailing)
        }
    }

    private func normalize(_ value: Double, min: Double, max: Double) -> Double {
        guard max > min else { return 0 }
        return Swift.min(1.0, Swift.max(0.0, (value - min) / (max - min)))
    }
}
