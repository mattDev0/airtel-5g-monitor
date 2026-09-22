import SwiftUI

public struct LanDnsCardView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var primaryDns: String = ""
    @State private var secondaryDns: String = ""
    @State private var isEditing: Bool = false
    @State private var showConfirmSave: Bool = false

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "network.badge.shield.half.filled")
                    .foregroundColor(.accentColor)
                    .font(.subheadline)
                Text("LAN DNS Configuration")
                    .font(.subheadline)
                    .fontWeight(.bold)
                Spacer()

                if viewModel.isDnsLoading {
                    ProgressView()
                        .scaleEffect(0.8)
                } else if viewModel.isDnsLoaded {
                    Button(isEditing ? "Done" : "Edit") {
                        if !isEditing {
                            primaryDns = viewModel.dnsConfig.primary
                            secondaryDns = viewModel.dnsConfig.secondary
                        }
                        isEditing.toggle()
                    }
                    .font(.caption)
                    .fontWeight(.semibold)
                } else {
                    Button("Retry") {
                        Task { await viewModel.loadDns() }
                    }
                    .font(.caption)
                    .fontWeight(.semibold)
                }
            }

            if isEditing {
                VStack(spacing: 8) {
                    TextField("Primary DNS (e.g. 1.1.1.1)", text: $primaryDns)
                        .textFieldStyle(RoundedBorderTextFieldStyle())
                        .font(.system(.subheadline, design: .monospaced))
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                        .keyboardType(.numbersAndPunctuation)

                    TextField("Secondary DNS (Optional)", text: $secondaryDns)
                        .textFieldStyle(RoundedBorderTextFieldStyle())
                        .font(.system(.subheadline, design: .monospaced))
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                        .keyboardType(.numbersAndPunctuation)

                    // Presets
                    HStack(spacing: 8) {
                        PresetPill(name: "Cloudflare") {
                            primaryDns = "1.1.1.1"
                            secondaryDns = "1.0.0.1"
                        }
                        PresetPill(name: "Google") {
                            primaryDns = "8.8.8.8"
                            secondaryDns = "8.8.4.4"
                        }
                        PresetPill(name: "AdGuard") {
                            primaryDns = "94.140.14.14"
                            secondaryDns = "94.140.15.15"
                        }
                    }

                    Button {
                        showConfirmSave = true
                    } label: {
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                            Text("Save DNS to Router")
                        }
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                    }
                    .liquidGlassActionButton()
                    .disabled(viewModel.isDnsLoading || primaryDns.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } else {
                if viewModel.isDnsLoaded {
                    HStack(spacing: 10) {
                        DnsReadout(label: "Primary DNS", value: viewModel.dnsConfig.primary.isEmpty ? "Router Default (DHCP)" : viewModel.dnsConfig.primary)
                        DnsReadout(label: "Secondary DNS", value: viewModel.dnsConfig.secondary.isEmpty ? "None" : viewModel.dnsConfig.secondary)
                    }
                } else {
                    HStack {
                        Image(systemName: "exclamationmark.triangle")
                            .foregroundColor(.orange)
                        Text(viewModel.isDnsLoading ? "Reading router DNS..." : "Unable to read DNS from router")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    .padding(10)
                    .liquidGlassPill()
                }
            }

            if let msg = viewModel.dnsStatusMessage {
                HStack(spacing: 6) {
                    Image(systemName: msg.contains("Error") || msg.contains("kept") ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                        .foregroundColor(msg.contains("Error") || msg.contains("kept") ? .orange : .green)
                    Text(msg)
                        .font(.caption2)
                        .foregroundColor(.primary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .liquidGlassPill()
                .padding(.top, 2)
            }
        }
        .liquidGlassCard()
        .onAppear {
            if !viewModel.isDnsLoaded {
                Task { await viewModel.loadDns() }
            }
        }
        .alert("Confirm DNS Change", isPresented: $showConfirmSave) {
            Button("Cancel", role: .cancel) { }
            Button("Save", role: .none) {
                Task {
                    await viewModel.saveDns(primary: primaryDns, secondary: secondaryDns)
                    isEditing = false
                }
            }
        } message: {
            Text("Saving DNS triggers a brief DHCP restart on the router. Existing connected devices will refresh leases.")
        }
    }
}

private struct PresetPill: View {
    let name: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(name)
                .font(.system(size: 11, weight: .semibold))
        }
        .liquidGlassSmallButton()
    }
}

private struct DnsReadout: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(.secondary)
            Text(value)
                .font(.system(size: 13, weight: .medium, design: .monospaced))
                .foregroundColor(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .liquidGlassPill()
    }
}

