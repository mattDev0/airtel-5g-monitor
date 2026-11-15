import SwiftUI

public struct MobileNetworkSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedMode = "1C"
    @State private var pendingConfirm: ConfirmPromptRequest?

    private static let modes: [(code: String, label: String)] = [
        ("1E", "Auto (5G/4G/3G)"),
        ("1C", "5G SA + NSA / 4G"),
        ("C", "5G NSA Only"),
        ("4", "4G LTE Only")
    ]

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                if viewModel.featureFlags.lockPlmnFlag {
                    Section {
                        Text("Network mode is locked by your carrier PLMN policy.")
                            .font(.caption)
                            .foregroundStyle(AirtelTheme.warning)
                    }
                }

                Section("Preferred Network Mode") {
                    ForEach(Self.modes, id: \.code) { mode in
                        Button {
                            selectedMode = mode.code
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(NetworkModeCatalog.labelFor(mode.code))
                                        .foregroundStyle(.primary)
                                    Text("Code \(mode.code)")
                                        .font(.caption2.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if selectedMode.caseInsensitiveCompare(mode.code) == .orderedSame {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(AirtelTheme.accent)
                                }
                            }
                        }
                        .disabled(viewModel.featureFlags.lockPlmnFlag || viewModel.sheetSaving)
                    }

                    Button("Apply Network Mode") {
                        pendingConfirm = ConfirmPromptRequest(
                            title: "Change Network Mode?",
                            impactMessage: "Cellular internet will briefly drop while the modem re-registers on the selected radio mode.",
                            confirmLabel: "Apply Mode",
                            tier: .connectivity,
                            onConfirm: { viewModel.setNetworkMode(selectedMode) }
                        )
                    }
                    .disabled(viewModel.featureFlags.lockPlmnFlag || viewModel.sheetSaving)
                }

                Section("Carrier Aggregation & Roaming") {
                    Toggle("LTE Carrier Aggregation", isOn: Binding(
                        get: { viewModel.mobileAdvanced.lteCa },
                        set: { viewModel.setLteCa($0) }
                    ))
                    .tint(AirtelTheme.accent)
                    .disabled(viewModel.sheetSaving)

                    Toggle("5G NR Carrier Aggregation", isOn: Binding(
                        get: { viewModel.mobileAdvanced.nrCa },
                        set: { viewModel.setNrCa($0) }
                    ))
                    .tint(AirtelTheme.accent)
                    .disabled(viewModel.sheetSaving)

                    Toggle("Data Roaming", isOn: Binding(
                        get: { viewModel.mobileAdvanced.roamingEnable },
                        set: { viewModel.setRoaming($0) }
                    ))
                    .tint(AirtelTheme.accent)
                    .disabled(viewModel.sheetSaving)
                }
            }
            .navigationTitle("Mobile Network")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .onAppear {
                if !viewModel.mobileAdvanced.networkMode.isEmpty {
                    selectedMode = viewModel.mobileAdvanced.networkMode
                }
            }
            .onChange(of: viewModel.mobileAdvanced) { cfg in
                if !cfg.networkMode.isEmpty { selectedMode = cfg.networkMode }
            }
            .sheet(item: $pendingConfirm) { req in
                ConfirmSheet(request: req).presentationDetents([.medium])
            }
        }
    }
}

