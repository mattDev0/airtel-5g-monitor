import SwiftUI

public struct DeviceFleetView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var selectedDeviceForAlias: DeviceItem? = nil
    @State private var aliasInput: String = ""

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "laptopcomputer.and.iphone")
                    .foregroundColor(.accentColor)
                    .font(.subheadline)
                Text("Connected Fleet")
                    .font(.subheadline)
                    .fontWeight(.bold)

                Text("\(viewModel.state.devices.count)")
                    .font(.system(size: 11, weight: .bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color.accentColor.opacity(0.18))
                    .foregroundColor(.accentColor)
                    .clipShape(Capsule())

                Spacer()
            }

            if viewModel.state.devices.isEmpty {
                Text(viewModel.isConnected ? "No active devices found on DHCP" : "Connect to router to view devices")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.vertical, 8)
            } else {
                VStack(spacing: 8) {
                    ForEach(viewModel.state.devices) { device in
                        DeviceRow(device: device) {
                            aliasInput = device.alias
                            selectedDeviceForAlias = device
                        }
                    }
                }
            }
        }
        .liquidGlassCard()
        .alert("Rename Device", isPresented: Binding(
            get: { selectedDeviceForAlias != nil },
            set: { if !$0 { selectedDeviceForAlias = nil } }
        )) {
            TextField("Device nickname", text: $aliasInput)
            Button("Cancel", role: .cancel) { selectedDeviceForAlias = nil }
            Button("Save") {
                if let dev = selectedDeviceForAlias {
                    let id = dev.mac.isEmpty ? dev.ip : dev.mac
                    viewModel.setDeviceAlias(identifier: id, alias: aliasInput)
                }
                selectedDeviceForAlias = nil
            }
        } message: {
            if let dev = selectedDeviceForAlias {
                Text("Enter a friendly nickname for \(dev.hostname) (\(dev.ip)).")
            }
        }
    }
}

private struct DeviceRow: View {
    let device: DeviceItem
    let onRename: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: device.type.sfSymbol)
                .font(.system(size: 16))
                .foregroundColor(.accentColor)
                .frame(width: 34, height: 34)
                .liquidGlassPill()

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(device.displayName)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.primary)

                    if !device.alias.isEmpty {
                        Image(systemName: "pencil.circle.fill")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }

                    if device.isRouter {
                        Text("GATEWAY")
                            .font(.system(size: 8, weight: .bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.orange.opacity(0.25))
                            .foregroundColor(.orange)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    }
                }

                HStack(spacing: 6) {
                    Text(device.ip)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Text("•")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Text(device.band)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                if device.signalPercent > 0 {
                    HStack(spacing: 4) {
                        Image(systemName: "wifi")
                            .font(.system(size: 10))
                            .foregroundColor(device.signalPercent > 60 ? .green : (device.signalPercent > 30 ? .orange : .red))
                        Text("\(device.signalPercent)%")
                            .font(.caption2)
                            .fontWeight(.medium)
                            .foregroundColor(.secondary)
                    }
                }

                if device.txRateMbps != "-" && device.txRateMbps.isNotEmpty {
                    Text("\(device.txRateMbps) Mbps")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(.secondary)
                }
            }
        }
        .padding(10)
        .liquidGlassPill()
        .contentShape(Rectangle())
        .onTapGesture {
            onRename()
        }
    }
}

private extension String {
    var isNotEmpty: Bool { !self.isEmpty }
}
