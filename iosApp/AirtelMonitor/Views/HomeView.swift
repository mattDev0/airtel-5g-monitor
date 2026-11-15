import SwiftUI

public struct HomeView: View {
    @ObservedObject public var container: AppContainer
    @ObservedObject public var shellVM: ShellViewModel
    public var onNavigate: (AppRoute) -> Void
    public var onSwitchTab: (AppTab) -> Void

    @StateObject private var viewModel: HomeViewModel
    @State private var confirmRequest: ConfirmPromptRequest?
    @State private var openConnectionModal = false

    public init(
        container: AppContainer,
        shellVM: ShellViewModel,
        onNavigate: @escaping (AppRoute) -> Void,
        onSwitchTab: @escaping (AppTab) -> Void
    ) {
        self.container = container
        self.shellVM = shellVM
        self.onNavigate = onNavigate
        self.onSwitchTab = onSwitchTab
        _viewModel = StateObject(wrappedValue: HomeViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                RouterConnectivityBanner(shellVM: shellVM) {
                    openConnectionModal = true
                }

                if let state = viewModel.routerState {
                    heroCard(state)
                    speedCard(state)
                    monthlyUsageCard(state.monthlyUsage)
                    quickTogglesCard(state)
                    quickToolsGrid(state)
                } else {
                    SkeletonBox(height: 170)
                    SkeletonBox(height: 120)
                    SkeletonBox(height: 110)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Home")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            RouterShellToolbarControls(shellVM: shellVM) {
                openConnectionModal = true
            }
        }
        .refreshable {
            shellVM.forceRefresh()
        }
        .onAppear {
            viewModel.ensureWifiRadiosLoaded()
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
        .sheet(item: $confirmRequest) { req in
            ConfirmSheet(request: req)
                .presentationDetents([.medium])
        }
        .sheet(isPresented: $openConnectionModal) {
            OnboardingView(container: container)
        }
    }

    private func heroCard(_ state: RouterState) -> some View {
        let primaryRsrp = Double(state.cellular.rsrp5g) ?? Double(state.cellular.rsrp4g)
        let primarySinr = Double(state.cellular.sinr5g) ?? Double(state.cellular.sinr4g)
        let rsrpColor = AirtelTheme.rsrpColor(primaryRsrp)
        let netColor: Color = state.cellular.networkType.contains("5G") ? AirtelTheme.nr5g : AirtelTheme.lte4g
        let cellPill = CarrierParser.formatCellPill(state.carriers)
        let caSummary = CarrierParser.formatCaSummary(state.carriers)

        return SectionCard(tint: netColor) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            BadgePill(state.cellular.networkType, color: netColor)
                            Text(state.cellular.operatorName)
                                .font(.headline)
                        }
                        Text(caSummary == "-" ? state.cellular.boardType : caSummary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 4) {
                        SignalBars(level: state.cellular.signalLvl, activeColor: rsrpColor)
                        Text(AirtelTheme.qualityLabel(rsrpDbm: primaryRsrp))
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(rsrpColor)
                    }
                }

                HStack(spacing: 10) {
                    StatTile(
                        title: state.cellular.has5gMetrics ? "5G RSRP" : "4G RSRP",
                        value: state.cellular.has5gMetrics ? state.cellular.rsrp5g : state.cellular.rsrp4g,
                        unit: "dBm",
                        accent: rsrpColor
                    )
                    StatTile(
                        title: state.cellular.has5gMetrics ? "5G SINR" : "4G SINR",
                        value: state.cellular.has5gMetrics ? state.cellular.sinr5g : state.cellular.sinr4g,
                        unit: "dB",
                        accent: AirtelTheme.sinrColor(primarySinr)
                    )
                }

