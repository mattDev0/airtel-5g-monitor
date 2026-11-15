import SwiftUI

public struct WifiBandEditorSheet: View {
    public let band: WifiBand
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var ssid = ""
    @State private var key = ""
    @State private var authType = "2"
    @State private var hidden = false
    @State private var sameBothBands = false
    @State private var showKey = false
    @State private var localError: String?
    @State private var pendingConfirm: ConfirmPromptRequest?

    public init(band: WifiBand, viewModel: SettingsHubViewModel) {
        self.band = band
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                Section("\(band.label) Radio") {
                    Toggle("Radio Enabled", isOn: Binding(
                        get: { band == .band24g ? viewModel.wifiDetails.band24g.enabled : viewModel.wifiDetails.band5g.enabled },
                        set: { viewModel.toggleWifiBandEnabled(band, enabled: $0) }
                    ))
                    .tint(AirtelTheme.accent)
                }

                Section("Network Credentials") {
                    TextField("SSID", text: $ssid)
                        .autocorrectionDisabled()
                    Picker("Security", selection: $authType) {
                        Text("Open (None)").tag("0")
                        Text("WPA-PSK").tag("1")
                        Text("WPA2-PSK").tag("2")
                        Text("WPA/WPA2 Mixed").tag("3")
                        Text("WPA3").tag("4")
                    }
                    if authType != "0" {
                        HStack {
                            if showKey {
                                TextField("Password (8–63 chars)", text: $key)
                            } else {
                                SecureField("Password (8–63 chars)", text: $key)
                            }
                            Button { showKey.toggle() } label: {
                                Image(systemName: showKey ? "eye.slash" : "eye").foregroundStyle(.secondary)
                            }.buttonStyle(.plain)
                        }
                    }
                    Toggle("Hide SSID Broadcast", isOn: $hidden)
                        .tint(AirtelTheme.accent)
                    if viewModel.wifiDetails.wifiSamesSupported {
                        Toggle("Use Same SSID on 2.4G & 5G", isOn: $sameBothBands)
                            .tint(AirtelTheme.accent)
                    }
                }
            }
            .navigationTitle("\(band.label) Wi-Fi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { validateAndConfirm() }
                        .disabled(viewModel.sheetSaving)
                }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.wifiDetails) { _ in seed() }
            .sheet(item: $pendingConfirm) { req in
                ConfirmSheet(request: req).presentationDetents([.medium])
            }
        }
    }

    private func seed() {
        let d = (band == .band24g) ? viewModel.wifiDetails.band24g : viewModel.wifiDetails.band5g
        ssid = d.ssid
        key = d.key
        authType = d.authenticationType
        hidden = !d.visibleBroadcast
        sameBothBands = viewModel.wifiDetails.wifiSamesEnabled || d.wifiSames
    }

    private func validateAndConfirm() {
        localError = nil
        if let err = WifiAndLanValidators.validateSsid(ssid, forbiddenSsids: viewModel.wifiDetails.forbidSsids) {
            localError = err
            return
        }
        if let err = WifiAndLanValidators.validateWifiPassword(key, authenticationType: authType, forbiddenKeys: viewModel.wifiDetails.forbidKeys) {
            localError = err
            return
        }
        pendingConfirm = ConfirmPromptRequest(
            title: "Save \(band.label) Wi-Fi?",
            impactMessage: "Wi-Fi clients on \(band.label) will briefly reconnect using the updated SSID/password.",
            confirmLabel: "Save",
            tier: .connectivity,
            onConfirm: {
                viewModel.saveWifiBand(
                    band: band,
                    ssid: ssid,
                    key: key,
                    authType: authType,
                    hidden: hidden,
                    sameSsidBothBands: sameBothBands
                )
            }
        )
    }
}

