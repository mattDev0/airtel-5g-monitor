import SwiftUI

public struct AlignmentView: View {
    @ObservedObject public var container: AppContainer
    @StateObject private var viewModel: SignalViewModel

    public init(container: AppContainer) {
        self.container = container
        _viewModel = StateObject(wrappedValue: SignalViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let snap = viewModel.alignmentSnapshot {
                    liveMetersCard(snap)
                    peaksCard
                    trendsCard
                    tipsCard
                } else {
                    SkeletonBox(height: 180)
                    SkeletonBox(height: 120)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Antenna Alignment")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            viewModel.startAlignmentMode()
        }
        .onDisappear {
            viewModel.stopAlignmentMode()
        }
    }

    private func liveMetersCard(_ snap: AlignmentSnapshot) -> some View {
        let sinr = snap.primarySinr
        let rsrp = snap.primaryRsrp
        let sinrColor = AirtelTheme.sinrColor(sinr)
        let rsrpColor = AirtelTheme.rsrpColor(rsrp)

        return SectionCard(
            title: "Live 2s Alignment",
            subtitle: "\(snap.activeTechLabel) · \(snap.carrierSummary)",
            tint: sinrColor,
            trailing: {
                BadgePill(snap.networkType, color: AirtelTheme.nr5g)
            }
        ) {
            VStack(spacing: 14) {
                HStack(spacing: 10) {
                    StatTile(
                        title: "Primary SINR",
                        value: sinr.map { String(format: "%.1f", $0) } ?? "--",
                        unit: "dB",
                        caption: sinrCaption(sinr),
                        accent: sinrColor
                    )
                    StatTile(
                        title: "Primary RSRP",
                        value: rsrp.map { String(format: "%.0f", $0) } ?? "--",
                        unit: "dBm",
                        caption: AirtelTheme.qualityLabel(rsrpDbm: rsrp),
                        accent: rsrpColor
                    )
                }

                SignalMeter(
                    title: "SINR Quality (-10 to +30 dB)",
                    valueText: sinr.map { String(format: "%.1f dB", $0) } ?? "--",
                    fraction: sinr.map { min(1.0, max(0.0, ($0 + 10.0) / 40.0)) } ?? 0,
                    tint: sinrColor
                )

                SignalMeter(
                    title: "RSRP Strength (-140 to -65 dBm)",
                    valueText: rsrp.map { String(format: "%.0f dBm", $0) } ?? "--",
                    fraction: rsrp.map { min(1.0, max(0.0, ($0 + 140.0) / 75.0)) } ?? 0,
                    tint: rsrpColor
                )

                if let updated = viewModel.alignmentLastUpdatedAt {
                    Text("Last reading change: \(updated, style: .time)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
        }
    }

    private var peaksCard: some View {
        SectionCard(title: "Peak Readings & Audio") {
            VStack(spacing: 12) {
                HStack(spacing: 10) {
                    StatTile(
                        title: "Best SINR",
                        value: viewModel.bestSinr.map { String(format: "%.1f", $0) } ?? "--",
                        unit: "dB",
                        accent: AirtelTheme.sinrColor(viewModel.bestSinr)
                    )
                    StatTile(
                        title: "Best RSRP",
                        value: viewModel.bestRsrp.map { String(format: "%.0f", $0) } ?? "--",
                        unit: "dBm",
                        accent: AirtelTheme.rsrpColor(viewModel.bestRsrp)
                    )
                }

                GlassActionGroup(spacing: 10) {
                    Button {
                        viewModel.toggleAlignmentSound()
                    } label: {
                        Label(
                            viewModel.alignmentSoundEnabled ? "Mute Audio Pip" : "Enable Audio Pip",
                            systemImage: viewModel.alignmentSoundEnabled ? "speaker.wave.2.fill" : "speaker.slash.fill"
                        )
                        .frame(maxWidth: .infinity)
                    }
                    .liquidGlassActionButton(tint: viewModel.alignmentSoundEnabled ? AirtelTheme.accent : .secondary)

                    Button("Reset Peaks") {
                        viewModel.resetAlignmentPeaks()
                    }
                    .liquidGlassSmallButton()
                }
            }
        }
    }

    private var trendsCard: some View {
        SectionCard(title: "Alignment Trend (last 45 samples)") {
            VStack(spacing: 12) {
                if viewModel.alignmentSinrHistory.count >= 2 {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("SINR (dB)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Sparkline(values: viewModel.alignmentSinrHistory, lineColor: AirtelTheme.nr5g)
                    }
                }
                if viewModel.alignmentRsrpHistory.count >= 2 {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("RSRP (dBm)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Sparkline(values: viewModel.alignmentRsrpHistory, lineColor: AirtelTheme.lte4g)
                    }
                }
                if viewModel.alignmentSinrHistory.count < 2 && viewModel.alignmentRsrpHistory.count < 2 {
                    Text("Rotate or reposition the ODU slowly while waiting for 2s updates...")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 12)
                }
            }
        }
    }

    private var tipsCard: some View {
        SectionCard(title: "Aiming Tips") {
            VStack(alignment: .leading, spacing: 6) {
                Text("1. Rotate in small 5–10° increments and wait ~4–6 seconds between stops so modem averaging settles.")
                Text("2. Prioritize higher SINR (15 dB+ is ideal for 5G throughput) over raw RSRP alone.")
                Text("3. Audio pip pitch rises as SINR improves.")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }

    private func sinrCaption(_ sinr: Double?) -> String {
        guard let v = sinr else { return "--" }
        if v >= 20 { return "Excellent" }
        if v >= 13 { return "Good" }
        if v >= 0 { return "Fair" }
        return "Noisy"
    }
}