public struct ApnSettingsSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectType = "IP"
    @State private var mtuText = "1500"
    @State private var natEnabled = true
    @State private var profiles: [ApnProfile] = []

    @State private var newName = ""
    @State private var newApn = ""
    @State private var newAuthType = "0"
    @State private var newUser = ""
    @State private var newPass = ""
    @State private var localError: String?

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                Section("PDP Protocol & MTU") {
                    Picker("PDP Type", selection: $selectType) {
                        Text("IPv4 (IP)").tag("IP")
                        Text("IPv6 (IPV6)").tag("IPV6")
                        Text("Dual Stack (IPV4V6)").tag("IPV4V6")
                    }
                    TextField("MTU (576–1500)", text: $mtuText)
                        .keyboardType(.numberPad)
                    Toggle("NAT Enabled", isOn: $natEnabled).tint(AirtelTheme.accent)
                    Button("Save APN Basic Settings") {
                        localError = nil
                        if let err = Phase71Validators.validateApnMtu(mtuText, selectType: selectType) {
                            localError = err
                            return
                        }
                        viewModel.saveApnBasic(selectType: selectType, mtu: Int(mtuText) ?? 1500, natEnabled: natEnabled)
                    }
                    .disabled(viewModel.sheetSaving)
                }

                Section("APN Profiles (\(profiles.count))") {
                    ForEach(Array(profiles.enumerated()), id: \.offset) { idx, p in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(p.name).font(.subheadline.weight(.semibold))
                                    if p.isDefault { BadgePill("Default", color: AirtelTheme.success) }
                                }
                                Text(p.apnName).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Set Default") {
                                profiles = profiles.enumerated().map { i, item in
                                    var copy = item
                                    copy.isDefault = (i == idx)
                                    return copy
                                }
                            }
                            .font(.caption)
                            Button(role: .destructive) {
                                profiles.remove(at: idx)
                            } label: { Image(systemName: "trash") }
                                .buttonStyle(.plain)
                        }
                    }

                    if !profiles.isEmpty {
                        Button("Save Profile List") {
                            viewModel.saveApnProfiles(profiles)
                        }
                        .disabled(viewModel.sheetSaving)
                    }
                }

                Section("Add Profile") {
                    TextField("Profile Name", text: $newName)
                    TextField("APN (e.g. internet)", text: $newApn)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Picker("Auth", selection: $newAuthType) {
                        Text("None").tag("0")
                        Text("PAP").tag("1")
                        Text("CHAP").tag("2")
                    }
                    TextField("Username (optional)", text: $newUser)
                        .textInputAutocapitalization(.never)
                    SecureField("Password (optional)", text: $newPass)
                    Button("Add & Save Profile") {
                        profiles.append(ApnProfile(
                            name: newName,
                            apnName: newApn,
                            selectType: selectType,
                            username: newUser,
                            password: newPass,
                            authType: newAuthType,
                            mtu: Int(mtuText) ?? 1500,
                            natEnabled: natEnabled,
                            isDefault: profiles.isEmpty
                        ))
                        viewModel.saveApnProfiles(profiles)
                        newName = ""
                        newApn = ""
                        newUser = ""
                        newPass = ""
                    }
                    .disabled(viewModel.sheetSaving)
                }
            }
            .navigationTitle("APN Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.apnConfig) { _ in seed() }
        }
    }

    private func seed() {
        let a = viewModel.apnConfig
        selectType = a.selectType
        mtuText = "\(a.mtu)"
        natEnabled = a.natEnabled
        profiles = a.profiles
    }
}

public struct SimPinSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var pinInput = ""
    @State private var oldPin = ""
    @State private var newPin = ""
    @State private var pukCode = ""

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        let sim = viewModel.simState
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                Section("SIM Status") {
                    HStack {
                        Text("Status")
                        Spacer()
                        Text(sim.pukRequired ? "PUK Locked" : (sim.pinRequired ? "PIN Required" : "Ready"))
                            .foregroundStyle(.secondary)
                    }
                    HStack {
                        Text("PIN Attempts Left")
                        Spacer()
                        Text("\(sim.pinAttemptsLeft)").foregroundStyle(.secondary)
                    }
                    HStack {
                        Text("PUK Attempts Left")
                        Spacer()
                        Text("\(sim.pukAttemptsLeft)").foregroundStyle(.secondary)
                    }
                }

                if sim.pukRequired {
                    Section("Unlock SIM with PUK") {
                        TextField("8-digit PUK", text: $pukCode).keyboardType(.numberPad)
                        SecureField("New PIN (4–8 digits)", text: $newPin).keyboardType(.numberPad)
                        Button("Unlock SIM") {
                            viewModel.unlockWithPuk(puk: pukCode, newPin: newPin)
                        }
                        .disabled(viewModel.sheetSaving)
                    }
                } else if sim.pinRequired {
                    Section("Verify SIM PIN") {
                        SecureField("Enter SIM PIN", text: $pinInput).keyboardType(.numberPad)
                        Button("Verify PIN") {
                            viewModel.verifySimPin(pinInput)
                        }
                        .disabled(viewModel.sheetSaving)
                    }
                } else {
                    Section("SIM PIN Lock") {
                        SecureField("Current SIM PIN", text: $pinInput).keyboardType(.numberPad)
                        Button(sim.pinLockEnabled ? "Disable PIN Lock" : "Enable PIN Lock") {
                            viewModel.setSimPinLock(enabled: !sim.pinLockEnabled, pin: pinInput)
                        }
                        .disabled(viewModel.sheetSaving)
                    }

                    if sim.pinLockEnabled {
                        Section("Change SIM PIN") {
                            SecureField("Current PIN", text: $oldPin).keyboardType(.numberPad)
                            SecureField("New PIN", text: $newPin).keyboardType(.numberPad)
                            Button("Change PIN") {
                                viewModel.changeSimPin(oldPin: oldPin, newPin: newPin)
                            }
                            .disabled(viewModel.sheetSaving)
                        }
                    }
                }
            }
            .navigationTitle("SIM PIN / PUK")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }
}

