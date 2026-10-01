import SwiftUI

/// Router Settings, same fields as Android's SettingsDialog.
public struct SettingsSheetView: View {
    @ObservedObject var settingsStore: SettingsStore = .shared
    var onSaved: () -> Void = {}
    @Environment(\.dismiss) private var dismiss

    @State private var host = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var interval: Double = 2

    public init(onSaved: @escaping () -> Void = {}) {
        self.onSaved = onSaved
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Gateway IP") {
                        TextField("192.168.1.1", text: $host)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numbersAndPunctuation)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    LabeledContent("Username") {
                        TextField("root", text: $user)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    LabeledContent("Password") {
                        SecureField("admin", text: $pass)
                            .multilineTextAlignment(.trailing)
                    }
                }

                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Poll Rate: \(String(format: "%.1f", interval))s")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Slider(value: $interval, in: 0.5...5.0, step: 0.5)
                    }
                } footer: {
                    Text("App version \(appVersion)")
                }
            }
            .navigationTitle("Router Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        settingsStore.routerHost = host.trimmingCharacters(in: .whitespaces)
                            .replacingOccurrences(of: "http://", with: "")
                            .replacingOccurrences(of: "https://", with: "")
                        settingsStore.username = user.trimmingCharacters(in: .whitespaces)
                        settingsStore.password = pass
                        settingsStore.pollIntervalSeconds = interval
                        onSaved()
                        dismiss()
                    }
                    .disabled(host.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                host = settingsStore.routerHost
                user = settingsStore.username
                pass = settingsStore.password
                interval = settingsStore.pollIntervalSeconds
            }
        }
    }

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}
