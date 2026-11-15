import SwiftUI

public struct OnboardingView: View {
    @ObservedObject public var container: AppContainer
    @StateObject private var viewModel: OnboardingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showPassword = false

    public init(container: AppContainer) {
        self.container = container
        _viewModel = StateObject(wrappedValue: OnboardingViewModel(container: container))
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 8) {
                        Image(systemName: "antenna.radiowaves.left.and.right.circle.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(AirtelTheme.accent)
                        Text("Airtel Monitor")
                            .font(.title.weight(.bold))
                        Text("Configure your ZLT X17M / W304VA PRO admin connection to monitor 5G/4G telemetry, devices, and router tools.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 12)

                    if viewModel.isWifiConnected, let gw = viewModel.detectedGatewayIp, !gw.isEmpty {
                        Button {
                            viewModel.applyDetectedGateway()
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "wifi")
                                    .foregroundStyle(AirtelTheme.info)
                                Text("Detected gateway: \(gw)")
                                    .font(.caption.weight(.semibold))
                                Spacer()
                                Text("Use")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(AirtelTheme.accent)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        Text("Router Credentials")
                            .font(.headline)

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Router IP or Host")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            TextField("192.168.1.1", text: Binding(
                                get: { viewModel.routerHost },
                                set: { viewModel.updateHost($0) }
                            ))
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Admin Username")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            TextField("user", text: Binding(
                                get: { viewModel.username },
                                set: { viewModel.updateUsername($0) }
                            ))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Admin Password")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            HStack {
                                Group {
                                    if showPassword {
                                        TextField("Enter router admin password", text: Binding(
                                            get: { viewModel.password },
                                            set: { viewModel.updatePassword($0) }
                                        ))
                                    } else {
                                        SecureField("Enter router admin password", text: Binding(
                                            get: { viewModel.password },
                                            set: { viewModel.updatePassword($0) }
                                        ))
                                    }
                                }
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .textFieldStyle(.roundedBorder)

                                Button {
                                    showPassword.toggle()
                                } label: {
                                    Image(systemName: showPassword ? "eye.slash" : "eye")
                                        .foregroundStyle(.secondary)
                                        .padding(8)
                                }
                                .buttonStyle(.plain)
                            }
                        }

                        Text("Passwords are stored exclusively in the encrypted iOS Keychain.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(18)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                    VStack(spacing: 10) {
                        Button {
                            viewModel.testConnection()
                        } label: {
                            HStack {
                                if viewModel.testStatus.isTesting {
                                    ProgressView()
                                        .controlSize(.small)
                                }
                                Text(viewModel.testStatus.isTesting ? "Testing Connection..." : "Test Connection")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .disabled(viewModel.testStatus.isTesting)
                        .liquidGlassSmallButton()

                        Button {
                            let wasComplete = container.preferences.onboardingCompleted
                            viewModel.saveAndContinue()
                            if wasComplete { dismiss() }
                        } label: {
                            Text("Save & Continue")
                                .frame(maxWidth: .infinity)
                        }
                        .liquidGlassActionButton(tint: AirtelTheme.accent)

                        Button("Skip for Now") {
                            let wasComplete = container.preferences.onboardingCompleted
                            viewModel.skipForNow()
                            if wasComplete { dismiss() }
                        }
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                    }

                    // Fixed feedback slot below buttons prevents vertical jumping during verification.
                    feedbackSlot
                        .frame(minHeight: 54, alignment: .top)
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Router Setup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if container.preferences.onboardingCompleted {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Cancel") { dismiss() }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var feedbackSlot: some View {
        switch viewModel.testStatus {
        case .idle:
            EmptyView()
        case .testing:
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                Text("Checking router reachability and SHA-256 credentials...")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        case .connected(let deviceLabel):
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(AirtelTheme.success)
                Text("Connected to \(deviceLabel)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AirtelTheme.success)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(AirtelTheme.success.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        case .wrongPassword(let msg):
            HStack(spacing: 8) {
                Image(systemName: "lock.slash.fill")
                    .foregroundStyle(AirtelTheme.error)
                Text(msg)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AirtelTheme.error)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(AirtelTheme.error.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        case .unreachable(let msg):
            HStack(spacing: 8) {
                Image(systemName: "wifi.exclamationmark")
                    .foregroundStyle(AirtelTheme.warning)
                Text(msg)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AirtelTheme.warning)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(AirtelTheme.warning.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}
