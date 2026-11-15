import SwiftUI

public struct SettingsHubView: View {
    @ObservedObject public var container: AppContainer
    @ObservedObject public var shellVM: ShellViewModel
    @Binding public var openConnectionTrigger: Bool
    public var onNavigate: (AppRoute) -> Void

    @StateObject private var viewModel: SettingsHubViewModel
    private static let pollOptions: [Double] = [1.0, 2.0, 3.0, 5.0, 10.0]

    public init(
        container: AppContainer,
        shellVM: ShellViewModel,
        openConnectionTrigger: Binding<Bool>,
        onNavigate: @escaping (AppRoute) -> Void
    ) {
        self.container = container
        self.shellVM = shellVM
        self._openConnectionTrigger = openConnectionTrigger
        self.onNavigate = onNavigate
        _viewModel = StateObject(wrappedValue: SettingsHubViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                RouterConnectivityBanner(shellVM: shellVM) {
                    viewModel.openSheet(.connection)
                }

                toolsNavigationCard
                wifiSectionCard
                mobileAndSimSectionCard
                lanAndDiagSectionCard
                appPreferencesCard
                connectionCard
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            RouterShellToolbarControls(shellVM: shellVM) {
                viewModel.openSheet(.connection)
            }
        }
        .onAppear {
            viewModel.ensureLoaded()
            if openConnectionTrigger {
                openConnectionTrigger = false
                viewModel.openSheet(.connection)
            }
        }
        .onChange(of: openConnectionTrigger) { triggered in
            if triggered {
                openConnectionTrigger = false
                viewModel.openSheet(.connection)
            }
        }
        .sheet(item: $viewModel.activeSheet) { sheet in
            activeSheetView(sheet)
        }
    }

    private var toolsNavigationCard: some View {
        SectionCard(title: "Router Features") {
            VStack(spacing: 8) {
                SettingValueRow(
                    title: "Messages & USSD",
                    subtitle: "Router SMS inbox, composer, and USSD dialer",
                    value: viewModel.unreadSmsCount > 0 ? "\(viewModel.unreadSmsCount) unread" : "",
                    icon: "message.fill"
                ) {
                    onNavigate(.messages)
                }
                Divider()
                SettingValueRow(
                    title: "Data Usage & Quota",
                    subtitle: "31-day bar history, monthly cap, calibration",
                    icon: "chart.bar.fill"
                ) {
                    onNavigate(.usage)
                }
                Divider()
                SettingValueRow(
                    title: "Security & Firewall",
                    subtitle: "Firewall level, DMZ, port forwards, MAC/IP/URL filters",
                    icon: "shield.fill"
                ) {
                    onNavigate(.security)
                }
                Divider()
                SettingValueRow(
                    title: "System & Speed Test",
                    subtitle: "Speed test, self-check, logs, NTP, scheduled reboot",
                    icon: "wrench.and.screwdriver.fill"
                ) {
                    onNavigate(.system)
                }
                Divider()
                SettingValueRow(
                    title: "Antenna Alignment",
                    subtitle: "Live 2s SINR & RSRP meter with audio pip",
                    icon: "scope"
                ) {
                    onNavigate(.alignment)
                }
            }
        }
    }

