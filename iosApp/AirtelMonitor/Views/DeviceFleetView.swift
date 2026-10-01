import SwiftUI
import UIKit

/// "Connected Devices" card, same layout as Android's DeviceFleetSection:
/// count, search, sort, band filter, and per-device copy-IP and rename.
public struct DeviceFleetView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var searchVisible = false
    @State private var renaming: DeviceItem?
    @State private var aliasInput = ""

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Text("Connected Devices").font(.headline)
                AppBadge(text: "\(viewModel.state.devices.count)", color: .accentColor)
                Spacer()
                Button {
                    searchVisible.toggle()
                    if !searchVisible { viewModel.deviceSearch = "" }
                } label: {
                    Image(systemName: searchVisible ? "xmark" : "magnifyingglass").font(.system(size: 15))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel("Toggle Search")
                Menu {
                    ForEach(DeviceSort.allCases, id: \.self) { sort in
                        Button {
                            viewModel.deviceSort = sort
                        } label: {
                            if sort == viewModel.deviceSort {
                                Label(sort.label, systemImage: "checkmark")
                            } else {
                                Text(sort.label)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down").font(.system(size: 15))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 8)
                }
                .accessibilityLabel("Sort")
            }

            if searchVisible {
                TextField("Search by name, IP, or MAC...", text: $viewModel.deviceSearch)
                    .font(.subheadline)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .liquidGlassPill()
                    .transition(.opacity)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(DeviceFilter.allCases, id: \.self) { filter in
                        let selected = viewModel.deviceFilter == filter
                        Button {
                            viewModel.deviceFilter = filter
                        } label: {
                            Text(filter.label)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(selected ? Color.accentColor : .primary)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 6)
                                .background(selected ? Color.accentColor.opacity(0.18) : Color.clear, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .liquidGlassPill(cornerRadius: 50)
                    }
                }
            }

            VStack(spacing: 8) {
                ForEach(viewModel.filteredDevices) { device in
                    DeviceRow(device: device) {
                        UIPasteboard.general.string = device.ip
                        viewModel.showToast("Copied \(device.ip)")
                    } onRename: {
                        aliasInput = device.alias
                        renaming = device
                    }
                }
            }
        }
        .liquidGlassCard()
        .alert("Rename Device", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("e.g. My Phone, Living Room TV", text: $aliasInput)
            Button("Cancel", role: .cancel) { renaming = nil }
            Button("Save") {
                if let d = renaming {
                    viewModel.setDeviceAlias(identifier: d.mac.isEmpty ? d.ip : d.mac, alias: aliasInput)
                    viewModel.showToast("Device renamed to \"\(aliasInput)\"")
                }
                renaming = nil
            }
        } message: {
            if let d = renaming { Text("\(d.ip) • \(d.mac)") }
        }
    }
}

private struct DeviceRow: View {
    let device: DeviceItem
    let onCopyIp: () -> Void
    let onRename: () -> Void

    private var bandLabel: String {
        if device.band.localizedCaseInsensitiveContains("LAN") || device.band.localizedCaseInsensitiveContains("Ethernet") {
            return "LAN"
        }
        return device.band.localizedCaseInsensitiveContains("5 GHz") ? "5 GHz" : "2.4 GHz"
    }

    private var speedLabel: String {
        let rate = device.txRateMbps
        return rate.isEmpty || rate == "-" || rate == "0" ? "" : " • \(rate) Mbps"
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: device.type.sfSymbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 36, height: 36)
                .background(Color.secondary.opacity(0.15), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(device.displayName).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("\(device.ip) • \(bandLabel)\(speedLabel)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Button(action: onCopyIp) {
                Image(systemName: "doc.on.doc").font(.system(size: 13))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .frame(width: 30, height: 30)
            .accessibilityLabel("Copy IP")
            Button(action: onRename) {
                Image(systemName: "pencil").font(.system(size: 13))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .frame(width: 30, height: 30)
            .accessibilityLabel("Rename")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .liquidGlassPill(cornerRadius: 14)
    }
}
