import SwiftUI

public struct DmzConfigSheet: View {
    @ObservedObject public var viewModel: SecurityViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var enabled = false
    @State private var ip = ""

    public init(viewModel: SecurityViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("DMZ Host") {
                    Toggle("Enable DMZ", isOn: $enabled).tint(AirtelTheme.accent)
                    if enabled {
                        TextField("DMZ Host IPv4 (e.g. 192.168.1.50)", text: $ip)
                            .keyboardType(.numbersAndPunctuation)
                    }
                }
            }
            .navigationTitle("DMZ Host")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.saveDmz(enabled: enabled, ip: ip) }
                        .disabled(viewModel.isSaving)
                }
            }
            .onAppear {
                enabled = viewModel.dmz.enabled
                ip = viewModel.dmz.ip
            }
        }
    }
}

public struct PortForwardRulesSheet: View {
    @ObservedObject public var viewModel: SecurityViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var rules: [PortForwardRule] = []
    @State private var extPort = ""
    @State private var intPort = ""
    @State private var mapIp = ""
    @State private var proto = "TCP"
    @State private var remark = ""

    public init(viewModel: SecurityViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Port Forwarding Rules (\(rules.count)/32)") {
                    ForEach(Array(rules.enumerated()), id: \.offset) { idx, r in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(r.protocolName) :\(r.port) → \(r.mappingIp):\(r.mappingPort)")
                                    .font(.subheadline.weight(.semibold).monospacedDigit())
                                if !r.remark.isEmpty {
                                    Text(r.remark).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button(role: .destructive) { rules.remove(at: idx) } label: { Image(systemName: "trash") }
                                .buttonStyle(.plain)
                        }
                    }
                }
                Section("Add Port Forward") {
                    TextField("External Port (e.g. 8080 or 8000:8010)", text: $extPort)
                    TextField("Internal Port (e.g. 80)", text: $intPort)
                    TextField("Target LAN IPv4", text: $mapIp).keyboardType(.numbersAndPunctuation)
                    Picker("Protocol", selection: $proto) {
                        Text("TCP").tag("TCP")
                        Text("UDP").tag("UDP")
                        Text("TCP+UDP").tag("TCP&UDP")
                    }
                    TextField("Remark (optional)", text: $remark)
                    Button("Add Rule") {
                        rules.append(PortForwardRule(
                            port: extPort,
                            mappingPort: intPort.isEmpty ? extPort : intPort,
                            mappingIp: mapIp,
                            protocolName: proto,
                            ippro: "IPV4",
                            remark: remark,
                            enableRule: true
                        ))
                        extPort = ""; intPort = ""; mapIp = ""; remark = ""
                    }
                    .disabled(rules.count >= 32)
                }
            }
            .navigationTitle("Port Forwarding")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.savePortForwards(rules) }
                        .disabled(viewModel.isSaving)
                }
            }
            .onAppear { rules = viewModel.portForwards }
            .onChange(of: viewModel.portForwards) { rules = $0 }
        }
    }
}

public struct IpPortFilterRulesSheet: View {
    @ObservedObject public var viewModel: SecurityViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var rules: [IpPortFilterRule] = []
    @State private var srcIp = ""
    @State private var destIp = ""
    @State private var srcPort = ""
    @State private var destPort = ""
    @State private var proto = "All"

