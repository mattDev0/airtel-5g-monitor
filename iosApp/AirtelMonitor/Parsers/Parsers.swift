import Foundation

// MARK: - Base64 & JSON Helpers

public enum CodecHelpers {
    public static func decodeBase64Utf8(_ raw: String?) -> String {
        let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = Data(base64Encoded: trimmed, options: .ignoreUnknownCharacters) else {
            return ""
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public static func encodeBase64Utf8(_ plain: String) -> String {
        Data(plain.utf8).base64EncodedString()
    }

    public static func str(_ dict: [String: Any], _ key: String, fallback: String = "") -> String {
        let s = RouterSession.str(dict, key)
        return s.isEmpty ? fallback : s
    }

    public static func nonBlankStr(_ dict: [String: Any], _ key: String) -> String? {
        let s = str(dict, key).trimmingCharacters(in: .whitespacesAndNewlines)
        return s.isEmpty || s == "NULL" || s == "null" ? nil : s
    }

    public static func firstNonBlank(_ dict: [String: Any], keys: [String], defaultValue: String = "-") -> String {
        for key in keys {
            if let v = nonBlankStr(dict, key) { return v }
        }
        return defaultValue
    }

    public static func doubleVal(_ raw: String?) -> Double? {
        guard let raw else { return nil }
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "dBm", with: "", options: .caseInsensitive)
            .replacingOccurrences(of: "dB", with: "", options: .caseInsensitive)
            .replacingOccurrences(of: "%", with: "")
            .trimmingCharacters(in: .whitespaces)
        return Double(clean)
    }
}

// MARK: - Carrier Aggregation & Summary

public enum CarrierParser {
    public static func parseCarriers(_ json: [String: Any]) -> [CarrierComponent] {
        var components: [CarrierComponent] = []

        // Primary & aggregated 4G LTE carriers
        let bands4g = splitTokens(CodecHelpers.str(json, "currentband").isEmpty ? CodecHelpers.str(json, "current_band") : CodecHelpers.str(json, "currentband"))
        let freqs4g = splitTokens(CodecHelpers.str(json, "FREQ"))
        let pcis4g = splitTokens(CodecHelpers.str(json, "PCI"))
        let bws4g = splitTokens(CodecHelpers.str(json, "bandwidth"))

        let lteCount = max(bands4g.count, max(freqs4g.count, pcis4g.count))
        for i in 0..<lteCount {
            let band = i < bands4g.count ? normalizeBand(bands4g[i], tech: .lte) : "-"
            let freq = i < freqs4g.count ? freqs4g[i] : "-"
            let pci = i < pcis4g.count ? pcis4g[i] : "-"
            let bw = i < bws4g.count ? Int(bws4g[i].filter { $0.isNumber }) : nil
            if band != "-" || freq != "-" || pci != "-" {
                components.append(CarrierComponent(
                    tech: .lte,
                    band: band,
                    earfcn: freq,
                    pci: pci,
                    bandwidthMHz: bw,
                    isPrimary: i == 0
                ))
            }
        }

        // Secondary 4G CA fields if not already embedded in `+`-delimited lists
        let caBands = splitTokens(CodecHelpers.str(json, "ca_band"))
        let caFreqs = splitTokens(CodecHelpers.str(json, "ca_freq"))
        let caPcis = splitTokens(CodecHelpers.str(json, "ca_pci"))
        let caBws = splitTokens(CodecHelpers.str(json, "ca_bandwidth"))
        let extraLte = max(caBands.count, max(caFreqs.count, caPcis.count))
        for i in 0..<extraLte {
            let band = i < caBands.count ? normalizeBand(caBands[i], tech: .lte) : "-"
            let freq = i < caFreqs.count ? caFreqs[i] : "-"
            let pci = i < caPcis.count ? caPcis[i] : "-"
            let bw = i < caBws.count ? Int(caBws[i].filter { $0.isNumber }) : nil
            if band != "-" || freq != "-" || pci != "-" {
                components.append(CarrierComponent(
                    tech: .lte,
                    band: band,
                    earfcn: freq,
                    pci: pci,
                    bandwidthMHz: bw,
                    isPrimary: false
                ))
            }
        }

        // 5G NR carriers
        let bands5g = splitTokens(CodecHelpers.str(json, "currentband_5g"))
        let freqs5g = splitTokens(CodecHelpers.str(json, "FREQ_5G"))
        let pcis5g = splitTokens(CodecHelpers.str(json, "PCI_5G"))
        let bws5g = splitTokens(CodecHelpers.str(json, "bandwidth_5g"))

        let nrCount = max(bands5g.count, max(freqs5g.count, pcis5g.count))
        for i in 0..<nrCount {
            let band = i < bands5g.count ? normalizeBand(bands5g[i], tech: .nr) : "-"
            let freq = i < freqs5g.count ? freqs5g[i] : "-"
            let pci = i < pcis5g.count ? pcis5g[i] : "-"
            let bw = i < bws5g.count ? Int(bws5g[i].filter { $0.isNumber }) : nil
            if band != "-" || freq != "-" || pci != "-" {
                components.append(CarrierComponent(
                    tech: .nr,
                    band: band,
                    earfcn: freq,
                    pci: pci,
                    bandwidthMHz: bw,
                    isPrimary: i == 0
                ))
            }
        }

        return components
    }

    public static func formatCaSummary(_ carriers: [CarrierComponent]) -> String {
        guard !carriers.isEmpty else { return "-" }
        let bandList = carriers.map(\.band).filter { !$0.isEmpty && $0 != "-" }
        let bandsText = bandList.isEmpty ? "-" : bandList.joined(separator: " + ")
        let totalBw = carriers.compactMap(\.bandwidthMHz).reduce(0, +)
        if totalBw > 0 {
            return "\(bandsText) · \(totalBw) MHz"
        }
        return bandsText
    }