public struct LanDhcpSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var lanIp = ""
    @State private var netMask = ""
    @State private var dhcpEnabled = true
    @State private var ipBegin = ""
    @State private var ipEnd = ""
    @State private var expireTime = "24h"
    @State private var dns1 = ""
    @State private var dns2 = ""
    @State private var localError: String?
    @State private var pendingConfirm: ConfirmPromptRequest?

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                Section("Router LAN") {
                    TextField("LAN IP", text: $lanIp).keyboardType(.numbersAndPunctuation)
                    TextField("Subnet Mask", text: $netMask).keyboardType(.numbersAndPunctuation)
                }

                Section("DHCP Pool") {
                    Toggle("Enable DHCP Server", isOn: $dhcpEnabled).tint(AirtelTheme.accent)
                    if dhcpEnabled {
                        TextField("Start IP", text: $ipBegin).keyboardType(.numbersAndPunctuation)
                        TextField("End IP", text: $ipEnd).keyboardType(.numbersAndPunctuation)
                        TextField("Lease Time (e.g. 24h)", text: $expireTime)
                    }
                }

                Section("DNS Override (Optional)") {
                    TextField("Primary DNS", text: $dns1).keyboardType(.numbersAndPunctuation)
                    TextField("Secondary DNS", text: $dns2).keyboardType(.numbersAndPunctuation)
                }
            }
            .navigationTitle("LAN & DHCP")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { validateAndConfirm() }
                        .disabled(viewModel.sheetSaving)
                }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.lanConfig) { _ in seed() }
            .sheet(item: $pendingConfirm) { req in
                ConfirmSheet(request: req).presentationDetents([.medium])
            }
        }
    }

    private func seed() {
        let c = viewModel.lanConfig
        lanIp = c.lanIp
        netMask = c.netMask
        dhcpEnabled = c.dhcpEnabled
        ipBegin = c.ipBegin
        ipEnd = c.ipEnd
        expireTime = c.expireTime
        dns1 = c.primaryDns
        dns2 = c.secondaryDns
    }

    private func validateAndConfirm() {
        localError = nil
        if let err = WifiAndLanValidators.validateDhcpSettings(
            lanIp: lanIp,
            netMask: netMask,
            dhcpEnabled: dhcpEnabled,
            ipBegin: ipBegin,
            ipEnd: ipEnd
        ) {
            localError = err
            return
        }
        pendingConfirm = ConfirmPromptRequest(
            title: "Save LAN & DHCP?",
            impactMessage: "Changing LAN subnet or DHCP restarts local leases for connected devices.",
            confirmLabel: "Save LAN Settings",
            tier: .connectivity,
            onConfirm: {
                let cur = viewModel.lanConfig
                let next = LanConfig(
                    lanIp: lanIp,
                    netMask: netMask,
                    dhcpEnabled: dhcpEnabled,
                    ipBegin: ipBegin,
                    ipEnd: ipEnd,
                    expireTime: expireTime,
                    primaryDns: dns1,
                    secondaryDns: dns2,
                    ipv6PrimaryDns: cur.ipv6PrimaryDns,
                    ipv6SecondaryDns: cur.ipv6SecondaryDns
                )
                viewModel.saveLanDhcp(next)
            }
        )
    }
}

public struct DiagnosisSheet: View {
    @ObservedObject public var viewModel: SettingsHubViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var mode: DiagnosisMode = .ping
    @State private var target = "8.8.8.8"
    @State private var pingCount = 4

    public init(viewModel: SettingsHubViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                SectionCard {
                    VStack(spacing: 12) {
                        Picker("Tool", selection: $mode) {
                            ForEach(DiagnosisMode.allCases) { m in
                                Text(m.rawValue).tag(m)
                            }
                        }
                        .pickerStyle(.segmented)

                        TextField("Host or IP (e.g. 8.8.8.8)", text: $target)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)

                        if mode == .ping {
                            Stepper("Ping Count: \(pingCount)", value: $pingCount, in: 1...10)
                        }

                        HStack(spacing: 10) {
                            if viewModel.diagnosisState.isRunning {
                                Button("Stop") {
                                    viewModel.stopDiagnosis()
                                }
                                .liquidGlassSmallButton()
                                .frame(maxWidth: .infinity)
                            } else {
                                Button("Run \(mode.rawValue)") {
                                    viewModel.runDiagnosis(mode: mode, target: target, pingCount: pingCount)
                                }
                                .liquidGlassActionButton(tint: AirtelTheme.accent)
                                .frame(maxWidth: .infinity)
                            }
                        }
                    }
                }

                SectionCard(title: "Router Output") {
                    ScrollView {
                        Text(viewModel.diagnosisState.output.isEmpty ? "Ready." : viewModel.diagnosisState.output)
                            .font(.system(.caption, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    .frame(maxHeight: 280)
                }

                Spacer()
            }
            .padding(16)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Network Diagnostics")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        viewModel.stopDiagnosis()
                        dismiss()
                    }
                }
            }
        }
    }
}
