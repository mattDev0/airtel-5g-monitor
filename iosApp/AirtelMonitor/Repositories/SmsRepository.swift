import Foundation

@MainActor
public final class SmsRepository: ObservableObject {
    private let session: RouterSession
    private let writer: SettingsWriter
    private let onUnreadCountSync: (@MainActor (Int) -> Void)?

    @Published public private(set) var inboxPage = SmsPage(folder: .inbox)
    @Published public private(set) var outboxPage = SmsPage(folder: .outbox)
    @Published public private(set) var settings = SmsSettings()
    @Published public private(set) var unreadCount: Int = 0

    public init(
        session: RouterSession,
        writer: SettingsWriter,
        onUnreadCountSync: (@MainActor (Int) -> Void)? = nil
    ) {
        self.session = session
        self.writer = writer
        self.onUnreadCountSync = onUnreadCountSync
    }

    @discardableResult
    public func loadPage(folder: SmsFolder, page: Int = 1) async -> RouterResult<SmsPage> {
        let safePage = max(1, page)
        let res = await session.execute(
            cmd: 12,
            method: "GET",
            extra: ["subcmd": folder.subcmd, "page": safePage],
            timeoutSeconds: 10.0
        )
        guard case .ok(let json) = res else { return res.castFailure() }

        let total = Int(CodecHelpers.firstNonBlank(json, keys: ["sms_total", "total"], defaultValue: "0")) ?? 0
        let receiveFull = CodecHelpers.str(json, "receive_full") == "1"
        let sendFull = CodecHelpers.str(json, "send_full") == "1"
        let messages = Self.parseSmsList(CodecHelpers.str(json, "sms_list"), folder: folder)

        let parsed = SmsPage(
            folder: folder,
            page: safePage,
            totalCount: total,
            isReceiveFull: receiveFull,
            isSendFull: sendFull,
            messages: messages
        )
        switch folder {
        case .inbox:
            inboxPage = parsed
            let unread = messages.filter { !$0.isRead }.count
            unreadCount = unread
            onUnreadCountSync?(unread)
        case .outbox:
            outboxPage = parsed
        }
        return .ok(parsed)
    }

