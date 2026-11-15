package com.airtel.monitor.core.util

import java.util.Locale

sealed interface UssdDecodedMessage {
    data object Empty : UssdDecodedMessage
    data object Timeout : UssdDecodedMessage
    data object CarrierError : UssdDecodedMessage
    data class Text(val text: String) : UssdDecodedMessage
}

/**
 * Input validators and firmware payload codecs derived from the ZLT X17M second-level web UI scripts.
 */
object Phase71Validators {

    private val APN_NAME_REGEX =
        Regex("""^(?!rac|lac|sgsn|\.)(?!.*\.gprs$)(?!.*\.\.)(?!.*-\.)(?!.*\.-)[.a-zA-Z0-9-\s]{1,63}$""")
    private val NON_WHITESPACE_REGEX = Regex("""^\S+$""")
    private val URL_NO_SPACE_REGEX = Regex("""^\S{1,128}$""")
    private val HHMM_REGEX = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")
    private val USSD_HEX_CHUNK_REGEX = Regex("""[A-Fa-f0-9]{1,4}""")
    private val IPV6_GROUP_REGEX = Regex("""^[0-9A-Fa-f]{1,4}$""")

    /** Normalizes a 12-hex MAC string into uppercase `AA:BB:CC:DD:EE:FF`, or returns null if malformed. */
    fun normalizeMac(raw: String): String? {
        val compact = raw.trim().replace(":", "").replace("-", "").replace(".", "")
        if (compact.length != 12 || !compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }
        val upper = compact.uppercase(Locale.US)
        return (0 until 12 step 2).joinToString(":") { upper.substring(it, it + 2) }
    }

    /** Validates unicast non-zero, non-broadcast MAC per `js/main.js` / `macFilter.js`. */
    fun validateUnicastMac(raw: String): String? {
        val normalized = normalizeMac(raw)
            ?: return "Enter a valid MAC address (AA:BB:CC:DD:EE:FF)"
        if (normalized == "00:00:00:00:00:00") return "All-zero MAC address is not allowed"
        if (normalized == "FF:FF:FF:FF:FF:FF") return "Broadcast MAC address is not allowed"
        val firstOctet = normalized.substring(0, 2).toInt(16)
        if ((firstOctet and 0x01) != 0) {
            return "Multicast MAC addresses are not allowed"
        }
        return null
    }

    /** Strict offline IPv6 literal syntax check (no DNS resolution). */
    fun isIpv6Literal(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || !value.contains(':')) return false
        if (value.count { it == '%' } > 0) return false
        val doubleColonCount = value.split("::").size - 1
        if (doubleColonCount > 1) return false

        val halves = if (doubleColonCount == 1) value.split("::", limit = 2) else listOf(value)
        val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val rightGroups = if (doubleColonCount == 1 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()

        // Handle optional trailing embedded IPv4 (e.g. ::ffff:192.0.2.1)
        val lastRight = rightGroups.lastOrNull() ?: leftGroups.lastOrNull()
        val hasEmbeddedIpv4 = lastRight != null && lastRight.contains('.')
        if (hasEmbeddedIpv4) {
            if (!NetworkFormatters.isIpv4(lastRight!!)) return false
        }

        val checkLeft = if (hasEmbeddedIpv4 && rightGroups.isEmpty()) leftGroups.dropLast(1) else leftGroups
        val checkRight = if (hasEmbeddedIpv4 && rightGroups.isNotEmpty()) rightGroups.dropLast(1) else rightGroups
        val ipv6GroupsOk = (checkLeft + checkRight).all { IPV6_GROUP_REGEX.matches(it) }
        if (!ipv6GroupsOk) return false

        val groupUnits = checkLeft.size + checkRight.size + (if (hasEmbeddedIpv4) 2 else 0)
        return if (doubleColonCount == 1) groupUnits < 8 else groupUnits == 8
    }

