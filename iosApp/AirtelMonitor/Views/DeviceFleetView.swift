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
                    .font(.caption2)
                    .fontWeight(.bold)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.accentColor.opacity(0.15))
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
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.04), radius: 3, x: 0, y: 1)
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
                .font(.system(size: 18))
                .foregroundColor(.accentColor)
                .frame(width: 32, height: 32)
                .background(Color(.secondarySystemBackground))
                .clipShape(Circle())

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
                            .font(.system(size: 9, weight: .bold))
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.orange.opacity(0.2))
                            .foregroundColor(.orange)
                            .cornerRadius(4)
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
        .padding(8)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(8)
        .contentShape(Rectangle())
        .onTapGesture {
            onRename()
        }
    }
}

private extension String {
    var isNotEmpty: Bool { !self.isEmpty }
}
