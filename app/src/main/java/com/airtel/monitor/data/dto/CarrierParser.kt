package com.airtel.monitor.data.dto

import com.airtel.monitor.domain.CarrierComponent
import com.airtel.monitor.domain.CarrierTech
import org.json.JSONObject

/**
 * Parses LTE and 5G NR component carriers from router telemetry JSON (cmd 205, 160, 133, 1018).
 * Multi-value fields use '+' (e.g., "393+393", "7+1", "10+15").
 */
fun parseCarriers(json: JSONObject): List<CarrierComponent> {
    val lte = parseGroup(
        tech = CarrierTech.LTE,
        bandPrefix = "B",
        bandsRaw = firstPresent(json, "currentband", "current_band"),
        freqsRaw = firstPresent(json, "FREQ", "freq"),
        pcisRaw = firstPresent(json, "PCI", "pci"),
        bwsRaw = firstPresent(json, "bandwidth")
    )
    val nr = parseGroup(
        tech = CarrierTech.NR,
        bandPrefix = "n",
        bandsRaw = firstPresent(json, "currentband_5g", "current_band_5g"),
        freqsRaw = firstPresent(json, "FREQ_5G", "freq_5g"),
        pcisRaw = firstPresent(json, "PCI_5G", "nr_pci", "pci_5g"),
        bwsRaw = firstPresent(json, "bandwidth_5g")
    )
    return lte + nr
}

fun formatCaSummary(carriers: List<CarrierComponent>): String {
    if (carriers.isEmpty()) return "-"
    val bands = carriers.mapNotNull { it.band.takeIf { b -> b.isNotBlank() } }
    if (bands.isEmpty()) return "-"
    val bandList = bands.joinToString(" + ")
    val totalBw = carriers.mapNotNull { it.bandwidthMHz }.sum()
    return if (totalBw > 0) "$bandList · $totalBw MHz" else bandList
}

fun formatBandPill(carriers: List<CarrierComponent>): String {
    val nrBands = carriers.filter { it.tech == CarrierTech.NR && it.band.isNotBlank() }.map { it.band }
    if (nrBands.isNotEmpty()) return nrBands.joinToString("+")
    val lteBands = carriers.filter { it.tech == CarrierTech.LTE && it.band.isNotBlank() }.map { it.band }
    return if (lteBands.isNotEmpty()) lteBands.joinToString("+") else "-"
}

fun formatCellPill(carriers: List<CarrierComponent>): String {
    if (carriers.isEmpty()) return "-"
    val preferred = carriers.firstOrNull { it.tech == CarrierTech.NR && it.pci.isNotBlank() }
        ?: carriers.firstOrNull { it.pci.isNotBlank() }
        ?: return "-"
    val countForTech = carriers.count { it.tech == preferred.tech }
    return if (countForTech > 1) {
        "PCI ${preferred.pci} · ${countForTech}CC"
    } else {
        "PCI ${preferred.pci}"
    }
}

private fun parseGroup(
    tech: CarrierTech,
    bandPrefix: String,
    bandsRaw: String,
    freqsRaw: String,
    pcisRaw: String,
    bwsRaw: String
): List<CarrierComponent> {
    val bands = splitMulti(bandsRaw)
    val freqs = splitMulti(freqsRaw)
    val pcis = splitMulti(pcisRaw)
    val bws = splitMulti(bwsRaw)

    val count = maxOf(bands.size, freqs.size, pcis.size)
    if (count == 0) return emptyList()

    val result = mutableListOf<CarrierComponent>()
    for (i in 0 until count) {
        val band = formatBand(bands.getOrElse(i) { "" }, bandPrefix)
        val earfcn = freqs.getOrElse(i) { "" }
        val pci = pcis.getOrElse(i) { "" }.removePrefix("PCI").removePrefix("pci").trim()
        val bw = parseBandwidthMHz(bws.getOrElse(i) { "" })

        if (band.isEmpty() && earfcn.isEmpty() && pci.isEmpty()) continue
        result.add(
            CarrierComponent(
                tech = tech,
                band = band,
                earfcn = earfcn,
                pci = pci,
                bandwidthMHz = bw,
                isPrimary = result.isEmpty()
            )
        )
    }
    return result
}

private fun firstPresent(json: JSONObject, vararg keys: String): String {
    for (k in keys) {
        val v = json.optString(k, "").trim()
        if (v.isNotEmpty() && !v.equals("null", ignoreCase = true) && v != "-") {
            return v
        }
    }
    return ""
}

private fun splitMulti(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.split('+', ',', ';', '/', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "-" && !it.equals("null", ignoreCase = true) }
}

private fun formatBand(raw: String, prefix: String): String {
    if (raw.isBlank()) return ""
    val stripped = raw.trim()
        .removePrefix(prefix.lowercase())
        .removePrefix(prefix.uppercase())
        .trim()
    return if (stripped.isEmpty()) "" else "$prefix$stripped"
}

private fun parseBandwidthMHz(raw: String): Int? {
    if (raw.isBlank()) return null
    val digits = raw.trim()
        .lowercase()
        .removeSuffix("mhz")
        .removeSuffix("m")
        .trim()
    return digits.toIntOrNull()?.takeIf { it > 0 }
}