    public static func formatBandPill(_ carriers: [CarrierComponent]) -> String {
        let bandList = carriers.map(\.band).filter { !$0.isEmpty && $0 != "-" }
        return bandList.isEmpty ? "-" : bandList.joined(separator: "+")
    }

    public static func formatCellPill(_ carriers: [CarrierComponent]) -> String {
        let nrPrimary = carriers.first(where: { $0.tech == .nr && $0.isPrimary })
        let ltePrimary = carriers.first(where: { $0.tech == .lte && $0.isPrimary })
        let pci = (nrPrimary?.pci ?? "").isEmpty || nrPrimary?.pci == "-"
            ? (ltePrimary?.pci ?? "-")
            : (nrPrimary?.pci ?? "-")
        if carriers.count > 1 {
            return "PCI \(pci) · \(carriers.count)CC"
        }
        return pci == "-" ? "-" : "PCI \(pci)"
    }

    private static func splitTokens(_ raw: String) -> [String] {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0 == "+" || $0 == "," || $0 == " " })
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private static func normalizeBand(_ raw: String, tech: CarrierTech) -> String {
        let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean != "-" else { return "-" }
        let up = clean.uppercased()
        switch tech {
        case .lte:
            return up.hasPrefix("B") ? up : "B\(up)"
        case .nr:
            return up.hasPrefix("N") ? up : "n\(up)"
        }
    }
}

// MARK: - Core Telemetry Parsers

public enum RouterParsers {
    public static func parseDnsConfig(_ json: [String: Any]) -> DnsConfig {
        DnsConfig(
            primary: CodecHelpers.str(json, "main_dns"),
            secondary: CodecHelpers.str(json, "vice_dns"),
            dhcpEnabled: CodecHelpers.str(json, "dhcpServer", fallback: "1") == "1"
        )
    }

    public static func parseLanConfig(_ json: [String: Any]) -> LanConfig {
        LanConfig(
            lanIp: CodecHelpers.firstNonBlank(json, keys: ["lanIp"], defaultValue: "192.168.1.1"),
            netMask: CodecHelpers.firstNonBlank(json, keys: ["netMask"], defaultValue: "255.255.255.0"),
            dhcpEnabled: CodecHelpers.str(json, "dhcpServer", fallback: "1") == "1",
            ipBegin: CodecHelpers.firstNonBlank(json, keys: ["ipBegin"], defaultValue: "192.168.1.100"),
            ipEnd: CodecHelpers.firstNonBlank(json, keys: ["ipEnd"], defaultValue: "192.168.1.200"),
            expireTime: CodecHelpers.firstNonBlank(json, keys: ["expireTime"], defaultValue: "24h"),
            primaryDns: CodecHelpers.str(json, "main_dns"),
            secondaryDns: CodecHelpers.str(json, "vice_dns"),
            ipv6PrimaryDns: CodecHelpers.str(json, "ipv6_main_dns"),
            ipv6SecondaryDns: CodecHelpers.str(json, "ipv6_vice_dns")
        )
    }

    public static func parseWifiConfig(json24g: [String: Any], json5g: [String: Any]) -> WifiRadioConfig {
        WifiRadioConfig(
            enabled24g: CodecHelpers.str(json24g, "wifiOpen", fallback: "1") == "1",
            ssid24g: CodecHelpers.decodeBase64Utf8(CodecHelpers.str(json24g, "ssid")),
            channel24g: CodecHelpers.str(json24g, "channel"),
            enabled5g: CodecHelpers.str(json5g, "wifiOpen", fallback: "1") == "1",
            ssid5g: CodecHelpers.decodeBase64Utf8(CodecHelpers.str(json5g, "ssid")),
            channel5g: CodecHelpers.str(json5g, "channel")
        )
    }

    public static func parseWifiBandStatus(_ json: [String: Any]) -> WifiBandStatus {
        let broadcastVal = CodecHelpers.str(json, "broadcast", fallback: "1")
        return WifiBandStatus(
            enabled: CodecHelpers.str(json, "wifiOpen", fallback: "1") == "1",
            ssid: CodecHelpers.decodeBase64Utf8(CodecHelpers.str(json, "ssid")),
            key: CodecHelpers.str(json, "key"),
            authenticationType: CodecHelpers.firstNonBlank(json, keys: ["authenticationType"], defaultValue: "2"),
            visibleBroadcast: broadcastVal != "0",
            channel: CodecHelpers.firstNonBlank(json, keys: ["channel"], defaultValue: "0"),
            wifiSames: CodecHelpers.str(json, "wifiSames") == "1",
            maxStaNum: CodecHelpers.str(json, "maxStaNum")
        )
    }

    public static func parseBandLock(_ json: [String: Any]) -> BandLockConfig {
        var cfg = BandLockConfig()
        cfg.lock4gEnabled = CodecHelpers.str(json, "band_4g_switch") == "1"
        cfg.supported4g = BandMask.decode(CodecHelpers.str(json, "all_band_4g")).sorted()
        cfg.locked4g = BandMask.decode(CodecHelpers.str(json, "band_4g_mask"))
        cfg.lock5gEnabled = CodecHelpers.str(json, "band_5g_switch") == "1"
        cfg.supported5g = BandMask.decode(CodecHelpers.str(json, "all_band_5g")).sorted()
        cfg.locked5g = BandMask.decode(CodecHelpers.str(json, "band_5g_mask"))
        return cfg
    }