    public init(viewModel: SecurityViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("IP / Port Rules (\(rules.count)/32)") {
                    ForEach(Array(rules.enumerated()), id: \.offset) { idx, r in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(r.protocolName) \(r.ip.isEmpty ? "*" : r.ip):\(r.srcPort.isEmpty ? "*" : r.srcPort) → \(r.destIp.isEmpty ? "*" : r.destIp):\(r.destPort.isEmpty ? "*" : r.destPort)")
                                    .font(.caption.weight(.semibold).monospacedDigit())
                            }
                            Spacer()
                            Button(role: .destructive) { rules.remove(at: idx) } label: { Image(systemName: "trash") }
                                .buttonStyle(.plain)
                        }
                    }
                }
                Section("Add IP / Port Filter") {
                    TextField("Source IP (optional)", text: $srcIp)
                    TextField("Destination IP (optional)", text: $destIp)
                    TextField("Source Port / range (optional)", text: $srcPort)
                    TextField("Dest Port / range (optional)", text: $destPort)
                    Picker("Protocol", selection: $proto) {
                        Text("All").tag("All")
                        Text("TCP").tag("tcp")
                        Text("UDP").tag("udp")
                        Text("ICMP").tag("icmp")
                    }
                    Button("Add Rule") {
                        rules.append(IpPortFilterRule(
                            ip: srcIp,
                            destIp: destIp,
                            srcPort: srcPort,
                            destPort: destPort,
                            protocolName: proto,
                            ippro: "IPV4",
                            remark: "",
                            enableRule: true,
                            enableLink: !viewModel.urlDefaultAcceptAll
                        ))
                        srcIp = ""; destIp = ""; srcPort = ""; destPort = ""
                    }
                    .disabled(rules.count >= 32)
                }
            }
            .navigationTitle("IP / Port Filter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.saveIpPortFilters(rules) }
                        .disabled(viewModel.isSaving)
                }
            }
            .onAppear { rules = viewModel.ipPortFilters }
            .onChange(of: viewModel.ipPortFilters) { rules = $0 }
        }
    }
}

public struct PortFilterRulesSheet: View {
    @ObservedObject public var viewModel: SecurityViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var rules: [PortFilterRule] = []
    @State private var port = ""
    @State private var proto = "tcp"
    @State private var remark = ""

    public init(viewModel: SecurityViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Port Rules (\(rules.count)/32)") {
                    ForEach(Array(rules.enumerated()), id: \.offset) { idx, r in
                        HStack {
                            Text("\(r.protocolName.uppercased()) :\(r.port) \(r.remark.isEmpty ? "" : "(\(r.remark))")")
                                .font(.subheadline.monospacedDigit())
                            Spacer()
                            Button(role: .destructive) { rules.remove(at: idx) } label: { Image(systemName: "trash") }
                                .buttonStyle(.plain)
                        }
                    }
                }
                Section("Add Port Filter") {
                    TextField("Port or range (e.g. 23 or 5000:5010)", text: $port)
                    Picker("Protocol", selection: $proto) {
                        Text("TCP").tag("tcp")
                        Text("UDP").tag("udp")
                        Text("TCP+UDP").tag("tcp+udp")
                    }
                    TextField("Remark (optional)", text: $remark)
                    Button("Add Port Rule") {
                        rules.append(PortFilterRule(
                            port: port,
                            protocolName: proto,
                            ippro: "IPV4",
                            remark: remark,
                            enableRule: true,
                            enableLink: !viewModel.urlDefaultAcceptAll
                        ))
                        port = ""; remark = ""
                    }
                    .disabled(rules.count >= 32)
                }
            }
            .navigationTitle("Port Filter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.savePortFilters(rules) }
                        .disabled(viewModel.isSaving)
                }
            }
            .onAppear { rules = viewModel.portFilters }
            .onChange(of: viewModel.portFilters) { rules = $0 }
        }
    }
}

public struct UrlFilterRulesSheet: View {
    @ObservedObject public var viewModel: SecurityViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var rules: [UrlFilterRule] = []
    @State private var urlInput = ""
    @State private var remark = ""

    public init(viewModel: SecurityViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Blocked / Allowed Domains (\(rules.count)/32)") {
                    ForEach(Array(rules.enumerated()), id: \.offset) { idx, r in
                        HStack {
                            Text(r.url).font(.subheadline)
                            Spacer()
                            Button(role: .destructive) { rules.remove(at: idx) } label: { Image(systemName: "trash") }
                                .buttonStyle(.plain)
                        }
                    }
                }
                Section("Add URL Rule") {
                    TextField("Domain or keyword", text: $urlInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Remark (optional)", text: $remark)
                    Button("Add URL") {
                        rules.append(UrlFilterRule(
                            url: urlInput,
                            ippro: "IPV4",
                            remark: remark,
                            enableRule: true,
                            enableLink: !viewModel.urlDefaultAcceptAll
                        ))
                        urlInput = ""; remark = ""
                    }
                    .disabled(rules.count >= 32)
                }
            }
            .navigationTitle("URL Filter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.saveUrlFilters(rules) }
                        .disabled(viewModel.isSaving)
                }
            }
            .onAppear { rules = viewModel.urlFilters }
            .onChange(of: viewModel.urlFilters) { rules = $0 }
        }
    }
}