public struct WifiQrSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedBand: WifiBand = .band5g
    @State private var revealPassword = false

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        let d = (selectedBand == .band24g) ? viewModel.wifiDetails.band24g : viewModel.wifiDetails.band5g
        let payload = Self.formatWifiQrPayload(ssid: d.ssid, key: d.key, authType: d.authenticationType, hidden: !d.visibleBroadcast)

        NavigationStack {
            VStack(spacing: 16) {
                Picker("Band", selection: $selectedBand) {
                    Text("5 GHz").tag(WifiBand.band5g)
                    Text("2.4 GHz").tag(WifiBand.band24g)
                }
                .pickerStyle(.segmented)

                QrCodeView(payload: payload)
                    .frame(maxWidth: 230, maxHeight: 230)

                SectionCard {
                    VStack(spacing: 8) {
                        HStack {
                            Text("SSID").font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Text(d.ssid.isEmpty ? "-" : d.ssid).font(.subheadline.weight(.semibold))
                        }
                        HStack {
                            Text("Password").font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Text(d.authenticationType == "0"
                                ? "None (Open)"
                                : (revealPassword ? d.key : SensitiveFormatters.maskIdentifier(d.key, visibleSuffix: 0)))
                                .font(.subheadline.monospacedDigit())
                            if d.authenticationType != "0" {
                                Button { revealPassword.toggle() } label: {
                                    Image(systemName: revealPassword ? "eye.slash" : "eye")
                                        .font(.caption)
                                }.buttonStyle(.plain)
                            }
                        }
                    }
                }
                Spacer()
            }
            .padding(16)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Share Wi-Fi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear {
                revealPassword = !viewModel.maskSensitiveInfo
                if !viewModel.wifiDetails.band5g.enabled && viewModel.wifiDetails.band24g.enabled {
                    selectedBand = .band24g
                }
            }
        }
    }

    public static func formatWifiQrPayload(ssid: String, key: String, authType: String, hidden: Bool) -> String {
        func esc(_ s: String) -> String {
            s.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: ";", with: "\\;")
                .replacingOccurrences(of: ",", with: "\\,")
                .replacingOccurrences(of: ":", with: "\\:")
        }
        let encType = authType == "0" ? "nopass" : "WPA"
        let pwdPart = authType == "0" ? "" : "P:\(esc(key));"
        let hiddenPart = hidden ? "H:true;" : ""
        return "WIFI:T:\(encType);S:\(esc(ssid));\(pwdPart)\(hiddenPart);"
    }
}

public struct GuestWifiSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var band: WifiBand = .band24g
    @State private var enabled = false
    @State private var ssid = ""
    @State private var key = ""
    @State private var authType = "2"
    @State private var broadcast = true
    @State private var wmm = true

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        let cfg = (band == .band24g) ? viewModel.guestWifi.band24g : viewModel.guestWifi.band5g
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                Section {
                    Picker("Band", selection: $band) {
                        Text("2.4 GHz").tag(WifiBand.band24g)
                        Text("5 GHz").tag(WifiBand.band5g)
                    }
                    .pickerStyle(.segmented)
                }

                if !cfg.mainWifiOpen {
                    Section {
                        Text("Main \(band.label) Wi-Fi radio is turned off; enable it before configuring Guest Wi-Fi.")
                            .font(.caption)
                            .foregroundStyle(AirtelTheme.warning)
                    }
                }

                Section("Guest Network (\(band.label))") {
                    Toggle("Enable Guest Wi-Fi", isOn: $enabled).tint(AirtelTheme.accent)
                    TextField("Guest SSID", text: $ssid)
                    Picker("Security", selection: $authType) {
                        Text("Open").tag("0")
                        Text("WPA2-PSK").tag("2")
                        Text("WPA/WPA2").tag("3")
                    }
                    if authType != "0" {
                        TextField("Password", text: $key)
                    }
                    Toggle("Broadcast SSID", isOn: $broadcast).tint(AirtelTheme.accent)
                    Toggle("WMM QoS", isOn: $wmm).tint(AirtelTheme.accent)
                }
            }
            .navigationTitle("Guest Wi-Fi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        let next = GuestWifiBandConfig(
                            wifiOpen: enabled,
                            ssid: ssid,
                            key: key,
                            authenticationType: authType,
                            encryption: cfg.encryption,
                            broadcast: broadcast,
                            wmm: wmm,
                            mainWifiOpen: cfg.mainWifiOpen,
                            customPort: cfg.customPort
                        )
                        viewModel.saveGuestWifi(band: band, config: next)
                    }
                    .disabled(viewModel.sheetSaving || !cfg.mainWifiOpen)
                }
            }
            .onAppear { loadBand() }
            .onChange(of: band) { _ in loadBand() }
            .onChange(of: viewModel.guestWifi) { _ in loadBand() }
        }
    }

    private func loadBand() {
        let c = (band == .band24g) ? viewModel.guestWifi.band24g : viewModel.guestWifi.band5g
        enabled = c.wifiOpen
        ssid = c.ssid
        key = c.key
        authType = c.authenticationType
        broadcast = c.broadcast
        wmm = c.wmm
    }
}

