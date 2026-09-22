import SwiftUI

public struct DiagnosisCardView: View {
    @ObservedObject var viewModel: MonitorViewModel
    @State private var followOutput: Bool = true

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "terminal.fill")
                    .foregroundColor(.accentColor)
                    .font(.subheadline)
                Text("Network Diagnosis")
                    .font(.subheadline)
                    .fontWeight(.bold)
                Spacer()

                if viewModel.diagnosisState.isRunning {
                    HStack(spacing: 6) {
                        ProgressView()
                            .scaleEffect(0.7)
                        Text("Running...")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
            }

            // Mode Selector
            Picker("Mode", selection: $viewModel.diagnosisState.mode) {
                ForEach(DiagnosisMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(SegmentedPickerStyle())
            .disabled(viewModel.diagnosisState.isRunning)

            // Target Input & Controls
            HStack(spacing: 8) {
                TextField("Target (e.g. 8.8.8.8)", text: $viewModel.diagnosisState.target)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .font(.system(.subheadline, design: .monospaced))
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .disabled(viewModel.diagnosisState.isRunning)

                if viewModel.diagnosisState.mode == .ping {
                    Stepper("\(viewModel.diagnosisState.pingCount)x", value: $viewModel.diagnosisState.pingCount, in: 1...20)
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .liquidGlassPill()
                        .disabled(viewModel.diagnosisState.isRunning)
                }
            }

            // Run / Stop Button
            Button {
                if viewModel.diagnosisState.isRunning {
                    viewModel.stopDiagnosis()
                } else {
                    viewModel.startDiagnosis()
                }
            } label: {
                HStack {
                    Image(systemName: viewModel.diagnosisState.isRunning ? "stop.fill" : "play.fill")
                    Text(viewModel.diagnosisState.isRunning ? "Stop Diagnostic" : "Run \(viewModel.diagnosisState.mode.rawValue)")
                }
                .font(.subheadline)
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(viewModel.diagnosisState.isRunning ? Color.red : Color.accentColor)
                .foregroundColor(.white)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .disabled(viewModel.diagnosisState.target.trimmingCharacters(in: .whitespaces).isEmpty)

            // Terminal Console
            if !viewModel.diagnosisState.output.isEmpty {
                ScrollViewReader { proxy in
                    ZStack(alignment: .bottomTrailing) {
                        ScrollView(.vertical) {
                            Text(viewModel.diagnosisState.output)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(Color.green)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(12)
                                .id("bottom")
                        }
                        .frame(height: 160)
                        .background(Color.black.opacity(0.85))
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(Color.white.opacity(0.15), lineWidth: 0.8)
                        )
                        .simultaneousGesture(
                            DragGesture()
                                .onChanged { value in
                                    // Dragging down scrolls up toward earlier terminal lines
                                    if value.translation.height > 8 {
                                        followOutput = false
                                    }
                                }
                        )

                        if !followOutput && viewModel.diagnosisState.isRunning {
                            Button {
                                followOutput = true
                                withAnimation {
                                    proxy.scrollTo("bottom", anchor: .bottom)
                                }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "arrow.down.circle.fill")
                                    Text("Auto-scroll paused")
                                }
                                .font(.system(size: 10, weight: .bold))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color.black.opacity(0.85))
                                .foregroundColor(.yellow)
                                .clipShape(Capsule())
                                .overlay(
                                    Capsule().stroke(Color.yellow.opacity(0.5), lineWidth: 1)
                                )
                                .shadow(radius: 2)
                            }
                            .padding(8)
                        }
                    }
                    .onChange(of: viewModel.diagnosisState.output) { _ in
                        if followOutput {
                            withAnimation {
                                proxy.scrollTo("bottom", anchor: .bottom)
                            }
                        }
                    }
                    .onChange(of: viewModel.diagnosisState.isRunning) { isRunning in
                        if isRunning {
                            followOutput = true
                        }
                    }
                }
            }
        }
        .liquidGlassCard()
    }
}
