package com.airtel.monitor.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun `formatWifiQrPayload escapes special characters and omits hidden when false`() {
        val wpa = QrCodeGenerator.formatWifiQrPayload(
            ssid = "Airtel;Home\"5G",
            key = "p@ss:word,1\\2",
            authenticationType = "2",
            hidden = false
        )
        assertEquals("WIFI:T:WPA;S:Airtel\\;Home\\\"5G;P:p@ss\\:word\\,1\\\\2;;", wpa)

        val hiddenOpen = QrCodeGenerator.formatWifiQrPayload(
            ssid = "Guest",
            key = "",
            authenticationType = "0",
            hidden = true
        )
        assertEquals("WIFI:T:nopass;S:Guest;P:;H:true;;", hiddenOpen)
    }

    @Test
    fun `encode creates properly sized matrices across QR versions with valid finders and timing`() {
        val v1Matrix = QrCodeGenerator.encode("HELLO-QR")
        assertEquals(21, v1Matrix.size)

        val v2Matrix = QrCodeGenerator.encode("WIFI:T:WPA;S:A;P:12345;;") // 24 bytes -> V2 (25x25)
        assertEquals(25, v2Matrix.size)

        val v3Matrix = QrCodeGenerator.encode("WIFI:T:WPA;S:Hi;P:12345678;;") // 28 bytes -> V3 (29x29)
        assertEquals(29, v3Matrix.size)
        assertTrue(v3Matrix.all { it.size == 29 })

        val v10Matrix = QrCodeGenerator.encode("a".repeat(213)) // max V10 capacity -> 57x57
        assertEquals(57, v10Matrix.size)

        // Check top-left, top-right, and bottom-left 7x7 finder outer borders
        assertFinderAt(v3Matrix, row0 = 0, col0 = 0)
        assertFinderAt(v3Matrix, row0 = 0, col0 = v3Matrix.size - 7)
        assertFinderAt(v3Matrix, row0 = v3Matrix.size - 7, col0 = 0)

        // ISO dark module always sits at (size - 8, 8)
        assertTrue(v3Matrix[v3Matrix.size - 8][8])

        // Timing pattern alternates black/white along row 6 and column 6
        for (i in 8 until v3Matrix.size - 8) {
            val expectedDark = i % 2 == 0
            assertEquals("Row 6 timing mismatch at $i", expectedDark, v3Matrix[6][i])
            assertEquals("Col 6 timing mismatch at $i", expectedDark, v3Matrix[i][6])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects payloads beyond Version 10 capacity`() {
        QrCodeGenerator.encode("a".repeat(214))
    }

    @Test
    fun `wifi and lan validators enforce router rules`() {
        assertNull(WifiAndLanValidators.validateSsid("Airtel_5G"))
        assertNotNull(WifiAndLanValidators.validateSsid(""))
        assertNotNull(WifiAndLanValidators.validateSsid(" LeadingSpace"))
        assertNotNull(WifiAndLanValidators.validateSsid("Bad;Ssid"))
        assertNotNull(WifiAndLanValidators.validateSsid("a".repeat(33)))
        assertNotNull(WifiAndLanValidators.validateSsid("Forbidden", forbiddenSsids = listOf("Forbidden")))

        assertNull(WifiAndLanValidators.validateWifiPassword("", authenticationType = "0"))
        assertNull(WifiAndLanValidators.validateWifiPassword("12345678", authenticationType = "2"))
        assertNotNull(WifiAndLanValidators.validateWifiPassword("1234567", authenticationType = "2"))
        assertNotNull(WifiAndLanValidators.validateWifiPassword("bad pass!", authenticationType = "2"))
        assertNotNull(WifiAndLanValidators.validateWifiPassword("bad\"quote", authenticationType = "2"))
        assertNotNull(
            WifiAndLanValidators.validateWifiPassword(
                key = "12345678",
                authenticationType = "2",
                forbiddenKeys = listOf("12345678")
            )
        )

        assertNull(
            WifiAndLanValidators.validateDhcpSettings(
                lanIp = "192.168.1.1",
                netMask = "255.255.255.0",
                dhcpEnabled = true,
                ipBegin = "192.168.1.100",
                ipEnd = "192.168.1.200"
            )
        )
        assertNotNull(
            WifiAndLanValidators.validateDhcpSettings(
                lanIp = "192.168.1.1",
                netMask = "255.255.255.0",
                dhcpEnabled = true,
                ipBegin = "192.168.2.100",
                ipEnd = "192.168.2.200"
            )
        )
        assertNotNull(
            WifiAndLanValidators.validateDhcpSettings(
                lanIp = "192.168.1.1",
                netMask = "255.255.255.0",
                dhcpEnabled = true,
                ipBegin = "192.168.1.1",
                ipEnd = "192.168.1.100"
            )
        )
        assertTrue(WifiAndLanValidators.isValidSubnetMask("255.255.255.0"))
        assertFalse(WifiAndLanValidators.isValidSubnetMask("255.0.255.0"))

        assertNull(WifiAndLanValidators.validatePin("1234"))
        assertNotNull(WifiAndLanValidators.validatePin("123"))
        assertNotNull(WifiAndLanValidators.validatePin("12a4"))
        assertNull(WifiAndLanValidators.validatePuk("12345678"))
        assertNotNull(WifiAndLanValidators.validatePuk("1234567"))
    }

    private fun assertFinderAt(matrix: List<List<Boolean>>, row0: Int, col0: Int) {
        for (dr in 0..6) {
            for (dc in 0..6) {
                val border = dr == 0 || dr == 6 || dc == 0 || dc == 6
                val inner = dr in 2..4 && dc in 2..4
                val expected = border || inner
                assertEquals(
                    "Finder mismatch at (${row0 + dr}, ${col0 + dc})",
                    expected,
                    matrix[row0 + dr][col0 + dc]
                )
            }
        }
    }
}
