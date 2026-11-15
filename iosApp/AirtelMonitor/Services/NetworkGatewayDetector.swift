import Foundation
import Network
import Darwin

public struct WifiGatewayInfo: Equatable, Sendable {
    public let isWifiConnected: Bool
    public let localIpv4: String?
    public let suggestedGatewayIp: String?

    public init(isWifiConnected: Bool, localIpv4: String?, suggestedGatewayIp: String?) {
        self.isWifiConnected = isWifiConnected
        self.localIpv4 = localIpv4
        self.suggestedGatewayIp = suggestedGatewayIp
    }
}

/// Inspects active iOS Wi-Fi interface IPv4/netmask to suggest likely router gateway IPs (`x.y.z.1`) on onboarding.
@MainActor
public final class NetworkGatewayDetector: ObservableObject {
    @Published public private(set) var info = WifiGatewayInfo(
        isWifiConnected: false,
        localIpv4: nil,
        suggestedGatewayIp: nil
    )

    private var monitor: NWPathMonitor?

    public init() {}

    public func startMonitoring() {
        refreshNow()
        guard monitor == nil else { return }
        let pathMonitor = NWPathMonitor()
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let wifi = path.status == .satisfied && path.usesInterfaceType(.wifi)
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.info = Self.inspectInterfaces(isWifiHint: wifi)
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "dev.mattdev0.airtelmonitor.pathmonitor"))
        self.monitor = pathMonitor
    }

    public func stopMonitoring() {
        monitor?.cancel()
        monitor = nil
    }

    public func refreshNow() {
        info = Self.inspectInterfaces(isWifiHint: nil)
    }

    public static func inspectInterfaces(isWifiHint: Bool?) -> WifiGatewayInfo {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else {
            return WifiGatewayInfo(isWifiConnected: isWifiHint ?? false, localIpv4: nil, suggestedGatewayIp: nil)
        }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        var foundIp: String?
        var foundGateway: String?

        while let current = ptr {
            let name = String(cString: current.pointee.ifa_name)
            let flags = Int32(current.pointee.ifa_flags)
            let isUp = (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING)
            if isUp, name == "en0", let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: host)
                    if !ip.hasPrefix("169.254.") && !ip.hasPrefix("127.") {
                        foundIp = ip
                        foundGateway = inferGatewayFromIpv4(ip)
                        break
                    }
                }
            }
            ptr = current.pointee.ifa_next
        }

        let connected = isWifiHint ?? (foundIp != nil)
        return WifiGatewayInfo(
            isWifiConnected: connected,
            localIpv4: foundIp,
            suggestedGatewayIp: foundGateway
        )
    }

    public static func inferGatewayFromIpv4(_ ip: String) -> String? {
        let parts = ip.split(separator: ".")
        guard parts.count == 4,
              parts.allSatisfy({ Int($0) != nil && (0...255).contains(Int($0)!) }) else {
            return nil
        }
        return "\(parts[0]).\(parts[1]).\(parts[2]).1"
    }
}