    private var wifiSectionCard: some View {
        let d24 = viewModel.wifiDetails.band24g
        let d5 = viewModel.wifiDetails.band5g
        let flags = viewModel.featureFlags

        return SectionCard(title: "Wi-Fi Settings") {
            VStack(spacing: 8) {
                SettingValueRow(
                    title: "2.4 GHz Wi-Fi",
                    subtitle: d24.ssid.isEmpty ? "Configure 2.4 GHz network" : d24.ssid,
                    value: d24.enabled ? "On" : "Off",
                    icon: "wifi"
                ) {
                    viewModel.openSheet(.wifi24g)
                }
                Divider()
                SettingValueRow(
                    title: "5 GHz Wi-Fi",
                    subtitle: d5.ssid.isEmpty ? "Configure 5 GHz network" : d5.ssid,
                    value: d5.enabled ? "On" : "Off",
                    icon: "wifi.circle"
                ) {
                    viewModel.openSheet(.wifi5g)
                }
                Divider()
                SettingValueRow(
                    title: "Share Wi-Fi QR Code",
                    subtitle: "Instant QR join for guests and phones",
                    icon: "qrcode"
                ) {
                    viewModel.openSheet(.wifiQr)
                }

                if !flags.isLoaded || flags.guestWifi24gAvailable || flags.guestWifi5gAvailable {
                    Divider()
                    SettingValueRow(
                        title: "Guest Wi-Fi",
                        subtitle: "Separate SSID with optional client isolation",
                        icon: "person.2.fill"
                    ) {
                        viewModel.openSheet(.guestWifi)
                    }
                }

                Divider()
                SettingValueRow(
                    title: "Advanced Wi-Fi Radio",
                    subtitle: "Channel, bandwidth, DFS, AP isolate, client limit",
                    icon: "slider.horizontal.3"
                ) {
                    viewModel.openSheet(.wifiAdvanced)
                }

                if flags.wifiPowerCmd411Available {
                    Divider()
                    SettingValueRow(
                        title: "Wi-Fi Transmit Power",
                        subtitle: "Adjust router TX power profile",
                        icon: "bolt.horizontal.fill"
                    ) {
                        viewModel.openSheet(.wifiPower)
                    }
                }

                if !flags.isLoaded || flags.wpsAvailable {
                    Divider()
                    SettingValueRow(
                        title: "WPS Pairing",
                        subtitle: "Push-button PBC, PIN, or random router PIN",
                        icon: "lock.open.fill"
                    ) {
                        viewModel.openSheet(.wifiWps)
                    }
                }

                if !flags.isLoaded || flags.wifiScheduleAvailable {
                    Divider()
                    SettingValueRow(
                        title: "Wi-Fi Schedule",
                        subtitle: "Automatic daily Wi-Fi on/off window",
                        icon: "calendar.badge.clock"
                    ) {
                        viewModel.openSheet(.wifiSchedule)
                    }
                }
            }
        }
    }

    private var mobileAndSimSectionCard: some View {
        let flags = viewModel.featureFlags
        return SectionCard(title: "Cellular & SIM") {
            VStack(spacing: 8) {
                if !flags.isLoaded || flags.networkSetAvailable {
                    SettingValueRow(
                        title: "Mobile Network & CA",
                        subtitle: viewModel.networkModeSummary,
                        value: viewModel.networkModeLabel,
                        icon: "antenna.radiowaves.left.and.right"
                    ) {
                        viewModel.openSheet(.mobileNetwork)
                    }
                    Divider()
                }
                if !flags.isLoaded || flags.apnAvailable {
                    SettingValueRow(
                        title: "APN & Profiles",
                        subtitle: viewModel.apnConfig.selectType.isEmpty ? "IPv4 / IPv6 & MTU" : "\(viewModel.apnConfig.selectType) · MTU \(viewModel.apnConfig.mtu)",
                        icon: "network"
                    ) {
                        viewModel.openSheet(.apn)
                    }
                    Divider()
                }
                SettingValueRow(
                    title: "SIM PIN / PUK",
                    subtitle: viewModel.simState.pukRequired
                        ? "PUK unlock required"
                        : (viewModel.simState.pinRequired ? "SIM PIN required" : (viewModel.simState.pinLockEnabled ? "PIN Lock Enabled" : "Unlocked")),
                    value: "Tries: \(viewModel.simState.pinAttemptsLeft)",
                    icon: "simcard.fill"
                ) {
                    viewModel.openSheet(.simPin)
                }
            }
        }
    }

    private var lanAndDiagSectionCard: some View {
        SectionCard(title: "LAN & Network Diagnostics") {
            VStack(spacing: 8) {
                SettingValueRow(
                    title: "LAN & DHCP Server",
                    subtitle: "\(viewModel.lanConfig.lanIp) · DHCP \(viewModel.lanConfig.dhcpEnabled ? "On" : "Off")",
                    icon: "server.rack"
                ) {
                    viewModel.openSheet(.lanDhcp)
                }
                Divider()
                SettingValueRow(
                    title: "Ping & Traceroute",
                    subtitle: "Run diagnostics directly from the router",
                    icon: "terminal.fill"
                ) {
                    viewModel.openSheet(.diagnosis)
                }
            }
        }
    }

