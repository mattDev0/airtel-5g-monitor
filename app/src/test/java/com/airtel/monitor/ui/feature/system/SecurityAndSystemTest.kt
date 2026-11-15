package com.airtel.monitor.ui.feature.system

import com.airtel.monitor.core.datastore.AppPreferencesSnapshot
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterSettings
import com.airtel.monitor.core.network.RouterTransport
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.util.SensitiveFormatters
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.model.AlgSettings
import com.airtel.monitor.data.model.FirewallLevel
import com.airtel.monitor.data.model.LogGrade
import com.airtel.monitor.data.model.ScheduledRebootConfig
import com.airtel.monitor.data.remote.SpeedTestRunner
import com.airtel.monitor.data.repository.SecurityRepository
import com.airtel.monitor.data.repository.SystemRepository
import com.airtel.monitor.ui.feature.security.SecurityViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class SecurityAndSystemTest {

    private class FakeTransport : RouterTransport {
        val requests = mutableListOf<JSONObject>()
        val rawHandlers = mutableMapOf<String, (JSONObject) -> String>()
        private var tokenSeq = 0

        init {
            onJson(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
            onJson(100) { JSONObject().put("success", true).put("sessionId", "SID").put("level", "2") }
            onJson(233) { JSONObject().put("success", true).put("token", "T-${++tokenSeq}") }
        }

        fun onJson(cmd: Int, method: String? = null, handler: (JSONObject) -> JSONObject) {
            val key = if (method == null) "$cmd" else "$cmd:$method"
            rawHandlers[key] = { req -> handler(req).toString() }
        }

        fun onRaw(cmd: Int, method: String? = null, handler: (JSONObject) -> String) {
            val key = if (method == null) "$cmd" else "$cmd:$method"
            rawHandlers[key] = handler
        }

        override fun post(url: String, body: String, timeoutSeconds: Long): String {
            val req = JSONObject(body)
            requests += req
            val cmd = req.getInt("cmd")
            val method = req.optString("method")
            val handler = rawHandlers["$cmd:$method"] ?: rawHandlers["$cmd"]
                ?: return JSONObject().put("success", true).toString()
            return handler(req)
        }

        fun sent(cmd: Int, method: String): List<JSONObject> =
            requests.filter { it.getInt("cmd") == cmd && it.optString("method") == method }
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transport: FakeTransport
    private lateinit var session: RouterSession
    private lateinit var writer: SettingsWriter
    private lateinit var settings: RouterSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        transport = FakeTransport()
        settings = object : RouterSettings {
            override var routerHost = "192.168.1.1"
            override var username = "user"
            override var password = "OldPassword!1"
        }
        session = RouterSession(settings, transport)
        writer = SettingsWriter(session, delayFn = {})
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sensitive formatters validate router password and mask identifiers`() {
        assertNull(SensitiveFormatters.validateRouterPassword("OldPass!1", "NewPass!23", "NewPass!23"))
        assertNotNull(SensitiveFormatters.validateRouterPassword("", "NewPass!23", "NewPass!23"))
        assertNotNull(SensitiveFormatters.validateRouterPassword("OldPass!1", "short!1A", "mismatch"))
        assertNotNull(SensitiveFormatters.validateRouterPassword("OldPass!1", "NoSpace !23", "NoSpace !23"))
        assertNotNull(SensitiveFormatters.validateRouterPassword("OldPass!1", "lowercase!1", "lowercase!1"))
        assertNotNull(SensitiveFormatters.validateRouterPassword("OldPass!1", "NoSymbol123", "NoSymbol123"))
        assertNotNull(SensitiveFormatters.validateRouterPassword("OldPass!1", "OldPass!1", "OldPass!1"))

        assertEquals("•••••••••••9012", SensitiveFormatters.maskIdentifier("860123456789012"))
        assertEquals("REDACTED", SensitiveFormatters.maskIdentifier("REDACTED"))
        assertEquals("2d 00h 00m", SensitiveFormatters.formatUptimeSeconds("172800"))
        assertNull(SensitiveFormatters.validateLogSizeKb("500"))
        assertNotNull(SensitiveFormatters.validateLogSizeKb("0"))
        assertNotNull(SensitiveFormatters.validateLogSizeKb("2500"))
    }

    @Test
    fun `security repository and viewmodel save firewall and alg flags with verification`() = runTest(testDispatcher) {
        var currentFwSwitch = "1"
        var currentFwLevel = "low"
        var currentAlg = mutableMapOf(
            "L2TPSW_Flag" to "1",
            "IPSecSW_Flag" to "1",
            "H323SW_Flag" to "1",
            "RTSPSW_Flag" to "1",
            "SIPSW_Flag" to "0",
            "FTPSW_Flag" to "1",
            "PPTPSW_Flag" to "1"
        )

        transport.onJson(367, "GET") {
            JSONObject()
                .put("success", true)
                .put("fw_switch", currentFwSwitch)
                .put("level", currentFwLevel)
        }
        transport.onJson(367, "POST") { req ->
            currentFwSwitch = req.getString("fw_switch")
            currentFwLevel = req.getString("level")
            JSONObject().put("success", true)
        }
        transport.onJson(504, "GET") {
            JSONObject(currentAlg as Map<*, *>).put("success", true)
        }
        transport.onJson(504, "POST") { req ->
            currentAlg.keys.forEach { k -> currentAlg[k] = req.getString(k) }
            JSONObject().put("success", true)
        }

        val repo = SecurityRepository(session, writer)
        val messages = mutableListOf<String>()
        val vm = SecurityViewModel(repo, ioDispatcher = testDispatcher) { messages += it }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.firewallEnabled)
        assertEquals(FirewallLevel.LOW, vm.uiState.value.firewallLevel)
        assertFalse(vm.uiState.value.alg.sip)

        vm.saveFirewall(enabled = true, level = FirewallLevel.HIGH)
        advanceUntilIdle()
        assertEquals(FirewallLevel.HIGH, vm.uiState.value.firewallLevel)

        vm.saveAlg(
            AlgSettings(
                l2tp = false,
                ipsec = true,
                h323 = true,
                rtsp = true,
                sip = true,
                ftp = false,
                pptp = true
            )
        )
        advanceUntilIdle()
        assertFalse(vm.uiState.value.alg.l2tp)
        assertTrue(vm.uiState.value.alg.sip)
        assertFalse(vm.uiState.value.alg.ftp)
        assertEquals("ALG settings saved", messages.last())
    }

    @Test
    fun `system repository parses 207 about, syslog, scheduled reboot, log settings, and cmd 102 password update`() = runTest(testDispatcher) {
        transport.onJson(207, "GET") {
            JSONObject()
                .put("success", true)
                .put("uptime", "172800")
                .put("cpu_usage", "12.5")
                .put("device_temperature", "52")
                .put("memory", "44")
                .put("board_type", "ZLT X17M")
                .put("idu_dev_type", "ZLT W304VA PRO")
                .put("hwversion", "V1.0")
                .put("real_fwversion", "V1.2.3")
                .put("build_date", "2025-11-12")
                .put("module_imei", "860123456789012")
                .put("negotiatton_rate", "1000,0,100,0")
        }
        transport.onJson(11, "GET") { JSONObject().put("success", true).put("systime", "2026-11-15 16:00:00") }

        var schedSwitch = "0"
        var schedHour = "03"
        var schedMin = "00"
        var schedWeek = "1,3,5"
        transport.onJson(263, "GET") {
            JSONObject()
                .put("success", true)
                .put("timrRestartSwitch", schedSwitch)
                .put("restartHour", schedHour)
                .put("restartMinute", schedMin)
                .put("restartWeek", schedWeek)
        }
        transport.onJson(263, "POST") { req ->
            schedSwitch = req.getString("timrRestartSwitch")
            if (schedSwitch == "1") {
                schedHour = req.getString("restartHour")
                schedMin = req.getString("restartMinute")
                schedWeek = req.getString("restartWeek")
            }
            JSONObject().put("success", true)
        }

        var logGrade = "0"
        var logSize = "500000"
        transport.onJson(322, "GET") {
            JSONObject().put("success", true).put("logGrade", logGrade).put("logSize", logSize)
        }
        transport.onJson(322, "POST") { req ->
            logGrade = req.getString("logGrade")
            logSize = req.get("logSize").toString()
            JSONObject().put("success", true)
        }

        var syslogRaw = "Jan 1 00:00:01 kernel: boot\nJan 1 00:01:00 daemon: linked\n"
        transport.onRaw(17, "GET") { syslogRaw }
        transport.onJson(17, "POST") {
            syslogRaw = ""
            JSONObject().put("success", true).put("message", "0")
        }

        transport.onJson(102, "POST") { req ->
            if (req.getString("old_passwd") == encodeBase64Utf8("Wrong!11")) {
                JSONObject().put("success", false).put("message", "passwd error")
            } else {
                JSONObject().put("success", true).put("message", "success")
            }
        }

        val repo = SystemRepository(session, writer)
        val prefsFlow = MutableStateFlow(AppPreferencesSnapshot())
        var savedNewPassword: String? = null
        var refreshCount = 0
        val statuses = mutableListOf<String>()
        val vm = SystemToolsViewModel(
            systemRepository = repo,
            preferencesFlow = prefsFlow,
            updateSavedPassword = { savedNewPassword = it },
            routerSession = session,
            speedTestRunner = SpeedTestRunner(),
            ioDispatcher = testDispatcher,
            onStatusMessage = { statuses += it },
            onForcePollRefresh = { refreshCount++ }
        )
        advanceUntilIdle()

        val about = vm.uiState.value.aboutInfo!!
        assertEquals("ZLT X17M", about.oduModel)
        assertEquals("ZLT W304VA PRO", about.iduModel)
        assertEquals("2d 00h 00m", about.uptimeText)
        assertEquals("44%", about.memoryText)
        assertEquals("52 °C", about.temperature)
        assertEquals(listOf("Port 1: 1000 Mbps", "Port 3: 100 Mbps"), about.linkRates)
        assertEquals("2026-11-15 16:00:00", vm.uiState.value.routerTime)

        vm.saveScheduledReboot(
            ScheduledRebootConfig(enabled = true, hour = 4, minute = 15, weekDays = setOf(1, 5, 0)),
            onError = { error("Unexpected schedule error: $it") }
        )
        advanceUntilIdle()
        assertEquals(setOf(1, 5, 0), vm.uiState.value.scheduledReboot?.weekDays)
        assertEquals("1,5,0", transport.sent(263, "POST").last().getString("restartWeek"))

        vm.loadSysLog()
        advanceUntilIdle()
        assertEquals(listOf("Jan 1 00:01:00 daemon: linked", "Jan 1 00:00:01 kernel: boot"), vm.uiState.value.sysLogLines)

        vm.saveLogSettings(LogGrade.WARNING, "1024", onError = { error("Unexpected log error: $it") })
        advanceUntilIdle()
        assertEquals(LogGrade.WARNING, vm.uiState.value.logSettings?.grade)
        assertEquals(1024, vm.uiState.value.logSettings?.sizeKb)

        vm.clearSysLog()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sysLogLines.isEmpty())

        // Wrong old password returns inline error without updating saved credentials
        vm.changeAdminPassword("Wrong!11", "NewPass!23", "NewPass!23")
        advanceUntilIdle()
        assertEquals("Current router password is incorrect", vm.uiState.value.passwordError)
        assertNull(savedNewPassword)

        // Valid password updates saved password, resets session, triggers poll refresh
        vm.changeAdminPassword("OldPassword!1", "NewPass!23", "NewPass!23")
        advanceUntilIdle()
        assertNull(vm.uiState.value.passwordError)
        assertEquals("NewPass!23", savedNewPassword)
        assertNull(session.currentSessionId())
        assertEquals(1, refreshCount)

        val passReq = transport.sent(102, "POST").last()
        assertEquals(3, passReq.getInt("subcmd"))
        assertEquals(encodeBase64Utf8("OldPassword!1"), passReq.getString("old_passwd"))
        assertEquals(encodeBase64Utf8("NewPass!23"), passReq.getString("newPasswd"))
        assertEquals("Router admin password updated", statuses.last())
    }

    @Test
    fun `speed test runner measures ping, jitter, download and upload offline via okhttp interceptor`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val bodyBytes = when {
                    url.contains("__down?bytes=0") -> ByteArray(0)
                    url.contains("__down") -> ByteArray(64 * 1024) { 0x41 }
                    else -> {
                        // Drain uploaded bytes just like server receipt
                        val sink = okio.Buffer()
                        req.body?.writeTo(sink)
                        ByteArray(0)
                    }
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyBytes.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            })
            .build()

        val runner = SpeedTestRunner(
            client = mockClient,
            baseUrl = "https://mock.speed.test",
            pingSamples = 3,
            downloadBytes = 64 * 1024L,
            uploadBytes = 32 * 1024
        )
        val result = runner.run()
        assertTrue(result is SpeedTestRunner.Result.Success)
        val success = result as SpeedTestRunner.Result.Success
        assertTrue(success.pingMs >= 0.0)
        assertTrue(success.jitterMs >= 0.0)
        assertTrue(success.downloadMbps > 0.0)
        assertTrue(success.uploadMbps > 0.0)
    }
}
