package com.airtel.monitor.data.dto

import com.airtel.monitor.domain.AlignmentSnapshot
import com.airtel.monitor.domain.CarrierComponent
import com.airtel.monitor.domain.CarrierTech
import com.airtel.monitor.domain.CellIdentity
import com.airtel.monitor.domain.LinkDetails
import com.airtel.monitor.domain.MonthlyUsageSummary
import com.airtel.monitor.domain.NeighborCell
import com.airtel.monitor.domain.SignalHistoryPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

private fun cleanMetric(vararg values: String): String =
    values.map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.equals("null", ignoreCase = true) && it != "-" }
        ?: "-"

fun parseLinkDetails(
    net205: JSONObject,
    routerInfo133: JSONObject = JSONObject()
): LinkDetails = LinkDetails(
    dlMcs4g = cleanMetric(net205.optString("dl_mcs"), routerInfo133.optString("dl_mcs")),
    ulMcs4g = cleanMetric(net205.optString("ul_mcs"), routerInfo133.optString("ul_mcs")),
    dlMcs5g = cleanMetric(net205.optString("dl_mcs_5g")),
    ulMcs5g = cleanMetric(net205.optString("ul_mcs_5g")),
    maxDlQam4g = cleanMetric(net205.optString("max_dl_qam")),
    maxUlQam4g = cleanMetric(net205.optString("max_ul_qam")),
    maxDlQam5g = cleanMetric(net205.optString("max_dl_qam_5g")),
    maxUlQam5g = cleanMetric(net205.optString("max_ul_qam_5g")),
    rank4g = cleanMetric(net205.optString("rank_4g")),
    rank5g = cleanMetric(net205.optString("rank_5g")),
    mimoStatus = cleanMetric(net205.optString("mimo_status")),
    bler4g = cleanMetric(net205.optString("bler_4g")),
    bler5g = cleanMetric(net205.optString("bler_5g"))
)

fun parseCellIdentity(
    net205: JSONObject,
    routerInfo133: JSONObject = JSONObject()
): CellIdentity = CellIdentity(
    plmn = cleanMetric(net205.optString("PLMN")),
    enbId4g = cleanMetric(net205.optString("ENODEBID")),
    cellId4g = cleanMetric(net205.optString("CELL_ID"), routerInfo133.optString("CELL_ID")),
    tac4g = cleanMetric(net205.optString("tac_4g")),
    ecgi = cleanMetric(net205.optString("ECGI")),
    enbId5g = cleanMetric(net205.optString("ENODEBID_5G")),
    cellId5g = cleanMetric(net205.optString("CELL_ID_5G"), routerInfo133.optString("CELL_ID_5G")),
    tac5g = cleanMetric(net205.optString("tac_5g")),
    ncgi = cleanMetric(net205.optString("NCGI"))
)

fun parseNeighborCells(json282: JSONObject): List<NeighborCell> {
    val lte = parseNeighborField(json282.opt("lte_info"), CarrierTech.LTE)
    val nr = parseNeighborField(json282.opt("nr_info"), CarrierTech.NR)
    return lte + nr
}

private fun parseNeighborField(raw: Any?, tech: CarrierTech): List<NeighborCell> {
    if (raw == null || raw == JSONObject.NULL) return emptyList()
    return when (raw) {
        is JSONArray -> {
            buildList {
                for (i in 0 until raw.length()) {
                    when (val entry = raw.opt(i)) {
                        is JSONObject -> parseNeighborJson(entry, tech)?.let { add(it) }
                        is String -> parseNeighborTuple(entry, tech)?.let { add(it) }
                    }
                }
            }
        }
        is String -> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true) || trimmed == "-") {
                emptyList()
            } else if (trimmed.startsWith("[")) {
                val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
                parseNeighborField(arr, tech)
            } else {
                trimmed.split(';', '\n')
                    .mapNotNull { parseNeighborTuple(it, tech) }
            }
        }
        else -> emptyList()
    }
}

