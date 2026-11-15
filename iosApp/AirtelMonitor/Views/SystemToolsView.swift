import SwiftUI

public struct SystemToolsView: View {
    @ObservedObject public var container: AppContainer
    @ObservedObject public var shellVM: ShellViewModel
    @StateObject private var viewModel: SystemToolsViewModel

    @State private var confirmPrompt: ConfirmPromptRequest?

    public init(container: AppContainer, shellVM: ShellViewModel) {
        self.container = container
        self.shellVM = shellVM
        _viewModel = StateObject(wrappedValue: SystemToolsViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                speedTestCard
                selfCheckCard
                aboutRouterCard
                maintenanceCard
                dangerZoneCard
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("System & Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    viewModel.loadOverview()
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
            viewModel.loadOverview()
        }
        .sheet(item: $viewModel.activeSheet) { sheet in
            switch sheet {
            case .scheduledReboot: ScheduledRebootSheet(viewModel: viewModel)
            case .logs: SystemLogsSheet(viewModel: viewModel)
            case .routerTime: RouterTimeSheet(viewModel: viewModel)
            case .wanLinkDetect: WanLinkDetectSheet(viewModel: viewModel)
            case .changePassword: ChangeAdminPasswordSheet(viewModel: viewModel)
            }
        }
        .sheet(item: $confirmPrompt) { req in
            ConfirmSheet(request: req).presentationDetents([.medium])
        }
    }

    private var speedTestCard: some View {
        let p = viewModel.speedTestProgress
        return SectionCard(title: "Internet Speed Test", subtitle: "Measured against Cloudflare edge") {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    StatTile(
                        title: "Download",
                        value: p.downloadMbps > 0 ? String(format: "%.1f", p.downloadMbps) : "--",
                        unit: "Mbps",
                        accent: AirtelTheme.nr5g
                    )
                    StatTile(
                        title: "Upload",
                        value: p.uploadMbps > 0 ? String(format: "%.1f", p.uploadMbps) : "--",
                        unit: "Mbps",
                        accent: AirtelTheme.upload
                    )
                    StatTile(
                        title: "Ping",
                        value: (p.latencyMs ?? 0) > 0 ? String(format: "%.0f", p.latencyMs ?? 0) : "--",
                        unit: "ms",
                        caption: (p.jitterMs ?? 0) > 0 ? String(format: "Jitter %.1fms", p.jitterMs ?? 0) : nil,
                        accent: AirtelTheme.info
                    )
                }

                if p.isRunning {
                    FlatProgressBar(fraction: p.progressFraction, tint: AirtelTheme.accent, height: 8)
                    Button("Cancel Speed Test") {
                        viewModel.cancelSpeedTest()
                    }
                    .liquidGlassSmallButton()
                } else {
                    if let err = p.errorMessage, !err.isEmpty {
                        Text(err).font(.caption).foregroundStyle(AirtelTheme.error)
                    }
                    Button {
                        viewModel.startSpeedTest()
                    } label: {
                        Label("Run Speed Test", systemImage: "speedometer")
                            .frame(maxWidth: .infinity)
                    }
                    .liquidGlassActionButton(tint: AirtelTheme.accent)
                }
            }
        }
    }

    private var selfCheckCard: some View {
        SectionCard(
            title: "Hardware Self-Check",
            trailing: {
                Button(viewModel.selfCheckLoading ? "Running..." : "Run Check") {
                    viewModel.runSelfCheck()
                }
                .font(.caption.weight(.semibold))
                .disabled(viewModel.selfCheckLoading)
                .liquidGlassSmallButton()
            }
        ) {
            if let err = viewModel.selfCheckError {
                Text(err).font(.caption).foregroundStyle(AirtelTheme.error)
            } else if let check = viewModel.selfCheck {
                VStack(alignment: .leading, spacing: 8) {
                    BadgePill(check.passed ? "Passed" : "Completed", color: check.passed ? AirtelTheme.success : AirtelTheme.warning)
                    ForEach(Array(check.details.prefix(12).enumerated()), id: \.offset) { _, row in
                        HStack {
                            Text(row.key).font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Text(row.value).font(.caption.weight(.semibold).monospacedDigit())
                        }
                    }
                }
            } else {
                Text("Runs an on-device hardware and modem diagnostic self-check.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var aboutRouterCard: some View {
        let info = viewModel.aboutInfo
        return SectionCard(title: "Router & SIM Info") {
            VStack(spacing: 8) {
                aboutRow("ODU Model", info.oduModel)
                if !info.iduModel.isEmpty {
                    aboutRow("IDU Model", info.iduModel)
                }
                aboutRow("Hardware / Board", info.hardwareVersion)
                aboutRow("Firmware", info.firmwareVersion)
                if !info.buildDate.isEmpty {
                    aboutRow("Build Date", info.buildDate)
                }
                if !info.uptimeText.isEmpty && info.uptimeText != "--" {
                    aboutRow("Uptime", info.uptimeText)
                }
                aboutRow("Router Time", viewModel.timeConfig.currentSystemTime)
                if !info.linkRates.isEmpty {
                    aboutRow("Ethernet Links", info.linkRates.joined(separator: " · "))
                }
                Divider()
                sensitiveRow("imei", label: "IMEI", value: info.imei)
                sensitiveRow("imsi", label: "IMSI", value: info.imsi)
                sensitiveRow("iccid", label: "ICCID", value: info.iccid)
                sensitiveRow("serial", label: "Serial Number", value: info.serialNumber)
                sensitiveRow("msisdn", label: "MSISDN", value: info.msisdn)
                sensitiveRow("eid", label: "eSIM EID", value: info.eid)
                sensitiveRow("iduMac", label: "WAN MAC", value: info.iduMac)
                sensitiveRow("lanMac", label: "LAN MAC", value: info.lanMac)
            }
        }
    }

    @ViewBuilder
    private func aboutRow(_ label: String, _ value: String) -> some View {
        if !value.isEmpty && value != "-" {
            HStack {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(value).font(.caption.weight(.semibold).monospacedDigit())
            }
        }
    }

    @ViewBuilder
    private func sensitiveRow(_ id: String, label: String, value: String) -> some View {
        if !value.isEmpty && value != "-" {
            let revealed = !viewModel.maskSensitive || viewModel.revealedFields.contains(id)
            HStack {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(revealed ? value : SensitiveFormatters.maskIdentifier(value))
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .textSelection(.enabled)
                if viewModel.maskSensitive {
                    Button {
                        viewModel.toggleReveal(id)
                    } label: {
                        Image(systemName: revealed ? "eye.slash" : "eye")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var maintenanceCard: some View {
        SectionCard(title: "Administration") {
            VStack(spacing: 8) {
                SettingValueRow(
                    title: "Scheduled Reboot",
                    value: viewModel.scheduledReboot.enabled ? viewModel.scheduledReboot.timeHhMm : "Off",
                    icon: "clock.arrow.2.circlepath"
                ) {
                    viewModel.openSheet(.scheduledReboot)
                }
                Divider()
                SettingValueRow(
                    title: "Router Logs & Verbosity",
                    icon: "doc.text.magnifyingglass"
                ) {
                    viewModel.openSheet(.logs)
                }
                Divider()
                SettingValueRow(
                    title: "Time Zone & NTP Sync",
                    value: viewModel.timeConfig.timezone,
                    icon: "globe.badge.chevron.backward"
                ) {
                    viewModel.openSheet(.routerTime)
                }
                Divider()
                SettingValueRow(
                    title: "WAN Link Detection",
                    value: viewModel.wanLinkDetect.enabled ? "On" : "Off",
                    icon: "stethoscope"
                ) {
                    viewModel.openSheet(.wanLinkDetect)
                }
                Divider()
                SettingValueRow(
                    title: "Change Admin Password",
                    icon: "key.viewfinder"
                ) {
                    viewModel.openSheet(.changePassword)
                }
            }
        }
    }

    private var dangerZoneCard: some View {
        SectionCard(title: "Power & Reset", tint: AirtelTheme.error) {
            VStack(spacing: 10) {
                Button {
                    confirmPrompt = ConfirmPromptRequest(
                        title: "Reboot Router?",
                        impactMessage: "All Wi-Fi, LAN, and cellular connections will drop for ~60–90 seconds.",
                        confirmLabel: "Reboot Router",
                        tier: .destructive,
                        requiredConfirmationText: "ZLT X17M",
                        onConfirm: { viewModel.rebootRouter() }
                    )
                } label: {
                    Label("Reboot ZLT X17M", systemImage: "power")
                        .frame(maxWidth: .infinity)
                }
                .liquidGlassSmallButton()

                Button {
                    confirmPrompt = ConfirmPromptRequest(
                        title: "Factory Reset Router?",
                        impactMessage: "Wipes ALL Wi-Fi names, passwords, band locks, firewall rules, and resets admin password back to default.",
                        confirmLabel: "Factory Reset",
                        tier: .destructive,
                        requiredConfirmationText: "RESET",
                        onConfirm: { viewModel.factoryReset() }
                    )
                } label: {
                    Label("Factory Reset", systemImage: "exclamationmark.arrow.triangle.2.circlepath")
                        .frame(maxWidth: .infinity)
                }
                .liquidGlassActionButton(tint: AirtelTheme.error)
            }
        }
    }
}
