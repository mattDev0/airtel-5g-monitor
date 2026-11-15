package com.airtel.monitor.ui.feature.settings

import com.airtel.monitor.core.datastore.AppPreferencesSnapshot
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.datastore.ThemePreference
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterSettings
import com.airtel.monitor.core.network.RouterTransport
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.repository.LanRepository
import com.airtel.monitor.data.repository.MobileRepository
import com.airtel.monitor.data.repository.SimRepository
import com.airtel.monitor.data.repository.SystemRepository
import com.airtel.monitor.data.repository.WifiRepository
import com.airtel.monitor.data.repository.WifiRepository.WifiBand
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiLanSimAndSettingsTest {

    private class FakeTransport : RouterTransport {
        val requests = mutableListOf<JSONObject>()
        val handlers = mutableMapOf<String, (JSONObject) -> JSONObject>()
        private var tokenSeq = 0

        init {
            on(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
            on(100) { JSONObject().put("success", true).put("sessionId", "SID") }
            on(233) { JSONObject().put("success", true).put("token", "WT-${++tokenSeq}") }
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

    private var hostSetting = "192.168.1.1"
    private val settings = object : RouterSettings {
        override val routerHost: String get() = hostSetting
        override val username: String = "root"
        override val password: String = "admin"
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
    fun `wifi repository loads details validates forbid list and syncs both bands`() = runTest(testDispatcher) {
        var band24State = JSONObject()
            .put("success", true)
            .put("wifiOpen", "1")
            .put("ssid", encodeBase64Utf8("Airtel_2G"))
            .put("key", "oldpass123")
            .put("broadcast", "1")
            .put("authenticationType", "2")
            .put("channel", "6")
            .put("wifiSames", "0")
            .put("maxStaNum", "32")

        var band5State = JSONObject()
            .put("success", true)
            .put("wifiOpen", "1")
            .put("ssid", encodeBase64Utf8("Airtel_5G"))
            .put("key", "oldpass123")
            .put("broadcast", "1")
            .put("authenticationType", "2")
            .put("channel", "149")
            .put("wifiSames", "0")

        transport.on(80, "GET") {
            JSONObject()
                .put("success", true)
                .put("wifi_ssid_forbid", "BlockedNet")
                .put("wifi_pwd_forbid", "12345678")
        }
        transport.on(2, "GET") { JSONObject(band24State.toString()) }
        transport.on(2, "POST") { req ->
            band24State = JSONObject(band24State.toString())
                .put("ssid", req.getString("ssid"))
                .put("key", req.getString("key"))
                .put("broadcast", req.getString("broadcast"))
                .put("authenticationType", req.getString("authenticationType"))
                .put("wifiSames", req.optString("wifiSames", "0"))
            JSONObject().put("success", true)
        }
        transport.on(211, "GET") { JSONObject(band5State.toString()) }
        transport.on(211, "POST") { req ->
            band5State = JSONObject(band5State.toString())
                .put("ssid", req.getString("ssid"))
                .put("key", req.getString("key"))
                .put("broadcast", req.getString("broadcast"))
                .put("authenticationType", req.getString("authenticationType"))
                .put("wifiSames", req.optString("wifiSames", "0"))
            JSONObject().put("success", true)
        }

        val wifiRepo = WifiRepository(session, writer)
        val loaded = wifiRepo.loadWifiBandDetails() as RouterResult.Ok
        assertEquals("Airtel_2G", loaded.data.band24g.ssid)
        assertEquals("Airtel_5G", loaded.data.band5g.ssid)

        // Forbidden SSID and password rejected locally before POST
        val forbidSsidRes = wifiRepo.saveWifiBandConfig(
            band = WifiBand.BAND_24G,
            ssid = "BlockedNet",
            key = "validpass1",
            authenticationType = "2",
            visibleBroadcast = true
        )
        assertTrue(forbidSsidRes is RouterResult.BadResponse)
        assertEquals(0, transport.sent(2, "POST").size)

        // Valid paired write sends Base64 SSID and updates both 2.4G and 5G
        val saveRes = wifiRepo.saveWifiBandConfig(
            band = WifiBand.BAND_24G,
            ssid = "Lagos_5G",
            key = "NewPass#99",
            authenticationType = "4",
            visibleBroadcast = false,
            wifiSames = true,
            applySameToBothBands = true
        ) as RouterResult.Ok

        assertEquals("Lagos_5G", saveRes.data.band24g.ssid)
        assertEquals("Lagos_5G", saveRes.data.band5g.ssid)
        assertFalse(saveRes.data.band5g.visibleBroadcast)
        assertTrue(saveRes.data.band24g.wifiSames)

        val post24 = transport.sent(2, "POST").single()
        assertEquals(encodeBase64Utf8("Lagos_5G"), post24.getString("ssid"))
        assertEquals("32", post24.getString("maxStaNum"))
        assertEquals(1, transport.sent(211, "POST").size)
    }

    @Test
    fun `lan repository preserves router keys including bindPort0 and saves dhcp config`() = runTest(testDispatcher) {
        var lanState = JSONObject()
            .put("success", true)
            .put("lanIp", "192.168.1.1")
            .put("netMask", "255.255.255.0")
            .put("dhcpServer", "1")
            .put("main_dns", "1.1.1.1")
            .put("vice_dns", "8.8.8.8")
            .put("ipBegin", "192.168.1.100")
            .put("ipEnd", "192.168.1.200")
            .put("expireTime", "24h")
            .put("ipv6_mode", "0")
            .put("ipv6_startIp", "::")
            .put("ipv6_endIp", "::")
            .put("ipv6_main_dns", "")
            .put("ipv6_vice_dns", "")
            .put("supportList", "1")
            .put("bindPort0", "eth0")

        transport.on(3, "GET") { JSONObject(lanState.toString()) }
        transport.on(3, "POST") { req ->
            lanState = JSONObject(lanState.toString())
                .put("lanIp", req.getString("lanIp"))
                .put("netMask", req.getString("netMask"))
                .put("dhcpServer", req.getString("dhcpServer"))
                .put("ipBegin", req.optString("ipBegin", lanState.optString("ipBegin")))
                .put("ipEnd", req.optString("ipEnd", lanState.optString("ipEnd")))
                .put("expireTime", req.optString("expireTime", lanState.optString("expireTime")))
                .put("main_dns", req.getString("main_dns"))
                .put("vice_dns", req.getString("vice_dns"))
            JSONObject().put("success", true)
        }

        val repo = LanRepository(session, writer)
        val initial = repo.loadLanConfig() as RouterResult.Ok
        assertEquals("192.168.1.1", initial.data.lanIp)

        val saved = repo.saveLanDhcpSettings(
            lanIp = "192.168.10.1",
            netMask = "255.255.255.0",
            dhcpEnabled = true,
            ipBegin = "192.168.10.50",
            ipEnd = "192.168.10.150",
            expireTime = "12h",
            primaryDns = "9.9.9.9",
            secondaryDns = ""
        ) as RouterResult.Ok

        assertEquals("192.168.10.1", saved.data.lanIp)
        assertEquals("12h", saved.data.expireTime)
        val sentPost = transport.sent(3, "POST").single()
        assertEquals("eth0", sentPost.getString("bindPort0"))
        assertEquals("0", sentPost.getString("ipv6_mode"))
    }

    @Test
    fun `sim repository handles status lock toggle and wrong pin remaining attempts`() = runTest(testDispatcher) {
        var pinSwitch = "0"
        var pinLeft = 3
        transport.on(113, "GET") {
            JSONObject().put("success", true).put("sim_status", "1").put("current_card_type", "USIM")
        }
        transport.on(134, "GET") {
            JSONObject()
                .put("success", true)
                .put("lock_pin_switch", pinSwitch)
                .put("lock_pin_flag", "0")
                .put("lock_puk_flag", "0")
                .put("pin_left_times", pinLeft.toString())
                .put("puk_left_times", "10")
        }
        transport.on(51, "POST") { req ->
            if (req.optString("pin") == "1234") {
                pinSwitch = req.optString("enable", pinSwitch)
                JSONObject().put("success", true).put("message", "0")
            } else {
                pinLeft -= 1
                JSONObject().put("success", true).put("message", "1")
            }
        }

        val simRepo = SimRepository(session, writer)
        val wrong = simRepo.setPinLockEnabled(enable = true, pin = "9999")
        assertTrue(wrong is RouterResult.BadResponse)
        assertEquals(2, simRepo.state.value?.pinAttemptsLeft)

        val ok = simRepo.setPinLockEnabled(enable = true, pin = "1234") as RouterResult.Ok
        assertTrue(ok.data.pinLockEnabled)
        assertEquals("1", transport.sent(51, "POST").last().getString("subcmd"))
    }

    @Test
    fun `settings viewmodel auto updates router host when gateway changes`() = runTest(testDispatcher) {
        var lanState = JSONObject()
            .put("success", true)
            .put("lanIp", "192.168.1.1")
            .put("netMask", "255.255.255.0")
            .put("dhcpServer", "1")
            .put("main_dns", "1.1.1.1")
            .put("vice_dns", "")
            .put("ipBegin", "192.168.1.100")
            .put("ipEnd", "192.168.1.200")
            .put("expireTime", "24h")
        transport.on(3, "GET") { JSONObject(lanState.toString()) }
        transport.on(3, "POST") { req ->
            lanState = JSONObject(lanState.toString())
                .put("lanIp", req.getString("lanIp"))
                .put("ipBegin", req.getString("ipBegin"))
                .put("ipEnd", req.getString("ipEnd"))
            JSONObject().put("success", true)
        }

        val prefsFlow = MutableStateFlow(AppPreferencesSnapshot())
        val updatedHosts = mutableListOf<String>()
        var refreshCount = 0

        val vm = SettingsHubViewModel(
            preferencesFlow = prefsFlow,
            storedPasswordProvider = { "admin" },
            updatePreferences = { host, _, _, _, _, _, _ ->
                if (host != null) {
                    hostSetting = host
                    updatedHosts += host
                    prefsFlow.value = prefsFlow.value.copy(routerHost = host)
                }
            },
            resetStatusState = {},
            routerStateFlow = MutableStateFlow(RouterState()),
            wifiRepository = WifiRepository(session, writer),
            lanRepository = LanRepository(session, writer),
            simRepository = SimRepository(session, writer),
            mobileRepository = MobileRepository(session, writer),
            systemRepository = SystemRepository(session),
            emitStatusMessage = {},
            onIntervalChanged = {},
            triggerRefresh = { refreshCount++ },
            ioDispatcher = testDispatcher
        )

        val collector = launch { vm.uiState.collect {} }
        vm.uiState.first { it.routerHost == "192.168.1.1" }

        vm.saveLanDhcpSettings(
            lanIp = "192.168.50.1",
            netMask = "255.255.255.0",
            dhcpEnabled = true,
            ipBegin = "192.168.50.10",
            ipEnd = "192.168.50.100",
            expireTime = "24h",
            primaryDns = "1.1.1.1",
            secondaryDns = "",
            updateHostIfChanged = true
        )
        advanceUntilIdle()

        assertEquals(listOf("192.168.50.1"), updatedHosts)
        assertEquals("192.168.50.1", vm.uiState.value.routerHost)
        assertEquals(1, refreshCount)
        assertEquals(ThemePreference.SYSTEM, vm.uiState.value.themePreference)
        assertEquals(SpeedUnitPreference.MBPS, vm.uiState.value.speedUnitPreference)
        collector.cancel()
    }
}
