import SwiftUI

/// Band lock and local physical cell lock, mirroring the web UI's Advanced Settings tabs.
public struct LockEditorView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @Environment(\.dismiss) private var dismiss

    private enum Tab: String, CaseIterable { case cells = "Cell Lock", bands = "Band Lock" }
    @State private var tab: Tab = .cells

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    private var isNsa: Bool {
        viewModel.state.cellular.networkType.uppercased().contains("NSA")
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Lock", selection: $tab) {
                        ForEach(Tab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }

                if let msg = viewModel.lockMessage {
                    Section {
                        Label(msg, systemImage: msg.contains("confirmed") ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(msg.contains("confirmed") ? .green : .orange)
                    }
                }

                if let settings = viewModel.lockSettings {
                    switch tab {
                    case .cells:
                        CellLockSections(config: settings.cells, isNsa: isNsa, isSaving: viewModel.isLockSaving,
                                         onSaveLte: { on, cells in Task { await viewModel.saveLteCellLock(enabled: on, cells: cells) } },
                                         onSaveNr: { on, cells in Task { await viewModel.saveNrCellLock(enabled: on, cells: cells) } })
                            .id(settings.cells)
                    case .bands:
                        BandLockSections(config: settings.bands, isNsa: isNsa, isSaving: viewModel.isLockSaving) { l4, b4, l5, b5 in
                            Task { await viewModel.saveBandLock(lock4g: l4, bands4g: b4, lock5g: l5, bands5g: b5) }
                        }
                        .id(settings.bands)
                    }
                } else if viewModel.isLockLoading {
                    Section { ProgressView("Reading lock settings…") }
                } else {
                    Section {
                        Button("Couldn't read the router. Retry") { Task { await viewModel.loadLocks() } }
                    }
                }
            }
            .navigationTitle("Network Locks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarLeading) {
                    if viewModel.isLockSaving || viewModel.isLockLoading { ProgressView() }
                }
            }
            .task {
                viewModel.lockMessage = nil
                await viewModel.loadLocks()
            }
        }
    }
}

// MARK: - Band lock

private struct BandLockSections: View {
    let config: BandLockConfig
    let isNsa: Bool
    let isSaving: Bool
    let onSave: (Bool, Set<Int>, Bool, Set<Int>) -> Void

    @State private var lock4g: Bool
    @State private var bands4g: Set<Int>
    @State private var lock5g: Bool
    @State private var bands5g: Set<Int>
    @State private var confirm = false

    init(config: BandLockConfig, isNsa: Bool, isSaving: Bool, onSave: @escaping (Bool, Set<Int>, Bool, Set<Int>) -> Void) {
        self.config = config
        self.isNsa = isNsa
        self.isSaving = isSaving
        self.onSave = onSave
        _lock4g = State(initialValue: config.lock4gEnabled)
        _bands4g = State(initialValue: config.locked4g.isEmpty ? Set(config.supported4g) : config.locked4g)
        _lock5g = State(initialValue: config.lock5gEnabled)
        _bands5g = State(initialValue: config.locked5g.isEmpty ? Set(config.supported5g) : config.locked5g)
    }