private fun parseNeighborJson(obj: JSONObject, tech: CarrierTech): NeighborCell? {
    val earfcn = cleanMetric(obj.optString("earfcn"), obj.optString("arfcn"), obj.optString("freq"))
    val pci = cleanMetric(obj.optString("pci"), obj.optString("PCI"))
    if (earfcn == "-" && pci == "-") return null
    return NeighborCell(
        tech = tech,
        earfcn = earfcn,
        pci = pci,
        rsrp = cleanMetric(obj.optString("rsrp"), obj.optString("RSRP")),
        rsrq = cleanMetric(obj.optString("rsrq"), obj.optString("RSRQ")),
        sinr = cleanMetric(obj.optString("sinr"), obj.optString("SINR"))
    )
}

private fun parseNeighborTuple(raw: String, tech: CarrierTech): NeighborCell? {
    val parts = raw.split(',').map { it.trim() }
    if (parts.size < 3) return null
    val earfcn = parts.getOrElse(0) { "" }.ifEmpty { "-" }
    val pci = parts.getOrElse(1) { "" }.ifEmpty { "-" }
    if ((earfcn == "-" || earfcn == "0") && (pci == "-" || pci == "0")) return null
    return NeighborCell(
        tech = tech,
        earfcn = earfcn,
        pci = pci,
        rsrp = parts.getOrElse(2) { "" }.ifEmpty { "-" },
        rsrq = parts.getOrElse(3) { "" }.ifEmpty { "-" },
        sinr = parts.getOrElse(4) { "" }.ifEmpty { "-" }
    )
}

fun parseSignalHistory1018(
    rows: List<JSONObject>,
    limit: Int = 60
): List<SignalHistoryPoint> = rows.takeLast(limit).mapNotNull { row ->
    val time = row.optString("time", "").trim()
    val rsrp4g = row.optString("rsrp_4g", "").trim().toFloatOrNull()
    val sinr4g = row.optString("sinr_4g", "").trim().toFloatOrNull()
    val rsrp5g = row.optString("rsrp_5g", "").trim().toFloatOrNull()
    val sinr5g = row.optString("sinr_5g", "").trim().toFloatOrNull()
    if (rsrp4g == null && sinr4g == null && rsrp5g == null && sinr5g == null) return@mapNotNull null
    SignalHistoryPoint(
        timeLabel = time,
        rsrp4g = rsrp4g,
        sinr4g = sinr4g,
        rsrp5g = rsrp5g,
        sinr5g = sinr5g,
        pci = row.optString("pci", "").trim()
    )
}

fun parseAlignmentSnapshot(
    net205: JSONObject,
    carriers: List<CarrierComponent>,
    timestampMs: Long
): AlignmentSnapshot = AlignmentSnapshot(
    networkType = cleanMetric(net205.optString("network_type_str")),
    carrierSummary = formatCaSummary(carriers),
    rsrp5g = net205.optString("RSRP_5G", "").trim().toFloatOrNull(),
    sinr5g = net205.optString("SINR_5G", "").trim().toFloatOrNull(),
    rsrp4g = net205.optString("RSRP", "").trim().toFloatOrNull(),
    sinr4g = net205.optString("SINR", "").trim().toFloatOrNull(),
    timestampMs = timestampMs
)

