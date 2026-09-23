package com.airtel.monitor.data.remote

import com.airtel.monitor.data.model.DeviceType
import com.airtel.monitor.data.model.LockState
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
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

        /** Plain-text replies (e.g. cmd 204 file reads), keyed like [on]. */
        val textHandlers = mutableMapOf<String, (JSONObject) -> String>()

        fun onText(cmd: Int, method: String, reply: (JSONObject) -> String) {
            textHandlers["$cmd:$method"] = reply
        }

        override fun post(url: String, body: String, timeoutSeconds: Long): String {
            val req = JSONObject(body)
            requests += req
            val key = "${req.getInt("cmd")}:${req.optString("method")}"
            textHandlers[key]?.let { return it(req) }
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

    @Test
    fun `startPing posts cmd 168 subcmd 0 with target and packet count`() {
        router.on(168, "POST") { JSONObject().put("success", true) }

        val res = client.startPing("8.8.8.8", pingTimes = 10)
        assertTrue(res.isSuccess)

        val pingReq = router.sent(168, "POST").last()
        assertEquals(0, pingReq.getInt("subcmd"))
        assertEquals(10, pingReq.getInt("pingTimes"))
        assertEquals("8.8.8.8", pingReq.getString("url"))
    }

    @Test
    fun `startTrace posts cmd 168 subcmd 2 with target`() {
        router.on(168, "POST") { JSONObject().put("success", true) }

        val res = client.startTrace("1.1.1.1")
        assertTrue(res.isSuccess)

        val traceReq = router.sent(168, "POST").last()
        assertEquals(2, traceReq.getInt("subcmd"))
        assertEquals("0", traceReq.getString("stopped"))
        assertEquals("1.1.1.1", traceReq.getString("url"))
    }

    @Test
    fun `isTraceRunning inspects cmd 168 GET subcmd 2 message`() {
        router.on(168, "GET") { JSONObject().put("success", true).put("message", "1") }
        assertTrue(client.isTraceRunning())

        router.on(168, "GET") { JSONObject().put("success", true).put("message", "0") }
        assertFalse(client.isTraceRunning())
    }

    @Test
    fun `ping and trace output files are read with GET so lines stream in`() {
        val partial = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\n64 bytes from 8.8.8.8: seq=0 ttl=115 time=14.9 ms"
        router.onText(204, "GET") { req ->
            if (req.getString("url").endsWith("pingrt")) partial else "1  192.168.1.1  2.1ms"
        }

        assertEquals(partial, client.getPingOutput())
        assertEquals("1  192.168.1.1  2.1ms", client.getTraceOutput())
        assertTrue(router.sent(204, "POST").isEmpty())
        assertEquals(listOf("/tmp/tzwww/pingrt", "/tmp/tzwww/tracepathrt"),
            router.sent(204, "GET").map { it.getString("url") })
    }

    @Test
    fun `a JSON status reply instead of file text is treated as no output yet`() {
        router.onText(204, "GET") { """{"success":true,"cmd":204}""" }
        assertEquals("", client.getPingOutput())
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
        .put("ssid", if (cmd == 2) "QWlydGVsXzVHXzIuNEdIeg==" else "QWlydGVsXzVHXzVHSHo=")
        .put("key", "placeholder-key")

    // ---- Band lock & cell lock ----

    /** cmd 161 reply shaped like this router's (B1/3/7/8/20/28/38/41, n41/n78). */
    private fun bandReply(sw4: String, mask4: String, sw5: String, mask5: String) = JSONObject()
        .put("success", true)
        .put("all_band_4g", "120080800C5").put("band_4g_switch", sw4).put("band_4g_mask", mask4)
        .put("all_band_5g", "20000000010000000000").put("band_5g_switch", sw5).put("band_5g_mask", mask5)

    @Test
    fun `band masks decode and encode like the router UI`() {
        assertEquals(setOf(1, 3, 7, 8, 20, 28, 38, 41), BandMask.decode("120080800C5"))
        assertEquals(setOf(41, 78), BandMask.decode("20000000010000000000"))
        assertEquals("120080800c5", BandMask.encode(setOf(1, 3, 7, 8, 20, 28, 38, 41)))
        assertEquals("5", BandMask.encode(setOf(1, 3)))
        assertEquals("", BandMask.encode(emptySet()))
        assertEquals(emptySet<Int>(), BandMask.decode(""))
    }

    @Test
    fun `lock settings are parsed from cmd 160 and 161`() {
        router.on(161) { bandReply("1", "5", "0", "20000000010000000000") }
        router.on(160) {
            JSONObject().put("success", true)
                .put("lte_lock_sw", "1").put("lte_lock_freq", "225").put("lte_lock_pci", "456").put("lock_4g_flag", "1")
                .put("nr_lock_sw", "1").put("nr_lock_freq", "627264").put("nr_lock_pci", "916")
                .put("nr_lock_cell_band", "78").put("lock_5g_flag", "-1")
                .put("FREQ", "225").put("PCI", "456").put("FREQ_5G", "627264").put("PCI_5G", "916").put("current_band_5g", "78")
        }
        val (ok, s) = client.getLockSettings()
        assertTrue(ok)
        assertEquals(listOf(1, 3, 7, 8, 20, 28, 38, 41), s.bands.supported4g)
        assertEquals(setOf(1, 3), s.bands.locked4g)
        assertTrue(s.bands.lock4gEnabled)
        assertFalse(s.bands.lock5gEnabled)
        assertEquals(listOf(LteLockCell("225", "456")), s.cells.lteCells)
        assertEquals(LockState.LOCKED, s.cells.lteState)
        assertEquals(listOf(NrLockCell("78", "627264", "916")), s.cells.nrCells)
        assertEquals(LockState.FAILED, s.cells.nrState)
        assertEquals(NrLockCell("78", "627264", "916"), s.cells.current5g)
    }

    @Test
    fun `band lock posts masks, switches and a token, then confirms`() = runTest {
        var stored = bandReply("0", "120080800C5", "0", "20000000010000000000")
        router.on(161, "GET") { stored }
        router.on(161, "POST") { req ->
            stored = bandReply(req.getString("band4gRadio"), req.getString("lock4gBand"),
                req.getString("band5gRadio"), req.getString("lock5gBand"))
            JSONObject().put("success", true)
        }
        val result = client.setBandLock(true, setOf(3, 7), true, setOf(78))
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val post = router.sent(161, "POST").single()
        assertEquals("1", post.getString("band4gRadio"))
        assertEquals("44", post.getString("lock4gBand"))
        assertEquals("1", post.getString("band5gRadio"))
        assertEquals("20000000000000000000", post.getString("lock5gBand"))
        assertTrue(post.getString("token").startsWith("T"))
    }

    @Test
    fun `invalid band locks never reach the router`() = runTest {
        router.on(161) { bandReply("0", "", "0", "") }
        assertTrue(client.setBandLock(true, emptySet(), false, emptySet()).isFailure)
        assertTrue(client.setBandLock(true, setOf(5), false, emptySet()).isFailure)   // B5 unsupported
        assertTrue(router.sent(161, "POST").isEmpty())
    }

    @Test
    fun `4G and 5G cell locks use subcmd 0 and 1 with comma lists`() = runTest {
        var cell = JSONObject().put("success", true).put("lte_lock_sw", "0").put("nr_lock_sw", "0")
        router.on(160, "GET") { cell }
        router.on(160, "POST") { req ->
            if (req.getInt("subcmd") == 0) {
                cell.put("lte_lock_sw", req.getString("lte_lock_sw")).put("lte_lock_freq", req.getString("lte_lock_freq"))
                    .put("lte_lock_pci", req.getString("lte_lock_pci"))
            } else {
                cell.put("nr_lock_sw", req.getString("nr_lock_sw")).put("nr_lock_freq", req.getString("nr_lock_freq"))
                    .put("nr_lock_pci", req.getString("nr_lock_pci")).put("nr_lock_cell_band", req.getString("nr_lock_cell_band"))
            }
            JSONObject().put("success", true)
        }

        assertTrue(client.setLteCellLock(true, listOf(LteLockCell("225", "456"), LteLockCell("1850", ""))).isSuccess)
        val lte = router.sent(160, "POST").single { it.getInt("subcmd") == 0 }
        assertEquals("1", lte.getString("lte_lock_sw"))
        assertEquals("225,1850", lte.getString("lte_lock_freq"))
        assertEquals("456,", lte.getString("lte_lock_pci"))

        assertTrue(client.setNrCellLock(true, listOf(NrLockCell("78", "627264", "916"))).isSuccess)
        val nr = router.sent(160, "POST").single { it.getInt("subcmd") == 1 }
        assertEquals("78", nr.getString("nr_lock_cell_band"))
        assertEquals("627264", nr.getString("nr_lock_freq"))
        assertEquals("916", nr.getString("nr_lock_pci"))

        assertTrue(client.setLteCellLock(false, emptyList()).isSuccess)
        val unlock = router.sent(160, "POST").last()
        assertEquals("0", unlock.getString("lte_lock_sw"))
        assertEquals("", unlock.getString("lte_lock_freq"))
    }

    @Test
    fun `out of range cells are rejected locally`() = runTest {
        assertTrue(client.setLteCellLock(true, listOf(LteLockCell("225", "504"))).isFailure)
        assertTrue(client.setLteCellLock(true, listOf(LteLockCell("abc", "1"))).isFailure)
        assertTrue(client.setNrCellLock(true, listOf(NrLockCell("300", "627264", "1"))).isFailure)
        assertTrue(client.setNrCellLock(true, listOf(NrLockCell("78", "627264", "1008"))).isFailure)
        assertTrue(client.setLteCellLock(true, emptyList()).isFailure)
        assertTrue(router.sent(160, "POST").isEmpty())
    }

    @Test
    fun `a cell lock the router does not keep is reported`() = runTest {
        router.on(160, "GET") { JSONObject().put("success", true).put("lte_lock_sw", "0") }
        router.on(160, "POST") { JSONObject().put("success", true) }
        val result = client.setLteCellLock(true, listOf(LteLockCell("225", "456")))
        assertTrue(result.isFailure)
    }

}