    public static func parseCellLock(_ json: [String: Any]) -> CellLockConfig {
        var cfg = CellLockConfig()
        cfg.lteEnabled = CodecHelpers.str(json, "lte_lock_sw") == "1"
        let lteFreqs = splitCommaOrPlus(CodecHelpers.str(json, "lte_lock_freq"))
        let ltePcis = splitCommaOrPlus(CodecHelpers.str(json, "lte_lock_pci"))
        let lteCount = max(lteFreqs.count, ltePcis.count)
        for i in 0..<lteCount {
            let f = i < lteFreqs.count ? lteFreqs[i] : ""
            let p = i < ltePcis.count ? ltePcis[i] : ""
            if !f.isEmpty {
                cfg.lteCells.append(LteLockCell(earfcn: f, pci: p))
            }
        }
        let lock4gFlag = CodecHelpers.str(json, "lock_4g_flag")
        if !cfg.lteEnabled {
            cfg.lteState = .unlocked
        } else if lock4gFlag == "2" {
            cfg.lteState = .failed
        } else {
            cfg.lteState = .locked
        }

        cfg.nrEnabled = CodecHelpers.str(json, "nr_lock_sw") == "1"
        let nrFreqs = splitCommaOrPlus(CodecHelpers.str(json, "nr_lock_freq"))
        let nrPcis = splitCommaOrPlus(CodecHelpers.str(json, "nr_lock_pci"))
        let nrBands = splitCommaOrPlus(CodecHelpers.str(json, "nr_lock_cell_band"))
        let nrCount = max(nrFreqs.count, max(nrPcis.count, nrBands.count))
        for i in 0..<nrCount {
            let b = i < nrBands.count ? nrBands[i] : ""
            let f = i < nrFreqs.count ? nrFreqs[i] : ""
            let p = i < nrPcis.count ? nrPcis[i] : ""
            if !f.isEmpty || !b.isEmpty {
                cfg.nrCells.append(NrLockCell(band: b, arfcn: f, pci: p))
            }
        }
        let lock5gFlag = CodecHelpers.str(json, "lock_5g_flag")
        if !cfg.nrEnabled {
            cfg.nrState = .unlocked
        } else if lock5gFlag == "2" {
            cfg.nrState = .failed
        } else {
            cfg.nrState = .locked
        }

        let cur4gFreq = splitCommaOrPlus(CodecHelpers.str(json, "FREQ")).first ?? ""
        let cur4gPci = splitCommaOrPlus(CodecHelpers.str(json, "PCI")).first ?? ""
        if !cur4gFreq.isEmpty && cur4gFreq != "-" {
            cfg.current4g = LteLockCell(earfcn: cur4gFreq, pci: cur4gPci)
        }
        let cur5gBand = splitCommaOrPlus(CodecHelpers.str(json, "current_band_5g").isEmpty ? CodecHelpers.str(json, "currentband_5g") : CodecHelpers.str(json, "current_band_5g")).first ?? ""
        let cur5gFreq = splitCommaOrPlus(CodecHelpers.str(json, "FREQ_5G")).first ?? ""
        let cur5gPci = splitCommaOrPlus(CodecHelpers.str(json, "PCI_5G")).first ?? ""
        if !cur5gFreq.isEmpty && cur5gFreq != "-" {
            cfg.current5g = NrLockCell(band: cur5gBand, arfcn: cur5gFreq, pci: cur5gPci)
        }
        return cfg
    }

    public static func parseLatestChartRow(_ json1018: [String: Any]) -> [String: Any]? {
        guard let arr = json1018["device_info"] as? [[String: Any]], let last = arr.last else {
            return nil
        }
        return last
    }

    public static func parseHardwareMetrics(chartRow: [String: Any]?, deviceInfo207: [String: Any]? = nil) -> HardwareMetrics {
        let cpuStr = CodecHelpers.str(chartRow ?? [:], "cpu_usage").isEmpty
            ? CodecHelpers.str(deviceInfo207 ?? [:], "cpu_usage")
            : CodecHelpers.str(chartRow ?? [:], "cpu_usage")
        let cpuVal = CodecHelpers.doubleVal(cpuStr) ?? 0.0
        let temp = CodecHelpers.nonBlankStr(chartRow ?? [:], "temperature")
            ?? CodecHelpers.nonBlankStr(deviceInfo207 ?? [:], "device_temperature")
            ?? "--"
        let mem = CodecHelpers.nonBlankStr(chartRow ?? [:], "memory")
            ?? CodecHelpers.nonBlankStr(deviceInfo207 ?? [:], "memory")
            ?? "--"
        return HardwareMetrics(cpuUsage: cpuVal, temperature: temp, memory: mem)
    }

    public static func parseSignalBarsLevel(
        net205: [String: Any],
        statusBar394: [String: Any] = [:],
        sysStatus: [String: Any] = [:],
        networkType: String = ""
    ) -> Int {
        let raw205 = Int(CodecHelpers.str(net205, "signal_lvl"))
        let raw394 = Int(CodecHelpers.str(statusBar394, "signal_lvl"))
        let raw113 = Int(CodecHelpers.str(sysStatus, "signal_lvl"))
        let candidate = raw205 ?? raw394 ?? raw113 ?? 0
        let clamped = min(max(candidate, 0), 5)
        let typeClean = networkType.trimmingCharacters(in: .whitespacesAndNewlines)
        if clamped == 0 && !typeClean.isEmpty && typeClean != "-" && typeClean != "--" &&
            typeClean.caseInsensitiveCompare("No Service") != .orderedSame {
            return 1
        }
        return clamped
    }

