package com.airtel.monitor.ui.feature.messages

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterSettings
import com.airtel.monitor.core.network.RouterTransport
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.util.SmsValidators
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.local.InMemoryDailyUsageStore
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.model.SmsFolder
import com.airtel.monitor.data.repository.SmsRepository
import com.airtel.monitor.domain.MonthlyUsageSummary
import com.airtel.monitor.ui.feature.usage.DataUsageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesAndUsageTest {

    private class FakeTransport : RouterTransport {
        val requests = mutableListOf<JSONObject>()
        val handlers = mutableMapOf<String, (JSONObject) -> JSONObject>()
        private var tokenSeq = 0

        init {
            on(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
            on(100) { JSONObject().put("success", true).put("sessionId", "SID") }
            on(233) { JSONObject().put("success", true).put("token", "T-${++tokenSeq}") }
        }

        fun on(cmd: Int, method: String? = null, handler: (JSONObject) -> JSONObject) {
            val key = if (method == null) "$cmd" else "$cmd:$method"
            handlers[key] = handler
        }

        override fun post(url: String, body: String, timeoutSeconds: Long): String {
            val req = JSONObject(body)
            requests += req
            val cmd = req.getInt("cmd")
            val method = req.optString("method")
            val handler = handlers["$cmd:$method"] ?: handlers["$cmd"]
                ?: return JSONObject().put("success", true).toString()
            return handler(req).toString()
        }

        fun sent(cmd: Int, method: String): List<JSONObject> =
            requests.filter { it.getInt("cmd") == cmd && it.optString("method") == method }
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transport: FakeTransport
    private lateinit var session: RouterSession
    private lateinit var writer: SettingsWriter

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        transport = FakeTransport()
        session = RouterSession(settings, transport)
        writer = SettingsWriter(session, delayFn = {})
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sms validators enforce phone pattern weighted length surrogates and capacity`() {
        assertNull(SmsValidators.validatePhoneNumber("+2348012345678"))
        assertNull(SmsValidators.validatePhoneNumber("121"))
        assertNotNull(SmsValidators.validatePhoneNumber(""))
        assertNotNull(SmsValidators.validatePhoneNumber("abc123"))

        assertEquals(5, SmsValidators.weightedSmsLength("Hello"))
        assertEquals(6, SmsValidators.weightedSmsLength("Hi你好")) // 2 + 2*2

        assertNull(SmsValidators.validateSmsBody("Hello router!"))
        assertNotNull(SmsValidators.validateSmsBody("   "))
        assertNotNull(SmsValidators.validateSmsBody("Bad emoji \uD83D\uDE00"))
        assertNotNull(SmsValidators.validateSmsBody("a".repeat(650), maxWeightedLength = 640))

        assertNull(SmsValidators.validateSmsMaxSize("200"))
        assertNotNull(SmsValidators.validateSmsMaxSize("10"))
        assertNotNull(SmsValidators.validateSmsMaxSize("1500"))
    }

    @Test
    fun `sms repository parses rows marks read deletes and enforces settings`() = runTest(testDispatcher) {
        var row1Read = "0"
        val row1Encoded = { encodeBase64Utf8("12 $row1Read Airtel\$NG 2026-11-15 14:20 Your data balance is 25 GB") }
        val row2Encoded = encodeBase64Utf8("13 1 +2348011122233 2026-11-15 15:00 Hello from mobile")

        var unreadCountInStatusBar = 1
        var smsSw = "1"
        var maxSize = "100"
        var smsc = "+2348020000009"

        transport.on(394, "GET") {
            JSONObject().put("success", true).put("sms_unread", unreadCountInStatusBar)
        }
        transport.on(12, "GET") { req ->
            when (req.optInt("subcmd")) {
                0 -> JSONObject()
                    .put("success", true)
                    .put("sms_total", "2")
                    .put("receive_full", "0")
                    .put("sms_list", "${row1Encoded()},$row2Encoded")
                else -> JSONObject()
                    .put("success", true)
                    .put("sms_total", "0")
                    .put("send_full", "0")
                    .put("sms_list", "")
            }
        }
        transport.on(12, "POST") { req ->
            if (req.optString("index") == "12" || req.optString("index") == "READ ALL") {
                row1Read = "1"
                unreadCountInStatusBar = 0
            }
            JSONObject().put("success", true).put("message", "0")
        }
        transport.on(13, "POST") {
            JSONObject().put("success", true).put("message", "0")
        }
        var sendCode = "502"
        transport.on(14, "POST") {
            JSONObject().put("success", true).put("message", sendCode)
        }
        transport.on(16, "GET") {
            JSONObject()
                .put("success", true)
                .put("smsSw", smsSw)
                .put("smscSw", "0") // carrier locked -> dmCsca omitted on save
                .put("dmCsca", smsc)
                .put("maxSize", maxSize)
                .put("maxLen", "640")
        }
        transport.on(16, "POST") { req ->
            smsSw = req.getString("smsSw")
            maxSize = req.getString("maxSize")
            JSONObject().put("success", true)
        }

        val syncedUnreads = mutableListOf<Int>()
        val repo = SmsRepository(session, writer) { syncedUnreads += it }

        val inbox = repo.loadMessages(SmsFolder.INBOX, page = 1) as RouterResult.Ok
        assertEquals(2, inbox.data.messages.size)
        assertEquals("Airtel NG", inbox.data.messages[0].phoneNumber)
        assertFalse(inbox.data.messages[0].isRead)
        assertEquals("Your data balance is 25 GB", inbox.data.messages[0].text)
        assertEquals(1, syncedUnreads.last())

        val markRes = repo.markRead(listOf("12"))
        assertTrue(markRes is RouterResult.Ok)
        assertTrue(repo.inboxPage.value!!.messages[0].isRead)
        assertEquals(0, syncedUnreads.last())

        // Delete Outbox item uses subcmd 1
        val delRes = repo.deleteMessages(SmsFolder.OUTBOX, listOf("99"))
        assertTrue(delRes is RouterResult.Ok)
        val delReq = transport.sent(13, "POST").single()
        assertEquals(1, delReq.getInt("subcmd"))
        assertEquals("99", delReq.getString("index"))

        // Send rejects storage-full 502, then succeeds on 0
        val fullErr = repo.sendSms("+2348011122233", "Test")
        assertTrue(fullErr is RouterResult.BadResponse)
        sendCode = "0"
        val sendOk = repo.sendSms("+2348011122233", "Test")
        assertTrue(sendOk is RouterResult.Ok)
        assertEquals(encodeBase64Utf8("Test"), transport.sent(14, "POST").last().getString("content"))

        // Settings omit dmCsca when smscSw == "0"
        val savedCfg = repo.saveSmsSettings(smsEnabled = false, smscNumber = smsc, maxSize = 250) as RouterResult.Ok
        assertFalse(savedCfg.data.smsEnabled)
        assertEquals(250, savedCfg.data.maxSize)
        val cfgPost = transport.sent(16, "POST").single()
        assertFalse(cfgPost.has("dmCsca"))
    }

    @Test
    fun `daily usage store computes daily deltas carries over prior baseline and handles cycle reset`() {
        val store = InMemoryDailyUsageStore(maxDays = 31)
        val day1Ms = 1_763_164_800_000L // 2025-11-15 UTC
        val day2Ms = day1Ms + 86_400_000L

        store.recordSample(day1Ms, MonthlyUsageSummary(usedMb = 1_000.0))
        store.recordSample(day1Ms + 3_600_000L, MonthlyUsageSummary(usedMb = 1_450.0))
        store.recordSample(day2Ms, MonthlyUsageSummary(usedMb = 1_700.0))
        // Billing cycle reset on day 2
        store.recordSample(day2Ms + 7_200_000L, MonthlyUsageSummary(usedMb = 120.0))

        val list = store.history.value
        assertEquals(2, list.size)
        assertEquals(450.0, list[0].dailyMb, 0.01)
        assertEquals(120.0, list[1].dailyMb, 0.01)

        store.clearHistory()
        assertTrue(store.history.value.isEmpty())
    }

    @Test
    fun `messages and usage viewmodels coordinate folder reads and clear history`() = runTest(testDispatcher) {
        transport.on(12, "GET") { req ->
            val subcmd = req.optInt("subcmd")
            if (subcmd == 0) {
                JSONObject()
                    .put("success", true)
                    .put("sms_total", "1")
                    .put("sms_list", encodeBase64Utf8("7 0 Airtel 2026-11-15 10:00 Welcome"))
            } else {
                JSONObject().put("success", true).put("sms_total", "0").put("sms_list", "")
            }
        }
        transport.on(12, "POST") { JSONObject().put("success", true).put("message", "0") }
        transport.on(16, "GET") {
            JSONObject().put("success", true).put("smsSw", "1").put("maxSize", "100").put("maxLen", "640")
        }

        val repo = SmsRepository(session, writer)
        val unreadFlow = MutableStateFlow(1)
        val messagesVm = MessagesViewModel(
            smsRepository = repo,
            unreadCountFlow = unreadFlow,
            emitStatusMessage = {},
            ioDispatcher = testDispatcher
        )

        val collector = launch { messagesVm.uiState.collect {} }
        messagesVm.ensureLoadedOnOpen()
        advanceUntilIdle()

        assertEquals(1, messagesVm.uiState.value.inboxPage.messages.size)
        val msg = messagesVm.uiState.value.inboxPage.messages.single()
        messagesVm.openMessage(msg)
        advanceUntilIdle()
        assertTrue(messagesVm.uiState.value.selectedMessage?.isRead == true)
        collector.cancel()

        val usageStore = InMemoryDailyUsageStore()
        usageStore.recordSample(1_763_164_800_000L, MonthlyUsageSummary(usedMb = 2048.0))
        val routerFlow = MutableStateFlow<RouterState?>(
            RouterState(monthlyUsage = MonthlyUsageSummary(usedMb = 2048.0, limitEnabled = true, limitMb = 10_240.0))
        )
        val usageVm = DataUsageViewModel(
            routerStateFlow = routerFlow,
            dailyUsageStore = usageStore,
            triggerRefresh = {}
        )
        val uCollector = launch { usageVm.uiState.collect {} }
        val loadedUsage = usageVm.uiState.first { it.dailyHistory.isNotEmpty() }
        assertEquals(1, loadedUsage.dailyHistory.size)
        usageVm.clearLocalHistory()
        assertTrue(usageStore.history.value.isEmpty())
        uCollector.cancel()
    }
}