    private var appPreferencesCard: some View {
        SectionCard(title: "App Preferences") {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Polling Interval")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Picker("Polling Interval", selection: Binding(
                        get: { viewModel.pollIntervalSeconds },
                        set: { viewModel.setPollInterval($0) }
                    )) {
                        ForEach(Self.pollOptions, id: \.self) { sec in
                            Text(NetworkFormatters.formatPollInterval(sec)).tag(sec)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("Appearance")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Picker("Theme", selection: Binding(
                        get: { viewModel.themePreference },
                        set: { viewModel.setThemePreference($0) }
                    )) {
                        ForEach(ThemePreference.allCases) { theme in
                            Text(theme.label).tag(theme)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("Speed Display Unit")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Picker("Speed Unit", selection: Binding(
                        get: { viewModel.speedUnit },
                        set: { viewModel.setSpeedUnitPreference($0) }
                    )) {
                        ForEach(SpeedUnitPreference.allCases) { u in
                            Text(u.label).tag(u)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Divider()

                SettingSwitchRow(
                    title: "Mask Sensitive Identifiers",
                    subtitle: "Hide IMEI, IMSI, ICCID, serial, and passwords by default",
                    icon: "eye.slash.fill",
                    isOn: Binding(
                        get: { viewModel.maskSensitiveInfo },
                        set: { viewModel.setMaskSensitiveInfo($0) }
                    ),
                    onChanged: { _ in }
                )
            }
        }
    }

    private var connectionCard: some View {
        SectionCard(title: "Router Connection") {
            VStack(spacing: 8) {
                SettingValueRow(
                    title: "Router Admin Host",
                    subtitle: "User: \(viewModel.username)",
                    value: viewModel.routerHost,
                    icon: "key.fill"
                ) {
                    viewModel.openSheet(.connection)
                }
            }
        }
    }

    @ViewBuilder
    private func activeSheetView(_ sheet: ActiveSettingsSheet) -> some View {
        switch sheet {
        case .wifi24g:
            WifiBandEditorSheet(band: .band24g, viewModel: viewModel)
        case .wifi5g:
            WifiBandEditorSheet(band: .band5g, viewModel: viewModel)
        case .wifiQr:
            WifiQrSheet(viewModel: viewModel)
        case .guestWifi:
            GuestWifiSheet(viewModel: viewModel)
        case .wifiAdvanced:
            WifiAdvancedSheet(viewModel: viewModel)
        case .wifiPower:
            WifiPower411Sheet(viewModel: viewModel)
        case .wifiWps:
            WifiWpsSheet(viewModel: viewModel)
        case .wifiSchedule:
            WifiScheduleSheet(viewModel: viewModel)
        case .mobileNetwork:
            MobileNetworkSheet(viewModel: viewModel)
        case .apn:
            ApnSettingsSheet(viewModel: viewModel)
        case .simPin:
            SimPinSheet(viewModel: viewModel)
        case .lanDhcp:
            LanDhcpSheet(viewModel: viewModel)
        case .diagnosis:
            DiagnosisSheet(viewModel: viewModel)
        case .connection:
            ConnectionEditSheet(viewModel: viewModel)
        }
    }
}

private struct ConnectionEditSheet: View {
    @ObservedObject var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var host = ""
    @State private var username = ""
    @State private var password = ""
    @State private var showPwd = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Router Connection") {
                    TextField("Host or IP (192.168.1.1)", text: $host)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Username (default: user)", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        if showPwd {
                            TextField("Admin Password", text: $password)
                        } else {
                            SecureField("Admin Password", text: $password)
                        }
                        Button {
                            showPwd.toggle()
                        } label: {
                            Image(systemName: showPwd ? "eye.slash" : "eye")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Router Credentials")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        viewModel.saveConnection(host: host, username: username, password: password)
                    }
                    .fontWeight(.semibold)
                }
            }
            .onAppear {
                host = viewModel.routerHost
                username = viewModel.username
                password = viewModel.storedPassword()
            }
        }
    }
}