    public static func parseCellularMetrics(
        net205: [String: Any],
        dash401: [String: Any] = [:],
        sysStatus113: [String: Any] = [:],
        statusBar394: [String: Any] = [:],
        routerInfo133: [String: Any] = [:],
        deviceInfo207: [String: Any] = [:],
        chartRow: [String: Any]? = nil
    ) -> CellularMetrics {
        let netType = CodecHelpers.nonBlankStr(net205, "network_type_str")
            ?? CodecHelpers.nonBlankStr(statusBar394, "network_type_str")
            ?? CodecHelpers.nonBlankStr(dash401, "network_type_str")
            ?? CodecHelpers.nonBlankStr(routerInfo133, "network_type_str")
            ?? CodecHelpers.nonBlankStr(sysStatus113, "network_type_str")
            ?? "-"
        let bars = parseSignalBarsLevel(
            net205: net205,
            statusBar394: statusBar394,
            sysStatus: sysStatus113,
            networkType: netType
        )
        return CellularMetrics(
            networkType: netType,
            signalLvl: bars,
            rsrp5g: CodecHelpers.nonBlankStr(net205, "RSRP_5G")
                ?? CodecHelpers.nonBlankStr(chartRow ?? [:], "rsrp_5g")
                ?? "-",
            sinr5g: CodecHelpers.nonBlankStr(net205, "SINR_5G")
                ?? CodecHelpers.nonBlankStr(chartRow ?? [:], "sinr_5g")
                ?? "-",
            rsrp4g: CodecHelpers.nonBlankStr(net205, "RSRP")
                ?? CodecHelpers.nonBlankStr(routerInfo133, "RSRP")
                ?? CodecHelpers.nonBlankStr(chartRow ?? [:], "rsrp_4g")
                ?? "-",
            sinr4g: CodecHelpers.nonBlankStr(net205, "SINR")
                ?? CodecHelpers.nonBlankStr(routerInfo133, "SINR")
                ?? CodecHelpers.nonBlankStr(chartRow ?? [:], "sinr_4g")
                ?? "-",
            operatorName: CodecHelpers.nonBlankStr(net205, "network_operator")
                ?? CodecHelpers.nonBlankStr(dash401, "network_operator")
                ?? "Airtel",
            boardType: CodecHelpers.nonBlankStr(deviceInfo207, "board_type")
                ?? CodecHelpers.nonBlankStr(dash401, "board_type")
                ?? "ZLT X17M",
            iduType: CodecHelpers.nonBlankStr(deviceInfo207, "idu_dev_type")
                ?? "ZLT W304VA PRO",
            nrCqi: CodecHelpers.nonBlankStr(net205, "CQI_5G")
                ?? CodecHelpers.nonBlankStr(chartRow ?? [:], "nr_cqi")
                ?? "-",
            lteCqi: CodecHelpers.nonBlankStr(net205, "CQI")
                ?? CodecHelpers.nonBlankStr(routerInfo133, "CQI")
                ?? CodecHelpers.nonBlankStr(chartRow ?? [:], "lte_cqi")
                ?? "-",
            nrQamDl: CodecHelpers.nonBlankStr(net205, "max_dl_qam_5g")
                ?? CodecHelpers.nonBlankStr(chartRow ?? [:], "nr_qam_dl")
                ?? "-",
            rsrq5g: CodecHelpers.nonBlankStr(net205, "RSRQ_5G") ?? "-",
            rssi5g: CodecHelpers.nonBlankStr(net205, "RSSI_5G") ?? "-",
            rsrq4g: CodecHelpers.nonBlankStr(net205, "RSRQ")
                ?? CodecHelpers.nonBlankStr(routerInfo133, "RSRQ")
                ?? "-",
            rssi4g: CodecHelpers.nonBlankStr(net205, "RSSI")
                ?? CodecHelpers.nonBlankStr(routerInfo133, "RSSI")
                ?? "-"
        )
    }

    public static func parseSmsUnreadCount(_ statusBar394: [String: Any]) -> Int {
        max(0, Int(CodecHelpers.str(statusBar394, "sms_unread")) ?? 0)
    }

    public static func parseNetworkModeSnapshot(_ json218: [String: Any], statusBar394: [String: Any] = [:]) -> NetworkModeSnapshot {
        let dataStr = CodecHelpers.nonBlankStr(json218, "dialMode") ?? CodecHelpers.nonBlankStr(statusBar394, "data_switch")
        let flightStr = CodecHelpers.nonBlankStr(json218, "flightMode") ?? CodecHelpers.nonBlankStr(statusBar394, "flightMode")
        return NetworkModeSnapshot(
            mobileDataOn: dataStr.map { $0 == "1" },
            flightModeOn: flightStr.map { $0 == "1" },
            networkMode: CodecHelpers.str(json218, "networkMode"),
            mode5g: CodecHelpers.str(json218, "mode5g"),
            roamingEnable: CodecHelpers.str(json218, "roamingEnable") == "1",
            lteCa: CodecHelpers.str(json218, "lteCA", fallback: "1") == "1",
            nrCa: CodecHelpers.str(json218, "nrCA", fallback: "1") == "1"
        )
    }

