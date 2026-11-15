import SwiftUI

public struct SecurityView: View {
    @ObservedObject public var container: AppContainer
    @StateObject private var viewModel: SecurityViewModel
    @State private var pendingConfirm: ConfirmPromptRequest?

    public init(container: AppContainer) {
        self.container = container
        _viewModel = StateObject(wrappedValue: SecurityViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let err = viewModel.errorMessage {
                    ErrorStateView(title: "Security", message: err) {
                        viewModel.loadAll()
                    }
                } else {
                    firewallCard
                    protectionsCard
                    algCard
                    rulesNavigationCard
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Security & Firewall")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    viewModel.loadAll()
                } label: {
                    if viewModel.isLoading || viewModel.isSaving {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                .disabled(viewModel.isLoading || viewModel.isSaving)
            }
        }
        .onAppear {
            viewModel.loadAll()
        }
        .sheet(item: $viewModel.activeSheet) { sheet in
            switch sheet {
            case .dmz: DmzConfigSheet(viewModel: viewModel)
            case .portForward: PortForwardRulesSheet(viewModel: viewModel)
            case .ipPortFilter: IpPortFilterRulesSheet(viewModel: viewModel)
            case .portFilter: PortFilterRulesSheet(viewModel: viewModel)
            case .urlFilter: UrlFilterRulesSheet(viewModel: viewModel)
            case .macFilter: SecurityMacFilterSheet(viewModel: viewModel)
            case .trustedIp: TrustedIpConfigSheet(viewModel: viewModel)
            }
        }
        .sheet(item: $pendingConfirm) { req in
            ConfirmSheet(request: req).presentationDetents([.medium])
        }
    }

    private var firewallCard: some View {
        SectionCard(title: "SPI Firewall", subtitle: "Level: \(viewModel.firewallLevel.label)") {
            VStack(alignment: .leading, spacing: 12) {
                SettingSwitchRow(
                    title: "Enable Firewall",
                    icon: "flame.fill",
                    isOn: Binding(
                        get: { viewModel.firewallEnabled },
                        set: { enable in
                            if enable {
                                viewModel.saveFirewall(enabled: true, level: viewModel.firewallLevel)
                            } else {
                                pendingConfirm = ConfirmPromptRequest(
                                    title: "Disable Router Firewall?",
                                    impactMessage: "Disabling the SPI firewall exposes WAN ports unless external rules block traffic.",
                                    confirmLabel: "Disable Firewall",
                                    tier: .connectivity,
                                    onConfirm: {
                                        viewModel.saveFirewall(enabled: false, level: viewModel.firewallLevel)
                                    }
                                )
                            }
                        }
                    ),
                    isBusy: viewModel.isSaving,
                    onChanged: { _ in }
                )

                Picker("Firewall Level", selection: Binding(
                    get: { viewModel.firewallLevel },
                    set: { level in
                        viewModel.saveFirewall(enabled: true, level: level)
                    }
                )) {
                    ForEach(FirewallLevel.allCases) { level in
                        Text(level.label).tag(level)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(!viewModel.firewallEnabled || viewModel.isSaving)
            }
        }
    }

    private var protectionsCard: some View {
        let flags = viewModel.featureFlags
        return SectionCard(title: "Network Protections") {
            VStack(spacing: 8) {
                if !flags.isLoaded || flags.ddosAvailable {
                    SettingSwitchRow(
                        title: "DDoS Flood Protection",
                        icon: "shield.checkered",
                        isOn: Binding(
                            get: { viewModel.ddos.enabled },
                            set: { viewModel.saveDdos($0) }
                        ),
                        isBusy: viewModel.isSaving,
                        onChanged: { _ in }
                    )
                }
                if (!flags.isLoaded || flags.upnpAvailable) && (!flags.isLoaded || flags.ddosAvailable) {
                    Divider()
                }
                if !flags.isLoaded || flags.upnpAvailable {
                    SettingSwitchRow(
                        title: "UPnP Auto Port Mapping",
                        icon: "arrow.left.arrow.right",
                        isOn: Binding(
                            get: { viewModel.upnp.enabled },
                            set: { viewModel.saveUpnp($0) }
                        ),
                        isBusy: viewModel.isSaving,
                        onChanged: { _ in }
                    )
                }
            }
        }
    }

    private var algCard: some View {
        let alg = viewModel.alg
        return SectionCard(title: "Application Layer Gateway (ALG)") {
            VStack(spacing: 6) {
                algRow("L2TP Passthrough", value: alg.l2tp) { next in
                    var copy = alg; copy.l2tp = next; viewModel.saveAlg(copy)
                }
                algRow("IPSec Passthrough", value: alg.ipsec) { next in
                    var copy = alg; copy.ipsec = next; viewModel.saveAlg(copy)
                }
                algRow("PPTP Passthrough", value: alg.pptp) { next in
                    var copy = alg; copy.pptp = next; viewModel.saveAlg(copy)
                }
                algRow("SIP VoIP ALG", value: alg.sip) { next in
                    var copy = alg; copy.sip = next; viewModel.saveAlg(copy)
                }
                algRow("FTP ALG", value: alg.ftp) { next in
                    var copy = alg; copy.ftp = next; viewModel.saveAlg(copy)
                }
                algRow("RTSP Streaming ALG", value: alg.rtsp) { next in
                    var copy = alg; copy.rtsp = next; viewModel.saveAlg(copy)
                }
                algRow("H.323 ALG", value: alg.h323) { next in
                    var copy = alg; copy.h323 = next; viewModel.saveAlg(copy)
                }
            }
        }
    }

    private func algRow(_ title: String, value: Bool, onChange: @escaping (Bool) -> Void) -> some View {
        Toggle(title, isOn: Binding(get: { value }, set: onChange))
            .font(.subheadline)
            .tint(AirtelTheme.accent)
            .disabled(viewModel.isSaving)
    }

    private var rulesNavigationCard: some View {
        let flags = viewModel.featureFlags
        return SectionCard(title: "Filtering & Forwarding Rules") {
            VStack(spacing: 8) {
                if !flags.isLoaded || flags.dmzAvailable {
                    SettingValueRow(
                        title: "DMZ Host",
                        value: viewModel.dmz.enabled ? viewModel.dmz.ip : "Off",
                        icon: "network.badge.shield.half.filled"
                    ) {
                        viewModel.activeSheet = .dmz
                    }
                    Divider()
                }
                if !flags.isLoaded || flags.portForwardAvailable {
                    SettingValueRow(
                        title: "Port Forwarding",
                        value: "\(viewModel.portForwards.count)",
                        icon: "arrow.triangle.branch"
                    ) {
                        viewModel.activeSheet = .portForward
                    }
                    Divider()
                }
                if !flags.isLoaded || flags.firewallRuleAvailable {
                    SettingValueRow(
                        title: "IP / Port Filter",
                        value: "\(viewModel.ipPortFilters.count)",
                        icon: "line.3.horizontal.decrease.circle"
                    ) {
                        viewModel.activeSheet = .ipPortFilter
                    }
                    Divider()
                    SettingValueRow(
                        title: "Port Filter",
                        value: "\(viewModel.portFilters.count)",
                        icon: "number.circle"
                    ) {
                        viewModel.activeSheet = .portFilter
                    }
                    Divider()
                }
                if !flags.isLoaded || flags.urlFilterAvailable {
                    SettingValueRow(
                        title: "URL / Domain Filter",
                        value: "\(viewModel.urlFilters.count)",
                        icon: "link"
                    ) {
                        viewModel.activeSheet = .urlFilter
                    }
                    Divider()
                }
                SettingValueRow(
                    title: "MAC Filter Policy",
                    subtitle: viewModel.urlDefaultAcceptAll ? "Blacklist (Allow all except listed)" : "Whitelist (Block all except listed)",
                    value: "\(viewModel.macFilters.count)",
                    icon: "cpu"
                ) {
                    viewModel.activeSheet = .macFilter
                }
                if !flags.isLoaded || flags.trustedIpAvailable {
                    Divider()
                    SettingValueRow(
                        title: "Trusted Management IPs",
                        value: viewModel.trustedIp.enabled ? "\(viewModel.trustedIp.ips.count)" : "Off",
                        icon: "person.badge.key.fill"
                    ) {
                        viewModel.activeSheet = .trustedIp
                    }
                }
            }
        }
    }
}
