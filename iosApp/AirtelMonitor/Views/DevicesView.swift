import SwiftUI

public struct DevicesView: View {
    @ObservedObject public var container: AppContainer
    @ObservedObject public var shellVM: ShellViewModel

    @StateObject private var viewModel: DevicesViewModel
    @State private var openConnectionModal = false

    public init(container: AppContainer, shellVM: ShellViewModel) {
        self.container = container
        self.shellVM = shellVM
        _viewModel = StateObject(wrappedValue: DevicesViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                RouterConnectivityBanner(shellVM: shellVM) {
                    openConnectionModal = true
                }

                summaryCountsCard
                policyActionsRow
                filtersCard

                if viewModel.filteredDevices.isEmpty {
                    EmptyStateView(
                        icon: "laptopcomputer.and.iphone",
                        title: viewModel.totalCount == 0 ? "No Devices Detected" : "No Matching Devices",
                        message: viewModel.totalCount == 0
                            ? "Connected Wi-Fi and Ethernet clients appear here after router telemetry loads."
                            : "Try clearing the search text or changing the band/presence filter."
                    )
                } else {
                    LazyVStack(spacing: 10) {
                        ForEach(viewModel.filteredDevices) { dev in
                            deviceRow(dev)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Devices")
        .navigationBarTitleDisplayMode(.large)
        .searchable(text: $viewModel.searchQuery, prompt: "Search name, IP, MAC, or vendor")
        .toolbar {
            RouterShellToolbarControls(shellVM: shellVM) {
                openConnectionModal = true
            }
        }
        .refreshable {
            shellVM.forceRefresh()
        }
        .sheet(item: $viewModel.selectedDevice) { dev in
            DeviceDetailSheet(device: dev, viewModel: viewModel)
        }
        .sheet(item: $viewModel.renameTarget) { dev in
            RenameDeviceSheet(device: dev, viewModel: viewModel)
                .presentationDetents([.medium])
        }
        .sheet(item: $viewModel.activePolicySheet) { sheet in
            switch sheet {
            case .staticLeases:
                StaticLeasesSheet(viewModel: viewModel)
            case .macFilter:
                MacFilterPolicySheet(viewModel: viewModel)
            case .parentalControl:
                ParentalControlSheet(viewModel: viewModel)
            }
        }
        .sheet(isPresented: $openConnectionModal) {
            OnboardingView(container: container)
        }
    }

    private var summaryCountsCard: some View {
        SectionCard {
            HStack(spacing: 10) {
                StatTile(title: "Online", value: "\(viewModel.onlineCount)", accent: AirtelTheme.success)
                StatTile(title: "5 GHz", value: "\(viewModel.wifi5gCount)", accent: AirtelTheme.nr5g)
                StatTile(title: "2.4 GHz", value: "\(viewModel.wifi24gCount)", accent: AirtelTheme.lte4g)
                StatTile(title: "LAN", value: "\(viewModel.lanCount)", accent: .secondary)
            }
        }
    }

    private var policyActionsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                Button {
                    viewModel.openPolicySheet(.staticLeases)
                } label: {
                    Label("Static DHCP", systemImage: "pin.fill")
                }
                .liquidGlassSmallButton()

                Button {
                    viewModel.openPolicySheet(.macFilter)
                } label: {
                    Label("MAC Filter", systemImage: "shield.lefthalf.filled")
                }
                .liquidGlassSmallButton()

                if !viewModel.featureFlags.isLoaded || viewModel.featureFlags.parentalControlAvailable {
                    Button {
                        viewModel.openPolicySheet(.parentalControl)
                    } label: {
                        Label("Parental Control", systemImage: "figure.2.and.child.holdinghands")
                    }
                    .liquidGlassSmallButton()
                }
            }
        }
    }

    private var filtersCard: some View {
        SectionCard {
            VStack(alignment: .leading, spacing: 10) {
                Picker("Presence", selection: $viewModel.presenceFilter) {
                    ForEach(DevicePresenceFilter.allCases) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                HStack {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(DeviceFilter.allCases) { f in
                                let selected = viewModel.filterMode == f
                                Button {
                                    viewModel.filterMode = f
                                } label: {
                                    Text(f.label)
                                        .font(.caption.weight(.semibold))
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .foregroundStyle(selected ? .white : .primary)
                                        .background(
                                            selected ? AirtelTheme.accent : Color.secondary.opacity(0.14),
                                            in: Capsule()
                                        )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }

                    Menu {
                        ForEach(DeviceSort.allCases) { sort in
                            Button {
                                viewModel.sortMode = sort
                            } label: {
                                HStack {
                                    Text(sort.label)
                                    if viewModel.sortMode == sort {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    } label: {
                        Label(viewModel.sortMode.label, systemImage: "arrow.up.arrow.down")
                            .font(.caption.weight(.semibold))
                    }
                    .liquidGlassSmallButton()
                }
            }
        }
    }

    private func deviceRow(_ dev: DeviceItem) -> some View {
        Button {
            viewModel.selectedDevice = dev
        } label: {
            SectionCard {
                HStack(spacing: 12) {
                    Image(systemName: dev.type.sfSymbol)
                        .font(.title3)
                        .foregroundStyle(dev.isOnline ? AirtelTheme.accent : .secondary)
                        .frame(width: 36, height: 36)
                        .background(
                            (dev.isOnline ? AirtelTheme.accent : Color.secondary).opacity(0.12),
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                        )

                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(dev.displayName)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            if dev.isRouter {
                                BadgePill("Router", color: AirtelTheme.info)
                            }
                            if !dev.isOnline {
                                BadgePill("Offline", color: .secondary)
                            }
                            if viewModel.isMacBlocked(dev.mac) {
                                BadgePill("Blocked", color: AirtelTheme.error)
                            } else if viewModel.isMacReserved(dev.mac) {
                                BadgePill("Static IP", color: AirtelTheme.success)
                            }
                        }

                        HStack(spacing: 8) {
                            Text(dev.ip.isEmpty ? dev.mac : dev.ip)
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                            if !dev.vendor.isEmpty {
                                Text("· \(dev.vendor)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            if dev.isPrivateMac {
                                BadgePill("Private MAC", color: AirtelTheme.warning)
                            }
                        }
                    }

                    Spacer(minLength: 4)

                    VStack(alignment: .trailing, spacing: 3) {
                        if !dev.band.isEmpty {
                            BadgePill(dev.band, color: bandColor(dev.band))
                        }
                        if dev.isOnline {
                            let rate = dev.txRateMbps != "-" ? "\(dev.txRateMbps)M" : dev.rssi
                            if !rate.isEmpty {
                                Text(rate)
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func bandColor(_ band: String) -> Color {
        if band.contains("5") { return AirtelTheme.nr5g }
        if band.contains("2.4") { return AirtelTheme.lte4g }
        return .secondary
    }
}