    public static func parseDevices(
        dhcp: [String: Any],
        wifi5g: [String: Any],
        wifi24g: [String: Any],
        aliasLookup: (_ identifier: String, _ fallback: String) -> String,
        hasAliasLookup: (_ identifier: String) -> Bool = { _ in false },
        ouiLookup: OuiVendorLookup = .empty
    ) -> [DeviceItem] {
        var wifi5Map: [String: [String: Any]] = [:]
        if let list5 = wifi5g["wlan5g_wifi_info"] as? [[String: Any]] {
            for row in list5 {
                let mac = CodecHelpers.str(row, "mac").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if !mac.isEmpty { wifi5Map[mac] = row }
            }
        }

        var wifi24Map: [String: [String: Any]] = [:]
        if let list24 = (wifi24g["wlan24g_wifi_info"] as? [[String: Any]]) ?? (wifi24g["wlan2g_wifi_info"] as? [[String: Any]]) {
            for row in list24 {
                let mac = CodecHelpers.str(row, "mac").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if !mac.isEmpty { wifi24Map[mac] = row }
            }
        }

        var result: [DeviceItem] = []
        var seenMacs = Set<String>()
        let dhcpRows = (dhcp["dhcp_list_info"] as? [[String: Any]]) ?? []

        for row in dhcpRows {
            let rawMac = CodecHelpers.str(row, "mac").trimmingCharacters(in: .whitespacesAndNewlines)
            let macLower = rawMac.lowercased()
            let macUpper = rawMac.uppercased()
            if !macLower.isEmpty { seenMacs.insert(macLower) }

            let rawHost = CodecHelpers.str(row, "hostname").trimmingCharacters(in: .whitespacesAndNewlines)
            let hostname = (rawHost.isEmpty || rawHost.caseInsensitiveCompare("NULL") == .orderedSame) ? "Unknown Device" : rawHost
            let ip = CodecHelpers.str(row, "ip")
            let iface = CodecHelpers.str(row, "interface", fallback: "wlan")
            let expires = CodecHelpers.str(row, "expires")
            let ipv6 = CodecHelpers.str(row, "ipv6")

            let w5 = wifi5Map[macLower]
            let w24 = wifi24Map[macLower]
            let wifiRow = w5 ?? w24

            let rssi = wifiRow.flatMap { CodecHelpers.nonBlankStr($0, "rssi") } ?? ""
            let txRate = wifiRow.flatMap { CodecHelpers.nonBlankStr($0, "txrate") } ?? "-"
            let rxRate = wifiRow.flatMap { CodecHelpers.nonBlankStr($0, "rxrate") } ?? "-"
            let ssid = wifiRow.flatMap { CodecHelpers.nonBlankStr($0, "ssid") } ?? ""

            let isLan = iface.localizedCaseInsensitiveContains("lan") || iface.localizedCaseInsensitiveContains("eth")
            let bandLabel: String
            if w5 != nil {
                bandLabel = "5 GHz"
            } else if w24 != nil {
                bandLabel = "2.4 GHz"
            } else if isLan {
                bandLabel = "Ethernet (LAN)"
            } else {
                bandLabel = "Wi-Fi"
            }

            let sigPct: Int
            if isLan {
                sigPct = 100
            } else if let rssiNum = Int(rssi) {
                sigPct = min(max(2 * (rssiNum + 100), 0), 100)
            } else {
                sigPct = 0
            }

            let match = ouiLookup.lookup(macUpper)
            let vendorName = match.vendor ?? ""
            let online = determineOnline(isLan: isLan, expires: expires, hasWifiAssoc: wifiRow != nil, rssi: rssi, txRate: txRate, rxRate: rxRate)
            let devType = inferDeviceType(hostname: hostname, vendor: vendorName, ip: ip)

            let identifier = macUpper.isEmpty ? ip : macUpper
            let customAlias = aliasLookup(identifier, hostname)
            let hasCustom = hasAliasLookup(identifier) || (customAlias != hostname)

            result.append(DeviceItem(
                mac: macUpper,
                ip: ip,
                hostname: hostname,
                alias: customAlias,
                type: devType,
                band: bandLabel,
                ssid: ssid,
                rssi: rssi,
                signalPercent: sigPct,
                txRateMbps: txRate,
                rxRateMbps: rxRate,
                interfaceName: iface,
                expires: expires,
                ipv6: ipv6,
                isRouter: devType == .router,
                vendor: vendorName,
                isPrivateMac: match.isPrivateMac,
                isOnline: online,
                hasCustomAlias: hasCustom
            ))
        }

        // Also include Wi-Fi associated clients missing from DHCP table
        let extraWifi = Array(wifi5Map.keys) + Array(wifi24Map.keys)
        for macLower in extraWifi where !seenMacs.contains(macLower) {
            seenMacs.insert(macLower)
            let is5g = wifi5Map[macLower] != nil
            guard let row = wifi5Map[macLower] ?? wifi24Map[macLower] else { continue }
            let macUpper = macLower.uppercased()
            let ip = CodecHelpers.str(row, "ip")
            let rssi = CodecHelpers.str(row, "rssi")
            let txRate = CodecHelpers.nonBlankStr(row, "txrate") ?? "-"
            let rxRate = CodecHelpers.nonBlankStr(row, "rxrate") ?? "-"
            let ssid = CodecHelpers.str(row, "ssid")
            let sigPct = Int(rssi).map { min(max(2 * ($0 + 100), 0), 100) } ?? 0
            let match = ouiLookup.lookup(macUpper)
            let vendorName = match.vendor ?? ""
            let fallbackName = vendorName.isEmpty ? "Wi-Fi Client" : vendorName
            let identifier = macUpper.isEmpty ? ip : macUpper

            result.append(DeviceItem(
                mac: macUpper,
                ip: ip,
                hostname: fallbackName,
                alias: aliasLookup(identifier, fallbackName),
                type: inferDeviceType(hostname: fallbackName, vendor: vendorName, ip: ip),
                band: is5g ? "5 GHz" : "2.4 GHz",
                ssid: ssid,
                rssi: rssi,
                signalPercent: sigPct,
                txRateMbps: txRate,
                rxRateMbps: rxRate,
                interfaceName: "wlan",
                expires: "",
                ipv6: "",
                isRouter: false,
                vendor: vendorName,
                isPrivateMac: match.isPrivateMac,
                isOnline: true,
                hasCustomAlias: hasAliasLookup(identifier)
            ))
        }

        return result
    }

