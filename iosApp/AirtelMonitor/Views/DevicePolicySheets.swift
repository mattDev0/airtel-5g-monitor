import SwiftUI

public struct StaticLeasesSheet: View {
    @ObservedObject public var viewModel: DevicesViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var draft: [StaticDhcpLease] = []
    @State private var newIp = ""
    @State private var newMac = ""
    @State private var localError: String?
    @State private var didSeed = false

    public init(viewModel: DevicesViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.policyErrorMessage {
                    Section {
                        Text(err).font(.caption).foregroundStyle(AirtelTheme.error)
                    }
                }

                Section("Reserved Static DHCP Leases (\(draft.count)/20)") {
                    if viewModel.staticLeasesLoading && draft.isEmpty {
                        ProgressView()
                    } else if draft.isEmpty {
                        Text("No static IP reservations configured.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(Array(draft.enumerated()), id: \.offset) { idx, lease in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(lease.ip).font(.subheadline.weight(.semibold).monospacedDigit())
                                    Text(lease.mac).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button(role: .destructive) {
                                    draft.remove(at: idx)
                                } label: {
                                    Image(systemName: "trash")
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                Section("Add Reservation") {
                    TextField("IPv4 Address (e.g. 192.168.1.50)", text: $newIp)
                        .keyboardType(.numbersAndPunctuation)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("MAC Address (AA:BB:CC:DD:EE:FF)", text: $newMac)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    Button("Add Reservation") {
                        addLease()
                    }
                    .disabled(draft.count >= 20 || viewModel.staticLeasesSaving)
                }
            }
            .navigationTitle("Static DHCP Leases")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        viewModel.saveStaticLeases(draft)
                    }
                    .disabled(viewModel.staticLeasesSaving)
                }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.staticLeases) { latest in
                draft = latest
            }
        }
    }

    private func seed() {
        guard !didSeed else { return }
        didSeed = true
        draft = viewModel.staticLeases
        if let pre = viewModel.prefillDevice {
            newIp = pre.ip
            newMac = pre.mac
        }
    }

    private func addLease() {
        localError = nil
        let ip = newIp.trimmingCharacters(in: .whitespacesAndNewlines)
        guard NetworkFormatters.isIpv4(ip) else {
            localError = "Enter a valid IPv4 address"
            return
        }
        if let err = Phase71Validators.validateUnicastMac(newMac) {
            localError = err
            return
        }
        let normMac = Phase71Validators.normalizeMac(newMac) ?? newMac
        if draft.contains(where: { $0.ip == ip || $0.mac.caseInsensitiveCompare(normMac) == .orderedSame }) {
            localError = "IP or MAC already reserved"
            return
        }
        draft.append(StaticDhcpLease(ip: ip, mac: normMac))
        newIp = ""
        newMac = ""
    }
}

public struct MacFilterPolicySheet: View {
    @ObservedObject public var viewModel: DevicesViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var rules: [MacFilterRule] = []
    @State private var newMac = ""
    @State private var newRemark = ""
    @State private var localError: String?

