package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterSettings
import com.airtel.monitor.core.network.RouterTransport
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.data.dto.encodeBase64Utf8
import com.airtel.monitor.data.model.ApnProfile
import com.airtel.monitor.data.model.GuestWifiBandConfig
import com.airtel.monitor.data.model.IpPortFilterRule
import com.airtel.monitor.data.model.MacFilterRule
import com.airtel.monitor.data.model.ParentalSpeedRule
import com.airtel.monitor.data.model.ParentalTimeRule
import com.airtel.monitor.data.model.ParentalUrlRule
import com.airtel.monitor.data.model.PortFilterRule
import com.airtel.monitor.data.model.PortForwardRule
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.RouterTimeConfig
import com.airtel.monitor.data.model.StaticDhcpLease
import com.airtel.monitor.data.model.TrustedIpConfig
import com.airtel.monitor.data.model.UrlFilterRule
import com.airtel.monitor.data.model.WanLinkDetectConfig
import com.airtel.monitor.data.model.WifiAdvancedBandConfig
import com.airtel.monitor.data.model.WifiScheduleConfig
import com.airtel.monitor.data.repository.WifiRepository.WifiBand
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase71SchemasAndReposTest {

    private class FakeTransport : RouterTransport {
        val sent = mutableListOf<JSONObject>()
        val handlers = mutableMapOf<String, (JSONObject) -> String>()
        private var tokenSeq = 0

        init {
            on(232) { """{"success":true,"token":"CHALLENGE"}""" }
            on(100) { """{"success":true,"sessionId":"SID","level":"2"}""" }
            on(233) { """{"success":true,"token":"TOK-${++tokenSeq}"}""" }
            on(20) { """{"success":true}""" }
            on(417) { """{"success":true,"wifiStatus":"1"}""" }
        }

        fun on(cmd: Int, method: String? = null, handler: (JSONObject) -> String) {
            handlers[if (method == null) "$cmd" else "$cmd:$method"] = handler
        }

        override fun post(url: String, body: String, timeoutSeconds: Long): String {
            val req = JSONObject(body)
            sent.add(req)
            val key = "${req.getInt("cmd")}:${req.optString("method")}"
            val handler = handlers[key] ?: handlers["${req.getInt("cmd")}"]
            return handler?.invoke(req) ?: """{"success":true}"""
        }

        fun posts(cmd: Int): List<JSONObject> =
            sent.filter { it.getInt("cmd") == cmd && it.optString("method") == "POST" }
    }

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "admin"
        override val password = "Password!1"
    }

    private lateinit var transport: FakeTransport
    private lateinit var session: RouterSession
    private lateinit var writer: SettingsWriter

    @Before
    fun setUp() {
        transport = FakeTransport()
        session = RouterSession(settings, transport)
        writer = SettingsWriter(session, delayFn = {})
    }

    @Test
    fun `Batch A - USSD session send, decode UTF-16BE, cancel, and timeout error`() = runTest {
        val ussdRepo = UssdRepository(session, writer)
        transport.on(350, "GET") {
            """{"success":true,"message":"00480069","need_reply":"1"}"""
        }
        val res = ussdRepo.sendUssd("*123#")
        assertTrue(res is RouterResult.Ok)
        val ok = (res as RouterResult.Ok).data
        assertEquals("Hi", ok.text)
        assertTrue(ok.needReply)
        assertEquals("0", transport.posts(350).single().getString("subcmd"))
        assertEquals("*123#", transport.posts(350).single().getString("ussd_code"))

        val cancelRes = ussdRepo.cancelSession()
        assertTrue(cancelRes is RouterResult.Ok && cancelRes.data.cancelled)
        assertEquals("1", transport.posts(350).last().getString("subcmd"))

        transport.on(350, "POST") { """{"success":false,"message":"507"}""" }
        val timeoutRes = ussdRepo.sendUssd("*123#")
        assertTrue(timeoutRes is RouterResult.BadResponse)
    }

    @Test
    fun `Batch A - Mobile networkMode PLMN lock guard, APN save, and traffic quota`() = runTest {
        val mobileRepo = MobileRepository(session, writer)

        val lockedFlags = RouterFeatureFlags(isLoaded = true, lockPlmnFlag = true)
        val blocked = mobileRepo.setNetworkMode("1C", lockedFlags)
        assertTrue(blocked is RouterResult.BadResponse)
        assertTrue(transport.posts(256).isEmpty())

        var apnState = JSONObject("""{"success":true,"selectType":"IP","apnMTU":"1500","apnNatName":"1"}""")
        transport.on(213, "GET") { apnState.toString() }
        transport.on(213, "POST") { req ->
            apnState.put("selectType", req.getString("selectType"))
            apnState.put("apnMTU", req.getString("apnMTU"))
            apnState.put("apnNatName", req.getString("apnNatName"))
            """{"success":true}"""
        }
        val apnRes = mobileRepo.saveApnBasic("IPV4V6", 1480, natEnabled = false)
        assertTrue(apnRes is RouterResult.Ok)
        assertEquals(1480, (apnRes as RouterResult.Ok).data.mtu)
        assertFalse(apnRes.data.natEnabled)

        var apnList = JSONArray()
        transport.on(248, "GET") { JSONObject().put("success", true).put("apn_list", apnList).toString() }
        transport.on(248, "POST") { req ->
            apnList = req.getJSONArray("apn_list")
            """{"success":true}"""
        }
        val saveProfiles = mobileRepo.saveApnProfiles(
            listOf(ApnProfile(name = "Airtel", apnName = "airtelgprs.com", isDefault = true))
        )
        assertTrue(saveProfiles is RouterResult.Ok)
        assertEquals("airtelgprs.com", transport.posts(248).single().getJSONArray("apn_list").getJSONObject(0).getString("apnName"))

        var traffic = JSONObject("""{"success":true,"limitSwitch":"0","limitSize":"0","flow_limit_unit":"1","startDate":"1","dl_mon_flow":"10","ul_mon_flow":"2"}""")
        transport.on(337, "GET") { traffic.toString() }
        transport.on(337, "POST") { req ->
            if (req.has("statisticsReset")) {
                traffic.put("dl_mon_flow", req.getString("usedFlow"))
            } else {
                traffic.put("limitSwitch", req.getString("limitSwitch"))
                traffic.put("limitSize", req.getString("limitSize"))
                traffic.put("startDate", req.getString("startDate"))
            }
            """{"success":true}"""
        }
        val quotaRes = mobileRepo.saveTrafficLimit(true, "500", "1", 5)
        assertTrue(quotaRes is RouterResult.Ok)
        val calRes = mobileRepo.resetTrafficStatistics(12.5)
        assertTrue(calRes is RouterResult.Ok)
        assertEquals("12.5", transport.posts(337).last().getString("usedFlow"))
    }

    @Test
    fun `Batch B - Static DHCP leases, MAC default filter sync across 23-32-21, and Parental Control`() = runTest {
        val lanRepo = LanRepository(session, writer)
        var leaseArr = JSONArray()
        var bindArr = JSONArray()
        transport.on(115, "GET") { JSONObject().put("success", true).put("datas", leaseArr).toString() }
        transport.on(115, "POST") { req -> leaseArr = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(24, "GET") { JSONObject().put("success", true).put("datas", bindArr).toString() }
        transport.on(24, "POST") { req -> bindArr = req.getJSONArray("datas"); """{"success":true}""" }

        val leaseRes = lanRepo.saveStaticLeases(
            listOf(StaticDhcpLease("192.168.1.50", "aa-bb-cc-dd-ee-ff")),
            syncIpMacBinding = true
        )
        assertTrue(leaseRes is RouterResult.Ok)
        assertEquals("AA:BB:CC:DD:EE:FF", (leaseRes as RouterResult.Ok).data.single().mac)
        assertEquals(1, transport.posts(24).size)
        assertEquals(1, transport.posts(20).size)

        val secRepo = SecurityRepository(session, writer)
        var defFilter = JSONArray("""[{"enableRule":true,"acceptAll":true,"ippro":"IPV4"},{"enableRule":true,"acceptAll":true,"ippro":"IPV6"}]""")
        var macArr = JSONArray("""[{"mac":"00:11:22:33:44:55","remark":"Phone","ippro":"IPV4","enableRule":true,"enableLink":false}]""")
        var ipArr = JSONArray("""[{"ip":"192.168.1.9","destIp":"","srcPort":"","destPort":"","protocol":"All","ippro":"IPV4","remark":"","enableRule":true,"enableLink":false}]""")
        var portArr = JSONArray("""[{"port":"80","protocol":"tcp","ippro":"IPV4","remark":"","enableRule":true,"enableLink":false}]""")
        var defFilter30 = JSONArray(defFilter.toString())
        transport.on(28, "GET") { JSONObject().put("success", true).put("datas", defFilter).toString() }
        transport.on(28, "POST") { req -> defFilter = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(30, "GET") { JSONObject().put("success", true).put("datas", defFilter30).toString() }
        transport.on(30, "POST") { req -> defFilter30 = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(23, "GET") { JSONObject().put("success", true).put("datas", macArr).toString() }
        transport.on(23, "POST") { req -> macArr = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(32, "GET") { JSONObject().put("success", true).put("datas", ipArr).toString() }
        transport.on(32, "POST") { req -> ipArr = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(21, "GET") { JSONObject().put("success", true).put("datas", portArr).toString() }
        transport.on(21, "POST") { req -> portArr = req.getJSONArray("datas"); """{"success":true}""" }

        val polRes = secRepo.saveDefaultFilterPolicy(acceptAll = false)
        assertTrue(polRes is RouterResult.Ok)
        assertTrue(macArr.getJSONObject(0).getBoolean("enableLink"))
        assertTrue(ipArr.getJSONObject(0).getBoolean("enableLink"))
        assertTrue(portArr.getJSONObject(0).getBoolean("enableLink"))

        val pcRepo = ParentalControlRepository(session, writer)
        var pcOn = "0"
        var pcTime = JSONArray()
        var pcSpeed = JSONArray()
        var pcUrl = JSONArray()
        transport.on(391, "GET") { """{"success":true,"enable":"$pcOn"}""" }
        transport.on(391, "POST") { req -> pcOn = req.getString("enable"); """{"success":true}""" }
        transport.on(385, "GET") { JSONObject().put("success", true).put("datas", pcTime).toString() }
        transport.on(385, "POST") { req -> pcTime = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(382, "GET") { JSONObject().put("success", true).put("datas", pcSpeed).toString() }
        transport.on(382, "POST") { req -> pcSpeed = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(383, "GET") { JSONObject().put("success", true).put("datas", pcUrl).toString() }
        transport.on(383, "POST") { req -> pcUrl = req.getJSONArray("datas"); """{"success":true}""" }
        assertTrue(pcRepo.setEnabled(true) is RouterResult.Ok)
        assertTrue(
            pcRepo.saveTimeRules(
                listOf(ParentalTimeRule("00:11:22:33:44:55", "09:00", "17:00", "1,2,3"))
            ) is RouterResult.Ok
        )
        assertTrue(pcRepo.saveSpeedRules(listOf(ParentalSpeedRule("192.168.1.50", 200))) is RouterResult.Ok)
        assertTrue(pcRepo.saveUrlRules(listOf(ParentalUrlRule("00:11:22:33:44:55", "social.example.com"))) is RouterResult.Ok)
    }

    @Test
    fun `Batch C - Wi-Fi 417 preflight, Guest Wi-Fi Base64, STA guard, DFS bandwidth clamp, WPS random PIN, and schedule`() = runTest {
        val wifiRepo = WifiRepository(session, writer)

        // Preflight 417 blocks guest save
        transport.on(417, "GET") { """{"success":true,"wifiStatus":"0"}""" }
        val preflightFail = wifiRepo.saveGuestWifi(
            WifiBand.BAND_24G,
            GuestWifiBandConfig(wifiOpen = true, ssid = "GuestNet", key = "StrongPass1", mainWifiOpen = true)
        )
        assertTrue(preflightFail is RouterResult.BadResponse)
        transport.on(417, "GET") { """{"success":true,"wifiStatus":"1"}""" }

        // Guest Wi-Fi Base64 roundtrip
        var guest24 = JSONObject().put("success", true).put("wifiOpen", "0").put("ssid", "")
        transport.on(2, "GET") { guest24.toString() }
        transport.on(2, "POST") { req ->
            guest24.put("wifiOpen", req.getString("wifiOpen"))
            guest24.put("ssid", req.getString("ssid"))
            guest24.put("authenticationType", req.getString("authenticationType"))
            """{"success":true}"""
        }
        val guestOk = wifiRepo.saveGuestWifi(
            WifiBand.BAND_24G,
            GuestWifiBandConfig(wifiOpen = true, ssid = "MyGuest", key = "GuestPass123", mainWifiOpen = true)
        )
        assertTrue(guestOk is RouterResult.Ok)
        assertEquals(encodeBase64Utf8("MyGuest"), transport.posts(2).single().getString("ssid"))

        // STA read-only guard and 5G DFS=false clamping bandWidth="4" to "3"
        val staRejected = wifiRepo.saveWifiAdvanced(
            WifiBand.BAND_5G,
            WifiAdvancedBandConfig(clientStaReadOnly = true)
        )
        assertTrue(staRejected is RouterResult.BadResponse)

        var adv5 = JSONObject().put("success", true).put("wifiOpen", "1").put("bandWidth", "0").put("maxNum", "32")
        transport.on(231, "GET") { adv5.toString() }
        transport.on(231, "POST") { req ->
            adv5.put("bandWidth", req.getString("bandWidth"))
            adv5.put("maxNum", req.getString("maxNum"))
            adv5.put("wifiOpen", req.getString("wifiOpen"))
            """{"success":true}"""
        }
        val advOk = wifiRepo.saveWifiAdvanced(
            WifiBand.BAND_5G,
            WifiAdvancedBandConfig(wifiOpen = true, bandWidth = "4", dfsSwitch = false, maxNum = 16)
        )
        assertTrue(advOk is RouterResult.Ok)
        assertEquals("3", transport.posts(231).single().getString("bandWidth"))

        // TX Power 411
        var pwr411 = JSONObject().put("success", true).put("txPower", "50").put("txPower5g", "50")
        transport.on(411, "GET") { pwr411.toString() }
        transport.on(411, "POST") { req ->
            pwr411.put("txPower", req.getString("txPower"))
            pwr411.put("txPower5g", req.getString("txPower5g"))
            """{"success":true}"""
        }
        assertTrue(wifiRepo.saveWifiPower411("15") is RouterResult.Ok)

        // WPS random PIN 244 (without "success":true) & 325 completion clear
        transport.on(244, "POST") { """{"random_ping":"87654325"}""" }
        val pinRes = wifiRepo.openRandomWpsPin(WifiBand.BAND_5G)
        assertEquals(RouterResult.Ok("87654325"), pinRes)
        transport.on(325, "GET") { """{"success":true,"wps_is_success_state":"1"}""" }
        transport.on(325, "POST") { """{"success":true}""" }
        assertEquals(RouterResult.Ok(true), wifiRepo.pollRandomWpsSuccess())
        assertEquals("0", transport.posts(325).single().getString("wps_is_success_state"))

        // Wi-Fi schedule 503
        var sched = JSONObject("""{"success":true,"wifiSwitch":"0"}""")
        transport.on(503, "GET") { sched.toString() }
        transport.on(503, "POST") { req ->
            sched.put("wifiSwitch", req.getString("wifiSwitch"))
            if (req.has("wifi_on_start_hour")) {
                sched.put("wifi_on_start_hour", req.getString("wifi_on_start_hour"))
                sched.put("wifi_off_start_hour", req.getString("wifi_off_start_hour"))
            }
            """{"success":true}"""
        }
        assertTrue(wifiRepo.saveWifiSchedule(WifiScheduleConfig(true, 8, 0, 22, 30)) is RouterResult.Ok)
    }

    @Test
    fun `Batch D - Security DMZ, DDoS, UPnP, cmd 400 conflict, PortForward, IP-Port-URL filters, and Trusted IPs`() = runTest {
        val secRepo = SecurityRepository(session, writer)

        // DMZ 172 subcmd 6
        var dmzState = JSONObject("""{"success":true,"enabled":"0","ip":""}""")
        transport.on(172, "GET") { dmzState.toString() }
        transport.on(172, "POST") { req ->
            dmzState.put("enabled", req.getString("enabled")).put("ip", req.getString("ip"))
            """{"success":true}"""
        }
        val dmzRes = secRepo.saveDmz(true, "192.168.1.99")
        assertTrue(dmzRes is RouterResult.Ok && dmzRes.data.enabled && dmzRes.data.ip == "192.168.1.99")

        // DDoS 275 & UPnP 333
        var ddosFlag = "0"
        transport.on(275, "GET") { """{"success":true,"ddosSwich":"$ddosFlag"}""" }
        transport.on(275, "POST") { req -> ddosFlag = req.getString("ddosSwich"); """{"success":true}""" }
        assertTrue(secRepo.saveDdos(true) is RouterResult.Ok)

        var upnpFlag = "0"
        transport.on(333, "GET") { """{"success":true,"upnpSwitch":"$upnpFlag"}""" }
        transport.on(333, "POST") { req -> upnpFlag = req.getString("upnpSwitch"); """{"success":true}""" }
        transport.on(202, "GET") { """{"success":false,"message":"LIMITED_ACCESS"}""" }
        val upnpLoaded = secRepo.loadUpnp()
        assertTrue(upnpLoaded is RouterResult.Ok)
        assertTrue(secRepo.saveUpnp(true) is RouterResult.Ok)

        // System ports 400 + Port Forwarding 27
        transport.on(400) { """{"success":true,"systemPort":"22 80 443"}""" }
        val sysPorts = secRepo.loadSystemPorts()
        assertEquals(setOf(22, 80, 443), (sysPorts as RouterResult.Ok).data)
        val pfRule = PortForwardRule(port = "80:90", mappingPort = "8080-8090", mappingIp = "192.168.1.50", protocol = "TCP", remark = "web")
        assertEquals(80, secRepo.findSystemPortConflict(pfRule))

        var pfArr = JSONArray()
        transport.on(27, "GET") { JSONObject().put("success", true).put("datas", pfArr).toString() }
        transport.on(27, "POST") { req -> pfArr = req.getJSONArray("datas"); """{"success":true}""" }
        val pfSaved = secRepo.savePortForwardRules(listOf(pfRule))
        assertTrue(pfSaved is RouterResult.Ok)
        val wirePf = pfArr.getJSONObject(0)
        assertEquals("80:90", wirePf.getString("port"))
        assertEquals("8080-8090", wirePf.getString("mappingPort"))
        assertEquals("192.168.1.50:8080-8090", wirePf.getString("mappingIpPort"))

        // IP/Port Filter 32 clears ports for ICMP/All
        var ipFilterArr = JSONArray()
        transport.on(32, "GET") { JSONObject().put("success", true).put("datas", ipFilterArr).toString() }
        transport.on(32, "POST") { req -> ipFilterArr = req.getJSONArray("datas"); """{"success":true}""" }
        val ipSaved = secRepo.saveIpPortFilterRules(
            listOf(IpPortFilterRule(ip = "192.168.1.10", destIp = "8.8.8.8", srcPort = "123", destPort = "53", protocol = "ICMP"))
        )
        assertTrue(ipSaved is RouterResult.Ok)
        assertEquals("", ipFilterArr.getJSONObject(0).getString("srcPort"))
        assertEquals("", ipFilterArr.getJSONObject(0).getString("destPort"))

        // Port Filter 21
        var portFilterArr = JSONArray()
        transport.on(21, "GET") { JSONObject().put("success", true).put("datas", portFilterArr).toString() }
        transport.on(21, "POST") { req -> portFilterArr = req.getJSONArray("datas"); """{"success":true}""" }
        assertTrue(secRepo.savePortFilterRules(listOf(PortFilterRule("8080", "tcp"))) is RouterResult.Ok)

        // URL Filter 26 + 29 default policy sync
        var urlDefArr = JSONArray("""[{"enableRule":true,"acceptAll":true,"ippro":"IPV4"},{"enableRule":true,"acceptAll":true,"ippro":"IPV6"}]""")
        var urlArr = JSONArray("""[{"url":"ads.example.com","ippro":"IPV4","remark":"","enableRule":true,"enableLink":false}]""")
        transport.on(29, "GET") { JSONObject().put("success", true).put("datas", urlDefArr).toString() }
        transport.on(29, "POST") { req -> urlDefArr = req.getJSONArray("datas"); """{"success":true}""" }
        transport.on(26, "GET") { JSONObject().put("success", true).put("datas", urlArr).toString() }
        transport.on(26, "POST") { req -> urlArr = req.getJSONArray("datas"); """{"success":true}""" }
        assertTrue(secRepo.saveUrlDefaultPolicy(acceptAll = false) is RouterResult.Ok)
        assertTrue(urlArr.getJSONObject(0).getBoolean("enableLink"))
        assertTrue(secRepo.saveUrlFilterRules(listOf(UrlFilterRule("example.org"))) is RouterResult.Ok)

        // Trusted IPs 502 + duplicate check
        var trustedJson = JSONObject().put("success", true).put("trusted_ip_sw", "0").put("trusted_ip_0", "")
        transport.on(502, "GET") { trustedJson.toString() }
        transport.on(502, "POST") { req ->
            trustedJson.put("trusted_ip_sw", req.getString("trusted_ip_sw"))
            for (i in 0 until 15) trustedJson.put("trusted_ip_$i", req.optString("trusted_ip_$i", ""))
            """{"success":true}"""
        }
        assertTrue(secRepo.saveTrustedIps(TrustedIpConfig(true, listOf("192.168.1.10", "192.168.1.10"))) is RouterResult.BadResponse)
        val trustedOk = secRepo.saveTrustedIps(TrustedIpConfig(true, listOf("192.168.1.10", "192.168.1.20")))
        assertTrue(trustedOk is RouterResult.Ok && trustedOk.data.enabled && trustedOk.data.ips.size == 2)
    }

    @Test
    fun `Batch D - System time-NTP 11-321, WAN link detect 336 validations, and factory reset 112`() = runTest {
        val sysRepo = SystemRepository(session, writer)

        var timeJson = JSONObject("""{"success":true,"systime":"2026-11-15 12:00:00","timezone":"UTC-1_1","ntpSwitch":"1","timeServer":"pool.ntp.org"}""")
        transport.on(11, "GET") { timeJson.toString() }
        transport.on(11, "POST") { req ->
            timeJson.put("timezone", req.getString("timezone"))
            timeJson.put("ntpSwitch", req.getString("ntpSwitch"))
            timeJson.put("timeServer", req.getString("timeServer"))
            """{"success":true}"""
        }
        val timeSaved = sysRepo.saveRouterTimeConfig(
            RouterTimeConfig(timezone = "UTC-1_2", ntpEnabled = true, server1 = "time.google.com"),
            manualDateTime = "2026-11-15 18:30:00"
        )
        assertTrue(timeSaved is RouterResult.Ok)
        val sent11 = transport.posts(11).single()
        assertEquals("UTC-1", sent11.getString("timezone"))
        assertEquals("11151830002026", sent11.getString("datetime"))

        transport.on(321, "POST") { """{"success":true}""" }
        assertTrue(sysRepo.syncClientTime("2026-11-15 18:30:00") is RouterResult.Ok)

        // WAN Link Detect 336
        var wanJson = JSONObject(
            """{"success":true,"wan_mode":"5","wanLinkDetectSwitch":"1","wanLinkDetectCheckTime":"10","checkWanLinkDetectMode":"0","wanLinkDetectIP1":"8.8.8.8","LinkDetectAction":"0","reboot_wait_time":"3","dial_wait_time":"10","dnsv4_server_sw":"0","dnsv6_server_sw":"0"}"""
        )
        transport.on(336, "GET") { wanJson.toString() }
        transport.on(336, "POST") { req ->
            wanJson.put("wanLinkDetectSwitch", req.getInt("wanLinkDetectSwitch").toString())
            wanJson.put("wanLinkDetectCheckTime", req.getInt("wanLinkDetectCheckTime").toString())
            """{"success":true}"""
        }
        val loadedWan = (sysRepo.loadWanLinkDetect() as RouterResult.Ok).data
        assertTrue(loadedWan.isMixMode)

        // Reboot wait constraint violation (1 * 60 = 60 < 120 checkTimeSec; rebootWaitMinutes min is 3*60=180 < 200)
        val badRebootWait = sysRepo.saveWanLinkDetect(
            WanLinkDetectConfig(enabled = true, checkTimeSec = 200, ip1 = "8.8.8.8", action = "1", rebootWaitMinutes = 3),
            rescanAllowed = true
        )
        assertTrue(badRebootWait is RouterResult.BadResponse)

        // Action 3 blocked when cmd 80 rescanAllowed=false
        val badRescan = sysRepo.saveWanLinkDetect(
            WanLinkDetectConfig(enabled = true, checkTimeSec = 10, ip1 = "8.8.8.8", action = "3"),
            rescanAllowed = false
        )
        assertTrue(badRescan is RouterResult.BadResponse)

        val okWan = sysRepo.saveWanLinkDetect(
            WanLinkDetectConfig(enabled = true, networkMode = "5", isMixMode = true, checkTimeSec = 15, ip1 = "1.1.1.1", action = "3"),
            rescanAllowed = true
        )
        assertTrue(okWan is RouterResult.Ok)
        assertEquals(1, transport.posts(336).single().getInt("wanLinkDetectSwitch"))

        // Factory Reset 112 tolerates empty reply and sends single POST with token
        transport.on(112, "POST") { "" }
        assertTrue(sysRepo.factoryReset() is RouterResult.Ok)
        assertEquals(1, transport.posts(112).size)
        assertTrue(transport.posts(112).single().getString("token").startsWith("TOK-"))
    }
}