    public static func determineOnline(
        isLan: Bool,
        expires: String,
        hasWifiAssoc: Bool,
        rssi: String,
        txRate: String,
        rxRate: String
    ) -> Bool {
        if expires.trimmingCharacters(in: .whitespacesAndNewlines) == "0" { return false }
        if isLan { return true }
        return hasWifiAssoc || !rssi.isEmpty || (txRate != "-" && txRate != "0") || (rxRate != "-" && rxRate != "0")
    }

    public static func inferDeviceType(hostname: String, vendor: String, ip: String) -> DeviceType {
        let lowHost = hostname.lowercased()
        let lowVendor = vendor.lowercased()
        if ip.hasSuffix(".1") || lowHost.contains("router") || lowHost.contains("gateway") || lowHost.contains("zlt") {
            return .router
        }
        if lowHost.contains("iphone") || lowHost.contains("pixel") || lowHost.contains("galaxy") || lowHost.contains("android") || lowHost.contains("redmi") || lowHost.contains("tecno") || lowHost.contains("infinix") {
            return .phone
        }
        if lowHost.contains("ipad") || lowHost.contains("tab") {
            return .tablet
        }
        if lowHost.contains("macbook") || lowHost.contains("imac") || lowHost.contains("laptop") || lowHost.contains("desktop") || lowHost.contains("pc") || lowHost.contains("thinkpad") {
            return .pc
        }
        if lowHost.contains("tv") || lowHost.contains("roku") || lowHost.contains("chromecast") || lowHost.contains("bravia") {
            return .tv
        }
        if lowHost.contains("playstation") || lowHost.contains("ps5") || lowHost.contains("xbox") || lowHost.contains("nintendo") || lowHost.contains("switch") {
            return .gaming
        }
        if ["apple", "samsung", "google", "xiaomi", "oppo", "vivo", "oneplus", "motorola", "transsion"].contains(where: { lowVendor.contains($0) }) {
            return .phone
        }
        if ["dell", "lenovo", "hp", "asus", "acer", "intel", "microsoft"].contains(where: { lowVendor.contains($0) }) {
            return .pc
        }
        if ["sony", "nintendo"].contains(where: { lowVendor.contains($0) }) {
            return .gaming
        }
        if ["tcl", "hisense", "roku", "lg"].contains(where: { lowVendor.contains($0) }) {
            return .tv
        }
        if ["tp-link", "netgear", "d-link", "ubiquiti", "mikrotik", "cisco", "linksys", "tenda", "mercusys", "zte", "tozed"].contains(where: { lowVendor.contains($0) }) {
            return .router
        }
        return .device
    }

    private static func splitCommaOrPlus(_ raw: String) -> [String] {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0 == "," || $0 == "+" || $0 == " " })
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}

// MARK: - Phase 1+ Parsers

public enum Phase1Parsers {
    public static func parseLinkDetails(_ net205: [String: Any], routerInfo133: [String: Any] = [:]) -> LinkDetails {
        let dlMcs4g = CodecHelpers.nonBlankStr(net205, "dl_mcs")
            ?? CodecHelpers.nonBlankStr(routerInfo133, "dl_mcs")
            ?? "-"
        let ulMcs4g = CodecHelpers.nonBlankStr(net205, "ul_mcs")
            ?? CodecHelpers.nonBlankStr(routerInfo133, "ul_mcs")
            ?? "-"
        return LinkDetails(
            dlMcs4g: dlMcs4g,
            ulMcs4g: ulMcs4g,
            dlMcs5g: CodecHelpers.firstNonBlank(net205, keys: ["dl_mcs_5g", "nr_dl_mcs"]),
            ulMcs5g: CodecHelpers.firstNonBlank(net205, keys: ["ul_mcs_5g", "nr_ul_mcs"]),
            maxDlQam4g: CodecHelpers.firstNonBlank(net205, keys: ["max_dl_qam", "dl_qam"]),
            maxUlQam4g: CodecHelpers.firstNonBlank(net205, keys: ["max_ul_qam", "ul_qam"]),
            maxDlQam5g: CodecHelpers.firstNonBlank(net205, keys: ["max_dl_qam_5g", "dl_qam_5g"]),
            maxUlQam5g: CodecHelpers.firstNonBlank(net205, keys: ["max_ul_qam_5g", "ul_qam_5g"]),
            rank4g: CodecHelpers.firstNonBlank(net205, keys: ["rank_4g", "rank"]),
            rank5g: CodecHelpers.firstNonBlank(net205, keys: ["rank_5g", "nr_rank"]),
            mimoStatus: CodecHelpers.firstNonBlank(net205, keys: ["mimo", "mimo_status"]),
            bler4g: CodecHelpers.firstNonBlank(net205, keys: ["bler_4g", "bler"]),
            bler5g: CodecHelpers.firstNonBlank(net205, keys: ["bler_5g", "nr_bler"])
        )
    }