    public init(viewModel: DevicesViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.policyErrorMessage {
                    Section {
                        Text(err).font(.caption).foregroundStyle(AirtelTheme.error)
                    }
                }

                Section {
                    Text(viewModel.macAcceptAll
                        ? "Current firewall mode: Blacklist (listed MACs are blocked from accessing the internet)."
                        : "Current firewall mode: Whitelist (only listed MACs are allowed).")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("MAC Rules (\(rules.count)/32)") {
                    if viewModel.macFilterLoading && rules.isEmpty {
                        ProgressView()
                    } else if rules.isEmpty {
                        Text("No MAC filter rules configured.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(Array(rules.enumerated()), id: \.offset) { idx, rule in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(rule.mac).font(.subheadline.weight(.semibold).monospacedDigit())
                                    if !rule.remark.isEmpty {
                                        Text(rule.remark).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Button(role: .destructive) {
                                    rules.remove(at: idx)
                                } label: {
                                    Image(systemName: "trash")
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                Section("Add MAC Rule") {
                    TextField("MAC (AA:BB:CC:DD:EE:FF)", text: $newMac)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("Remark (optional)", text: $newRemark)
                    Button("Add MAC Rule") {
                        addRule()
                    }
                    .disabled(rules.count >= 32 || viewModel.macFilterSaving)
                }
            }
            .navigationTitle("MAC Filter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        viewModel.saveMacFilters(rules)
                    }
                    .disabled(viewModel.macFilterSaving)
                }
            }
            .onAppear {
                rules = viewModel.macFilterRules
                if let pre = viewModel.prefillDevice {
                    newMac = pre.mac
                    newRemark = pre.displayName
                }
            }
            .onChange(of: viewModel.macFilterRules) { latest in
                rules = latest
            }
        }
    }

    private func addRule() {
        localError = nil
        if let err = Phase71Validators.validateUnicastMac(newMac) {
            localError = err
            return
        }
        guard let norm = Phase71Validators.normalizeMac(newMac) else { return }
        if rules.contains(where: { $0.mac.caseInsensitiveCompare(norm) == .orderedSame }) {
            localError = "MAC rule already exists"
            return
        }
        rules.append(MacFilterRule(
            mac: norm,
            remark: newRemark.trimmingCharacters(in: .whitespacesAndNewlines),
            ippro: "IPV4",
            enableRule: true,
            enableLink: !viewModel.macAcceptAll
        ))
        newMac = ""
        newRemark = ""
    }
}

public struct ParentalControlSheet: View {
    @ObservedObject public var viewModel: DevicesViewModel
    @Environment(\.dismiss) private var dismiss

    private enum Tab: String, CaseIterable, Identifiable {
        case schedule = "Schedule"
        case speed = "Speed"
        case url = "URL"
        var id: String { rawValue }
    }

    @State private var tab: Tab = .schedule
    @State private var timeRules: [ParentalTimeRule] = []
    @State private var speedRules: [ParentalSpeedRule] = []
    @State private var urlRules: [ParentalUrlRule] = []

    @State private var timeMac = ""
    @State private var timeStart = "22:00"
    @State private var timeEnd = "07:00"
    @State private var timeDays = "1,2,3,4,5"

    @State private var speedIp = ""
    @State private var speedKbps = "2000"

    @State private var urlMac = ""
    @State private var urlDomain = ""

    @State private var localError: String?

    public init(viewModel: DevicesViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.policyErrorMessage {
                    Section {
                        Text(err).font(.caption).foregroundStyle(AirtelTheme.error)
                    }
                }

                Section {
                    Toggle("Enable Parental Control", isOn: Binding(
                        get: { viewModel.parental.enabled },
                        set: { viewModel.setParentalEnabled($0) }
                    ))
                    .tint(AirtelTheme.accent)
                    .disabled(viewModel.parentalLoading || viewModel.parentalSaving)
                }

                Section {
                    Picker("Rule Type", selection: $tab) {
                        ForEach(Tab.allCases) { t in
                            Text(t.rawValue).tag(t)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                switch tab {
                case .schedule:
                    timeRulesSection
                case .speed:
                    speedRulesSection
                case .url:
                    urlRulesSection
                }
            }
            .navigationTitle("Parental Control")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save Tab") {
                        switch tab {
                        case .schedule: viewModel.saveParentalTimeRules(timeRules)
                        case .speed: viewModel.saveParentalSpeedRules(speedRules)
                        case .url: viewModel.saveParentalUrlRules(urlRules)
                        }
                    }
                    .disabled(viewModel.parentalSaving)
                }
            }
            .onAppear {
                seedFromSnapshot()
                if let pre = viewModel.prefillDevice {
                    timeMac = pre.mac
                    urlMac = pre.mac
                    speedIp = pre.ip
                }
            }
            .onChange(of: viewModel.parental) { _ in
                seedFromSnapshot()
            }
        }
    }

    private func seedFromSnapshot() {
        timeRules = viewModel.parental.timeRules
        speedRules = viewModel.parental.speedRules
        urlRules = viewModel.parental.urlRules
    }

