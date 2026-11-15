package com.airtel.monitor.core.util

import com.airtel.monitor.data.model.NetworkModeCatalog
import com.airtel.monitor.data.model.RouterFeatureFlags
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase71ValidatorsAndFlagsTest {

    @Test
    fun `cmd 80 parses hex page_hide and applies IP passthrough and networkMode gates`() {
        val raw = JSONObject(
            """
            {
              "success": true,
              "web_page_hide": "ffffffffffffffffffffffffffffffffffffffffffffffffffff",
              "account_level": "1",
              "aeraId": "NG0131",
              "network_mode": "1",
              "fw_switch": "1",
              "parentControlSwitch": "1",
              "lock_plmn_flag": "0",
              "ip_passthrough_sw": "0",
              "smsSw": "1",
              "iad_ready_status": "1",
              "wifi_ssid_forbid": "Admin,Default",
              "wifi_pwd_forbid": "12345678"
            }
            """.trimIndent()
        )
        val flags = RouterFeatureFlags.fromJson80(raw)
        assertTrue(flags.isLoaded)
        assertTrue(flags.dmzAvailable)
        assertTrue(flags.portForwardAvailable)
        assertTrue(flags.firewallRuleAvailable)
        assertTrue(flags.urlFilterAvailable)
        assertTrue(flags.ddosAvailable)
        assertTrue(flags.upnpAvailable)
        assertTrue(flags.trustedIpAvailable)
        assertTrue(flags.wanDetectRescanAvailable)
        assertTrue(flags.wifiPowerCmd411Available)
        assertTrue(flags.ussdAvailable)
        assertTrue(flags.networkSetAvailable)
        assertTrue(flags.apnAvailable)
        assertTrue(flags.trafficLimitAvailable)
        assertTrue(flags.guestWifi24gAvailable)
        assertTrue(flags.guestWifi5gAvailable)
        assertTrue(flags.wpsAvailable)
        assertTrue(flags.wifiScheduleAvailable)
        assertTrue(flags.parentalControlAvailable)
        assertTrue(flags.parentSpeedVisible)
        assertTrue(flags.parentUrlVisible)
        assertTrue(flags.parentTimeVisible)
        assertEquals(listOf("Admin", "Default"), flags.wifiSsidForbid)
        assertEquals(listOf("12345678"), flags.wifiPwdForbid)

        val passthroughFlags = RouterFeatureFlags.fromJson80(
            JSONObject(raw.toString()).put("ip_passthrough_sw", "1")
        )
        assertFalse(passthroughFlags.dmzAvailable)
        assertFalse(passthroughFlags.portForwardAvailable)
        assertFalse(passthroughFlags.trustedIpAvailable)
        assertTrue(passthroughFlags.ddosAvailable)

        val bridgeModeFlags = RouterFeatureFlags.fromJson80(
            JSONObject(raw.toString()).put("network_mode", "7")
        )
        assertFalse(bridgeModeFlags.dmzAvailable)
        assertFalse(bridgeModeFlags.portForwardAvailable)
        assertFalse(bridgeModeFlags.trustedIpAvailable)
        assertTrue(bridgeModeFlags.wanDetectRescanAvailable)
    }

    @Test
    fun `pageHideCheck distinguishes hidden, visible, and restricted bits`() {
        val flagsZero = RouterFeatureFlags.fromJson80(
            JSONObject().put("web_page_hide", "0000")
        )
        // Standard pageHideCheck defaults to hidden when not level 1; inverted pageHideCheck2 defaults to visible.
        assertTrue(flagsZero.dmzAvailable)
        assertFalse(flagsZero.upnpAvailable)

        val flagsAllFLevel3 = RouterFeatureFlags.fromJson80(
            JSONObject()
                .put("web_page_hide", "ffffffffffffffffffffffffffffffffffffffffffffffffffff")
                .put("account_level", "3")
        )
        assertTrue(flagsAllFLevel3.upnpAvailable)
        assertFalse(flagsAllFLevel3.dmzAvailable)

        val flagsAllFLevel1 = RouterFeatureFlags.fromJson80(
            JSONObject()
                .put("web_page_hide", "ffffffffffffffffffffffffffffffffffffffffffffffffffff")
                .put("account_level", "1")
        )
        assertTrue(flagsAllFLevel1.upnpAvailable)
        assertTrue(flagsAllFLevel1.dmzAvailable)
    }

    @Test
    fun `NetworkModeCatalog resolves area-specific and normalized bitmask options`() {
        val ng = NetworkModeCatalog.availableOptions("NG0002", "1F")
        assertEquals(listOf("C", "1C", "1E"), ng.map { it.code })
        assertEquals("Auto", ng.last().label)

        val tz = NetworkModeCatalog.availableOptions("TZ0355", "1E")
        assertEquals(listOf("C", "1C"), tz.map { it.code })

        val th = NetworkModeCatalog.availableOptions("TH0307", "C")
        assertTrue(th.any { it.code == "E" })
        assertEquals("5G NSA Only/4G", th.first { it.code == "C" }.label)
    }

    @Test
    fun `MAC normalization and unicast check reject multicast and malformed MACs`() {
        assertEquals("AA:BB:CC:DD:EE:FF", Phase71Validators.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("00:11:22:33:44:55", Phase71Validators.normalizeMac("001122334455"))
        assertNull(Phase71Validators.validateUnicastMac("aa-bb-cc-dd-ee-ff"))
        assertNotNull(Phase71Validators.validateUnicastMac("01:00:5E:00:00:01"))
        assertNotNull(Phase71Validators.validateUnicastMac("FF:FF:FF:FF:FF:FF"))
        assertNotNull(Phase71Validators.validateUnicastMac("not-a-mac"))
    }

    @Test
    fun `IP and URL and time-window validators enforce bounds`() {
        assertNull(Phase71Validators.validateIpOrRange("192.168.1.50", "IPV4"))
        assertNull(Phase71Validators.validateIpOrRange("192.168.1.10-192.168.1.20", "IPV4", allowRange = true))
        assertNotNull(Phase71Validators.validateIpOrRange("192.168.1.20-192.168.1.10", "IPV4", allowRange = true))
        assertNull(Phase71Validators.validateIpOrRange("2001:db8::1", "IPV6"))

        assertNull(Phase71Validators.validateFilterUrl("example.com"))
        assertNull(Phase71Validators.validateFilterUrl("https://sub.example.com/news"))
        assertNotNull(Phase71Validators.validateFilterUrl("invalid url with spaces"))

        assertNull(Phase71Validators.validateTimeWindow("08:00", "22:30"))
        assertNotNull(Phase71Validators.validateTimeWindow("22:30", "08:00"))
    }

    @Test
    fun `WPS PIN checksum and APN validators enforce router constraints`() {
        assertNull(Phase71Validators.validateWpsPin("12345670"))
        assertNotNull(Phase71Validators.validateWpsPin("12345671"))
        assertNotNull(Phase71Validators.validateWpsPin("1234"))

        assertNull(Phase71Validators.validateApnMtu("1500", "IPV4V6"))
        assertNotNull(Phase71Validators.validateApnMtu("1200", "IPV6"))
        assertNull(Phase71Validators.validateApnMtu("576", "IP"))
        assertNotNull(Phase71Validators.validateApnMtu("500", "IP"))

        assertNull(Phase71Validators.validateApnString("Airtel 5G", "Profile name"))
        assertNotNull(Phase71Validators.validateApnString("", "APN"))
        assertNull(Phase71Validators.validateApnCredential("user_1", "Username"))
    }

    @Test
    fun `USSD input validation and UTF-16BE hex decoding`() {
        assertNull(Phase71Validators.validateUssdInput("*123#", isReply = false))
        assertNotNull(Phase71Validators.validateUssdInput("123", isReply = false))
        assertNull(Phase71Validators.validateUssdInput("1", isReply = true))

        val decodedHex = Phase71Validators.decodeUssdResponse("00420061006C")
        assertTrue(decodedHex is UssdDecodedMessage.Text && decodedHex.text == "Bal")

        val decodedAscii = Phase71Validators.decodeUssdResponse("Balance: NGN 500")
        assertTrue(decodedAscii is UssdDecodedMessage.Text && decodedAscii.text == "Balance: NGN 500")

        assertTrue(Phase71Validators.decodeUssdResponse("507") is UssdDecodedMessage.Timeout)
        assertTrue(Phase71Validators.decodeUssdResponse("506") is UssdDecodedMessage.CarrierError)
        assertTrue(Phase71Validators.decodeUssdResponse("") is UssdDecodedMessage.Empty)
    }

    @Test
    fun `Port, cmd 400 conflict, cmd 11 datetime, timezone suffix, and parental speed validators`() {
        assertNull(Phase71Validators.validatePortOrRange("80", required = true))
        assertNull(Phase71Validators.validatePortOrRange("8000:8010", required = true))
        assertNotNull(Phase71Validators.validatePortOrRange("8010:8000", required = true))
        assertEquals("8000-8010", Phase71Validators.normalizePortRange("8000:8010", '-'))
        assertEquals(80, Phase71Validators.findPortConflict("75:85", setOf(22, 80, 443)))
        assertNull(Phase71Validators.findPortConflict("9000:9010", setOf(22, 80, 443)))

        assertEquals("111518302026", Phase71Validators.formatCmd11Datetime(2026, 11, 15, 18, 30))
        assertEquals("UTC-1", Phase71Validators.stripTimezoneSuffix("UTC-1_2"))

        assertNull(Phase71Validators.validateParentalSpeed("500"))
        assertNotNull(Phase71Validators.validateParentalSpeed("10001"))
    }
}
