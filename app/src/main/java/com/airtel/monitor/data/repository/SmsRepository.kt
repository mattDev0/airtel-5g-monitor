package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.SmsValidators
import com.airtel.monitor.data.dto.decodeBase64Utf8
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.dto.parseSmsUnreadCount
import com.airtel.monitor.data.model.SmsFolder
import com.airtel.monitor.data.model.SmsMessage
import com.airtel.monitor.data.model.SmsPage
import com.airtel.monitor.data.model.SmsSettingsConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Manages router SMS Inbox/Outbox (`cmd 12`, `cmd 13`, `cmd 14`) and SMS settings (`cmd 16`).
 */
class SmsRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter,
    private val onUnreadCountSync: ((Int) -> Unit)? = null
) {
    private val _inboxPage = MutableStateFlow<SmsPage?>(null)
    val inboxPage: StateFlow<SmsPage?> = _inboxPage.asStateFlow()

    private val _outboxPage = MutableStateFlow<SmsPage?>(null)
    val outboxPage: StateFlow<SmsPage?> = _outboxPage.asStateFlow()

    private val _settings = MutableStateFlow<SmsSettingsConfig?>(null)
    val settings: StateFlow<SmsSettingsConfig?> = _settings.asStateFlow()

    fun loadMessages(folder: SmsFolder, page: Int = 1): RouterResult<SmsPage> {
        val safePage = page.coerceAtLeast(1)
        val res = session.execute(
            cmd = 12,
            method = "GET",
            extra = mapOf("page_num" to safePage, "subcmd" to folder.subcmd),
            timeoutSeconds = 8L
        )
        if (res !is RouterResult.Ok) {
            return when (res) {
                is RouterResult.NotLoggedIn -> res
                is RouterResult.LimitedAccess -> res
                is RouterResult.Unreachable -> res
                is RouterResult.BadResponse -> res
                is RouterResult.Ok -> error("unreachable")
            }
        }
        val parsed = parseSmsPage(res.data, folder, safePage)
        if (folder == SmsFolder.INBOX) {
            _inboxPage.value = parsed
        } else {
            _outboxPage.value = parsed
        }
        syncUnreadBadgeBestEffort()
        return RouterResult.Ok(parsed)
    }

    fun markRead(indices: List<String>): RouterResult<Unit> {
        val clean = indices.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return RouterResult.Ok(Unit)
        val res = postWithToken(
            cmd = 12,
            extra = mapOf("index" to clean.joinToString(","))
        )
        if (res is RouterResult.Ok) {
            val currentInbox = _inboxPage.value
            if (currentInbox != null) {
                val readSet = clean.toSet()
                _inboxPage.value = currentInbox.copy(
                    messages = currentInbox.messages.map { msg ->
                        if (msg.index in readSet) msg.copy(isRead = true) else msg
                    }
                )
            }
            syncUnreadBadgeBestEffort()
        }
        return res.mapUnit()
    }

    fun markAllRead(): RouterResult<Unit> {
        val res = postWithToken(
            cmd = 12,
            extra = mapOf("index" to "READ ALL")
        )
        if (res is RouterResult.Ok) {
            _inboxPage.value?.let { cur ->
                _inboxPage.value = cur.copy(messages = cur.messages.map { it.copy(isRead = true) })
            }
            onUnreadCountSync?.invoke(0)
        }
        return res.mapUnit()
    }

    fun sendSms(phoneNumber: String, content: String): RouterResult<Unit> {
        val cleanPhone = phoneNumber.trim()
        SmsValidators.validatePhoneNumber(cleanPhone)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }
        val limit = _settings.value?.maxLength?.takeIf { it > 0 } ?: 4096
        SmsValidators.validateSmsBody(content, limit)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }

        val postRes = postWithToken(
            cmd = 14,
            extra = mapOf(
                "phoneNo" to cleanPhone,
                "content" to encodeBase64Utf8(content)
            ),
            timeoutSeconds = 15L
        )
        if (postRes !is RouterResult.Ok) return postRes.mapUnit()

        val msgCode = postRes.data.optString("message", "")
        if (msgCode == "502") {
            return RouterResult.BadResponse("SMS storage is full (502)", postRes.data.toString())
        }
        if (postRes.data.has("message") && msgCode != "0" && msgCode != "success") {
            return RouterResult.BadResponse(
                "Router rejected SMS send ($msgCode)",
                postRes.data.toString()
            )
        }
        if (!postRes.data.has("message") && !postRes.data.optBoolean("success", false)) {
            return RouterResult.BadResponse("Router rejected SMS send", postRes.data.toString())
        }
        return RouterResult.Ok(Unit)
    }

    fun deleteMessages(folder: SmsFolder, indices: List<String>): RouterResult<Unit> {
        val clean = indices.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return RouterResult.Ok(Unit)
        val res = postWithToken(
            cmd = 13,
            extra = mapOf(
                "index" to clean.joinToString(","),
                "subcmd" to folder.subcmd
            ),
            timeoutSeconds = 10L
        )
        if (res !is RouterResult.Ok) return res.mapUnit()
        val msgCode = res.data.optString("message", "")
        if (res.data.has("message") && msgCode != "0" && msgCode != "success") {
            return RouterResult.BadResponse("Could not delete message(s) ($msgCode)", res.data.toString())
        }
        syncUnreadBadgeBestEffort()
        return RouterResult.Ok(Unit)
    }

    fun deleteAll(folder: SmsFolder): RouterResult<Unit> {
        val res = postWithToken(
            cmd = 13,
            extra = mapOf(
                "index" to "DELETE ALL",
                "subcmd" to folder.subcmd
            ),
            timeoutSeconds = 10L
        )
        if (res !is RouterResult.Ok) return res.mapUnit()
        val msgCode = res.data.optString("message", "")
        if (res.data.has("message") && msgCode != "0" && msgCode != "success") {
            return RouterResult.BadResponse("Could not clear messages ($msgCode)", res.data.toString())
        }
        if (folder == SmsFolder.INBOX) {
            onUnreadCountSync?.invoke(0)
        }
        return RouterResult.Ok(Unit)
    }

    fun loadSmsSettings(): RouterResult<SmsSettingsConfig> {
        val res = session.execute(cmd = 16, method = "GET", timeoutSeconds = 5L)
        if (res !is RouterResult.Ok) {
            return when (res) {
                is RouterResult.NotLoggedIn -> res
                is RouterResult.LimitedAccess -> res
                is RouterResult.Unreachable -> res
                is RouterResult.BadResponse -> res
                is RouterResult.Ok -> error("unreachable")
            }
        }
        val parsed = parseSmsSettings(res.data)
        _settings.value = parsed
        return RouterResult.Ok(parsed)
    }

    suspend fun saveSmsSettings(
        smsEnabled: Boolean,
        smscNumber: String,
        maxSize: Int
    ): RouterResult<SmsSettingsConfig> {
        SmsValidators.validateSmsMaxSize(maxSize.toString())?.let { err ->
            return RouterResult.BadResponse(err, "")
        }

        val expectedSmsSw = if (smsEnabled) "1" else "0"
        val cleanSmsc = smscNumber.trim()
        val res = writer.write(
            WriteSpec(
                cmd = 16,
                preflight = { cur ->
                    val smscEditable = cur.optString("smscSw") == "1"
                    if (smscEditable) {
                        SmsValidators.validateSmscNumber(cleanSmsc, required = false)?.let { err ->
                            RouterResult.BadResponse(err, "")
                        }
                    } else {
                        null
                    }
                },
                buildPayload = { cur ->
                    buildMap {
                        put("smsSw", expectedSmsSw)
                        put("maxSize", maxSize.toString())
                        if (cur.optString("smscSw") == "1") {
                            put("dmCsca", cleanSmsc)
                        }
                    }
                },
                verify = { readBack ->
                    readBack.optString("smsSw") == expectedSmsSw &&
                        readBack.optString("maxSize").toIntOrNull() == maxSize
                },
                parseResult = ::parseSmsSettings,
                writeTimeoutSeconds = 8L,
                verifyRetries = 3,
                verifyDelayMs = 800L
            )
        )
        return when (res) {
            is RouterResult.Ok -> {
                _settings.value = res.data
                res
            }
            is RouterResult.LimitedAccess -> RouterResult.LimitedAccess(16, res.message)
            else -> res
        }
    }

    private fun postWithToken(
        cmd: Int,
        extra: Map<String, Any>,
        timeoutSeconds: Long = 8L
    ): RouterResult<JSONObject> {
        val tokenRes = session.fetchWriteToken()
        val token = when (tokenRes) {
            is RouterResult.Ok -> tokenRes.data
            is RouterResult.NotLoggedIn -> return tokenRes
            is RouterResult.LimitedAccess -> return RouterResult.LimitedAccess(cmd, tokenRes.message)
            is RouterResult.Unreachable -> return tokenRes
            is RouterResult.BadResponse -> return tokenRes
        }
        val payload = extra.toMutableMap().apply { put("token", token) }
        val res = session.execute(
            cmd = cmd,
            method = "POST",
            extra = payload,
            timeoutSeconds = timeoutSeconds
        )
        return when (res) {
            is RouterResult.Ok -> {
                val json = res.data
                val msg = json.optString("message", "")
                val explicitFailure = json.has("success") && !json.optBoolean("success", false) &&
                    msg != "0" && msg != "success"
                if (explicitFailure) {
                    RouterResult.BadResponse(msg.ifEmpty { "Router rejected cmd $cmd" }, json.toString())
                } else {
                    res
                }
            }
            is RouterResult.LimitedAccess -> RouterResult.LimitedAccess(cmd, res.message)
            else -> res
        }
    }

    private fun syncUnreadBadgeBestEffort() {
        val cb = onUnreadCountSync ?: return
        val bar = session.execute(cmd = 394, method = "GET", timeoutSeconds = 3L).getOrNull()
        if (bar != null) {
            cb(parseSmsUnreadCount(bar))
        } else {
            _inboxPage.value?.messages?.let { msgs ->
                if (_inboxPage.value?.pageNumber == 1) {
                    cb(msgs.count { !it.isRead })
                }
            }
        }
    }

    private fun RouterResult<JSONObject>.mapUnit(): RouterResult<Unit> = when (this) {
        is RouterResult.Ok -> RouterResult.Ok(Unit)
        is RouterResult.NotLoggedIn -> this
        is RouterResult.LimitedAccess -> this
        is RouterResult.Unreachable -> this
        is RouterResult.BadResponse -> this
    }

    companion object {
        internal fun parseSmsPage(json: JSONObject, folder: SmsFolder, pageNumber: Int): SmsPage {
            val total = json.optString("sms_total", "0").trim().toIntOrNull() ?: 0
            val receiveFull = json.optString("receive_full", "0") == "1"
            val sendFull = json.optString("send_full", "0") == "1"
            val rawList = json.optString("sms_list", "").trim()
            val messages = if (rawList.isEmpty() || rawList == "null") {
                emptyList()
            } else {
                rawList.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { encoded -> decodeSmsRow(encoded, folder) }
            }
            return SmsPage(
                folder = folder,
                messages = messages,
                totalCount = total.coerceAtLeast(messages.size),
                pageNumber = pageNumber,
                isReceiveFull = receiveFull,
                isSendFull = sendFull
            )
        }

        private fun decodeSmsRow(encoded: String, folder: SmsFolder): SmsMessage? {
            val decoded = decodeBase64Utf8(encoded)
            if (decoded.isBlank()) return null
            val parts = decoded.split(' ')
            if (parts.size < 4) return null
            val index = parts[0].trim()
            if (index.isEmpty()) return null
            val flag = parts[1].trim()
            val phone = parts[2].replace('$', ' ').trim()
            val datePart = parts.getOrNull(3).orEmpty()
            val timePart = parts.getOrNull(4).orEmpty()
            val timestamp = listOf(datePart, timePart).filter { it.isNotBlank() }.joinToString(" ")
            val text = if (parts.size > 5) parts.drop(5).joinToString(" ") else ""
            return SmsMessage(
                index = index,
                isRead = flag == "1",
                phoneNumber = phone.ifEmpty { "-" },
                timestamp = timestamp,
                text = text,
                folder = folder
            )
        }

        internal fun parseSmsSettings(json: JSONObject): SmsSettingsConfig = SmsSettingsConfig(
            smsEnabled = json.optString("smsSw", "1") == "1",
            smscEditable = json.optString("smscSw", "0") == "1",
            smscNumber = json.optString("dmCsca", "").trim(),
            maxSize = json.optString("maxSize", "100").trim().toIntOrNull() ?: 100,
            maxLength = json.optString("maxLen", "640").trim().toIntOrNull() ?: 640
        )
    }
}