                HStack(spacing: 14) {
                    heroMetaLabel(icon: "globe", text: state.wanIp)
                    heroMetaLabel(icon: "clock", text: state.onlineDurationText)
                    if cellPill != "-" {
                        heroMetaLabel(icon: "dot.radiowaves.left.and.right", text: cellPill)
                    }
                }
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
    }

    private func heroMetaLabel(icon: String, text: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.caption2)
            Text(text)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }

    private func speedCard(_ state: RouterState) -> some View {
        let dl = NetworkFormatters.formatThroughput(state.wan.downloadMbps, unit: viewModel.speedUnit)
        let ul = NetworkFormatters.formatThroughput(state.wan.uploadMbps, unit: viewModel.speedUnit)

        return SectionCard(
            title: "Live Throughput",
            subtitle: "Updated \(state.timeFormatted)"
        ) {
            VStack(spacing: 12) {
                HStack(spacing: 10) {
                    StatTile(
                        title: "Download",
                        value: dl.0,
                        unit: dl.1,
                        caption: NetworkFormatters.formatPeakThroughput(state.wan.peakDlMbps, unit: viewModel.speedUnit),
                        accent: AirtelTheme.nr5g
                    )
                    StatTile(
                        title: "Upload",
                        value: ul.0,
                        unit: ul.1,
                        caption: NetworkFormatters.formatPeakThroughput(state.wan.peakUlMbps, unit: viewModel.speedUnit),
                        accent: AirtelTheme.upload
                    )
                }

                if state.history.count >= 2 {
                    Sparkline(
                        values: state.history.map(\.downloadMbps),
                        lineColor: AirtelTheme.nr5g,
                        secondaryValues: state.history.map(\.uploadMbps),
                        secondaryColor: AirtelTheme.upload
                    )
                }
            }
        }
    }

    private func monthlyUsageCard(_ usage: MonthlyUsageSummary) -> some View {
        let barTint: Color = usage.isOverWarning ? AirtelTheme.warning : AirtelTheme.accent
        return SectionCard(
            title: "Monthly Data Usage",
            trailing: {
                Button("Details") {
                    onNavigate(.usage)
                }
                .font(.caption.weight(.semibold))
            }
        ) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(NetworkFormatters.formatDataMb(usage.usedMb))
                        .font(.title3.weight(.bold).monospacedDigit())
                    if usage.limitEnabled, let quota = usage.quotaMb, quota > 0 {
                        Text("of \(NetworkFormatters.formatDataMb(quota))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let pct = usage.percentUsed, usage.limitEnabled {
                        BadgePill("\(pct)%", color: usage.isOverWarning ? AirtelTheme.warning : .secondary)
                    }
                }

                if let fraction = usage.progressFraction {
                    FlatProgressBar(fraction: fraction, tint: barTint, height: 8)
                } else {
                    HStack(spacing: 10) {
                        Text("No monthly quota configured")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("Set a limit") {
                            onNavigate(.usage)
                        }
                        .font(.caption.weight(.semibold))
                        .liquidGlassSmallButton()
                    }
                }

                if let days = usage.daysLeftInCycle {
                    Text("\(days) day\(days == 1 ? "" : "s") remaining in billing cycle")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func quickTogglesCard(_ state: RouterState) -> some View {
        let mobileOn = state.networkModeSnapshot.mobileDataOn ?? true
        let flightOn = state.networkModeSnapshot.flightModeOn ?? false

        return SectionCard(title: "Quick Controls") {
            VStack(spacing: 8) {
                SettingSwitchRow(
                    title: "Mobile Data",
                    subtitle: mobileOn ? "Cellular WAN active" : "Cellular WAN paused",
                    icon: "cellularbars",
                    isOn: Binding(
                        get: { mobileOn },
                        set: { newVal in
                            if !newVal {
                                confirmRequest = ConfirmPromptRequest(
                                    title: "Turn Off Mobile Data?",
                                    impactMessage: "All connected devices will lose internet access until mobile data is turned back on.",
                                    confirmLabel: "Turn Off",
                                    tier: .connectivity,
                                    onConfirm: { viewModel.setMobileData(false) }
                                )
                            } else {
                                viewModel.setMobileData(true)
                            }
                        }
                    ),
                    isBusy: viewModel.networkActionBusy,
                    onChanged: { _ in }
                )

                Divider()

                SettingSwitchRow(
                    title: "Flight Mode",
                    subtitle: flightOn ? "Cellular modem disabled" : "Modem radios active",
                    icon: "airplane",
                    isOn: Binding(
                        get: { flightOn },
                        set: { newVal in
                            if newVal {
                                confirmRequest = ConfirmPromptRequest(
                                    title: "Enable Flight Mode?",
                                    impactMessage: "Turning on Flight Mode shuts down cellular 4G/5G radios until disabled.",
                                    confirmLabel: "Enable Flight Mode",
                                    tier: .connectivity,
                                    onConfirm: { viewModel.setFlightMode(true) }
                                )
                            } else {
                                viewModel.setFlightMode(false)
                            }
                        }
                    ),
                    isBusy: viewModel.networkActionBusy,
                    onChanged: { _ in }
                )

                Divider()

                SettingSwitchRow(
                    title: "Wi-Fi 2.4 GHz",
                    subtitle: viewModel.wifiRadios.ssid24g.isEmpty ? "2.4 GHz radio" : viewModel.wifiRadios.ssid24g,
                    icon: "wifi",
                    isOn: Binding(
                        get: { viewModel.wifiRadios.enabled24g },
                        set: { newVal in
                            if !newVal {
                                confirmRequest = ConfirmPromptRequest(
                                    title: "Disable 2.4 GHz Wi-Fi?",
                                    impactMessage: "2.4 GHz clients will disconnect immediately.",
                                    confirmLabel: "Disable 2.4 GHz",
                                    tier: .connectivity,
                                    onConfirm: { viewModel.setWifi24g(false) }
                                )
                            } else {
                                viewModel.setWifi24g(true)
                            }
                        }
                    ),
                    isBusy: viewModel.networkActionBusy,
                    onChanged: { _ in }
                )

                Divider()

                SettingSwitchRow(
                    title: "Wi-Fi 5 GHz",
                    subtitle: viewModel.wifiRadios.ssid5g.isEmpty ? "5 GHz radio" : viewModel.wifiRadios.ssid5g,
                    icon: "wifi.circle",
                    isOn: Binding(
                        get: { viewModel.wifiRadios.enabled5g },
                        set: { newVal in
                            if !newVal {
                                confirmRequest = ConfirmPromptRequest(
                                    title: "Disable 5 GHz Wi-Fi?",
                                    impactMessage: "5 GHz clients will disconnect immediately.",
                                    confirmLabel: "Disable 5 GHz",
                                    tier: .connectivity,
                                    onConfirm: { viewModel.setWifi5g(false) }
                                )
                            } else {
                                viewModel.setWifi5g(true)
                            }
                        }
                    ),
                    isBusy: viewModel.networkActionBusy,
                    onChanged: { _ in }
                )
            }
        }
    }

    private func quickToolsGrid(_ state: RouterState) -> some View {
        let onlineDevices = state.devices.filter { !$0.isRouter && $0.isOnline }.count
        return SectionCard(title: "Tools & Shortcuts") {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                quickTile(title: "Band & Cell Lock", subtitle: "Pin bands / PCI", icon: "lock.fill") {
                    viewModel.openLockEditor()
                }
                quickTile(title: "Antenna Align", subtitle: "Live SINR & sound", icon: "scope") {
                    onNavigate(.alignment)
                }
                quickTile(title: "Devices", subtitle: "\(onlineDevices) online", icon: "laptopcomputer.and.iphone") {
                    onSwitchTab(.devices)
                }
                quickTile(
                    title: "Messages & USSD",
                    subtitle: viewModel.unreadSmsCount > 0 ? "\(viewModel.unreadSmsCount) unread" : "SMS & dialer",
                    icon: "message.fill",
                    badgeCount: viewModel.unreadSmsCount
                ) {
                    onNavigate(.messages)
                }
                quickTile(title: "Security", subtitle: "Firewall & filters", icon: "shield.fill") {
                    onNavigate(.security)
                }
                quickTile(title: "System & Speed", subtitle: "Speed test & logs", icon: "speedometer") {
                    onNavigate(.system)
                }
            }
        }
    }

    private func quickTile(
        title: String,
        subtitle: String,
        icon: String,
        badgeCount: Int = 0,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: icon)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AirtelTheme.accent)
                    Spacer()
                    if badgeCount > 0 {
                        BadgePill("\(badgeCount)", color: AirtelTheme.accent)
                    }
                }
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
