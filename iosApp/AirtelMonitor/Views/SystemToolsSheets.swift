import SwiftUI

public struct ScheduledRebootSheet: View {
    @ObservedObject public var viewModel: SystemToolsViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var enabled = false
    @State private var selectedTime = Calendar.current.date(bySettingHour: 3, minute: 0, second: 0, of: Date()) ?? Date()
    @State private var selectedDays: Set<String> = ["1", "3", "5"]

    private static let dayLabels: [(id: String, name: String)] = [
        ("1", "Mon"), ("2", "Tue"), ("3", "Wed"), ("4", "Thu"),
        ("5", "Fri"), ("6", "Sat"), ("7", "Sun")
    ]

    public init(viewModel: SystemToolsViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Automatic Restart Schedule") {
                    Toggle("Enable Scheduled Reboot", isOn: $enabled).tint(AirtelTheme.accent)
                    if enabled {
                        DatePicker("Restart Time", selection: $selectedTime, displayedComponents: .hourAndMinute)
                        ForEach(Self.dayLabels, id: \.id) { day in
                            let active = selectedDays.contains(day.id)
                            Button {
                                if active { selectedDays.remove(day.id) } else { selectedDays.insert(day.id) }
                            } label: {
                                HStack {
                                    Text(day.name).foregroundStyle(.primary)
                                    Spacer()
                                    if active { Image(systemName: "checkmark").foregroundStyle(AirtelTheme.accent) }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Scheduled Reboot")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        let cal = Calendar.current
                        let daysStr = Self.dayLabels.map(\.id).filter { selectedDays.contains($0) }.joined(separator: ",")
                        viewModel.saveScheduledReboot(
                            enabled: enabled,
                            hour: cal.component(.hour, from: selectedTime),
                            minute: cal.component(.minute, from: selectedTime),
                            weekDays: daysStr
                        )
                    }
                    .disabled(viewModel.sheetBusy)
                }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.scheduledReboot) { _ in seed() }
        }
    }

    private func seed() {
        let s = viewModel.scheduledReboot
        enabled = s.enabled
        let parts = s.timeHhMm.split(separator: ":").compactMap { Int($0) }
        if parts.count == 2 {
            selectedTime = Calendar.current.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: Date()) ?? selectedTime
        }
        let parsedDays = Set(s.weekDays.split(separator: ",").map(String.init).filter { $0 != "0" && !$0.isEmpty })
        if !parsedDays.isEmpty { selectedDays = parsedDays }
    }
}

public struct SystemLogsSheet: View {
    @ObservedObject public var viewModel: SystemToolsViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var sizeKb = "200"
    @State private var logGrade: LogGrade = .defaultLevel

    public init(viewModel: SystemToolsViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            List {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }

                Section("Log Configuration") {
                    TextField("Max Log Size KB (1–2000)", text: $sizeKb).keyboardType(.numberPad)
                    Picker("Severity Grade", selection: $logGrade) {
                        ForEach(LogGrade.allCases) { g in
                            Text(g.label).tag(g)
                        }
                    }
                    HStack {
                        Button("Save Log Config") {
                            if let kb = Int(sizeKb) {
                                viewModel.saveLogSettings(sizeKb: kb, grade: logGrade)
                            }
                        }
                        Spacer()
                        Button("Clear Logs", role: .destructive) {
                            viewModel.clearSystemLogs()
                        }
                    }
                    .disabled(viewModel.sheetBusy)
                }

                Section("Recent Router Entries (\(viewModel.logs.count))") {
                    if viewModel.logsLoading {
                        ProgressView()
                    } else if viewModel.logs.isEmpty {
                        Text("No log lines returned by router.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(viewModel.logs.prefix(200)) { entry in
                            VStack(alignment: .leading, spacing: 2) {
                                if !entry.time.isEmpty || !entry.type.isEmpty {
                                    Text("\(entry.time) \(entry.type)")
                                        .font(.caption2.monospacedDigit().weight(.bold))
                                        .foregroundStyle(.secondary)
                                }
                                Text(entry.message)
                                    .font(.caption.monospaced())
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }
            }
            .navigationTitle("System Logs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
            .onAppear {
                sizeKb = "\(viewModel.logSettings.logSizeKb)"
                logGrade = viewModel.logSettings.logGrade
            }
            .onChange(of: viewModel.logSettings) { cfg in
                sizeKb = "\(cfg.logSizeKb)"
                logGrade = cfg.logGrade
            }
        }
    }
}

public struct RouterTimeSheet: View {
    @ObservedObject public var viewModel: SystemToolsViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var timezone = "UTC+1"
    @State private var ntpEnabled = true
    @State private var server1 = ""
    @State private var server2 = ""

    public init(viewModel: SystemToolsViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Clock & NTP") {
                    HStack {
                        Text("Current Router Time")
                        Spacer()
                        Text(viewModel.timeConfig.currentSystemTime)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    TextField("Timezone (e.g. UTC+1)", text: $timezone)
                    Toggle("Enable NTP Auto-Sync", isOn: $ntpEnabled).tint(AirtelTheme.accent)
                    if ntpEnabled {
                        TextField("NTP Server 1", text: $server1).textInputAutocapitalization(.never)
                        TextField("NTP Server 2", text: $server2).textInputAutocapitalization(.never)
                    }
                }
                Section {
                    Button("Sync Router Clock to This Device") {
                        viewModel.syncClientTime()
                    }
                    .disabled(viewModel.sheetBusy)
                }
            }
            .navigationTitle("Time & NTP")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        viewModel.saveTimeConfig(timezone: timezone, ntpEnabled: ntpEnabled, server1: server1, server2: server2)
                    }
                    .disabled(viewModel.sheetBusy)
                }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.timeConfig) { _ in seed() }
        }
    }

    private func seed() {
        let c = viewModel.timeConfig
        timezone = c.timezone
        ntpEnabled = c.ntpEnabled
        server1 = c.server1
        server2 = c.server2
    }
}

