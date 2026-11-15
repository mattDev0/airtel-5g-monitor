import SwiftUI

@main
struct AirtelMonitorApp: App {
    @StateObject private var container = AppContainer()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            AppShellView(container: container)
                .preferredColorScheme(preferredColorScheme)
                .onAppear {
                    container.gatewayDetector.startMonitoring()
                    container.pollingCoordinator.onForegroundChanged(scenePhase == .active)
                }
                .onChange(of: scenePhase) { newPhase in
                    container.pollingCoordinator.onForegroundChanged(newPhase == .active)
                    if newPhase == .active {
                        container.gatewayDetector.refreshNow()
                    }
                }
        }
    }

    private var preferredColorScheme: ColorScheme? {
        switch container.preferences.themePreference {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
