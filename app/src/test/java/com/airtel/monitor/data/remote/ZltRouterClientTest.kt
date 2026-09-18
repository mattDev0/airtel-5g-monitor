package com.airtel.monitor.data.remote

import com.airtel.monitor.data.model.DeviceType
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class ZltRouterClientTest {

    /** Stands in for the router CGI: answers by cmd and records every request. */
    private class FakeRouter : RouterTransport {
        val requests = mutableListOf<JSONObject>()
        val handlers = mutableMapOf<String, (JSONObject) -> JSONObject>()
        private var tokenCounter = 0

        init {
            on(232) { JSONObject().put("success", true).put("token", "CHALLENGE") }
            on(100) { JSONObject().put("success", true).put("sessionId", "SESSION") }
            on(233) { JSONObject().put("success", true).put("token", "T${++tokenCounter}") }
        }

        /** Handler for a cmd; a method-specific handler ("3:POST") wins over a cmd-wide one. */
        fun on(cmd: Int, method: String? = null, reply: (JSONObject) -> JSONObject) {
            handlers[if (method == null) "$cmd" else "$cmd:$method"] = reply
        }

        override fun post(url: String, body: String, timeoutSeconds: Long): String {
            val req = JSONObject(body)
            requests += req
            val key = "${req.getInt("cmd")}:${req.optString("method")}"
            val handler = handlers[key] ?: handlers["${req.getInt("cmd")}"]
                ?: return JSONObject().put("success", true).toString()
            return handler(req).toString()
        }

        fun sent(cmd: Int, method: String) =
            requests.filter { it.getInt("cmd") == cmd && it.optString("method") == method }
    }

    private val settings = object : RouterSettings {
        override val routerHost = "192.168.1.1"
        override val username = "root"
        override val password = "admin"
    }

    private lateinit var router: FakeRouter
    private var nowMs = 1_000_000L
    private lateinit var client: ZltRouterClient

    @Before
    fun setUp() {
        router = FakeRouter()
        client = ZltRouterClient(settings, { _, fallback -> fallback }, router) { nowMs }
    }

    // ---- Session handling ----

    @Test
    fun `login sends sha256 of challenge token plus password`() {
        assertTrue(client.login())
        val login = router.sent(100, "POST").single()
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("CHALLENGEadmin".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, login.getString("passwd"))
        assertEquals("root", login.getString("username"))
    }

    @Test
    fun `expired session re-logs in and repeats a read once`() {
        var calls = 0
        router.on(3, "GET") {
            if (calls++ == 0) JSONObject().put("success", false).put("message", "NO_AUTH")
            else lanRecord()
        }
        val (ok, dns) = client.getDns()
        assertTrue(ok)
        assertEquals("1.1.1.1", dns.primary)
        assertEquals(2, router.sent(3, "GET").size)
        assertEquals(2, router.sent(100, "POST").size) // first login + re-login
    }

    @Test
    fun `a write the router rejects is not sent twice`() {
        router.on(6, "POST") { JSONObject().put("success", false).put("message", "LIMITED_ACCESS") }
        assertFalse(client.reboot())
        assertEquals(1, router.sent(6, "POST").size)
    }

    @Test
    fun `a write that fails on the network is not retried`() {
        router.on(6, "POST") { throw IOException("connection reset") }
        assertFalse(client.reboot())
        assertEquals(1, router.sent(6, "POST").size)
    }

    @Test
    fun `a read that fails on the network is retried once`() {
        var calls = 0
        router.on(3, "GET") {
            if (calls++ == 0) throw IOException("stale connection") else lanRecord()
        }
        assertTrue(client.getDns().first)
        assertEquals(2, router.sent(3, "GET").size)
    }

    @Test
    fun `a write refused for an expired session is resent once with a fresh token`() = runTest {
        var state = lanRecord()
        var posts = 0
        router.on(3, "GET") { state }
        router.on(3, "POST") { req ->
            if (posts++ == 0) JSONObject().put("success", false).put("message", "NO_AUTH")
            else {
                state = lanRecord().put("vice_dns", req.getString("vice_dns"))
                JSONObject().put("success", true)
            }
        }
        assertTrue(client.setDns("1.1.1.1", "8.8.8.8").isSuccess)
        val sent = router.sent(3, "POST")
        assertEquals(2, sent.size)
        assertTrue(sent[0].getString("token") != sent[1].getString("token"))
    }

    // ---- LAN DNS ----

    @Test
    fun `saving DNS sends the full LAN record with only the DNS changed`() = runTest {
        var state = lanRecord()
        router.on(3, "GET") { state }
        router.on(3, "POST") { req ->
            state = lanRecord().put("main_dns", req.getString("main_dns")).put("vice_dns", req.getString("vice_dns"))
            JSONObject().put("success", true)
        }

        val result = client.setDns("1.1.1.1", "8.8.8.8")

        assertTrue(result.isSuccess)
        val post = router.sent(3, "POST").single()
        val original = lanRecord()
        val changed = post.keys().asSequence()
            .filter { original.has(it) && original.get(it).toString() != post.get(it).toString() }
            .toSet()
        assertEquals(setOf("vice_dns"), changed)
        for (key in listOf("lanIp", "netMask", "dhcpServer", "ipBegin", "ipEnd", "expireTime", "bindPort0")) {
            assertEquals(key, original.getString(key), post.getString(key))
        }
        assertTrue(post.getString("token").isNotEmpty())
    }

    @Test
    fun `invalid DNS input never reaches the router`() = runTest {
        router.on(3, "GET") { lanRecord() }
        val bad = listOf(
            "" to "8.8.8.8", "1.1.1" to "", "1.1.1.1" to "1.1.1.1",
            "1.1.1.01" to "", "300.1.1.1" to "", "1.1.1.1" to "abc"
        )
        for ((p, s) in bad) {
            assertTrue("$p / $s should be rejected", client.setDns(p, s).isFailure)
        }
        assertEquals(0, router.sent(3, "POST").size)
    }

    @Test
    fun `DNS save fails when the router keeps its old values`() = runTest {
        router.on(3, "GET") { lanRecord() }
        router.on(3, "POST") { JSONObject().put("success", true) }
        val result = client.setDns("1.1.1.1", "8.8.8.8")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("kept"))
    }

    @Test
    fun `a blank secondary DNS is allowed`() = runTest {
        router.on(3, "GET") { lanRecord().put("vice_dns", "") }
        router.on(3, "POST") { JSONObject().put("success", true) }
        assertTrue(client.setDns("1.1.1.1", "").isSuccess)
    }

    // ---- Wi-Fi radios ----

    @Test
    fun `refuses to turn off the only broadcasting band`() = runTest {
        router.on(211, "GET") { wifiRecord(211, on = true) }
        router.on(2, "GET") { wifiRecord(2, on = false) }
        val result = client.setWifi5g(false)
        assertTrue(result.isFailure)
        assertEquals(0, router.sent(211, "POST").size)
    }

    @Test
    fun `turning a band off is confirmed by reading it back`() = runTest {
        var fiveOn = true
        router.on(211, "GET") { wifiRecord(211, on = fiveOn) }
        router.on(2, "GET") { wifiRecord(2, on = true) }
        // An empty reply is ambiguous (the Wi-Fi restart can cut the request).
        router.on(211, "POST") { fiveOn = false; JSONObject() }

        val result = client.setWifi5g(false)

        assertTrue(result.isSuccess)
        val post = router.sent(211, "POST").single()
        assertEquals("0", post.getString("wifiOpen"))
        val current = wifiRecord(211, on = true)
        for (key in listOf("ssid", "key", "broadcast", "authenticationType", "wifiSames")) {
            assertEquals(key, current.getString(key), post.getString(key))
        }
    }

    @Test
    fun `an explicit Wi-Fi rejection is reported`() = runTest {
        router.on(2, "GET") { wifiRecord(2, on = false) }
        router.on(2, "POST") { JSONObject().put("success", false).put("message", "LIMITED_ACCESS") }
        val result = client.setWifi24g(true)
        assertTrue(result.isFailure)
        assertEquals("LIMITED_ACCESS", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `a band the router kept unchanged is reported as a failure`() = runTest {
        router.on(2, "GET") { wifiRecord(2, on = false) }
        router.on(2, "POST") { JSONObject().put("success", true) }
        val result = client.setWifi24g(true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("kept"))
    }

    @Test
    fun `no confirmation from the router is not reported as success`() = runTest {
        var posted = false
        router.on(211, "GET") { if (posted) throw IOException("unreachable") else wifiRecord(211, on = true) }
        router.on(2, "GET") { wifiRecord(2, on = true) }
        router.on(211, "POST") { posted = true; JSONObject() }
        assertTrue(client.setWifi5g(false).isFailure)
    }

    @Test
    fun `a band already in the requested state sends nothing`() = runTest {
        router.on(2, "GET") { wifiRecord(2, on = true) }
        assertTrue(client.setWifi24g(true).isSuccess)
        assertEquals(0, router.sent(2, "POST").size)
    }

    // ---- Polling ----

    @Test
    fun `WAN speed comes from byte-counter deltas and ignores resets`() {
        var rx = 1_000_000L
        var tx = 500_000L
        router.on(18) { JSONObject().put("success", true).put("rxBytes", rx.toString()).put("txBytes", tx.toString()) }

        val first = client.pollMetrics().getOrThrow().wan
        assertEquals(0.0, first.downloadKbps, 0.0) // no baseline yet

        nowMs += 2_000
        rx += 2 * 2048 // 2 kB/s over 2 s
        tx += 2 * 1024 // 1 kB/s over 2 s
        val second = client.pollMetrics().getOrThrow().wan
        assertEquals(2.0, second.downloadKbps, 0.001)
        assertEquals(1.0, second.uploadKbps, 0.001)
        assertEquals(0.02, second.downloadMbps, 0.001)

        nowMs += 2_000
        rx = 10 // counter reset (router reboot)
        val third = client.pollMetrics().getOrThrow().wan
        assertEquals(0.0, third.downloadKbps, 0.0)
    }

    @Test
    fun `devices get signal, band, type and alias from the router lists`() {
        client = ZltRouterClient(settings, { id, fallback -> if (id == "aa:aa:aa:aa:aa:aa") "Matt's Pixel" else fallback }, router) { nowMs }
        router.on(223) {
            JSONObject().put("success", true).put("dhcp_list_info", JSONArray()
                .put(JSONObject().put("mac", "AA:AA:AA:AA:AA:AA").put("ip", "192.168.1.192").put("hostname", "pixel-6").put("interface", "wlan"))
                .put(JSONObject().put("mac", "bb:bb:bb:bb:bb:bb").put("ip", "192.168.1.50").put("hostname", "NULL").put("interface", "lan")))
        }
        router.on(225) {
            JSONObject().put("success", true).put("wlan5g_wifi_info", JSONArray()
                .put(JSONObject().put("mac", "aa:aa:aa:aa:aa:aa").put("rssi", "-60").put("txrate", "866").put("rxrate", "866")))
        }

        val devices = client.pollMetrics().getOrThrow().devices.associateBy { it.ip }

        val phone = devices.getValue("192.168.1.192")
        assertEquals("Matt's Pixel", phone.alias)
        assertEquals(DeviceType.PHONE, phone.type)
        assertEquals(56, phone.signalPercent) // (-60 + 100) * 1.42, truncated
        assertTrue(phone.band.startsWith("5 GHz"))

        val wired = devices.getValue("192.168.1.50")
        assertEquals("Unknown Device", wired.hostname)
        assertEquals("Ethernet (LAN)", wired.band)
        assertEquals(100, wired.signalPercent)
    }

    // ---- Fixtures ----

    /** The router's real cmd 3 reply (captured 2026-09-17), before 8.8.8.8 was added. */
    private fun lanRecord() = JSONObject(
        """{"success":true,"cmd":3,"lanIp":"192.168.1.1","netMask":"255.255.255.0","dhcpServer":"1",
           "main_dns":"1.1.1.1","vice_dns":"","ipBegin":"192.168.1.100","ipEnd":"192.168.1.200",
           "expireTime":"24h","bindPort0":"f0f0f","ipv6_mode":"0","ipv6_startIp":"64","ipv6_endIp":"ffaa",
           "ipv6_main_dns":"","ipv6_vice_dns":"","relay_sw":"0","mtu":"1500","domain":"m.home",
           "supportList":"70F0F03","lanMark":"1"}"""
    )

    /** Shape of the router's cmd 2 / cmd 211 reply; the key is a placeholder. */
    private fun wifiRecord(cmd: Int, on: Boolean) = JSONObject()
        .put("success", true).put("cmd", cmd)
        .put("wifiOpen", if (on) "1" else "0")
        .put("broadcast", "1").put("wifiSames", "0").put("authenticationType", "2")
        .put("ssid", if (cmd == 2) "QWlydGVsX1czMDRWQSBQUk9fMDUyMA==" else "UmVuZ29rdV81X0dIeg==")
        .put("key", "placeholder-key")
}
