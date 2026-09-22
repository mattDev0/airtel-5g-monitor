import Foundation
import SwiftUI
import Combine

@MainActor
public final class MonitorViewModel: ObservableObject {
    @Published public var state: RouterState = RouterState()
    @Published public var isConnected: Bool = false
    @Published public var isRefreshing: Bool = false
    @Published public var errorMessage: String? = nil

    // DNS state
    @Published public var dnsConfig: DnsConfig = DnsConfig()
    @Published public var isDnsLoading: Bool = false
    @Published public var isDnsLoaded: Bool = false
    @Published public var dnsStatusMessage: String? = nil

    // Wi-Fi state
    @Published public var wifiRadios: WifiRadioConfig = WifiRadioConfig()
    @Published public var isWifiLoading: Bool = false
    @Published public var wifiStatusMessage: String? = nil

    // Network Diagnosis state
    @Published public var diagnosisState: DiagnosisState = DiagnosisState()

    // Reboot state
    @Published public var isRebooting: Bool = false
    @Published public var rebootStatusMessage: String? = nil

    private let client: ZltRouterClient
    private let settingsStore: SettingsStore
    private var pollTask: Task<Void, Never>?
    private var diagnosisTask: Task<Void, Never>?

    public init(client: ZltRouterClient? = nil, settingsStore: SettingsStore = .shared) {
        self.settingsStore = settingsStore
        self.client = client ?? ZltRouterClient(settingsStore: settingsStore)
    }

    public func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { break }
                await self.pollOnce()

                let interval = max(1, self.settingsStore.pollIntervalSeconds)
                try? await Task.sleep(nanoseconds: UInt64(interval) * 1_000_000_000)
            }
        }
    }

    public func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    public func pollOnce() async {
        let result = await client.pollMetrics()
        switch result {
        case .success(let newState):
            self.state = newState
            self.isConnected = true
            self.errorMessage = nil
        case .failure(let error):
            self.isConnected = false
            self.errorMessage = error.localizedDescription
        }
    }

    public func reloadAll() async {
        isRefreshing = true
        defer { isRefreshing = false }
        await pollOnce()
        await loadDns()
        await loadWifiRadios()
    }

    // MARK: - DNS Management

    public func loadDns() async {
        isDnsLoading = true
        defer { isDnsLoading = false }
        let (ok, config) = await client.getDns()
        if ok {
            self.dnsConfig = config
            self.isDnsLoaded = true
            self.dnsStatusMessage = nil
        } else {
            self.isDnsLoaded = false
            self.dnsStatusMessage = "Could not read LAN DNS settings from router"
        }
    }

    public func saveDns(primary: String, secondary: String) async {
        isDnsLoading = true
        dnsStatusMessage = "Saving DNS settings..."
        defer { isDnsLoading = false }

        let result = await client.setDns(primary: primary, secondary: secondary)
        await loadDns()
        switch result {
        case .success(let msg):
            dnsStatusMessage = msg
        case .failure(let error):
            dnsStatusMessage = "Error: \(error.localizedDescription)"
        }
    }

    // MARK: - Wi-Fi Radios Management

    public func loadWifiRadios() async {
        isWifiLoading = true
        defer { isWifiLoading = false }
        let (ok, config) = await client.getWifiRadios()
        if ok {
            self.wifiRadios = config
        }
    }

    public func toggleWifi24g(enabled: Bool) async {
        isWifiLoading = true
        wifiStatusMessage = "Switching 2.4 GHz Wi-Fi..."
        defer { isWifiLoading = false }

        let result = await client.setWifi24g(enabled: enabled)
        switch result {
        case .success(let msg):
            wifiStatusMessage = msg
            await loadWifiRadios()
        case .failure(let error):
            wifiStatusMessage = "Error: \(error.localizedDescription)"
            await loadWifiRadios()
        }
    }

    public func toggleWifi5g(enabled: Bool) async {
        isWifiLoading = true
        wifiStatusMessage = "Switching 5 GHz Wi-Fi..."
        defer { isWifiLoading = false }

        let result = await client.setWifi5g(enabled: enabled)
        switch result {
        case .success(let msg):
            wifiStatusMessage = msg
            await loadWifiRadios()
        case .failure(let error):
            wifiStatusMessage = "Error: \(error.localizedDescription)"
            await loadWifiRadios()
        }
    }

    // MARK: - Network Diagnosis

    public func startDiagnosis() {
        guard !diagnosisState.isRunning else { return }
        let mode = diagnosisState.mode
        let target = diagnosisState.target
        let count = diagnosisState.pingCount

        diagnosisState.isRunning = true
        diagnosisState.output = "Starting \(mode.rawValue) to \(target)...\n"
        diagnosisState.statusMessage = nil

        diagnosisTask = Task { [weak self] in
            guard let self = self else { return }
            if mode == .ping {
                let startRes = await self.client.startPing(target: target, count: count)
                switch startRes {
                case .success:
                    var lastOutput = ""
                    var sameCount = 0
                    for _ in 0..<(count * 4 + 10) {
                        if Task.isCancelled { break }
                        try? await Task.sleep(nanoseconds: 800_000_000)
                        let out = await self.client.getPingOutput()
                        if !out.isEmpty {
                            self.diagnosisState.output = out
                            if out == lastOutput {
                                sameCount += 1
                                if sameCount >= 4 && (out.contains("rtt") || out.contains("packets transmitted") || out.contains("packet loss")) {
                                    break
                                }
                            } else {
                                sameCount = 0
                                lastOutput = out
                            }
                        }
                    }
                    self.diagnosisState.isRunning = false
                case .failure(let err):
                    self.diagnosisState.output += "\nError: \(err.localizedDescription)"
                    self.diagnosisState.isRunning = false
                }
            } else {
                let startRes = await self.client.startTrace(target: target)
                switch startRes {
                case .success:
                    for _ in 0..<60 {
                        if Task.isCancelled { break }
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                        let out = await self.client.getTraceOutput()
                        if !out.isEmpty {
                            self.diagnosisState.output = out
                        }
                        let running = await self.client.isTraceRunning()
                        if !running && !out.isEmpty {
                            break
                        }
                    }
                    self.diagnosisState.isRunning = false
                case .failure(let err):
                    self.diagnosisState.output += "\nError: \(err.localizedDescription)"
                    self.diagnosisState.isRunning = false
                }
            }
        }
    }

    public func stopDiagnosis() {
        diagnosisTask?.cancel()
        diagnosisTask = nil
        let mode = diagnosisState.mode
        Task {
            if mode == .ping {
                await client.stopPing()
            } else {
                await client.stopTrace()
            }
            self.diagnosisState.isRunning = false
            self.diagnosisState.output += "\n[Stopped by user]"
        }
    }

    // MARK: - Reboot

    public func reboot() async {
        isRebooting = true
        rebootStatusMessage = "Sending reboot command to router..."
        let ok = await client.reboot()
        if ok {
            rebootStatusMessage = "Router is rebooting. Connection will drop."
            isConnected = false
        } else {
            rebootStatusMessage = "Reboot command was not accepted."
        }
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        isRebooting = false
    }

    // MARK: - Device Alias

    public func setDeviceAlias(identifier: String, alias: String) {
        settingsStore.setAlias(alias, for: identifier)
        Task {
            await self.pollOnce()
        }
    }
}
