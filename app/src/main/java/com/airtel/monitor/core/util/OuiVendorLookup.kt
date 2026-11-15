package com.airtel.monitor.core.util

import android.content.Context
import java.util.Locale

data class OuiMatch(
    val vendor: String?,
    val isPrivateMac: Boolean
)

/**
 * Offline IEEE OUI MAC vendor lookup backed by bundled `assets/oui.csv`.
 */
class OuiVendorLookup(
    private val prefixMap: Map<String, String>
) {
    val size: Int get() = prefixMap.size

    fun lookup(mac: String): OuiMatch {
        val clean = stripHex(mac)
        if (clean.length < 6) {
            return OuiMatch(vendor = null, isPrivateMac = false)
        }
        val prefix = clean.substring(0, 6)
        val vendor = prefixMap[prefix]
        val isPrivate = vendor == null && isLocallyAdministeredUnicast(clean)
        return OuiMatch(vendor = vendor, isPrivateMac = isPrivate)
    }

    companion object {
        val EMPTY = OuiVendorLookup(emptyMap())

        fun fromAsset(context: Context, assetName: String = "oui.csv"): OuiVendorLookup {
            val map = runCatching {
                context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { reader ->
                    parseLines(reader.lineSequence())
                }
            }.getOrDefault(emptyMap())
            return OuiVendorLookup(map)
        }

        fun fromLines(lines: Sequence<String>): OuiVendorLookup =
            OuiVendorLookup(parseLines(lines))

        internal fun parseLines(lines: Sequence<String>): Map<String, String> {
            val out = HashMap<String, String>(8192)
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val comma = line.indexOf(',')
                if (comma != 6) continue
                val prefix = line.substring(0, 6).uppercase(Locale.US)
                val vendor = line.substring(comma + 1).trim()
                if (prefix.all(::isHexDigit) && vendor.isNotEmpty()) {
                    out[prefix] = vendor
                }
            }
            return out
        }

        internal fun formatMac(mac: String): String {
            val hex = stripHex(mac)
            if (hex.length != 12) return mac.trim().uppercase(Locale.US)
            return hex.chunked(2).joinToString(":")
        }

        internal fun isLocallyAdministeredUnicast(cleanHexOrMac: String): Boolean {
            val clean = stripHex(cleanHexOrMac)
            if (clean.length < 2) return false
            val firstOctet = clean.substring(0, 2).toIntOrNull(16) ?: return false
            // Bit 1 (0x02) = locally administered, bit 0 (0x01) = multicast (must be 0 for unicast)
            return (firstOctet and 0x03) == 0x02
        }

        private fun stripHex(mac: String): String {
            val sb = StringBuilder(12)
            for (ch in mac) {
                if (isHexDigit(ch)) {
                    sb.append(ch.uppercaseChar())
                }
            }
            return sb.toString()
        }

        private fun isHexDigit(ch: Char): Boolean =
            ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
    }
}
