import SwiftUI

public struct LockEditorSheet: View {
    public let lockSettings: LockSettings?
    public let cellLock: CellLockInfo?
    public let isLoading: Bool
    public let isSaving: Bool
    public let message: String?
    public let onSaveBandLock: (Bool, Set<Int>, Bool, Set<Int>) -> Void
    public let onSaveLteCellLock: (Bool, [LteLockCell]) -> Void
    public let onSaveNrCellLock: (Bool, [NrLockCell]) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var section: LockSection = .bands
    @State private var didSeed = false

    @State private var lock4gEnabled = false
    @State private var selected4gBands: Set<Int> = []
    @State private var lock5gEnabled = false
    @State private var selected5gBands: Set<Int> = []

    @State private var lteCellEnabled = false
    @State private var lteEarfcn = ""
    @State private var ltePci = ""

    @State private var nrCellEnabled = false
    @State private var nrBand = "78"
    @State private var nrArfcn = ""
    @State private var nrPci = ""

    @State private var pendingConfirm: ConfirmPromptRequest?

    private enum LockSection: String, CaseIterable, Identifiable {
        case bands = "Band Lock"
        case cell4g = "4G Cell"
        case cell5g = "5G Cell"
        var id: String { rawValue }
    }

    private static let fallbackLteBands = [1, 3, 5, 7, 8, 20, 28, 38, 40, 41]
    private static let fallbackNrBands = [1, 3, 28, 41, 77, 78, 79]