public struct WifiAdvancedSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var band: WifiBand = .band5g
    @State private var channel = "0"
    @State private var bandWidth = "0"
    @State private var txPower = "15"
    @State private var maxNum = 32
    @State private var apIsolate = false
    @State private var dfsSwitch = false

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        let cfg = (band == .band24g) ? viewModel.adv24g : viewModel.adv5g
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                Section {
                    Picker("Band", selection: $band) {
                        Text("5 GHz").tag(WifiBand.band5g)
                        Text("2.4 GHz").tag(WifiBand.band24g)
                    }
                    .pickerStyle(.segmented)
                }

                if let cfg {
                    Section("Radio Parameters") {
                        TextField("Channel (0 = Auto)", text: $channel)
                            .keyboardType(.numberPad)
                        Picker("Channel Bandwidth", selection: $bandWidth) {
                            Text("Auto").tag("0")
                            Text("20 MHz").tag("1")
                            Text("40 MHz").tag("2")
                            if band == .band5g {
                                Text("80 MHz").tag("3")
                                if dfsSwitch { Text("160 MHz (DFS)").tag("4") }
                            }
                        }
                        if band == .band5g {
                            Toggle("Enable 5 GHz DFS Channels", isOn: $dfsSwitch)
                                .tint(AirtelTheme.accent)
                        }
                        Stepper("Max Stations: \(maxNum) (max \(cfg.maxNumLimit))", value: $maxNum, in: 1...max(1, cfg.maxNumLimit))
                            .disabled(cfg.clientStaReadOnly)
                        Toggle("AP Client Isolation", isOn: $apIsolate)
                            .tint(AirtelTheme.accent)
                    }
                } else {
                    Section { ProgressView() }
                }
            }
            .navigationTitle("Advanced Wi-Fi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        guard let cur = (band == .band24g ? viewModel.adv24g : viewModel.adv5g) else { return }
                        let next = WifiAdvancedBandConfig(
                            wifiOpen: cur.wifiOpen,
                            countryCode: cur.countryCode,
                            workMode: cur.workMode,
                            channel: channel,
                            bandWidth: bandWidth,
                            txPower: txPower,
                            maxNum: maxNum,
                            maxNumLimit: cur.maxNumLimit,
                            apIsolate: apIsolate,
                            dfsSwitch: dfsSwitch,
                            clientStaReadOnly: cur.clientStaReadOnly,
                            staLimitPrompt: cur.staLimitPrompt
                        )
                        viewModel.saveWifiAdvanced(band: band, config: next)
                    }
                    .disabled(viewModel.sheetSaving)
                }
            }
            .onAppear { seedBand() }
            .onChange(of: band) { _ in seedBand() }
            .onChange(of: viewModel.adv24g) { _ in seedBand() }
            .onChange(of: viewModel.adv5g) { _ in seedBand() }
        }
    }

    private func seedBand() {
        guard let c = (band == .band24g ? viewModel.adv24g : viewModel.adv5g) else { return }
        channel = c.channel
        bandWidth = c.bandWidth
        txPower = c.txPower
        maxNum = c.maxNum
        apIsolate = c.apIsolate
        dfsSwitch = c.dfsSwitch
    }
}

