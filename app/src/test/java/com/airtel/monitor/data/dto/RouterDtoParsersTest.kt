package com.airtel.monitor.data.dto

import com.airtel.monitor.core.network.AliasLookup
import com.airtel.monitor.data.model.DeviceType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterDtoParsersTest {

    private fun loadFixture(name: String): JSONObject {
        val text = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().readText()
        return JSONObject(text)
    }

    @Test
    fun `parses cmd 3 DNS fixture`() {
        val dns = parseDnsConfig(loadFixture("3.json"))
        assertEquals("1.1.1.1", dns.primary)
        assertEquals("8.8.8.8", dns.secondary)
        assertTrue(dns.dhcpEnabled)
    }

    @Test
    fun `parses cmd 161 band lock fixture`() {
        val band = parseBandLock(loadFixture("161.json"))
        assertTrue(band.lock4gEnabled)
        assertEquals(listOf(1, 3, 7, 8, 20, 28, 38, 41), band.supported4g)
        assertEquals(setOf(1, 3, 7), band.locked4g)
    }

    @Test
    fun `parses cmd 223 and cmd 225 device fixtures with alias`() {
        val alias = AliasLookup { id, fallback -> if (id == "02:11:22:33:44:01") "Matt's Pixel" else fallback }
        val devices = parseDevices(
            dhcp = loadFixture("223.json"),
            dash = JSONObject(),
            wifi5g = loadFixture("225.json"),
            wifi24g = JSONObject(),
            aliasLookup = alias
        )
        assertEquals(2, devices.size)
        val phone = devices.first { it.ip == "192.168.1.192" }
        assertEquals("Matt's Pixel", phone.alias)
        assertEquals(DeviceType.PHONE, phone.type)
        assertTrue(phone.band.startsWith("5 GHz"))
        assertEquals("1201", phone.txRateMbps)

        val wired = devices.first { it.ip == "192.168.1.50" }
        assertEquals("Unknown Device", wired.hostname)
        assertEquals("Ethernet (LAN)", wired.band)
        assertEquals(100, wired.signalPercent)
    }

    @Test
    fun `parses cmd 1018 hardware row and cmd 394 unread sms count`() {
        val row = parseLatestChartRow(loadFixture("1018.json"))
        val hw = parseHardwareMetrics(row)
        assertEquals(7.0, hw.cpuUsage, 0.01)
        assertEquals("54", hw.temperature)
        assertEquals("43", hw.memory)

        val unread = parseSmsUnreadCount(loadFixture("394.json"))
        assertEquals(3, unread)
    }

    @Test
    fun `decodes base64 UTF-8 SSID on pure JVM without android framework`() {
        assertEquals("Airtel_5G_2.4GHz", decodeBase64Utf8("QWlydGVsXzVHXzIuNEdIeg=="))
        assertEquals("Airtel_5G_5GHz", decodeBase64Utf8("QWlydGVsXzVHXzVHSHo="))
        assertEquals("", decodeBase64Utf8(""))
    }
}