fun parseMonthlyUsage(
    net205: JSONObject,
    dash: JSONObject,
    traffic337: JSONObject,
    todayDayOfMonth: Int? = null,
    daysInMonth: Int? = null
): MonthlyUsageSummary {
    val dlMon = traffic337.optString("dl_mon_flow", "").trim().toDoubleOrNull()
        ?: traffic337.optString("mon_download_flow", "").trim().toDoubleOrNull()
    val ulMon = traffic337.optString("ul_mon_flow", "").trim().toDoubleOrNull()
    val totalFromStatus = net205.optString("mon_total_flow", "").trim().toDoubleOrNull()
        ?: dash.optString("mon_total_flow", "").trim().toDoubleOrNull()
    val usedMb = totalFromStatus ?: if (dlMon != null || ulMon != null) (dlMon ?: 0.0) + (ulMon ?: 0.0) else null

    val limitSwitch = traffic337.optString("limitSwitch", "").trim() in listOf("1", "true", "True")
    val rawLimit = traffic337.optString("limitSize", "").trim().toDoubleOrNull()
    val limitUnit = traffic337.optString("flow_limit_unit", "").trim().lowercase(Locale.US)
    val limitMb = rawLimit?.takeIf { it > 0.0 }?.let { value ->
        when {
            limitUnit == "mb" || limitUnit == "m" || limitUnit == "0" -> value
            limitUnit == "gb" || limitUnit == "g" || limitUnit == "1" -> value * 1024.0
            value > 10_000.0 -> value
            else -> value * 1024.0
        }
    }

    val startDay = traffic337.optString("startDate", "").trim().toIntOrNull()?.takeIf { it in 1..31 }
    val warnPct = traffic337.optString("warn_percentage", "").trim().toIntOrNull()?.takeIf { it in 1..100 }
    val daysLeft = if (startDay != null) {
        val cal = Calendar.getInstance()
        val today = todayDayOfMonth ?: cal.get(Calendar.DAY_OF_MONTH)
        val monthDays = daysInMonth ?: cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        calculateDaysLeftInCycle(startDay, today, monthDays)
    } else {
        null
    }

    return MonthlyUsageSummary(
        usedMb = usedMb,
        dlMonthMb = dlMon,
        ulMonthMb = ulMon,
        limitEnabled = limitSwitch,
        limitMb = limitMb,
        cycleStartDay = startDay,
        warnPercentage = warnPct,
        daysLeftInCycle = daysLeft
    )
}

fun calculateDaysLeftInCycle(startDay: Int, today: Int, daysInMonth: Int): Int {
    val safeStart = startDay.coerceIn(1, daysInMonth.coerceAtLeast(1))
    val safeToday = today.coerceIn(1, daysInMonth.coerceAtLeast(1))
    return if (safeToday < safeStart) {
        (safeStart - safeToday).coerceAtLeast(1)
    } else {
        ((daysInMonth - safeToday) + safeStart).coerceAtLeast(1)
    }
}

fun formatDurationText(raw: String): String {
    val clean = raw.trim()
    if (clean.isEmpty() || clean == "-" || clean.equals("null", ignoreCase = true)) return "-"
    val totalSeconds = clean.toLongOrNull()
    if (totalSeconds != null) {
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3600L
        val mins = (totalSeconds % 3600L) / 60L
        val secs = totalSeconds % 60L
        return formatDurationParts(days, hours, mins, secs)
    }
    val dhms = Regex("""^(\d+)-(\d{1,2})-(\d{1,2})-(\d{1,2})$""").matchEntire(clean)
    if (dhms != null) {
        return formatDurationParts(
            days = dhms.groupValues[1].toLong(),
            hours = dhms.groupValues[2].toLong(),
            mins = dhms.groupValues[3].toLong(),
            secs = dhms.groupValues[4].toLong()
        )
    }
    val hms = Regex("""^(\d+):(\d{1,2}):(\d{1,2})$""").matchEntire(clean)
    if (hms != null) {
        val h = hms.groupValues[1].toLong()
        val m = hms.groupValues[2].toLong()
        val s = hms.groupValues[3].toLong()
        return formatDurationParts(h / 24L, h % 24L, m, s)
    }
    return clean
}

private fun formatDurationParts(days: Long, hours: Long, mins: Long, secs: Long): String = when {
    days > 0L -> "$days d $hours h"
    hours > 0L -> "$hours h $mins m"
    mins > 0L -> "$mins m"
    else -> "$secs s"
}

fun formatDataSizeMb(mb: Double): String {
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
