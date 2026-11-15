package com.airtel.monitor.core.util

import java.nio.charset.StandardCharsets

/**
 * Input validators aligned with the ZLT X17M web UI validation rules (`js/step_rules.js`).
 */
object WifiAndLanValidators {

    fun validateSsid(
        ssid: String,
        forbiddenSsids: List<String> = emptyList()
    ): String? {
        if (ssid.isEmpty()) return "SSID cannot be blank"
        if (ssid.first().isWhitespace() || ssid.last().isWhitespace()) {
            return "SSID cannot start or end with whitespace"
        }
        if (ssid.contains(';') || ssid.contains('\\')) {
            return "SSID cannot contain ';' or '\\'"
        }
        val utf8Bytes = ssid.toByteArray(StandardCharsets.UTF_8).size
        if (utf8Bytes > 32) {
            return "SSID must be 32 UTF-8 bytes or fewer (currently $utf8Bytes)"
        }
        if (forbiddenSsids.any { it.isNotEmpty() && it == ssid }) {
            return "SSID is not allowed by the router"
        }
        return null
    }

    fun validateWifiPassword(
        key: String,
        authenticationType: String,
        forbiddenKeys: List<String> = emptyList()
    ): String? {
        if (authenticationType == "0") return null
        if (key.length < 8 || key.length > 63) {
            return "Wi-Fi password must be 8–63 characters"
        }
        if (key.any { it.isWhitespace() || it == '"' || it == ';' || it == '\\' }) {
            return "Wi-Fi password cannot contain spaces, '\"', ';', or '\\'"
        }
        if (forbiddenKeys.any { it.isNotEmpty() && it == key }) {
            return "Password is too common or forbidden by the router"
        }
        return null
    }

    fun validateDhcpSettings(
        lanIp: String,
        netMask: String,
        dhcpEnabled: Boolean,
        ipBegin: String,
        ipEnd: String
    ): String? {
        if (!NetworkFormatters.isIpv4(lanIp)) return "Invalid router LAN IP address"
        if (!isValidSubnetMask(netMask)) return "Invalid subnet mask"
        if (!dhcpEnabled) return null
        if (!NetworkFormatters.isIpv4(ipBegin)) return "Invalid DHCP start IP"
        if (!NetworkFormatters.isIpv4(ipEnd)) return "Invalid DHCP end IP"

        val lanNum = ipv4ToLong(lanIp)
        val maskNum = ipv4ToLong(netMask)
        val beginNum = ipv4ToLong(ipBegin)
        val endNum = ipv4ToLong(ipEnd)

        if (beginNum > endNum) return "DHCP start IP must be <= end IP"
        if ((beginNum and maskNum) != (lanNum and maskNum) || (endNum and maskNum) != (lanNum and maskNum)) {
            return "DHCP range must be in the same subnet as $lanIp"
        }
        if (lanNum in beginNum..endNum) {
            return "DHCP pool cannot include the router IP ($lanIp)"
        }
        return null
    }

    fun validatePin(pin: String): String? {
        if (pin.isEmpty()) return "PIN cannot be blank"
        if (!pin.all { it in '0'..'9' }) return "PIN must contain digits only"
        if (pin.length !in 4..8) return "PIN must be 4–8 digits"
        return null
    }

    fun validatePuk(puk: String): String? {
        if (puk.isEmpty()) return "PUK cannot be blank"
        if (!puk.all { it in '0'..'9' }) return "PUK must contain digits only"
        if (puk.length != 8) return "PUK must be exactly 8 digits"
        return null
    }

    fun isValidSubnetMask(mask: String): Boolean {
        if (!NetworkFormatters.isIpv4(mask)) return false
        val value = ipv4ToLong(mask)
        if (value == 0L || value == 0xFFFFFFFFL) return false
        val inverted = value.inv() and 0xFFFFFFFFL
        return ((inverted + 1L) and inverted) == 0L
    }

    private fun ipv4ToLong(ip: String): Long {
        val parts = ip.split('.')
        var out = 0L
        for (p in parts) {
            out = (out shl 8) or (p.toLong() and 0xFFL)
        }
        return out
    }
}