public struct SecurityMacFilterSheet: View {
    @ObservedObject public var viewModel: SecurityViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var rules: [MacFilterRule] = []
    @State private var macInput = ""
    @State private var remark = ""

    public init(viewModel: SecurityViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Default Filter Policy") {
                    Picker("Policy", selection: Binding(
                        get: { viewModel.urlDefaultAcceptAll },
                        set: { viewModel.saveDefaultPolicy(acceptAll: $0) }
                    )) {
                        Text("Blacklist (Allow All Unless Listed)").tag(true)
                        Text("Whitelist (Block All Unless Listed)").tag(false)
                    }
                }
                Section("MAC Rules (\(rules.count)/32)") {
                    ForEach(Array(rules.enumerated()), id: \.offset) { idx, r in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.mac).font(.subheadline.monospacedDigit())
                                if !r.remark.isEmpty {
                                    Text(r.remark).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button(role: .destructive) { rules.remove(at: idx) } label: { Image(systemName: "trash") }
                                .buttonStyle(.plain)
                        }
                    }
                }
                Section("Add MAC") {
                    TextField("MAC (AA:BB:CC:DD:EE:FF)", text: $macInput)
                        .textInputAutocapitalization(.characters)
                    TextField("Remark (optional)", text: $remark)
                    Button("Add MAC") {
                        rules.append(MacFilterRule(
                            mac: macInput,
                            remark: remark,
                            ippro: "IPV4",
                            enableRule: true,
                            enableLink: !viewModel.urlDefaultAcceptAll
                        ))
                        macInput = ""; remark = ""
                    }
                    .disabled(rules.count >= 32)
                }
            }
            .navigationTitle("MAC Filter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.saveMacFilters(rules) }
                        .disabled(viewModel.isSaving)
                }
            }
            .onAppear { rules = viewModel.macFilters }
            .onChange(of: viewModel.macFilters) { rules = $0 }
        }
    }
}

public struct TrustedIpConfigSheet: View {
    @ObservedObject public var viewModel: SecurityViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var enabled = false
    @State private var ips: [String] = []
    @State private var newIp = ""

    public init(viewModel: SecurityViewModel) { self.viewModel = viewModel }

    public var body: some View {
        NavigationStack {
            Form {
                if let err = viewModel.sheetError {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Admin Access Whitelist") {
                    Toggle("Restrict Admin Login to Trusted IPs", isOn: $enabled).tint(AirtelTheme.accent)
                }
                Section("Allowed IPs (\(ips.count)/15)") {
                    ForEach(Array(ips.enumerated()), id: \.offset) { idx, ip in
                        HStack {
                            Text(ip).font(.subheadline.monospacedDigit())
                            Spacer()
                            Button(role: .destructive) { ips.remove(at: idx) } label: { Image(systemName: "trash") }
                                .buttonStyle(.plain)
                        }
                    }
                    HStack {
                        TextField("Add IPv4", text: $newIp).keyboardType(.numbersAndPunctuation)
                        Button("Add") {
                            let clean = newIp.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !clean.isEmpty { ips.append(clean); newIp = "" }
                        }
                        .disabled(ips.count >= 15)
                    }
                }
            }
            .navigationTitle("Trusted IPs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { viewModel.saveTrustedIps(enabled: enabled, ips: ips) }
                        .disabled(viewModel.isSaving)
                }
            }
            .onAppear {
                enabled = viewModel.trustedIp.enabled
                ips = viewModel.trustedIp.ips
            }
            .onChange(of: viewModel.trustedIp) { cfg in
                enabled = cfg.enabled
                ips = cfg.ips
            }
        }
    }
}
