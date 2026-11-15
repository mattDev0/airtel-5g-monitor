import Foundation

@MainActor
public final class WifiRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter

    @Published public private(set) var config = WifiRadioConfig()
    @Published public private(set) var bandDetails = WifiBandDetails()
    @Published public private(set) var guestWifi = GuestWifiSnapshot()
    @Published public private(set) var adv24g: WifiAdvancedBandConfig?
    @Published public private(set) var adv5g: WifiAdvancedBandConfig?
    @Published public private(set) var power411: WifiPower411Config?
    @Published public private(set) var wps = WpsSnapshot()
    @Published public private(set) var schedule = WifiScheduleConfig()

    public init(session: RouterSession, writer: SettingsWriter) {
        self.session = session
        self.writer = writer
    }

    @discardableResult
    public func loadWifiConfig() async -> RouterResult<WifiRadioConfig> {
        let res24 = await session.execute(cmd: 2, method: "GET", extra: ["subcmd": 0])
        let res5 = await session.execute(cmd: 211, method: "GET", extra: ["subcmd": 0])
        if case .notLoggedIn(let msg) = res24 { return .notLoggedIn(msg) }
        if case .notLoggedIn(let msg) = res5 { return .notLoggedIn(msg) }
        guard case .ok(let j24) = res24 else { return res24.castFailure() }
        guard case .ok(let j5) = res5 else { return res5.castFailure() }
        let parsed = RouterParsers.parseWifiConfig(json24g: j24, json5g: j5)
        self.config = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func loadWifiBandDetails() async -> RouterResult<WifiBandDetails> {
        let res80 = await session.execute(cmd: 80, method: "GET")
        let j80 = res80.getOrNull() ?? [:]
        let forbidSsids = Self.splitForbidList(CodecHelpers.str(j80, "wifi_ssid_forbid"))
        let forbidKeys = Self.splitForbidList(CodecHelpers.str(j80, "wifi_pwd_forbid"))

        let res24 = await session.execute(cmd: 2, method: "GET", extra: ["subcmd": 0])
        let res5 = await session.execute(cmd: 211, method: "GET", extra: ["subcmd": 0])
        if case .notLoggedIn(let msg) = res24 { return .notLoggedIn(msg) }
        if case .notLoggedIn(let msg) = res5 { return .notLoggedIn(msg) }
        guard case .ok(let j24) = res24 else { return res24.castFailure() }
        guard case .ok(let j5) = res5 else { return res5.castFailure() }

        let b24 = RouterParsers.parseWifiBandStatus(j24)
        let b5 = RouterParsers.parseWifiBandStatus(j5)
        let details = WifiBandDetails(band24g: b24, band5g: b5, forbidSsids: forbidSsids, forbidKeys: forbidKeys)
        self.bandDetails = details
        self.config = RouterParsers.parseWifiConfig(json24g: j24, json5g: j5)
        return .ok(details)
    }

    /// Toggles 2.4 GHz or 5 GHz Wi-Fi while guarding against shutting off the last active radio.
    public func setWifiEnabled(band: WifiBand, enabled: Bool) async -> RouterResult<String> {
        let stateWord = enabled ? "on" : "off"
        let other: WifiBand = (band == .band24g) ? .band5g : .band24g

        let curRes = await session.execute(cmd: band.cmd, method: "GET", extra: ["subcmd": 0])
        guard case .ok(let current) = curRes else { return curRes.castFailure() }
        if current["wifiOpen"] == nil {
            let msg = CodecHelpers.nonBlankStr(current, "message") ?? "Could not read \(band.label) Wi-Fi settings"
            return .badResponse(message: msg, rawBody: "")
        }
        if (CodecHelpers.str(current, "wifiOpen") == "1") == enabled {
            return .ok("\(band.label) Wi-Fi is already \(stateWord)")
        }

        if !enabled {
            let otherRes = await session.execute(cmd: other.cmd, method: "GET", extra: ["subcmd": 0])
            guard case .ok(let otherJson) = otherRes, otherJson["wifiOpen"] != nil else {
                return .badResponse(message: "Could not confirm \(other.label) is on, so \(band.label) was left on.", rawBody: "")
            }
            if CodecHelpers.str(otherJson, "wifiOpen") != "1" {
                return .badResponse(
                    message: "\(other.label) is off. Turning off \(band.label) too would disconnect every wireless device, including this phone. Turn on \(other.label) first.",
                    rawBody: ""
                )
            }
        }

        let res = await writer.write(
            WriteSpec<String>(
                cmd: band.cmd,
                readExtra: ["subcmd": 0],
                buildPayload: { cur in
                    var payload: [String: Any] = [
                        "subcmd": 0,
                        "wifiOpen": enabled ? "1" : "0",
                        "broadcast": CodecHelpers.str(cur, "broadcast", fallback: "1"),
                        "ssid": CodecHelpers.str(cur, "ssid"),
                        "key": CodecHelpers.str(cur, "key"),
                        "authenticationType": CodecHelpers.str(cur, "authenticationType", fallback: "2")
                    ]
                    if cur["wifiSames"] != nil {
                        payload["wifiSames"] = CodecHelpers.str(cur, "wifiSames", fallback: "0")
                    }
                    if cur["maxStaNum"] != nil {
                        payload["maxStaNum"] = CodecHelpers.str(cur, "maxStaNum")
                    }
                    return payload
                },
                verify: { rb in
                    rb["wifiOpen"] != nil && (CodecHelpers.str(rb, "wifiOpen") == "1") == enabled
                },
                parseResult: { _ in "\(band.label) Wi-Fi turned \(stateWord), confirmed by router" },
                writeTimeoutSeconds: 20.0,
                verifyRetries: 10,
                verifyDelaySeconds: 2.0,
                allowEmptyWriteReply: true
            )
        )
        if case .ok = res {
            config = WifiRadioConfig(
                enabled24g: band == .band24g ? enabled : config.enabled24g,
                ssid24g: config.ssid24g,
                channel24g: config.channel24g,
                enabled5g: band == .band5g ? enabled : config.enabled5g,
                ssid5g: config.ssid5g,
                channel5g: config.channel5g
            )
        }
        return res
    }

    @discardableResult
    public func saveWifiBandConfig(
        band: WifiBand,
        ssid: String,
        key: String,
        authenticationType: String,
        visibleBroadcast: Bool,
        wifiSames: Bool = false,
        applySameToBothBands: Bool = false
    ) async -> RouterResult<WifiBandDetails> {
        let cleanSsid = ssid.trimmingCharacters(in: .whitespacesAndNewlines)
        if let ssidErr = WifiAndLanValidators.validateSsid(cleanSsid, forbiddenSsids: bandDetails.forbidSsids) {
            return .badResponse(message: ssidErr, rawBody: "")
        }
        if let keyErr = WifiAndLanValidators.validateWifiPassword(key, authenticationType: authenticationType, forbiddenKeys: bandDetails.forbidKeys) {
            return .badResponse(message: keyErr, rawBody: "")
        }

        let encodedSsid = CodecHelpers.encodeBase64Utf8(cleanSsid)
        let targets: [WifiBand] = applySameToBothBands ? [.band24g, .band5g] : [band]

        for target in targets {
            let writeRes = await writer.write(
                WriteSpec<WifiBandStatus>(
                    cmd: target.cmd,
                    readExtra: ["subcmd": 0],
                    buildPayload: { cur in
                        var payload: [String: Any] = [
                            "subcmd": 0,
                            "wifiOpen": CodecHelpers.str(cur, "wifiOpen", fallback: "1"),
                            "ssid": encodedSsid,
                            "key": authenticationType == "0" ? CodecHelpers.str(cur, "key") : key,
                            "authenticationType": authenticationType,
                            "broadcast": visibleBroadcast ? "1" : "0",
                            "wifiSames": (applySameToBothBands || wifiSames) ? "1" : "0"
                        ]
                        let maxSta = CodecHelpers.str(cur, "maxStaNum")
                        if !maxSta.isEmpty { payload["maxStaNum"] = maxSta }
                        return payload
                    },
                    verify: { rb in
                        CodecHelpers.str(rb, "ssid") == encodedSsid &&
                            CodecHelpers.str(rb, "authenticationType") == authenticationType &&
                            (CodecHelpers.str(rb, "broadcast", fallback: "1") != "0") == visibleBroadcast
                    },
                    parseResult: { RouterParsers.parseWifiBandStatus($0) },
                    writeTimeoutSeconds: 20.0,
                    verifyRetries: 8,
                    verifyDelaySeconds: 1.5,
                    allowEmptyWriteReply: true
                )
            )
            if !writeRes.isOk { return writeRes.castFailure() }
        }
        return await loadWifiBandDetails()
    }

    @discardableResult
    public func loadGuestWifi(load24g: Bool = true, load5g: Bool = true) async -> RouterResult<GuestWifiSnapshot> {
        var b24 = guestWifi.band24g
        var b5 = guestWifi.band5g
        var firstErr: RouterResult<GuestWifiSnapshot>?

        if load24g {
            let r = await loadGuestBand(.band24g)
            switch r {
            case .ok(let cfg): b24 = cfg
            case .notLoggedIn(let msg): return .notLoggedIn(msg)
            default: firstErr = firstErr ?? r.castFailure()
            }
        }
        if load5g {
            let r = await loadGuestBand(.band5g)
            switch r {
            case .ok(let cfg): b5 = cfg
            case .notLoggedIn(let msg): return .notLoggedIn(msg)
            default: firstErr = firstErr ?? r.castFailure()
            }
        }
        let snap = GuestWifiSnapshot(band24g: b24, band5g: b5)
        self.guestWifi = snap
        if let firstErr, (!load24g && !load5g) == false {
            // If all requested failed, surface the error
            if (load24g && !load5g) || (!load24g && load5g) {
                return firstErr
            }
        }
        return .ok(snap)
    }

    private func loadGuestBand(_ band: WifiBand) async -> RouterResult<GuestWifiBandConfig> {
        let mainRes = await session.execute(cmd: band.cmd, method: "GET", extra: ["subcmd": 0], timeoutSeconds: 5.0)
        guard case .ok(let mainJson) = mainRes else { return mainRes.castFailure() }
        let mainOpen = CodecHelpers.str(mainJson, "wifiOpen", fallback: "0") == "1"
        if !mainOpen {
            return .ok(GuestWifiBandConfig(wifiOpen: false, mainWifiOpen: false))
        }
        let guestRes = await session.execute(cmd: band.cmd, method: "GET", extra: ["subcmd": 1], timeoutSeconds: 5.0)
        guard case .ok(let guestJson) = guestRes else { return guestRes.castFailure() }
        return .ok(Self.parseGuestBand(guestJson, mainOpen: true))
    }

    @discardableResult
    public func saveGuestWifi(band: WifiBand, config cfg: GuestWifiBandConfig) async -> RouterResult<GuestWifiBandConfig> {
        if let preflightErr = await checkWifiInitReady() {
            return preflightErr.castFailure()
        }
        if cfg.wifiOpen {
            if let err = WifiAndLanValidators.validateSsid(cfg.ssid, forbiddenSsids: bandDetails.forbidSsids) {
                return .badResponse(message: err, rawBody: "")
            }
            if let err = WifiAndLanValidators.validateWifiPassword(cfg.key, authenticationType: cfg.authenticationType, forbiddenKeys: bandDetails.forbidKeys) {
                return .badResponse(message: err, rawBody: "")
            }
        }
        let encodedSsid = CodecHelpers.encodeBase64Utf8(cfg.ssid.trimmingCharacters(in: .whitespacesAndNewlines))
        let res = await writer.write(
            WriteSpec<GuestWifiBandConfig>(
                cmd: band.cmd,
                readExtra: ["subcmd": 1],
                buildPayload: { cur in
                    var payload: [String: Any] = [
                        "subcmd": 1,
                        "wifiOpen": cfg.wifiOpen ? "1" : "0",
                        "ssid": encodedSsid,
                        "key": cfg.authenticationType == "0" ? CodecHelpers.str(cur, "key") : cfg.key,
                        "authenticationType": cfg.authenticationType,
                        "encryption": cfg.encryption,
                        "broadcast": cfg.broadcast ? "1" : "0",
                        "wmm": cfg.wmm ? "1" : "0"
                    ]
                    if !cfg.customPort.isEmpty { payload["customPort"] = cfg.customPort }
                    return payload
                },
                verify: { rb in
                    (CodecHelpers.str(rb, "wifiOpen", fallback: "0") == "1") == cfg.wifiOpen &&
                        (!cfg.wifiOpen || CodecHelpers.str(rb, "ssid") == encodedSsid)
                },
                parseResult: { Self.parseGuestBand($0, mainOpen: cfg.mainWifiOpen) },
                writeTimeoutSeconds: 20.0,
                verifyRetries: 6,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true
            )
        )
        if case .ok(let saved) = res {
            guestWifi = GuestWifiSnapshot(
                band24g: band == .band24g ? saved : guestWifi.band24g,
                band5g: band == .band5g ? saved : guestWifi.band5g
            )
        }
        return res
    }

    @discardableResult
    public func loadWifiAdvanced(band: WifiBand) async -> RouterResult<WifiAdvancedBandConfig> {
        let res = await session.execute(cmd: band.advancedCmd, method: "GET", timeoutSeconds: 6.0)
        guard case .ok(let json) = res else { return res.castFailure() }
        let parsed = Self.parseWifiAdvanced(json, band: band)
        if band == .band24g { adv24g = parsed } else { adv5g = parsed }
        return .ok(parsed)
    }

    @discardableResult
    public func saveWifiAdvanced(band: WifiBand, config cfg: WifiAdvancedBandConfig) async -> RouterResult<WifiAdvancedBandConfig> {
        if cfg.clientStaReadOnly {
            return .badResponse(message: cfg.staLimitPrompt.isEmpty ? "Station limit is locked by operator" : cfg.staLimitPrompt, rawBody: "")
        }
        guard (1...cfg.maxNumLimit).contains(cfg.maxNum) else {
            return .badResponse(message: "Max connected stations must be between 1 and \(cfg.maxNumLimit)", rawBody: "")
        }
        let effectiveBandwidth = (band == .band5g && !cfg.dfsSwitch && cfg.bandWidth == "4") ? "3" : cfg.bandWidth

        let res = await writer.write(
            WriteSpec<WifiAdvancedBandConfig>(
                cmd: band.advancedCmd,
                buildPayload: { cur in
                    var payload: [String: Any] = [
                        "wifiOpen": cfg.wifiOpen ? "1" : "0",
                        "channel": cfg.channel,
                        "bandWidth": effectiveBandwidth,
                        "maxNum": "\(cfg.maxNum)",
                        "apIsolate": cfg.apIsolate ? "1" : "0",
                        "txPower": cfg.txPower
                    ]
                    if band == .band5g {
                        payload["dfs_switch"] = cfg.dfsSwitch ? "1" : "0"
                    }
                    for keepKey in ["countryCode", "wifi_workMode"] {
                        let v = CodecHelpers.str(cur, keepKey)
                        if !v.isEmpty { payload[keepKey] = v }
                    }
                    return payload
                },
                verify: { rb in
                    CodecHelpers.str(rb, "bandWidth") == effectiveBandwidth &&
                        (Int(CodecHelpers.str(rb, "maxNum")) == cfg.maxNum)
                },
                parseResult: { Self.parseWifiAdvanced($0, band: band) },
                writeTimeoutSeconds: 20.0,
                verifyRetries: 5,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true
            )
        )
        if case .ok(let saved) = res {
            if band == .band24g { adv24g = saved } else { adv5g = saved }
        }
        return res
    }

    @discardableResult
    public func loadWifiPower411() async -> RouterResult<WifiPower411Config> {
        let res = await session.execute(cmd: 411, method: "GET", timeoutSeconds: 5.0)
        guard case .ok(let json) = res else { return res.castFailure() }
        let cfg = WifiPower411Config(
            txPower24g: CodecHelpers.firstNonBlank(json, keys: ["txPower"], defaultValue: "15"),
            txPower5g: CodecHelpers.firstNonBlank(json, keys: ["txPower5g", "txPower"], defaultValue: "15")
        )
        self.power411 = cfg
        return .ok(cfg)
    }

    @discardableResult
    public func saveWifiPower411(_ txPower: String) async -> RouterResult<WifiPower411Config> {
        let clean = txPower.trimmingCharacters(in: .whitespacesAndNewlines)
        guard ["15", "50", "100"].contains(clean) else {
            return .badResponse(message: "Invalid TX power level", rawBody: "")
        }
        let res = await writer.write(
            WriteSpec<WifiPower411Config>(
                cmd: 411,
                buildPayload: { _ in ["txPower": clean, "txPower5g": clean] },
                verify: { rb in CodecHelpers.str(rb, "txPower") == clean },
                parseResult: { rb in
                    WifiPower411Config(
                        txPower24g: CodecHelpers.firstNonBlank(rb, keys: ["txPower"], defaultValue: clean),
                        txPower5g: CodecHelpers.firstNonBlank(rb, keys: ["txPower5g"], defaultValue: clean)
                    )
                },
                writeTimeoutSeconds: 20.0,
                verifyRetries: 5,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true
            )
        )
        if case .ok(let saved) = res { self.power411 = saved }
        return res
    }

    @discardableResult
    public func loadWpsStatus() async -> RouterResult<WpsSnapshot> {
        let r24 = await session.execute(cmd: 132, method: "GET", extra: ["subcmd": 0], timeoutSeconds: 5.0)
        let r5 = await session.execute(cmd: 132, method: "GET", extra: ["subcmd": 1], timeoutSeconds: 5.0)
        let w24 = r24.getOrNull()
        let w5 = r5.getOrNull()
        if w24 == nil && w5 == nil {
            return r24.isOk ? r5.castFailure() : r24.castFailure()
        }
        let main24 = (await session.execute(cmd: 2, method: "GET", extra: ["subcmd": 0], timeoutSeconds: 5.0)).getOrNull()
        let main5 = (await session.execute(cmd: 211, method: "GET", extra: ["subcmd": 0], timeoutSeconds: 5.0)).getOrNull()
        let open24 = CodecHelpers.nonBlankStr(main24 ?? [:], "wifiOpen") ?? CodecHelpers.nonBlankStr(w24 ?? [:], "wifiOpen") ?? "1"
        let open5 = CodecHelpers.nonBlankStr(main5 ?? [:], "wifiOpen") ?? CodecHelpers.nonBlankStr(w5 ?? [:], "wifiOpen") ?? "1"

        let snap = WpsSnapshot(
            band24g: WpsBandStatus(
                enabled: CodecHelpers.str(w24 ?? [:], "wlan2g_wps_switch", fallback: "0") == "1",
                wifiOpen: open24 == "1",
                authType: CodecHelpers.firstNonBlank(main24 ?? [:], keys: ["authenticationType"], defaultValue: "2"),
                visibleBroadcast: CodecHelpers.str(main24 ?? [:], "broadcast", fallback: "1") != "0"
            ),
            band5g: WpsBandStatus(
                enabled: CodecHelpers.str(w5 ?? [:], "wlan5g_wps_switch", fallback: "0") == "1",
                wifiOpen: open5 == "1",
                authType: CodecHelpers.firstNonBlank(main5 ?? [:], keys: ["authenticationType"], defaultValue: "2"),
                visibleBroadcast: CodecHelpers.str(main5 ?? [:], "broadcast", fallback: "1") != "0"
            )
        )
        self.wps = snap
        return .ok(snap)
    }

    @discardableResult
    public func setWpsEnabled(band: WifiBand, enabled: Bool) async -> RouterResult<WpsSnapshot> {
        if let err = await checkWifiInitReady() { return err.castFailure() }
        let keyName = (band == .band24g) ? "wlan2g_wps_switch" : "wlan5g_wps_switch"
        let sub = (band == .band24g) ? 0 : 1
        let val = enabled ? "1" : "0"
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 132,
                readExtra: ["subcmd": sub],
                buildPayload: { _ in ["subcmd": sub, keyName: val] },
                verify: { rb in CodecHelpers.str(rb, keyName) == val },
                parseResult: { _ in Unit() },
                writeTimeoutSeconds: 18.0,
                verifyRetries: 5,
                verifyDelaySeconds: 1.5,
                allowEmptyWriteReply: true
            )
        )
        if !res.isOk { return res.castFailure() }
        return await loadWpsStatus()
    }

    public func triggerWpsPbc(band: WifiBand) async -> RouterResult<Unit> {
        let sub = (band == .band24g) ? 2 : 3
        return await writer.write(
            WriteSpec<Unit>(
                cmd: 243,
                buildPayload: { _ in ["subcmd": sub] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true,
                postAccepted: { $0["success"] as? Bool == true || CodecHelpers.str($0, "message") == "0" }
            )
        )
    }

    public func submitWpsPin(band: WifiBand, pin: String) async -> RouterResult<Unit> {
        if let err = Phase71Validators.validateWpsPin(pin) {
            return .badResponse(message: err, rawBody: "")
        }
        let sub = (band == .band24g) ? 0 : 1
        let clean = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        return await writer.write(
            WriteSpec<Unit>(
                cmd: 243,
                buildPayload: { _ in ["subcmd": sub, "wpsPin": clean] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true,
                postAccepted: { $0["success"] as? Bool == true }
            )
        )
    }

    public func openRandomWpsPin(band: WifiBand) async -> RouterResult<String> {
        let sub = (band == .band24g) ? 0 : 1
        return await writer.write(
            WriteSpec<String>(
                cmd: 244,
                buildPayload: { _ in ["subcmd": sub] },
                verify: { _ in true },
                parseResult: { CodecHelpers.str($0, "random_ping") },
                skipPreRead: true,
                skipVerifyRead: true,
                postAccepted: { !CodecHelpers.str($0, "random_ping").isEmpty }
            )
        )
    }

    public func pollRandomWpsSuccess() async -> RouterResult<Bool> {
        let res = await session.execute(cmd: 325, method: "GET", timeoutSeconds: 5.0)
        guard case .ok(let json) = res else { return res.castFailure() }
        if CodecHelpers.str(json, "wps_is_success_state") == "1" {
            _ = await writer.write(
                WriteSpec<Unit>(
                    cmd: 325,
                    buildPayload: { _ in ["wps_is_success_state": "0"] },
                    verify: { _ in true },
                    parseResult: { _ in Unit() },
                    skipPreRead: true,
                    skipVerifyRead: true
                )
            )
            return .ok(true)
        }
        return .ok(false)
    }

    @discardableResult
    public func loadWifiSchedule() async -> RouterResult<WifiScheduleConfig> {
        let res = await session.execute(cmd: 503, method: "GET", timeoutSeconds: 5.0)
        guard case .ok(let json) = res else { return res.castFailure() }
        let cfg = Self.parseSchedule(json)
        self.schedule = cfg
        return .ok(cfg)
    }

    @discardableResult
    public func saveWifiSchedule(_ cfg: WifiScheduleConfig) async -> RouterResult<WifiScheduleConfig> {
        let res = await writer.write(
            WriteSpec<WifiScheduleConfig>(
                cmd: 503,
                buildPayload: { _ in
                    var payload: [String: Any] = ["wifiSwitch": cfg.enabled ? "1" : "0"]
                    if cfg.enabled {
                        payload["wifi_on_start_hour"] = "\(cfg.onHour)"
                        payload["wifi_on_start_min"] = "\(cfg.onMinute)"
                        payload["wifi_off_start_hour"] = "\(cfg.offHour)"
                        payload["wifi_off_start_min"] = "\(cfg.offMinute)"
                    }
                    return payload
                },
                verify: { rb in
                    let p = Self.parseSchedule(rb)
                    return p.enabled == cfg.enabled &&
                        (!cfg.enabled || (p.onHour == cfg.onHour && p.offHour == cfg.offHour))
                },
                parseResult: { Self.parseSchedule($0) },
                verifyRetries: 3,
                verifyDelaySeconds: 1.0,
                skipPreRead: true
            )
        )
        if case .ok(let saved) = res { self.schedule = saved }
        return res
    }

    private func checkWifiInitReady() async -> RouterResult<Unit>? {
        let res = await session.execute(cmd: 417, method: "GET", timeoutSeconds: 5.0)
        guard case .ok(let json) = res else { return res.castFailure() }
        if CodecHelpers.str(json, "wifiStatus") != "1" {
            return .badResponse(message: "Wi-Fi radio is still initializing; please retry in a moment.", rawBody: "")
        }
        return nil
    }

    private static func splitForbidList(_ raw: String) -> [String] {
        raw.split(whereSeparator: { $0 == "," || $0 == ";" })
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private static func parseGuestBand(_ json: [String: Any], mainOpen: Bool) -> GuestWifiBandConfig {
        GuestWifiBandConfig(
            wifiOpen: CodecHelpers.str(json, "wifiOpen", fallback: "0") == "1",
            ssid: CodecHelpers.decodeBase64Utf8(CodecHelpers.str(json, "ssid")),
            key: CodecHelpers.str(json, "key"),
            authenticationType: CodecHelpers.firstNonBlank(json, keys: ["authenticationType"], defaultValue: "2"),
            encryption: CodecHelpers.firstNonBlank(json, keys: ["encryption"], defaultValue: "1"),
            broadcast: CodecHelpers.str(json, "broadcast", fallback: "1") != "0",
            wmm: CodecHelpers.str(json, "wmm", fallback: "1") != "0",
            mainWifiOpen: mainOpen,
            customPort: CodecHelpers.str(json, "customPort")
        )
    }

    private static func parseWifiAdvanced(_ json: [String: Any], band: WifiBand) -> WifiAdvancedBandConfig {
        let maxLimKey = (band == .band24g) ? "maxNum24Limit" : "maxNumLimit"
        let limit = Int(CodecHelpers.str(json, maxLimKey)) ?? 64
        let rawMax = Int(CodecHelpers.str(json, "maxNum")) ?? 32
        return WifiAdvancedBandConfig(
            wifiOpen: CodecHelpers.str(json, "wifiOpen", fallback: "1") == "1",
            countryCode: CodecHelpers.str(json, "countryCode"),
            workMode: CodecHelpers.firstNonBlank(json, keys: ["wifi_workMode"], defaultValue: "0"),
            channel: CodecHelpers.firstNonBlank(json, keys: ["channel"], defaultValue: "0"),
            bandWidth: CodecHelpers.firstNonBlank(json, keys: ["bandWidth"], defaultValue: "0"),
            txPower: CodecHelpers.firstNonBlank(json, keys: ["txPower"], defaultValue: "15"),
            maxNum: min(max(rawMax, 1), max(limit, 1)),
            maxNumLimit: max(limit, 1),
            apIsolate: CodecHelpers.str(json, "apIsolate") == "1",
            dfsSwitch: CodecHelpers.str(json, "dfs_switch") == "1",
            clientStaReadOnly: CodecHelpers.str(json, "clientStaReadOnly") == "1",
            staLimitPrompt: CodecHelpers.str(json, "staLimitPrompt")
        )
    }

    private static func parseSchedule(_ json: [String: Any]) -> WifiScheduleConfig {
        WifiScheduleConfig(
            enabled: CodecHelpers.str(json, "wifiSwitch") == "1",
            onHour: Int(CodecHelpers.str(json, "wifi_on_start_hour")) ?? 7,
            onMinute: Int(CodecHelpers.str(json, "wifi_on_start_min")) ?? 0,
            offHour: Int(CodecHelpers.str(json, "wifi_off_start_hour")) ?? 23,
            offMinute: Int(CodecHelpers.str(json, "wifi_off_start_min")) ?? 0
        )
    }
}