    @discardableResult
    public func refreshUnreadCountFromStatusBar() async -> RouterResult<Int> {
        let res = await session.execute(cmd: 394, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let count = RouterParsers.parseSmsUnreadCount(json)
        unreadCount = count
        onUnreadCountSync?(count)
        return .ok(count)
    }

    @discardableResult
    public func markRead(indices: [String]) async -> RouterResult<Unit> {
        let clean = indices.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard !clean.isEmpty else { return .ok(Unit()) }
        return await markReadToken(clean.joined(separator: ","))
    }

    @discardableResult
    public func markAllRead() async -> RouterResult<Unit> {
        await markReadToken("READ ALL")
    }

    private func markReadToken(_ indexToken: String) async -> RouterResult<Unit> {
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 12,
                buildPayload: { _ in
                    ["subcmd": 0, "index": indexToken]
                },
                verifyPost: { json in
                    let msg = CodecHelpers.str(json, "message")
                    if !msg.isEmpty && msg != "0" && json["success"] as? Bool != true {
                        return .badResponse(message: "Router rejected mark-as-read request", rawBody: "")
                    }
                    return nil
                },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        if res.isOk {
            _ = await loadPage(folder: .inbox, page: inboxPage.page)
            _ = await refreshUnreadCountFromStatusBar()
        }
        return res
    }

    @discardableResult
    public func sendSms(phoneNumber: String, body: String) async -> RouterResult<Unit> {
        let cleanPhone = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        if let err = SmsValidators.validatePhoneNumber(cleanPhone) {
            return .badResponse(message: err, rawBody: "")
        }
        if let err = SmsValidators.validateSmsBody(body, maxWeightedLength: settings.maxWeightedLength) {
            return .badResponse(message: err, rawBody: "")
        }
        let encodedBody = CodecHelpers.encodeBase64Utf8(body)
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 14,
                buildPayload: { _ in
                    ["phone": cleanPhone, "content": encodedBody]
                },
                verifyPost: { json in
                    let msg = CodecHelpers.str(json, "message")
                    if msg == "502" {
                        return .badResponse(message: "Router SMS box is full — delete old messages first", rawBody: "")
                    }
                    if msg != "0" && json["success"] as? Bool != true {
                        return .badResponse(message: "Failed to send SMS (code \(msg))", rawBody: "")
                    }
                    return nil
                },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                writeTimeoutSeconds: 15.0,
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        if res.isOk {
            _ = await loadPage(folder: .outbox, page: 1)
        }
        return res
    }

    @discardableResult
    public func deleteMessages(folder: SmsFolder, indices: [String]) async -> RouterResult<Unit> {
        let clean = indices.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard !clean.isEmpty else {
            return .badResponse(message: "Select at least one SMS to delete", rawBody: "")
        }
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 13,
                buildPayload: { _ in
                    ["subcmd": folder.subcmd, "index": clean.joined(separator: ",")]
                },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        if res.isOk {
            let current = folder == .inbox ? inboxPage.page : outboxPage.page
            _ = await loadPage(folder: folder, page: current)
            if folder == .inbox {
                _ = await refreshUnreadCountFromStatusBar()
            }
        }
        return res
    }

    @discardableResult
    public func deleteAll(folder: SmsFolder) async -> RouterResult<Unit> {
        let res = await writer.write(
            WriteSpec<Unit>(
                cmd: 15,
                buildPayload: { _ in ["subcmd": folder.subcmd] },
                verify: { _ in true },
                parseResult: { _ in Unit() },
                skipPreRead: true,
                skipVerifyRead: true
            )
        )
        if res.isOk {
            _ = await loadPage(folder: folder, page: 1)
            if folder == .inbox {
                unreadCount = 0
                onUnreadCountSync?(0)
            }
        }
        return res
    }

    @discardableResult
    public func loadSettings() async -> RouterResult<SmsSettings> {
        let res = await session.execute(cmd: 16, method: "GET")
        guard case .ok(let json) = res else { return res.castFailure() }
        let parsed = Self.parseSettings(json)
        self.settings = parsed
        return .ok(parsed)
    }

    @discardableResult
    public func saveSettings(
        smsEnabled: Bool,
        smscNumber: String,
        maxSize: Int
    ) async -> RouterResult<SmsSettings> {
        let current = settings
        let sizeStr = "\(maxSize)"
        if let err = SmsValidators.validateSmsMaxSize(sizeStr) {
            return .badResponse(message: err, rawBody: "")
        }
        let cleanSmsc = smscNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        if current.smscEditable {
            if let err = SmsValidators.validateSmscNumber(cleanSmsc, required: true) {
                return .badResponse(message: err, rawBody: "")
            }
        }

        let res = await writer.write(
            WriteSpec<SmsSettings>(
                cmd: 16,
                buildPayload: { cur in
                    var payload: [String: Any] = [
                        "smsSw": smsEnabled ? "1" : "0",
                        "maxSize": sizeStr
                    ]
                    let smscSw = CodecHelpers.str(cur, "smscSw", fallback: current.smscEditable ? "1" : "0")
                    if smscSw != "0" {
                        payload["dmCsca"] = cleanSmsc
                    }
                    return payload
                },
                verify: { rb in
                    let p = Self.parseSettings(rb)
                    let smscOk = !p.smscEditable || p.smscNumber == cleanSmsc
                    return p.smsEnabled == smsEnabled && p.maxSize == maxSize && smscOk
                },
                parseResult: { Self.parseSettings($0) },
                verifyRetries: 3,
                verifyDelaySeconds: 1.0
            )
        )
        if case .ok(let updated) = res {
            self.settings = updated
        }
        return res
    }

    public static func parseSettings(_ json: [String: Any]) -> SmsSettings {
        let sw = CodecHelpers.str(json, "smsSw", fallback: "1") == "1"
        let editable = CodecHelpers.str(json, "smscSw", fallback: "0") != "0"
        let smsc = CodecHelpers.str(json, "dmCsca")
        let maxSize = Int(CodecHelpers.str(json, "maxSize", fallback: "100")) ?? 100
        let maxLen = Int(CodecHelpers.str(json, "maxLen", fallback: "640")) ?? 640
        return SmsSettings(
            smsEnabled: sw,
            smscEditable: editable,
            smscNumber: smsc,
            maxSize: maxSize,
            maxWeightedLength: max(160, maxLen)
        )
    }

    public static func parseSmsList(_ rawList: String, folder: SmsFolder) -> [SmsMessage] {
        let trimmed = rawList.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let entries = trimmed.split(separator: ",").map(String.init)
        var result: [SmsMessage] = []
        result.reserveCapacity(entries.count)

        for encoded in entries {
            let decoded = CodecHelpers.decodeBase64Utf8(encoded)
            guard !decoded.isEmpty else { continue }
            if let parsed = parseDecodedSmsRow(decoded, folder: folder) {
                result.append(parsed)
            }
        }
        return result
    }

    public static func parseDecodedSmsRow(_ decoded: String, folder: SmsFolder) -> SmsMessage? {
        // Expected shape: "<idx> <readFlag> <phoneWith$> <yyyy-MM-dd HH:mm> <body...>"
        let parts = decoded.split(maxSplits: 5, omittingEmptySubsequences: false, whereSeparator: { $0 == " " })
        guard parts.count >= 5 else { return nil }
        let idx = String(parts[0]).trimmingCharacters(in: .whitespacesAndNewlines)
        let readFlag = String(parts[1]).trimmingCharacters(in: .whitespacesAndNewlines)
        let phone = String(parts[2])
            .replacingOccurrences(of: "$", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let dateStr: String
        let body: String
        if parts.count >= 6 {
            dateStr = "\(parts[3]) \(parts[4])"
            body = String(parts[5])
        } else {
            dateStr = String(parts[3])
            body = String(parts[4])
        }
        guard !idx.isEmpty else { return nil }
        return SmsMessage(
            index: idx,
            folder: folder,
            phoneNumber: phone.isEmpty ? "Unknown" : phone,
            date: dateStr,
            text: body,
            isRead: readFlag == "1"
        )
    }
}