    public static func parseCellIdentity(_ net205: [String: Any], routerInfo133: [String: Any] = [:]) -> CellIdentity {
        let cellId4g = CodecHelpers.nonBlankStr(net205, "CELL_ID")
            ?? CodecHelpers.nonBlankStr(routerInfo133, "CELL_ID")
            ?? "-"
        let plmn = CodecHelpers.firstNonBlank(net205, keys: ["PLMN", "plmn"], defaultValue: CodecHelpers.firstNonBlank(routerInfo133, keys: ["PLMN", "plmn"]))
        let ecgi = CodecHelpers.firstNonBlank(net205, keys: ["ECGI"], defaultValue: (plmn != "-" && cellId4g != "-") ? "\(plmn)-\(cellId4g)" : "-")
        return CellIdentity(
            plmn: plmn,
            enbId4g: CodecHelpers.firstNonBlank(net205, keys: ["ENODEBID", "enodebid"]),
            cellId4g: cellId4g,
            tac4g: CodecHelpers.firstNonBlank(net205, keys: ["tac_4g", "TAC"]),
            ecgi: ecgi,
            enbId5g: CodecHelpers.firstNonBlank(net205, keys: ["ENODEBID_5G", "gnb_id"]),
            cellId5g: CodecHelpers.firstNonBlank(net205, keys: ["CELL_ID_5G", "nr_cell_id"]),
            tac5g: CodecHelpers.firstNonBlank(net205, keys: ["tac_5g", "TAC_5G"]),
            ncgi: CodecHelpers.firstNonBlank(net205, keys: ["NCGI", "ncgi"])
        )
    }

    public static func parseNeighborCells(_ json282: [String: Any]) -> [NeighborCell] {
        var cells: [NeighborCell] = []
        cells.append(contentsOf: parseNeighborField(json282["lte_info"], tech: .lte))
        cells.append(contentsOf: parseNeighborField(json282["nr_info"], tech: .nr))
        return cells
    }