public struct WanLinkDetectSheet: View {
    @ObservedObject public var viewModel: SystemToolsViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var enabled = false
    @State private var checkTimeSec = 10
    @State private var mode = "0"
    @State private var ip1 = "8.8.8.8"
    @State private var ip2 = "1.1.1.1"
    @State private var action = "0"
    @State private var rebootWaitMinutes = 3

    public init(viewModel: SystemToolsViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Watchdog Settings") {
                    Toggle("Enable WAN Link Detection", isOn: $enabled).tint(AirtelTheme.accent)
                    Stepper("Check Interval: \(checkTimeSec)s", value: $checkTimeSec, in: 1...1800)
                    Picker("Probe Mode", selection: $mode) {
                        Text("Ping / ICMP").tag("0")
                        Text("NTP Probe").tag("2")
                        Text("DNS Query").tag("3")
                    }
                    TextField("Primary Probe Host/IP", text: $ip1).textInputAutocapitalization(.never)
                    TextField("Secondary Probe Host/IP", text: $ip2).textInputAutocapitalization(.never)
                    Picker("Failure Action", selection: $action) {
                        Text("Reconnect WAN").tag("0")
                        Text("Reboot Router").tag("1")
                        if viewModel.featureFlags.wanDetectRescanAvailable {
                            Text("Rescan Network").tag("3")
                        }
                    }
                    Stepper("Wait Before Action: \(rebootWaitMinutes)m", value: $rebootWaitMinutes, in: 1...60)
                }
            }
            .navigationTitle("WAN Link Detection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        var cfg = viewModel.wanLinkDetect
                        cfg.enabled = enabled
                        cfg.checkTimeSec = checkTimeSec
                        cfg.mode = mode
                        cfg.ip1 = ip1
                        cfg.ip2 = ip2
                        cfg.action = action
                        cfg.rebootWaitMinutes = rebootWaitMinutes
                        viewModel.saveWanLinkDetect(cfg)
                    }
                    .disabled(viewModel.sheetBusy)
                }
            }
            .onAppear { seed() }
            .onChange(of: viewModel.wanLinkDetect) { _ in seed() }
        }
    }

    private func seed() {
        let w = viewModel.wanLinkDetect
        enabled = w.enabled
        checkTimeSec = w.checkTimeSec
        mode = w.mode
        ip1 = w.ip1
        ip2 = w.ip2
        action = w.action
        rebootWaitMinutes = w.rebootWaitMinutes
    }
}

public struct ChangeAdminPasswordSheet: View {
    @ObservedObject public var viewModel: SystemToolsViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var oldPwd = ""
    @State private var newPwd = ""
    @State private var confirmPwd = ""
    @State private var localError: String?

    public init(viewModel: SystemToolsViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Admin Password") {
                    SecureField("Current Password", text: $oldPwd)
                    SecureField("New Password (8–63, upper/lower/digit/symbol)", text: $newPwd)
                    SecureField("Confirm New Password", text: $confirmPwd)
                }
            }
            .navigationTitle("Change Admin Password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Update") {
                        localError = SensitiveFormatters.validateRouterPassword(
                            oldPassword: oldPwd,
                            newPassword: newPwd,
                            confirmPassword: confirmPwd
                        )
                        if localError == nil {
                            viewModel.changeAdminPassword(oldPassword: oldPwd, newPassword: newPwd, confirmPassword: confirmPwd)
                        }
                    }
                    .disabled(viewModel.sheetBusy)
                }
            }
        }
    }
}
