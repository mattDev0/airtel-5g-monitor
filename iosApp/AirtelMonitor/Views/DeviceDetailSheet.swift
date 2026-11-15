import SwiftUI

public struct DeviceDetailSheet: View {
    public let device: DeviceItem
    @ObservedObject public var viewModel: DevicesViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var showBlockConfirm = false

    public init(device: DeviceItem, viewModel: DevicesViewModel) {
        self.device = device
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    SectionCard {
                        HStack(spacing: 12) {
                            Image(systemName: device.type.sfSymbol)
                                .font(.title)
                                .foregroundStyle(AirtelTheme.accent)
                                .frame(width: 48, height: 48)
                                .background(AirtelTheme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                            VStack(alignment: .leading, spacing: 4) {
                                Text(device.displayName)
                                    .font(.title3.weight(.bold))
                                HStack(spacing: 6) {
                                    BadgePill(device.isOnline ? "Online" : "Offline", color: device.isOnline ? AirtelTheme.success : .secondary)
                                    if !device.band.isEmpty {
                                        BadgePill(device.band, color: AirtelTheme.info)
                                    }
                                    if device.isPrivateMac {
                                        BadgePill("Private MAC", color: AirtelTheme.warning)
                                    }
                                }
                            }
                            Spacer()
                        }
                    }

                    SectionCard(title: "Connection Details") {
                        VStack(spacing: 8) {
                            detailRow("Hostname", device.hostname.isEmpty ? "-" : device.hostname)
                            if device.hasCustomAlias {
                                detailRow("Custom Label", device.alias)
                            }
                            detailRow("IPv4 Address", device.ip.isEmpty ? "-" : device.ip)
                            detailRow("MAC Address", device.mac.isEmpty ? "-" : device.mac)
                            detailRow("Vendor", device.vendor.isEmpty ? "Unknown" : device.vendor)
                            detailRow("Interface / SSID", device.ssid.isEmpty ? device.interfaceName : "\(device.interfaceName) (\(device.ssid))")
                            detailRow("TX / RX Rate", "\(device.txRateMbps) / \(device.rxRateMbps) Mbps")
                            if !device.rssi.isEmpty {
                                detailRow("Signal (RSSI)", "\(device.rssi) dBm (\(device.signalPercent)%)")
                            }
                            if !device.expires.isEmpty {
                                detailRow("DHCP Lease Expires", device.expires)
                            }
                            if !device.ipv6.isEmpty {
                                detailRow("IPv6", device.ipv6)
                            }
                        }
                    }

                    if !device.isRouter {
                        SectionCard(title: "Device Actions") {
                            VStack(spacing: 10) {
                                Button {
                                    let target = device
                                    dismiss()
                                    viewModel.renameTarget = target
                                } label: {
                                    Label("Rename Label", systemImage: "pencil")
                                        .frame(maxWidth: .infinity)
                                }
                                .liquidGlassSmallButton()

                                if !device.mac.isEmpty && !device.ip.isEmpty {
                                    Button {
                                        let target = device
                                        dismiss()
                                        viewModel.openPolicySheet(.staticLeases, prefill: target)
                                    } label: {
                                        Label("Reserve Static IP", systemImage: "pin")
                                            .frame(maxWidth: .infinity)
                                    }
                                    .liquidGlassSmallButton()
                                }

                                if !device.mac.isEmpty {
                                    let blocked = viewModel.isMacBlocked(device.mac)
                                    Button {
                                        showBlockConfirm = true
                                    } label: {
                                        Label(
                                            blocked ? "Allow Device (Unblock MAC)" : "Block Device (MAC Filter)",
                                            systemImage: blocked ? "checkmark.shield" : "hand.raised.fill"
                                        )
                                        .frame(maxWidth: .infinity)
                                    }
                                    .liquidGlassActionButton(tint: blocked ? AirtelTheme.success : AirtelTheme.error)
                                }

                                if (!viewModel.featureFlags.isLoaded || viewModel.featureFlags.parentalControlAvailable) && !device.mac.isEmpty {
                                    Button {
                                        let target = device
                                        dismiss()
                                        viewModel.openPolicySheet(.parentalControl, prefill: target)
                                    } label: {
                                        Label("Parental Control Rules", systemImage: "figure.2.and.child.holdinghands")
                                            .frame(maxWidth: .infinity)
                                    }
                                    .liquidGlassSmallButton()
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Device Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $showBlockConfirm) {
                let blocked = viewModel.isMacBlocked(device.mac)
                ConfirmSheet(
                    request: ConfirmPromptRequest(
                        title: blocked ? "Allow \(device.displayName)?" : "Block \(device.displayName)?",
                        impactMessage: blocked
                            ? "Removes or permits MAC \(device.mac) in the router MAC filter."
                            : "Blocks internet and router access for MAC \(device.mac) immediately.",
                        confirmLabel: blocked ? "Allow Device" : "Block Device",
                        tier: .connectivity,
                        onConfirm: {
                            viewModel.toggleBlockMac(for: device)
                            dismiss()
                        }
                    )
                )
                .presentationDetents([.medium])
            }
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .font(.caption.weight(.semibold).monospacedDigit())
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
    }
}

public struct RenameDeviceSheet: View {
    public let device: DeviceItem
    @ObservedObject public var viewModel: DevicesViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var aliasInput: String

    public init(device: DeviceItem, viewModel: DevicesViewModel) {
        self.device = device
        self.viewModel = viewModel
        _aliasInput = State(initialValue: device.alias)
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section("Device") {
                    Text(device.hostname.isEmpty ? device.mac : device.hostname)
                        .font(.subheadline.weight(.semibold))
                    if !device.ip.isEmpty {
                        Text("\(device.ip) · \(device.mac)")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Custom Label") {
                    TextField("Enter friendly name", text: $aliasInput)
                        .autocorrectionDisabled()
                }

                if device.hasCustomAlias {
                    Section {
                        Button("Clear Custom Label", role: .destructive) {
                            viewModel.clearAlias(for: device)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle("Rename Device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        let clean = aliasInput.trimmingCharacters(in: .whitespacesAndNewlines)
                        if clean.isEmpty {
                            viewModel.clearAlias(for: device)
                        } else {
                            viewModel.saveAlias(clean, for: device)
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }
}