    private static func parseNeighborField(_ value: Any?, tech: CarrierTech) -> [NeighborCell] {
        if let arr = value as? [[String: Any]] {
            return arr.compactMap { obj in
                let earfcn = CodecHelpers.firstNonBlank(obj, keys: ["arfcn", "earfcn", "freq", "FREQ"], defaultValue: "")
                let pci = CodecHelpers.firstNonBlank(obj, keys: ["pci", "PCI"], defaultValue: "")
                guard !earfcn.isEmpty || !pci.isEmpty else { return nil }
                return NeighborCell(
                    tech: tech,
                    earfcn: earfcn.isEmpty ? "-" : earfcn,
                    pci: pci.isEmpty ? "-" : pci,
                    rsrp: CodecHelpers.firstNonBlank(obj, keys: ["rsrp", "RSRP"]),
                    rsrq: CodecHelpers.firstNonBlank(obj, keys: ["rsrq", "RSRQ"]),
                    sinr: CodecHelpers.firstNonBlank(obj, keys: ["sinr", "SINR"])
                )
            }
        }
        if let raw = value as? String {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return [] }
            return trimmed
                .split(whereSeparator: { $0 == ";" || $0 == "|" })
                .compactMap { entry -> NeighborCell? in
                    let parts = entry.split(separator: ",", omittingEmptySubsequences: false)
                        .map { String($0).trimmingCharacters(in: .whitespaces) }
                    guard parts.count >= 3 else { return nil }
                    return NeighborCell(
                        tech: tech,
                        earfcn: parts[0].isEmpty ? "-" : parts[0],
                        pci: parts[1].isEmpty ? "-" : parts[1],
                        rsrp: parts[2].isEmpty ? "-" : parts[2],
                        rsrq: parts.count > 3 && !parts[3].isEmpty ? parts[3] : "-",
                        sinr: parts.count > 4 && !parts[4].isEmpty ? parts[4] : "-"
                    )
                }
        }
        return []
    }

    public static func parseSignalHistory1018(_ rows: [[String: Any]]) -> [SignalHistoryPoint] {
        rows.compactMap { row in
            let time = CodecHelpers.nonBlankStr(row, "time") ?? ""
            let r4 = CodecHelpers.doubleVal(CodecHelpers.str(row, "rsrp_4g"))
            let s4 = CodecHelpers.doubleVal(CodecHelpers.str(row, "sinr_4g"))
            let r5 = CodecHelpers.doubleVal(CodecHelpers.str(row, "rsrp_5g"))
            let s5 = CodecHelpers.doubleVal(CodecHelpers.str(row, "sinr_5g"))
            guard r4 != nil || s4 != nil || r5 != nil || s5 != nil else { return nil }
            return SignalHistoryPoint(
                timeLabel: time,
                rsrp4g: r4,
                sinr4g: s4,
                rsrp5g: r5,
                sinr5g: s5,
                pci: CodecHelpers.str(row, "pci")
            )
        }
    }

    public static func parseAlignmentSnapshot(
        _ net205: [String: Any],
        carriers: [CarrierComponent],
        timestampMs: Int64
    ) -> AlignmentSnapshot {
        let netType = CodecHelpers.firstNonBlank(net205, keys: ["network_type_str"])
        let ca = CarrierParser.formatCaSummary(carriers)
        return AlignmentSnapshot(
            networkType: netType,
            carrierSummary: ca,
            rsrp5g: CodecHelpers.doubleVal(CodecHelpers.str(net205, "RSRP_5G")),
            sinr5g: CodecHelpers.doubleVal(CodecHelpers.str(net205, "SINR_5G")),
            rsrp4g: CodecHelpers.doubleVal(CodecHelpers.str(net205, "RSRP")),
            sinr4g: CodecHelpers.doubleVal(CodecHelpers.str(net205, "SINR")),
            timestampMs: timestampMs
        )
    }

    public static func parseMonthlyUsage(
        net205: [String: Any],
        dash401: [String: Any] = [:],
        traffic337: [String: Any] = [:],
        date: Date = Date()
    ) -> MonthlyUsageSummary {
        // ZLT X17M swaps dl_mon_flow and ul_mon_flow on cmd 337: ul_mon_flow = download, dl_mon_flow = upload.
        let dlMonthMb = CodecHelpers.doubleVal(CodecHelpers.str(traffic337, "ul_mon_flow")) ?? 0.0
        let ulMonthMb = CodecHelpers.doubleVal(CodecHelpers.str(traffic337, "dl_mon_flow")) ?? 0.0

        let monTotal = CodecHelpers.doubleVal(CodecHelpers.str(net205, "mon_total_flow"))
            ?? CodecHelpers.doubleVal(CodecHelpers.str(dash401, "mon_total_flow"))
            ?? (dlMonthMb + ulMonthMb)

        let flowDl = CodecHelpers.doubleVal(CodecHelpers.str(net205, "flow_dl"))
        let flowUl = CodecHelpers.doubleVal(CodecHelpers.str(net205, "flow_ul"))
        let currentFlowMb: Double? = (flowDl != nil || flowUl != nil)
            ? (flowDl ?? 0.0) + (flowUl ?? 0.0)
            : nil

        let limitEnabled = CodecHelpers.str(traffic337, "limitSwitch") == "1"
        let limitRaw = CodecHelpers.doubleVal(CodecHelpers.str(traffic337, "limitSize"))
        let unitRaw = CodecHelpers.str(traffic337, "flow_limit_unit").uppercased()
        let quotaMb: Double?
        if let limitRaw, limitRaw > 0 {
            switch unitRaw {
            case "0", "MB": quotaMb = limitRaw
            case "2", "TB": quotaMb = limitRaw * 1_048_576.0
            default: quotaMb = limitRaw * 1024.0
            }
        } else {
            quotaMb = nil
        }

        let warnPct = Int(CodecHelpers.str(traffic337, "warn_percentage"))
        let startDay = Int(CodecHelpers.str(traffic337, "startDate"))

        let daysLeft: Int?
        if let startDay, (1...28).contains(startDay) {
            let cal = Calendar(identifier: .gregorian)
            let today = cal.component(.day, from: date)
            let range = cal.range(of: .day, in: .month, for: date)
            let daysInMonth = range?.count ?? 30
            daysLeft = calculateDaysLeftInCycle(startDay: startDay, todayDayOfMonth: today, daysInMonth: daysInMonth)
        } else {
            daysLeft = nil
        }

        return MonthlyUsageSummary(
            usedMb: monTotal,
            currentFlowMb: currentFlowMb,
            dlMonthMb: dlMonthMb,
            ulMonthMb: ulMonthMb,
            roamDlMb: CodecHelpers.doubleVal(CodecHelpers.str(traffic337, "roam_dl_mon_flow")) ?? 0.0,
            roamUlMb: CodecHelpers.doubleVal(CodecHelpers.str(traffic337, "roam_ul_mon_flow")) ?? 0.0,
            limitEnabled: limitEnabled,
            quotaMb: quotaMb,
            warnPercent: warnPct,
            billingStartDay: startDay,
            daysLeftInCycle: daysLeft,
            lastResetLabel: nil
        )
    }

    public static func calculateDaysLeftInCycle(startDay: Int, todayDayOfMonth: Int, daysInMonth: Int) -> Int {
        guard (1...28).contains(startDay) else { return 0 }
        if todayDayOfMonth < startDay {
            return startDay - todayDayOfMonth
        }
        return (daysInMonth - todayDayOfMonth) + startDay
    }

    public static func formatDurationText(_ raw: String?) -> String {
        let clean = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean != "-", clean != "--" else { return "--" }

        // Handle "D-H-M-S"
        let dashParts = clean.split(separator: "-")
        if dashParts.count == 4,
           let d = Int(dashParts[0]), let h = Int(dashParts[1]), let m = Int(dashParts[2]), let s = Int(dashParts[3]) {
            let totalSec = max(0, ((d * 86400) + (h * 3600) + (m * 60) + s))
            return formatTotalSeconds(totalSec)
        }

        // Handle "H:M:S"
        let colonParts = clean.split(separator: ":")
        if colonParts.count == 3,
           let h = Int(colonParts[0]), let m = Int(colonParts[1]), let s = Int(colonParts[2]) {
            let totalSec = max(0, (h * 3600) + (m * 60) + s)
            return formatTotalSeconds(totalSec)
        }

        if let seconds = Int(clean), seconds >= 0 {
            return formatTotalSeconds(seconds)
        }
        return clean
    }

    private static func formatTotalSeconds(_ totalSeconds: Int) -> String {
        let days = totalSeconds / 86400
        let hours = (totalSeconds % 86400) / 3600
        let minutes = (totalSeconds % 3600) / 60
        if days > 0 {
            return "\(days) d \(hours) h"
        }
        if hours > 0 {
            return "\(hours) h \(minutes) m"
        }
        if minutes > 0 {
            return "\(minutes) m"
        }
        return "\(totalSeconds % 60) s"
    }

    public static func formatDataSizeMb(_ mb: Double) -> String {
        let safe = max(0.0, mb)
        if safe >= 1_048_576.0 {
            return String(format: "%.2f TB", safe / 1_048_576.0)
        }
        if safe >= 1024.0 {
            return String(format: "%.2f GB", safe / 1024.0)
        }
        return String(format: "%.2f MB", safe)
    }
}
