import SwiftUI
import UIKit

/// Ping & traceroute card, same layout and wording as Android's NetworkDiagnosisCard.
public struct DiagnosisCardView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var followOutput = true
    @FocusState private var targetFocused: Bool

    private let quickTargets = ["8.8.8.8", "1.1.1.1", "google.com"]
    private let packetCounts = [4, 10, 20]

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    private var state: DiagnosisState { viewModel.diagnosisState }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                IconCircle(systemImage: "waveform.path.ecg")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Network Diagnosis").font(.headline)
                    Text("Ping & Traceroute diagnostics").font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                HStack(spacing: 5) {
                    if state.isRunning { ProgressView().controlSize(.mini) }
                    Text(state.isRunning ? "Running" : "Ready")
                }
                .font(.caption2.weight(.bold))
                .foregroundStyle(state.isRunning ? StatusColors.success : .secondary)
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background((state.isRunning ? StatusColors.success : Color.secondary).opacity(0.15), in: Capsule())
            }

            Picker("Mode", selection: $viewModel.diagnosisState.mode) {
                Text("Ping Test").tag(DiagnosisMode.ping)
                Text("Traceroute").tag(DiagnosisMode.traceroute)
            }
            .pickerStyle(.segmented)
            .disabled(state.isRunning)

            VStack(alignment: .leading, spacing: 4) {
                Text("Target Host / IP").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                TextField("8.8.8.8 or google.com", text: $viewModel.diagnosisState.target)
                    .font(.subheadline.monospaced())
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($targetFocused)
                    .submitLabel(.done)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .liquidGlassPill()
                    .disabled(state.isRunning)
            }

            HStack(spacing: 6) {
                Text("Quick:").font(.caption2).foregroundStyle(.secondary)
                ForEach(quickTargets, id: \.self) { target in
                    Button(target) { viewModel.diagnosisState.target = target }
                        .font(.caption2.weight(.semibold))
                        .liquidGlassSmallButton()
                        .disabled(state.isRunning)
                }
            }

            if state.mode == .ping {
                HStack(spacing: 6) {
                    Text("Packets:").font(.caption2).foregroundStyle(.secondary)
                    Picker("Packets", selection: $viewModel.diagnosisState.pingCount) {
                        ForEach(packetCounts, id: \.self) { Text("\($0)").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 180)
                    .disabled(state.isRunning)
                }
            }

            if let error = state.statusMessage {
                Text(error).font(.caption.monospaced()).foregroundStyle(StatusColors.error)
            }

            HStack {
                if !state.output.isEmpty {
                    Button {
                        viewModel.clearDiagnosisOutput()
                    } label: {
                        Label("Clear", systemImage: "xmark.circle").font(.caption.weight(.semibold))
                    }
                    .liquidGlassSmallButton()
                    .disabled(state.isRunning)
                }
                Spacer()
                if state.isRunning {
                    Button {
                        viewModel.stopDiagnosis()
                    } label: {
                        Label("Stop", systemImage: "stop.fill").font(.subheadline.weight(.bold))
                    }
                    .liquidGlassActionButton(tint: .red)
                    .fixedSize()
                } else {
                    Button {
                        targetFocused = false
                        viewModel.startDiagnosis()
                    } label: {
                        Label(state.mode == .ping ? "Start Ping" : "Start Trace",
                              systemImage: state.mode == .ping ? "play.fill" : "point.topleft.down.curvedto.point.bottomright.up")
                            .font(.subheadline.weight(.semibold))
                    }
                    .liquidGlassActionButton()
                    .fixedSize()
                    .disabled(state.target.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }

            console
        }
        .liquidGlassCard()
        .onChange(of: state.isRunning) { running in
            if running { followOutput = true }
        }
    }

    private var console: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 5) {
                Circle().fill(StatusColors.error.opacity(0.7)).frame(width: 8, height: 8)
                Circle().fill(Color.secondary.opacity(0.4)).frame(width: 8, height: 8)
                Circle().fill(StatusColors.success.opacity(0.7)).frame(width: 8, height: 8)
                Text(state.mode == .ping ? "PING CONSOLE" : "TRACEROUTE CONSOLE")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .padding(.leading, 6)
                Spacer()
                if !state.output.isEmpty {
                    Button {
                        UIPasteboard.general.string = state.output
                        viewModel.showToast("Output copied to clipboard")
                    } label: {
                        Image(systemName: "doc.on.doc").font(.system(size: 12))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Copy output")
                }
            }
            Divider().opacity(0.3).padding(.vertical, 8)

            ScrollViewReader { proxy in
                ZStack(alignment: .bottomTrailing) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            if state.output.isEmpty {
                                Text("Enter a host or IP above and tap Start to run diagnostic test directly from the router.")
                                    .foregroundStyle(.secondary)
                            } else {
                                Text(state.output).textSelection(.enabled)
                            }
                            Color.clear.frame(height: 1).id("bottom")
                        }
                        .font(.system(size: 11, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 110, maxHeight: 230)
                    .simultaneousGesture(DragGesture().onChanged { value in
                        // Dragging down scrolls back through earlier lines: stop following.
                        if value.translation.height > 8 { followOutput = false }
                    })

                    if !followOutput && state.isRunning {
                        Button {
                            followOutput = true
                            withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
                        } label: {
                            Label("Auto-scroll paused", systemImage: "arrow.down.circle.fill")
                                .font(.system(size: 10, weight: .bold))
                        }
                        .liquidGlassSmallButton()
                    }
                }
                .onChange(of: state.output) { _ in
                    if followOutput { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
                }
            }
        }
        .padding(12)
        .liquidGlassPill(cornerRadius: 14)
    }
}