    public init(
        lockSettings: LockSettings?,
        cellLock: CellLockInfo?,
        isLoading: Bool,
        isSaving: Bool,
        message: String?,
        onSaveBandLock: @escaping (Bool, Set<Int>, Bool, Set<Int>) -> Void,
        onSaveLteCellLock: @escaping (Bool, [LteLockCell]) -> Void,
        onSaveNrCellLock: @escaping (Bool, [NrLockCell]) -> Void
    ) {
        self.lockSettings = lockSettings
        self.cellLock = cellLock
        self.isLoading = isLoading
        self.isSaving = isSaving
        self.message = message
        self.onSaveBandLock = onSaveBandLock
        self.onSaveLteCellLock = onSaveLteCellLock
        self.onSaveNrCellLock = onSaveNrCellLock
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    Picker("Section", selection: $section) {
                        ForEach(LockSection.allCases) { sec in
                            Text(sec.rawValue).tag(sec)
                        }
                    }
                    .pickerStyle(.segmented)

                    if let message, !message.isEmpty {
                        Text(message)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(AirtelTheme.warning)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(AirtelTheme.warning.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }

                    if isLoading {
                        ProgressView("Loading router lock configuration...")
                            .padding(.vertical, 32)
                    } else {
                        switch section {
                        case .bands:
                            bandLockContent
                        case .cell4g:
                            lteCellLockContent
                        case .cell5g:
                            nrCellLockContent
                        }
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Band & Cell Lock")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close") { dismiss() }
                }
            }
            .onAppear { seedIfNeeded() }
            .onChange(of: lockSettings) { _ in
                didSeed = false
                seedIfNeeded()
            }
            .sheet(item: $pendingConfirm) { req in
                ConfirmSheet(request: req)
                    .presentationDetents([.medium])
            }
        }
    }

    private func seedIfNeeded() {
        guard !didSeed, let cfg = lockSettings else { return }
        didSeed = true
        lock4gEnabled = cfg.bands.lock4gEnabled
        selected4gBands = cfg.bands.locked4g
        lock5gEnabled = cfg.bands.lock5gEnabled
        selected5gBands = cfg.bands.locked5g

        lteCellEnabled = cfg.cells.lteEnabled
        if let first4g = cfg.cells.lteCells.first {
            lteEarfcn = first4g.earfcn
            ltePci = first4g.pci
        } else if let cur4g = cfg.cells.current4g {
            lteEarfcn = cur4g.earfcn
            ltePci = cur4g.pci
        } else if let cellLock, cellLock.servingFreq4g != "-" {
            lteEarfcn = cellLock.servingFreq4g
            ltePci = cellLock.servingPci4g
        }

        nrCellEnabled = cfg.cells.nrEnabled
        if let first5g = cfg.cells.nrCells.first {
            nrBand = first5g.band
            nrArfcn = first5g.arfcn
            nrPci = first5g.pci
        } else if let cur5g = cfg.cells.current5g {
            nrBand = cur5g.band
            nrArfcn = cur5g.arfcn
            nrPci = cur5g.pci
        } else if let cellLock, cellLock.servingFreq5g != "-" {
            nrBand = cellLock.currentBand5g.filter(\.isNumber)
            if nrBand.isEmpty { nrBand = "78" }
            nrArfcn = cellLock.servingFreq5g
            nrPci = cellLock.servingPci5g
        }
    }

    private var bandLockContent: some View {
        let supported4g = lockSettings?.bands.supported4g ?? []
        let supported5g = lockSettings?.bands.supported5g ?? []
        let list4g = supported4g.isEmpty ? Self.fallbackLteBands : supported4g
        let list5g = supported5g.isEmpty ? Self.fallbackNrBands : supported5g

        return VStack(spacing: 14) {
            SectionCard(title: "4G LTE Bands") {
                VStack(alignment: .leading, spacing: 10) {
                    Toggle("Enable 4G Band Lock", isOn: $lock4gEnabled)
                        .tint(AirtelTheme.accent)
                    bandGrid(bands: list4g, prefix: "B", selection: $selected4gBands)
                }
            }

            SectionCard(title: "5G NR Bands") {
                VStack(alignment: .leading, spacing: 10) {
                    Toggle("Enable 5G Band Lock", isOn: $lock5gEnabled)
                        .tint(AirtelTheme.accent)
                    bandGrid(bands: list5g, prefix: "n", selection: $selected5gBands)
                }
            }

            Button {
                pendingConfirm = ConfirmPromptRequest(
                    title: "Apply Band Lock?",
                    impactMessage: "Changing locked 4G/5G bands forces the modem to re-register and temporarily drops cellular internet.",
                    confirmLabel: "Apply Band Lock",
                    tier: .connectivity,
                    onConfirm: {
                        onSaveBandLock(lock4gEnabled, selected4gBands, lock5gEnabled, selected5gBands)
                    }
                )
            } label: {
                if isSaving {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text("Apply Band Lock").frame(maxWidth: .infinity)
                }
            }
            .disabled(isSaving)
            .liquidGlassActionButton(tint: AirtelTheme.accent)
        }
    }

    private func bandGrid(bands: [Int], prefix: String, selection: Binding<Set<Int>>) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: 8)], spacing: 8) {
            ForEach(bands, id: \.self) { band in
                let isSelected = selection.wrappedValue.contains(band)
                Button {
                    if isSelected {
                        selection.wrappedValue.remove(band)
                    } else {
                        selection.wrappedValue.insert(band)
                    }
                } label: {
                    Text("\(prefix)\(band)")
                        .font(.caption.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .foregroundStyle(isSelected ? .white : .primary)
                        .background(
                            isSelected ? AirtelTheme.accent : Color.secondary.opacity(0.14),
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var lteCellLockContent: some View {
        SectionCard(title: "4G LTE Cell Lock") {
            VStack(alignment: .leading, spacing: 12) {
                Toggle("Lock to Specific 4G Cell", isOn: $lteCellEnabled)
                    .tint(AirtelTheme.accent)

                TextField("EARFCN", text: $lteEarfcn)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)

                TextField("PCI (0–503)", text: $ltePci)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)

                if let serving = cellLock, serving.servingFreq4g != "-", serving.servingPci4g != "-" {
                    Button("Use Serving 4G Cell (EARFCN \(serving.servingFreq4g) / PCI \(serving.servingPci4g))") {
                        lteEarfcn = serving.servingFreq4g
                        ltePci = serving.servingPci4g
                    }
                    .font(.caption.weight(.semibold))
                    .liquidGlassSmallButton()
                }

                Button {
                    let cells = lteCellEnabled && !lteEarfcn.isEmpty && !ltePci.isEmpty
                        ? [LteLockCell(earfcn: lteEarfcn, pci: ltePci)]
                        : []
                    pendingConfirm = ConfirmPromptRequest(
                        title: lteCellEnabled ? "Apply 4G Cell Lock?" : "Unlock 4G Cell?",
                        impactMessage: "Cellular data will drop briefly while the modem applies the LTE PCI lock.",
                        confirmLabel: "Apply",
                        tier: .connectivity,
                        onConfirm: {
                            onSaveLteCellLock(lteCellEnabled && !cells.isEmpty, cells)
                        }
                    )
                } label: {
                    Text(lteCellEnabled ? "Apply 4G Cell Lock" : "Unlock 4G Cell")
                        .frame(maxWidth: .infinity)
                }
                .disabled(isSaving)
                .liquidGlassActionButton(tint: AirtelTheme.accent)
            }
        }
    }

    private var nrCellLockContent: some View {
        SectionCard(title: "5G NR Cell Lock") {
            VStack(alignment: .leading, spacing: 12) {
                Toggle("Lock to Specific 5G Cell", isOn: $nrCellEnabled)
                    .tint(AirtelTheme.accent)

                TextField("NR Band (e.g. 78)", text: $nrBand)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)

                TextField("NR-ARFCN", text: $nrArfcn)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)

                TextField("NR PCI (0–1007)", text: $nrPci)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)

                if let serving = cellLock, serving.servingFreq5g != "-", serving.servingPci5g != "-" {
                    Button("Use Serving 5G Cell (ARFCN \(serving.servingFreq5g) / PCI \(serving.servingPci5g))") {
                        let digits = serving.currentBand5g.filter(\.isNumber)
                        if !digits.isEmpty { nrBand = digits }
                        nrArfcn = serving.servingFreq5g
                        nrPci = serving.servingPci5g
                    }
                    .font(.caption.weight(.semibold))
                    .liquidGlassSmallButton()
                }

                Button {
                    let cells = nrCellEnabled && !nrArfcn.isEmpty && !nrPci.isEmpty
                        ? [NrLockCell(band: nrBand.isEmpty ? "78" : nrBand, arfcn: nrArfcn, pci: nrPci)]
                        : []
                    pendingConfirm = ConfirmPromptRequest(
                        title: nrCellEnabled ? "Apply 5G Cell Lock?" : "Unlock 5G Cell?",
                        impactMessage: "Cellular data will drop briefly while the modem applies the 5G NR PCI lock.",
                        confirmLabel: "Apply",
                        tier: .connectivity,
                        onConfirm: {
                            onSaveNrCellLock(nrCellEnabled && !cells.isEmpty, cells)
                        }
                    )
                } label: {
                    Text(nrCellEnabled ? "Apply 5G Cell Lock" : "Unlock 5G Cell")
                        .frame(maxWidth: .infinity)
                }
                .disabled(isSaving)
                .liquidGlassActionButton(tint: AirtelTheme.accent)
            }
        }
    }
}
