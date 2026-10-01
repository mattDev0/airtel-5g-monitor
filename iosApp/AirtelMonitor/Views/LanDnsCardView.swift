import SwiftUI

/// LAN DNS card, same layout and wording as Android's LanDnsCard.
public struct LanDnsCardView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var primaryInput = ""
    @State private var secondaryInput = ""
    @State private var showConfirmSave = false

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    private var busy: Bool { viewModel.isDnsLoading }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            AppCardHeader(title: "LAN DNS", subtitle: "Router DHCP DNS assignment") {
                if !viewModel.isDnsLoaded {
                    AppBadge(text: "Loading", color: .secondary)
                } else if viewModel.dnsConfig.dhcpEnabled {
                    AppBadge(text: "DHCP Active", color: StatusColors.success)
                } else {
                    AppBadge(text: "DHCP Off", color: StatusColors.warning)
                }
            }

            HStack(spacing: 10) {
                DnsField(label: "Primary DNS", placeholder: "1.1.1.1", text: $primaryInput)
                DnsField(label: "Secondary DNS", placeholder: "Optional", text: $secondaryInput)
            }

            if let msg = viewModel.dnsStatusMessage {
                Text(msg)
                    .font(.caption.monospaced())
                    .foregroundStyle(msg.hasPrefix("Error") ? StatusColors.error : Color.accentColor)
            }

            HStack(spacing: 8) {
                Spacer()
                Button {
                    Task { await viewModel.loadDns() }
                } label: {
                    Label("Reload", systemImage: "arrow.clockwise").font(.caption.weight(.semibold))
                }
                .liquidGlassSmallButton()
                .disabled(busy)

                Button {
                    showConfirmSave = true
                } label: {
                    Group {
                        if busy && viewModel.isDnsLoaded { Text("Saving...") } else { Text("Save DNS") }
                    }
                    .font(.subheadline.weight(.semibold))
                }
                .liquidGlassActionButton()
                .fixedSize()
                .disabled(!viewModel.isDnsLoaded || busy || primaryInput.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .liquidGlassCard()
        .onAppear { syncInputs() }
        .onChange(of: viewModel.dnsConfig) { _ in syncInputs() }
        .alert("Confirm DNS Change", isPresented: $showConfirmSave) {
            Button("Cancel", role: .cancel) {}
            Button("Confirm") {
                Task { await viewModel.saveDns(primary: primaryInput.trimmingCharacters(in: .whitespaces),
                                               secondary: secondaryInput.trimmingCharacters(in: .whitespaces)) }
            }
        } message: {
            let secondary = secondaryInput.trimmingCharacters(in: .whitespaces)
            Text("Set router DNS to:\n• Primary: \(primaryInput.trimmingCharacters(in: .whitespaces))\n• Secondary: \(secondary.isEmpty ? "router IP (192.168.1.1)" : secondary)\n\nThe router will restart its DHCP service for ~30 seconds.")
        }
    }

    private func syncInputs() {
        primaryInput = viewModel.dnsConfig.primary
        secondaryInput = viewModel.dnsConfig.secondary
    }
}

private struct DnsField: View {
    let label: String
    let placeholder: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            TextField(placeholder, text: $text)
                .font(.subheadline.monospaced())
                .keyboardType(.decimalPad)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .liquidGlassPill()
        }
        .frame(maxWidth: .infinity)
    }
}
