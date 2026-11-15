package com.airtel.monitor.core.util

import com.airtel.monitor.core.datastore.SpeedUnitPreference
import java.math.BigInteger
import java.util.Locale
import kotlin.math.roundToLong

object NetworkFormatters {
    fun formatThroughput(mbps: Double, unit: SpeedUnitPreference): Pair<String, String> =
        when (unit) {
            SpeedUnitPreference.MBPS -> String.format(Locale.US, "%.2f", mbps) to "Mbps"
            SpeedUnitPreference.MBS -> String.format(Locale.US, "%.2f", mbps / 8.0) to "MB/s"
        }

    fun formatPeakThroughput(mbps: Double, unit: SpeedUnitPreference): String =
        when (unit) {
            SpeedUnitPreference.MBPS -> String.format(Locale.US, "Peak %.1fM", mbps)
            SpeedUnitPreference.MBS -> String.format(Locale.US, "Peak %.2f MB/s", mbps / 8.0)
        }

    fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        for (p in parts) {
            val n = p.toIntOrNull() ?: return false
            if (n !in 0..255 || (p.length > 1 && p.startsWith("0"))) return false
        }
        return true
    }

    fun maskMac(mac: String): String {
        val clean = mac.trim()
        val parts = clean.split(":")
        if (parts.size != 6) return clean
        return "${parts[0]}:${parts[1]}:**:**:${parts[4]}:${parts[5]}"
    }

    fun roundTo2(value: Double): Double = (value * 100.0).roundToLong() / 100.0

    fun formatPollInterval(seconds: Float): String =
        if (seconds % 1f == 0f) "${seconds.toInt()}s" else "${seconds}s"
}

/**
 * Band sets encoded as a hex bitmask where bit 0 is band 1
 * (e.g. B1+B3 = "5", n41+n78 = "20000000010000000000").
 */
object BandMask {
    fun decode(hex: String?): Set<Int> {
        val clean = hex?.trim().orEmpty()
        if (clean.isEmpty()) return emptySet()
        val value = clean.toBigIntegerOrNull(16) ?: return emptySet()
        return (0 until value.bitLength()).filter { value.testBit(it) }.map { it + 1 }.toSet()
    }

    fun encode(bands: Set<Int>): String {
        if (bands.isEmpty()) return ""
        var value = BigInteger.ZERO
        bands.filter { it >= 1 }.forEach { value = value.setBit(it - 1) }
        return value.toString(16)
    }
}