    var body: some View {
        bandSection(title: "4G bands", prefix: "B", isOn: $lock4g, supported: config.supported4g, selected: $bands4g,
                    footer: "Only the ticked bands are allowed while the lock is on. Off lets the router use every band it supports.")
        bandSection(title: "5G bands", prefix: "n", isOn: $lock5g, supported: config.supported5g, selected: $bands5g,
                    footer: isNsa ? "You're on 5G NSA. The router notes that a 5G band lock only takes effect in SA mode." : "")

        Section {
            Button("Save Band Lock") { confirm = true }
                .frame(maxWidth: .infinity)
                .disabled(isSaving || (lock4g && bands4g.isEmpty) || (lock5g && bands5g.isEmpty))
        }
        .confirmationDialog("Apply band lock?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Apply") { onSave(lock4g, bands4g, lock5g, bands5g) }
        } message: {
            Text("The modem re-registers with the network, so the internet can drop for a few seconds.")
        }
    }

    @ViewBuilder
    private func bandSection(title: String, prefix: String, isOn: Binding<Bool>, supported: [Int],
                             selected: Binding<Set<Int>>, footer: String) -> some View {
        Section {
            Toggle("Lock \(title)", isOn: isOn)
            if isOn.wrappedValue {
                ForEach(supported, id: \.self) { band in
                    Button {
                        if selected.wrappedValue.contains(band) {
                            selected.wrappedValue.remove(band)
                        } else {
                            selected.wrappedValue.insert(band)
                        }
                    } label: {
                        HStack {
                            Text("\(prefix)\(band)").font(.body.monospaced()).foregroundStyle(.primary)
                            Spacer()
                            if selected.wrappedValue.contains(band) {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                }
            }
        } header: {
            Text(isOn.wrappedValue ? "\(title) · \(selected.wrappedValue.count) of \(supported.count)" : "\(title) · all allowed")
        } footer: {
            if !footer.isEmpty { Text(footer) }
        }
    }
}

// MARK: - Cell lock

private struct CellLockSections: View {
    let config: CellLockConfig
    let isNsa: Bool
    let isSaving: Bool
    let onSaveLte: (Bool, [LteLockCell]) -> Void
    let onSaveNr: (Bool, [NrLockCell]) -> Void

    @State private var lteOn: Bool
    @State private var lteCells: [LteLockCell]
    @State private var nrOn: Bool
    @State private var nrCells: [NrLockCell]
    @State private var newEarfcn = ""
    @State private var newLtePci = ""
    @State private var newBand = ""
    @State private var newArfcn = ""
    @State private var newNrPci = ""
    @State private var pendingApply: (() -> Void)?

    init(config: CellLockConfig, isNsa: Bool, isSaving: Bool,
         onSaveLte: @escaping (Bool, [LteLockCell]) -> Void, onSaveNr: @escaping (Bool, [NrLockCell]) -> Void) {
        self.config = config
        self.isNsa = isNsa
        self.isSaving = isSaving
        self.onSaveLte = onSaveLte
        self.onSaveNr = onSaveNr
        _lteOn = State(initialValue: config.lteEnabled)
        _lteCells = State(initialValue: config.lteCells)
        _nrOn = State(initialValue: config.nrEnabled)
        _nrCells = State(initialValue: config.nrCells)
    }

    var body: some View {
        Section {
            Label(isNsa
                  ? "You're on 5G NSA: lock 4G and 5G together, because the 5G link rides on the 4G anchor cell."
                  : "Lock the router to specific cells instead of letting it choose.",
                  systemImage: "info.circle")
            Label("If a lock leaves the router without signal, it's still reachable over Wi-Fi, so you can unlock it here.",
                  systemImage: "wifi")
        }
        .font(.footnote)
        .foregroundStyle(.secondary)

        // 4G
        Section {
            Toggle("Lock 4G cell", isOn: $lteOn)
            if lteOn {
                ForEach(lteCells, id: \.self) { cell in
                    CellRow(main: "EARFCN \(cell.earfcn)", detail: "PCI \(cell.pci.isEmpty ? "any" : cell.pci)")
                }
                .onDelete { lteCells.remove(atOffsets: $0) }

                if let current = config.current4g, !lteCells.contains(current) {
                    Button { lteCells.append(current) } label: {
                        Label("Use current cell (\(current.earfcn) / \(current.pci))", systemImage: "location.fill")
                    }
                }
                HStack {
                    TextField("EARFCN", text: $newEarfcn).keyboardType(.numberPad)
                    TextField("PCI (optional)", text: $newLtePci).keyboardType(.numberPad)
                    Button {
                        lteCells.append(LteLockCell(earfcn: newEarfcn, pci: newLtePci))
                        newEarfcn = ""; newLtePci = ""
                    } label: { Image(systemName: "plus.circle.fill") }
                    .disabled(newEarfcn.isEmpty)
                }
            }
            Button(lteOn ? "Apply 4G Lock" : "Unlock 4G (router picks the cell)") {
                pendingApply = { onSaveLte(lteOn, lteCells) }
            }
            .disabled(isSaving || (lteOn && lteCells.isEmpty))
        } header: {
            StatusHeader(title: "4G cell", state: config.lteState)
        } footer: {
            if let c = config.current4g { Text("Now on EARFCN \(c.earfcn) · PCI \(c.pci)") }
        }

        // 5G
        Section {
            Toggle("Lock 5G cell", isOn: $nrOn)
            if nrOn {
                ForEach(nrCells, id: \.self) { cell in
                    CellRow(main: "n\(cell.band) · ARFCN \(cell.arfcn)", detail: "PCI \(cell.pci.isEmpty ? "any" : cell.pci)")
                }
                .onDelete { nrCells.remove(atOffsets: $0) }

                if let current = config.current5g, !nrCells.contains(current) {
                    Button { nrCells.append(current) } label: {
                        Label("Use current cell (n\(current.band) / \(current.arfcn) / \(current.pci))", systemImage: "location.fill")
                    }
                }
                HStack {
                    TextField("Band", text: $newBand).keyboardType(.numberPad).frame(maxWidth: 60)
                    TextField("ARFCN", text: $newArfcn).keyboardType(.numberPad)
                    TextField("PCI", text: $newNrPci).keyboardType(.numberPad).frame(maxWidth: 60)
                    Button {
                        nrCells.append(NrLockCell(band: newBand, arfcn: newArfcn, pci: newNrPci))
                        newBand = ""; newArfcn = ""; newNrPci = ""
                    } label: { Image(systemName: "plus.circle.fill") }
                    .disabled(newBand.isEmpty || newArfcn.isEmpty)
                }
            }
            Button(nrOn ? "Apply 5G Lock" : "Unlock 5G (router picks the cell)") {
                pendingApply = { onSaveNr(nrOn, nrCells) }
            }
            .disabled(isSaving || (nrOn && nrCells.isEmpty))
        } header: {
            StatusHeader(title: "5G cell", state: config.nrState)
        } footer: {
            if let c = config.current5g { Text("Now on n\(c.band) · ARFCN \(c.arfcn) · PCI \(c.pci)") }
        }
        .confirmationDialog("Apply to router?", isPresented: Binding(
            get: { pendingApply != nil },
            set: { if !$0 { pendingApply = nil } }
        ), titleVisibility: .visible) {
            Button("Apply") { pendingApply?(); pendingApply = nil }
        } message: {
            Text("The modem re-attaches to the network, so the internet can drop for a few seconds. Locking to a cell it can't hear leaves it without service until you change the lock.")
        }
    }
}

private struct CellRow: View {
    let main: String
    let detail: String

    var body: some View {
        HStack {
            Text(main).font(.body.monospaced())
            Spacer()
            Text(detail).font(.footnote.monospaced()).foregroundStyle(.secondary)
        }
    }
}

private struct StatusHeader: View {
    let title: String
    let state: LockState

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            switch state {
            case .locked: Text("Locked").foregroundStyle(.green)
            case .failed: Text("Lock failed").foregroundStyle(.red)
            case .unlocked: Text("Auto").foregroundStyle(.secondary)
            }
        }
        .font(.footnote.weight(.semibold))
    }
}