    /** Validates a single IP or optional `start-end` range for `IPV4`, `IPV6`, or `IPV4V6`. */
    fun validateIpOrRange(
        raw: String,
        ippro: String,
        allowRange: Boolean = false,
        required: Boolean = true
    ): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return if (required) "IP address cannot be blank" else null
        if (allowRange && clean.contains('-')) {
            val parts = clean.split('-')
            if (parts.size != 2) return "Enter IP range as start-end"
            val start = parts[0].trim()
            val end = parts[1].trim()
            val startErr = validateSingleIp(start, ippro) ?: return validateRangeOrder(start, end, ippro)
            return startErr
        }
        return validateSingleIp(clean, ippro)
    }

    private fun validateRangeOrder(start: String, end: String, ippro: String): String? {
        val endErr = validateSingleIp(end, ippro)
        if (endErr != null) return endErr
        val startV4 = NetworkFormatters.isIpv4(start)
        val endV4 = NetworkFormatters.isIpv4(end)
        if (startV4 != endV4) return "Start and end IPs must use the same IP family"
        if (startV4 && ipv4ToLong(start) > ipv4ToLong(end)) {
            return "Range start IP must be <= end IP"
        }
        return null
    }

    private fun validateSingleIp(clean: String, ippro: String): String? {
        return when (ippro.uppercase(Locale.US)) {
            "IPV6" -> when {
                !isIpv6Literal(clean) -> "Enter a valid IPv6 address"
                clean == "::" -> "Unspecified IPv6 address '::' is not allowed"
                else -> null
            }
            "IPV4V6" -> if (NetworkFormatters.isIpv4(clean)) {
                if (clean == "0.0.0.0" || clean == "255.255.255.255") {
                    "Enter a valid unicast IPv4 address"
                } else null
            } else if (isIpv6Literal(clean)) {
                if (clean == "::") "Unspecified IPv6 address '::' is not allowed" else null
            } else {
                "Enter a valid IPv4 or IPv6 address"
            }
            else -> when {
                !NetworkFormatters.isIpv4(clean) -> "Enter a valid IPv4 address"
                clean == "0.0.0.0" || clean == "255.255.255.255" -> "Enter a valid unicast IPv4 address"
                else -> null
            }
        }
    }

    /** Validates a port (`"80"`) or port range (`"80:90"` or `"80-90"`). */
    fun validatePortOrRange(raw: String, required: Boolean = false): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return if (required) "Port cannot be blank" else null
        val sep = when {
            clean.contains(':') -> ':'
            clean.contains('-') -> '-'
            else -> null
        }
        if (sep == null) {
            val port = clean.toIntOrNull() ?: return "Port must be a number (1–65535)"
            return if (port in 1..65535) null else "Port must be between 1 and 65535"
        }
        val parts = clean.split(sep)
        if (parts.size != 2) return "Enter port range as start${sep}end"
        val start = parts[0].trim().toIntOrNull()
        val end = parts[1].trim().toIntOrNull()
        if (start == null || end == null || start !in 1..65535 || end !in 1..65535) {
            return "Ports must be between 1 and 65535"
        }
        if (start > end) return "Start port must be <= end port"
        return null
    }

    /** Normalizes `"80-90"` / `"80:90"` / `"80"` using [rangeSeparator] (`':'` for external, `'-'` for mapping). */
    fun normalizePortRange(raw: String, rangeSeparator: Char): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return ""
        val sep = when {
            clean.contains(':') -> ':'
            clean.contains('-') -> '-'
            else -> return clean.toIntOrNull()?.toString() ?: clean
        }
        val parts = clean.split(sep)
        if (parts.size != 2) return clean
        val start = parts[0].trim()
        val end = parts[1].trim()
        return if (start == end) start else "$start$rangeSeparator$end"
    }

    /** Checks if any port inside [portExpr] overlaps a router-reserved port from `cmd 400`. */
    fun findPortConflict(portExpr: String, reservedPorts: Set<Int>): Int? {
        if (reservedPorts.isEmpty()) return null
        val clean = portExpr.trim()
        val sep = when {
            clean.contains(':') -> ':'
            clean.contains('-') -> '-'
            else -> null
        }
        return if (sep == null) {
            val p = clean.toIntOrNull() ?: return null
            if (p in reservedPorts) p else null
        } else {
            val parts = clean.split(sep)
            val start = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val end = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
            reservedPorts.firstOrNull { it in start..end }
        }
    }

    /** Validates 8-digit WPS PIN checksum per `js/config/wirelessWpsSetting.js:150-202`. */
    fun validateWpsPin(raw: String): String? {
        val clean = raw.trim()
        if (clean.length != 8 || !clean.all { it in '0'..'9' }) {
            return "WPS PIN must be exactly 8 digits"
        }
        val digits = clean.map { it - '0' }
        val sum = 3 * (digits[0] + digits[2] + digits[4] + digits[6]) +
            digits[1] + digits[3] + digits[5] + digits[7]
        if (sum % 10 != 0) {
            return "Invalid WPS PIN checksum"
        }
        return null
    }

    /** Validates APN profile display name & APN string (`setting_apn.js` + `main.js:2408`). */
    fun validateApnString(raw: String, fieldLabel: String): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return "$fieldLabel cannot be blank"
        if (clean.equals("auto", ignoreCase = true) || !APN_NAME_REGEX.matches(clean)) {
            return "Enter a valid $fieldLabel (1–63 letters, digits, '.' or '-')"
        }
        return null
    }

    fun validateApnCredential(raw: String, fieldLabel: String): String? {
        if (raw.isEmpty()) return null
        if (raw.length > 63) return "$fieldLabel must be 63 characters or fewer"
        if (!NON_WHITESPACE_REGEX.matches(raw) || raw.any { it.code > 0x7E }) {
            return "$fieldLabel cannot contain spaces or non-ASCII characters"
        }
        return null
    }

    /** Validates APN MTU (`576..1500` for IPv4-only `"IP"`, `1280..1500` for `"IPV6"` / `"IPV4V6"`). */
    fun validateApnMtu(raw: String, selectType: String): String? {
        val mtu = raw.trim().toIntOrNull() ?: return "MTU must be a whole number"
        val min = if (selectType == "IP") 576 else 1280
        if (mtu !in min..1500) {
            return "MTU for $selectType must be between $min and 1500"
        }
        return null
    }

    /** Validates a USSD dial code or interactive menu reply (`js/manage/ussd.js`). */
    fun validateUssdInput(raw: String, isReply: Boolean): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return if (isReply) "Reply cannot be blank" else "Enter a USSD code (e.g. *123#)"
        if (clean.length > 160) return "USSD code is too long"
        if (clean.any { it.code < 0x20 }) return "Invalid control character in USSD input"
        if (!isReply && !clean.startsWith('*') && !clean.startsWith('#')) {
            return "USSD dial code should start with * or #"
        }
        return null
    }

    /** Decodes ZLT UTF-16BE 4-hex-digit USSD response (`"507"`, `"506"`, or encoded text). */
    fun decodeUssdResponse(rawMessage: String): UssdDecodedMessage {
        val clean = rawMessage.trim()
        return when (clean) {
            "" -> UssdDecodedMessage.Empty
            "507" -> UssdDecodedMessage.Timeout
            "506" -> UssdDecodedMessage.CarrierError
            else -> {
                val sb = StringBuilder()
                val matches = USSD_HEX_CHUNK_REGEX.findAll(clean).toList()
                val joinedLen = matches.sumOf { it.value.length }
                if (matches.isEmpty() || joinedLen != clean.length) {
                    return UssdDecodedMessage.Text(clean)
                }
                for (match in matches) {
                    val chunk = match.value
                    if (chunk == "0009" || chunk == "0000") continue
                    val code = chunk.toIntOrNull(16) ?: continue
                    sb.append(code.toChar())
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) UssdDecodedMessage.Empty else UssdDecodedMessage.Text(text)
            }
        }
    }

    /** Formats router manual datetime for `cmd 11` (`MMDDHHmmYYYY`). */
    fun formatCmd11Datetime(year: Int, month: Int, day: Int, hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d%02d%02d%02d%04d", month, day, hour, minute, year)

    /** Strips ZLT web UI duplicate-offset suffix `_\d+$` before posting `timezone` in `cmd 11`. */
    fun stripTimezoneSuffix(timezone: String): String =
        timezone.trim().replace(Regex("""_\d+$"""), "")

    fun validateFilterUrl(raw: String): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return "URL / domain cannot be blank"
        if (!URL_NO_SPACE_REGEX.matches(clean)) {
            return "URL must be 1–128 non-space characters"
        }
        return null
    }

    fun validateParentalSpeed(raw: String): String? {
        val v = raw.trim().toIntOrNull() ?: return "Speed limit must be a whole number (KB/s)"
        if (v !in 1..10_000) return "Speed limit must be 1–10000 KB/s"
        return null
    }

    fun validateTimeWindow(startHhMm: String, endHhMm: String): String? {
        val m1 = HHMM_REGEX.matchEntire(startHhMm.trim())
            ?: return "Start time must use HH:mm (24h)"
        val m2 = HHMM_REGEX.matchEntire(endHhMm.trim())
            ?: return "End time must use HH:mm (24h)"
        val startMin = m1.groupValues[1].toInt() * 60 + m1.groupValues[2].toInt()
        val endMin = m2.groupValues[1].toInt() * 60 + m2.groupValues[2].toInt()
        if (startMin >= endMin) return "End time must be after start time"
        return null
    }

    fun validateWanDetectSettings(
        enabled: Boolean,
        checkTimeSec: Int?,
        action: String,
        rebootWaitMin: Int?,
        dialWaitSec: Int?,
        ip1: String,
        ip2: String,
        ip3: String
    ): String? {
        if (!enabled) return null
        if (checkTimeSec == null || checkTimeSec !in 1..1800) {
            return "Check interval must be 1–1800 seconds"
        }
        if (ip1.trim().isEmpty()) return "Primary probe host / IP is required"
        for (ip in listOf(ip1, ip2, ip3)) {
            val trimmed = ip.trim()
            if (trimmed.isNotEmpty() && !URL_NO_SPACE_REGEX.matches(trimmed)) {
                return "Probe target '$trimmed' cannot contain spaces"
            }
        }
        if (action == "1") {
            if (rebootWaitMin == null || rebootWaitMin !in 3..30) {
                return "Reboot wait time must be 3–30 minutes"
            }
            if (rebootWaitMin * 60 < checkTimeSec) {
                return "Reboot wait time (${rebootWaitMin * 60}s) must be >= check interval (${checkTimeSec}s)"
            }
        } else if (action == "3") {
            if (dialWaitSec == null || dialWaitSec !in 10..600) {
                return "Redial wait time must be 10–600 seconds"
            }
        }
        return null
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