public struct WifiPower411Sheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedLevel = "100"

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Transmit Power Profile") {
                    Picker("TX Power", selection: $selectedLevel) {
                        Text("Low (15%)").tag("15")
                        Text("Medium (50%)").tag("50")
                        Text("High (100%)").tag("100")
                    }
                    .pickerStyle(.inline)
                }
            }
            .navigationTitle("Wi-Fi TX Power")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.saveWifiPower411(selectedLevel) }
                        .disabled(viewModel.sheetSaving)
                }
            }
            .onAppear {
                if let p = viewModel.power411 { selectedLevel = p.txPower24g }
            }
        }
    }
}

public struct WifiWpsSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var band: WifiBand = .band24g
    @State private var clientPin = ""

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        let status = (band == .band24g) ? viewModel.wps.band24g : viewModel.wps.band5g
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section {
                    Picker("Band", selection: $band) {
                        Text("2.4 GHz").tag(WifiBand.band24g)
                        Text("5 GHz").tag(WifiBand.band5g)
                    }
                    .pickerStyle(.segmented)
                }
                Section("WPS Control") {
                    Toggle("Enable WPS (\(band.label))", isOn: Binding(
                        get: { status.enabled },
                        set: { viewModel.setWpsEnabled(band: band, enabled: $0) }
                    ))
                    .tint(AirtelTheme.accent)
                    .disabled(viewModel.sheetSaving)
                }
                if status.enabled {
                    Section("Push Button (PBC)") {
                        Button("Start WPS Push-Button Pairing (2 min)") {
                            viewModel.triggerWpsPbc(band: band)
                        }
                        .disabled(viewModel.sheetSaving)
                    }
                    Section("Client PIN") {
                        TextField("8-digit client WPS PIN", text: $clientPin)
                            .keyboardType(.numberPad)
                        Button("Connect with Client PIN") {
                            viewModel.submitWpsPin(band: band, pin: clientPin)
                        }
                        .disabled(viewModel.sheetSaving)
                    }
                    Section("Router PIN") {
                        if let pin = viewModel.randomWpsPin {
                            Text("Active Router PIN: \(pin)")
                                .font(.headline.monospacedDigit())
                        }
                        Button("Generate 2-Min Router PIN") {
                            viewModel.generateRandomWpsPin(band: band)
                        }
                        .disabled(viewModel.sheetSaving)
                    }
                }
            }
            .navigationTitle("WPS Pairing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }
}

public struct WifiScheduleSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var enabled = false
    @State private var onTime = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
    @State private var offTime = Calendar.current.date(bySettingHour: 23, minute: 0, second: 0, of: Date()) ?? Date()

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Daily Wi-Fi Schedule") {
                    Toggle("Enable Wi-Fi Schedule", isOn: $enabled).tint(AirtelTheme.accent)
                    if enabled {
                        DatePicker("Turn Wi-Fi On", selection: $onTime, displayedComponents: .hourAndMinute)
                        DatePicker("Turn Wi-Fi Off", selection: $offTime, displayedComponents: .hourAndMinute)
                    }
                }
            }
            .navigationTitle("Wi-Fi Schedule")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        let cal = Calendar.current
                        let cfg = WifiScheduleConfig(
                            enabled: enabled,
                            onHour: cal.component(.hour, from: onTime),
                            onMinute: cal.component(.minute, from: onTime),
                            offHour: cal.component(.hour, from: offTime),
                            offMinute: cal.component(.minute, from: offTime)
                        )
                        viewModel.saveWifiSchedule(cfg)
                    }
                    .disabled(viewModel.sheetSaving)
                }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.wifiSchedule) { _ in seed() }
        }
    }

    private func seed() {
        let s = viewModel.wifiSchedule
        enabled = s.enabled
        onTime = Calendar.current.date(bySettingHour: s.onHour, minute: s.onMinute, second: 0, of: Date()) ?? onTime
        offTime = Calendar.current.date(bySettingHour: s.offHour, minute: s.offMinute, second: 0, of: Date()) ?? offTime
    }
}
