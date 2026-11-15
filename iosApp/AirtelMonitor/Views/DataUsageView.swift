import SwiftUI

public struct DataUsageView: View {
    @ObservedObject public var container: AppContainer
    @StateObject private var viewModel: DataUsageViewModel

    public init(container: AppContainer) {
        self.container = container
        _viewModel = StateObject(wrappedValue: DataUsageViewModel(container: container))
    }

    public var body: some View {
        let usage = viewModel.monthlyUsage
        ScrollView {
            VStack(spacing: 14) {
                summaryCard(usage)
                dailyHistoryCard
                actionsCard
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Data Usage")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    viewModel.loadTrafficLimit()
                } label: {
                    if viewModel.isLoading {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
        .onAppear {
            viewModel.ensureLoaded()
        }
        .sheet(isPresented: $viewModel.showQuotaSheet) {
            QuotaEditorSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $viewModel.showCalibrateSheet) {
            CalibrateUsageSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $viewModel.showClearRouterConfirm) {
            ConfirmSheet(
                request: ConfirmPromptRequest(
                    title: "Clear Router Usage Counters?",
                    impactMessage: "Resets the ZLT X17M monthly statistical traffic counters back to zero.",
                    confirmLabel: "Clear Counters",
                    tier: .destructive,
                    requiredConfirmationText: "RESET",
                    onConfirm: { viewModel.clearRouterTrafficStatistics() }
                )
            )
            .presentationDetents([.medium])
        }
    }

    private func summaryCard(_ usage: MonthlyUsageSummary) -> some View {
        let tint: Color = usage.isOverWarning ? AirtelTheme.warning : AirtelTheme.accent
        return SectionCard(
            title: "Billing Cycle Summary",
            trailing: {
                if usage.limitEnabled, let pct = usage.percentUsed {
                    BadgePill("\(pct)%", color: tint)
                }
            }
        ) {
            VStack(alignment: .leading, spacing: 12) {
                Text(usage.limitEnabled && usage.quotaMb != nil
                    ? "\(NetworkFormatters.formatDataMb(usage.usedMb)) of \(NetworkFormatters.formatDataMb(usage.quotaMb ?? 0))"
                    : NetworkFormatters.formatDataMb(usage.usedMb))
                    .font(.title2.weight(.bold).monospacedDigit())

                if let frac = usage.progressFraction {
                    FlatProgressBar(fraction: frac, tint: tint, height: 9)
                }

                HStack(spacing: 10) {
                    StatTile(
                        title: "Monthly Total",
                        value: NetworkFormatters.formatDataMb(usage.usedMb)
                    )
                    StatTile(
                        title: "Current Session",
                        value: usage.currentFlowMb.map { NetworkFormatters.formatDataMb($0) } ?? "--"
                    )
                }

                HStack {
                    Text("Download / Upload Split")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("↓ \(NetworkFormatters.formatDataMb(usage.dlMonthMb)) · ↑ \(NetworkFormatters.formatDataMb(usage.ulMonthMb))")
                        .font(.caption.weight(.semibold).monospacedDigit())
                }

                if usage.roamDlMb > 0 || usage.roamUlMb > 0 {
                    HStack {
                        Text("Roaming Split")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text("↓ \(NetworkFormatters.formatDataMb(usage.roamDlMb)) · ↑ \(NetworkFormatters.formatDataMb(usage.roamUlMb))")
                            .font(.caption.weight(.semibold).monospacedDigit())
                    }
                }

                if let start = usage.billingStartDay {
                    HStack {
                        Text("Billing Cycle")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(usage.daysLeftInCycle.map { "Starts Day \(start) · \($0)d left" } ?? "Starts Day \(start)")
                            .font(.caption.weight(.semibold))
                    }
                }
            }
        }
    }

    private var dailyHistoryCard: some View {
        SectionCard(
            title: "Daily Usage History",
            subtitle: "Recorded by this device (up to 31 days shown)"
        ) {
            if viewModel.dailyHistory.isEmpty {
                EmptyStateView(
                    icon: "chart.bar",
                    title: "No Daily Samples Yet",
                    message: "Keep the app open periodically so daily deltas from the router can be tracked locally."
                )
            } else {
                VStack(spacing: 12) {
                    DailyUsageBarChart(entries: viewModel.dailyHistory, tint: AirtelTheme.accent)
                    Divider()
                    ForEach(viewModel.dailyHistory.suffix(10).reversed()) { entry in
                        HStack {
                            Text(entry.dateIso)
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(NetworkFormatters.formatDataMb(entry.dailyMb))
                                .font(.caption.weight(.semibold).monospacedDigit())
                        }
                    }
                }
            }
        }
    }

    private var actionsCard: some View {
        SectionCard(title: "Quota & Maintenance") {
            VStack(spacing: 8) {
                if viewModel.quotaEditable {
                    SettingValueRow(
                        title: "Configure Monthly Quota",
                        subtitle: viewModel.monthlyUsage.limitEnabled ? "Enabled" : "Not configured",
                        icon: "gauge.with.dots.needle.67percent"
                    ) {
                        viewModel.showQuotaSheet = true
                    }
                    Divider()
                }
                SettingValueRow(
                    title: "Calibrate Router Counter",
                    subtitle: "Match carrier app reported usage",
                    icon: "slider.horizontal.3"
                ) {
                    viewModel.showCalibrateSheet = true
                }
                Divider()
                SettingValueRow(
                    title: "Reset Router Traffic Counters",
                    subtitle: "Zero monthly DL/UL traffic counters on ZLT X17M",
                    icon: "arrow.counterclockwise"
                ) {
                    viewModel.showClearRouterConfirm = true
                }
                if !viewModel.dailyHistory.isEmpty {
                    Divider()
                    SettingValueRow(
                        title: "Clear Local Daily History",
                        subtitle: "Remove local 62-day history stored on this phone",
                        icon: "trash"
                    ) {
                        viewModel.clearLocalHistory()
                    }
                }
            }
        }
    }
}

private struct QuotaEditorSheet: View {
    @ObservedObject var viewModel: DataUsageViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var enabled = false
    @State private var sizeText = "500"
    @State private var unitCode = "1"
    @State private var startDay = 1

    var body: some View {
        NavigationStack {
            Form {
                if let msg = viewModel.statusMessage {
                    Section { Text(msg).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Monthly Data Limit") {
                    Toggle("Enable Monthly Limit", isOn: $enabled).tint(AirtelTheme.accent)
                    if enabled {
                        TextField("Quota Size (0–99999)", text: $sizeText)
                            .keyboardType(.numberPad)
                        Picker("Unit", selection: $unitCode) {
                            Text("MB").tag("0")
                            Text("GB").tag("1")
                            Text("TB").tag("2")
                        }
                    }
                    Stepper("Billing Start Day: \(startDay)", value: $startDay, in: 1...28)
                }
            }
            .navigationTitle("Monthly Quota")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        viewModel.saveQuota(enabled: enabled, size: sizeText, unitCode: unitCode, startDay: startDay)
                    }
                    .disabled(viewModel.isSaving)
                }
            }
            .onAppear {
                if let cfg = viewModel.limitSettings {
                    enabled = cfg.limitEnabled
                    sizeText = cfg.limitSize == "0" ? "500" : cfg.limitSize
                    unitCode = cfg.unitCode
                    startDay = cfg.startDate
                } else {
                    enabled = viewModel.monthlyUsage.limitEnabled
                    startDay = viewModel.monthlyUsage.billingStartDay ?? 1
                }
            }
        }
    }
}

private struct CalibrateUsageSheet: View {
    @ObservedObject var viewModel: DataUsageViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var inputVal = ""
    @State private var isGb = true

    var body: some View {
        NavigationStack {
            Form {
                if let msg = viewModel.statusMessage {
                    Section { Text(msg).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Target Used Data") {
                    TextField(isGb ? "Used GB (e.g. 125.5)" : "Used MB", text: $inputVal)
                        .keyboardType(.decimalPad)
                    Picker("Unit", selection: $isGb) {
                        Text("GB").tag(true)
                        Text("MB").tag(false)
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("Calibrate Counter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Apply") {
                        let raw = Double(inputVal.trimmingCharacters(in: .whitespaces)) ?? 0
                        let mb = isGb ? raw * 1024.0 : raw
                        viewModel.calibrateUsage(targetUsedMb: mb)
                    }
                    .disabled(viewModel.isSaving || Double(inputVal.trimmingCharacters(in: .whitespaces)) == nil)
                }
            }
        }
    }
}
