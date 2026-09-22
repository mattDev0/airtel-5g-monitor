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
                .padding(.vertical, 8)
                .background(viewModel.diagnosisState.isRunning ? Color.red : Color.accentColor)
                .foregroundColor(.white)
                .cornerRadius(8)
            }
            .disabled(viewModel.diagnosisState.target.trimmingCharacters(in: .whitespaces).isEmpty)

            // Terminal Console
            if !viewModel.diagnosisState.output.isEmpty {
                ScrollViewReader { proxy in
                    ScrollView(.vertical) {
                        Text(viewModel.diagnosisState.output)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(Color.green)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(10)
                            .id("bottom")
                    }
                    .frame(height: 160)
                    .background(Color.black.opacity(0.92))
                    .cornerRadius(8)
                    .onChange(of: viewModel.diagnosisState.output) { _ in
                        withAnimation {
                            proxy.scrollTo("bottom", anchor: .bottom)
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.04), radius: 3, x: 0, y: 1)
    }
}
