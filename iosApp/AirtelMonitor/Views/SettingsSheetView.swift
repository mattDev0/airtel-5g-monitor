import SwiftUI

public struct SettingsSheetView: View {
    @ObservedObject var settingsStore: SettingsStore = .shared
    @Environment(\.dismiss) private var dismiss

    @State private var host: String = ""
    @State private var user: String = ""
    @State private var pass: String = ""
    @State private var interval: Int = 2

    public init() {}

    public var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Router Connection")) {
                    HStack {
                        Text("IP Address")
                        Spacer()
                        TextField("192.168.1.1", text: $host)
                            .multilineTextAlignment(.trailing)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                            .keyboardType(.numbersAndPunctuation)
                    }

                    HStack {
                        Text("Username")
                        Spacer()
                        TextField("root", text: $user)
                            .multilineTextAlignment(.trailing)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    }

                    HStack {
                        Text("Password")
                        Spacer()
                        SecureField("admin", text: $pass)
                            .multilineTextAlignment(.trailing)
                    }
                }

                Section(header: Text("Telemetry Polling")) {
                    Stepper("Interval: \(interval) second\(interval == 1 ? "" : "s")", value: $interval, in: 1...10)
                    Text("Faster intervals provide more responsive speed readings but use slightly more router CPU.")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                Section(header: Text("About")) {
                    HStack {
                        Text("App Version")
                        Spacer()
                        Text("2.0.0 (Native iOS)")
                            .foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Supported Hardware")
                        Spacer()
                        Text("ZLT X17M & W304VA PRO")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        saveSettings()
                        dismiss()
                    }
                    .fontWeight(.bold)
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

    private func saveSettings() {
        settingsStore.routerHost = host.trimmingCharacters(in: .whitespaces)
        settingsStore.username = user.trimmingCharacters(in: .whitespaces)
        settingsStore.password = pass
        settingsStore.pollIntervalSeconds = interval
    }
}
