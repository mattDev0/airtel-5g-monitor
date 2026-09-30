import Foundation

/// Pure helpers for the Network Configuration controls, static so tests can reach them.
public enum NetworkControls {
    /// "1"/"0" flag from a router reply; nil when the key is missing or empty.
    static func flag(_ dict: [String: Any], _ key: String) -> Bool? {
        guard let v = dict[key] else { return nil }
        let s = "\(v)"
        return s.isEmpty ? nil : s == "1"
    }

    /// The web UI treats `message == "0"` as success for these commands.
    static func accepted(_ reply: [String: Any]) -> Bool {
        "\(reply["message"] ?? "")" == "0" || reply["success"] as? Bool == true
    }
}

private func networkError(_ message: String) -> Error {
    NSError(domain: "AirtelMonitor", code: 30, userInfo: [NSLocalizedDescriptionKey: message])
}

extension ZltRouterClient {
    /// Turns the cellular data connection on or off (cmd 222). Wi-Fi and the LAN keep working.
    public func setMobileData(_ on: Bool) async -> Result<String, Error> {
        await setNetworkSwitch(cmd: 222, key: "dialMode", on: on, label: "Mobile data")
    }

    /// Flight mode switches the modem's radio off or on (cmd 226).
    public func setFlightMode(_ on: Bool) async -> Result<String, Error> {
        await setNetworkSwitch(cmd: 226, key: "flightMode", on: on, label: "Flight mode")
    }

    private func setNetworkSwitch(cmd: Int, key: String, on: Bool, label: String) async -> Result<String, Error> {
        let value = on ? "1" : "0"
        let token = "\((await query(cmdId: 233))["token"] ?? "")"
        let res = await query(cmdId: cmd, method: "POST", extra: [key: value, "token": token], timeout: 20)
        guard NetworkControls.accepted(res) else {
            let msg = "\(res["message"] ?? "")"
            return .failure(networkError(msg.isEmpty ? "Router rejected the change" : msg))
        }
        for _ in 0..<8 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            let now = await query(cmdId: 218)
            if now["flightMode"] != nil { cachedNetMode218 = now }
            if NetworkControls.flag(now, key) == on {
                return .success("\(label) turned \(on ? "on" : "off"), confirmed by router")
            }
        }
        return .failure(networkError("\(label) was sent, but the router still reports the old setting"))
    }

    /// Makes the modem search for the mobile network again (cmd 288, like the web UI's
    /// re-search button). The connection drops while it searches. Sent through `queryRaw`,
    /// which never repeats a failed request, so it can't start a second search.
    public func rescanNetwork() async -> Result<String, Error> {
        let raw = await queryRaw(cmdId: 288, method: "GET", timeout: 20)
        guard !raw.isEmpty else { return .failure(networkError("No answer from the router")) }
        let reply = (try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any]) ?? [:]
        if NetworkControls.accepted(reply) {
            return .success("Searching for the network. The connection is back in about a minute.")
        }
        let msg = "\(reply["message"] ?? "")"
        return .failure(networkError(msg.isEmpty ? "Router rejected the network search" : msg))
    }
}