    @ViewBuilder
    private var timeRulesSection: some View {
        Section("Internet Schedules (\(timeRules.count)/32)") {
            ForEach(Array(timeRules.enumerated()), id: \.offset) { idx, rule in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.mac).font(.subheadline.weight(.semibold).monospacedDigit())
                        Text("\(rule.startTime)–\(rule.endTime) · Days: \(rule.week)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(role: .destructive) {
                        timeRules.remove(at: idx)
                    } label: { Image(systemName: "trash") }
                        .buttonStyle(.plain)
                }
            }
        }

        Section("Add Schedule Rule") {
            TextField("MAC (AA:BB:CC:DD:EE:FF)", text: $timeMac)
                .textInputAutocapitalization(.characters)
            HStack {
                TextField("Start HH:mm", text: $timeStart)
                TextField("End HH:mm", text: $timeEnd)
            }
            TextField("Weekdays (1..7 comma-sep)", text: $timeDays)
            Button("Add Schedule Rule") {
                localError = nil
                guard let mac = Phase71Validators.normalizeMac(timeMac) else {
                    localError = "Enter a valid MAC address"
                    return
                }
                if let err = Phase71Validators.validateTimeWindow(startHhMm: timeStart, endHhMm: timeEnd) {
                    localError = err
                    return
                }
                timeRules.append(ParentalTimeRule(mac: mac, startTime: timeStart, endTime: timeEnd, week: timeDays, enableRule: true))
            }
            .disabled(timeRules.count >= 32)
        }
    }

    @ViewBuilder
    private var speedRulesSection: some View {
        Section("Bandwidth Caps (\(speedRules.count)/32)") {
            ForEach(Array(speedRules.enumerated()), id: \.offset) { idx, rule in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.ip).font(.subheadline.weight(.semibold).monospacedDigit())
                        Text("\(rule.speedKbps) KB/s")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(role: .destructive) {
                        speedRules.remove(at: idx)
                    } label: { Image(systemName: "trash") }
                        .buttonStyle(.plain)
                }
            }
        }

        Section("Add Speed Limit Rule") {
            TextField("Client IPv4", text: $speedIp)
                .keyboardType(.numbersAndPunctuation)
            TextField("Max Speed (KB/s)", text: $speedKbps)
                .keyboardType(.numberPad)
            Button("Add Speed Rule") {
                localError = nil
                let ip = speedIp.trimmingCharacters(in: .whitespacesAndNewlines)
                guard NetworkFormatters.isIpv4(ip) else {
                    localError = "Enter a valid IPv4 address"
                    return
                }
                guard let spd = Int(speedKbps), (1...1_000_000).contains(spd) else {
                    localError = "Speed limit must be 1–1,000,000 KB/s"
                    return
                }
                speedRules.append(ParentalSpeedRule(ip: ip, speedKbps: spd, enableRule: true))
            }
            .disabled(speedRules.count >= 32)
        }
    }

    @ViewBuilder
    private var urlRulesSection: some View {
        Section("URL Filter Rules (\(urlRules.count)/32)") {
            ForEach(Array(urlRules.enumerated()), id: \.offset) { idx, rule in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.url).font(.subheadline.weight(.semibold))
                        Text(rule.mac).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(role: .destructive) {
                        urlRules.remove(at: idx)
                    } label: { Image(systemName: "trash") }
                        .buttonStyle(.plain)
                }
            }
        }

        Section("Add Parental URL Rule") {
            TextField("Client MAC", text: $urlMac)
                .textInputAutocapitalization(.characters)
            TextField("Domain / URL", text: $urlDomain)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Add URL Rule") {
                localError = nil
                guard let mac = Phase71Validators.normalizeMac(urlMac) else {
                    localError = "Enter a valid MAC address"
                    return
                }
                if let err = Phase71Validators.validateFilterUrl(urlDomain) {
                    localError = err
                    return
                }
                urlRules.append(ParentalUrlRule(mac: mac, url: urlDomain.trimmingCharacters(in: .whitespacesAndNewlines), enableRule: true))
            }
            .disabled(urlRules.count >= 32)
        }
    }
}
