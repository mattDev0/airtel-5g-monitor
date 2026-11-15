package com.airtel.monitor.data.local

import android.content.Context
import androidx.core.content.edit
import com.airtel.monitor.data.model.DailyUsageEntry
import com.airtel.monitor.domain.MonthlyUsageSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface DailyUsageStore {
    val history: StateFlow<List<DailyUsageEntry>>
    fun recordSample(timestampMs: Long, summary: MonthlyUsageSummary)
    fun clearHistory()
}

/**
 * Persists daily cellular usage deltas derived locally from cumulative `cmd 337`/`cmd 205` totals
 * so daily breakdown remains available even when `405–408` stats tables are absent.
 */
class InMemoryDailyUsageStore(
    private val maxDays: Int = 31
) : DailyUsageStore {
    private val _history = MutableStateFlow<List<DailyUsageEntry>>(emptyList())
    override val history: StateFlow<List<DailyUsageEntry>> = _history.asStateFlow()

    @Synchronized
    override fun recordSample(timestampMs: Long, summary: MonthlyUsageSummary) {
        val totalMb = summary.usedMb ?: return
        if (totalMb < 0.0) return
        val dateIso = formatDateIso(timestampMs)
        _history.value = mergeSample(_history.value, dateIso, totalMb, maxDays)
    }

    @Synchronized
    override fun clearHistory() {
        _history.value = emptyList()
    }

    companion object {
        fun formatDateIso(timestampMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return fmt.format(Date(timestampMs))
        }

        internal fun mergeSample(
            current: List<DailyUsageEntry>,
            dateIso: String,
            totalMb: Double,
            maxDays: Int
        ): List<DailyUsageEntry> {
            val map = current.associateBy { it.dateIso }.toMutableMap()
            val existing = map[dateIso]
            val previousDayEnd = map.values
                .filter { it.dateIso < dateIso }
                .maxByOrNull { it.dateIso }
                ?.endTotalMb

            val updated = if (existing == null) {
                val base = when {
                    previousDayEnd != null && previousDayEnd <= totalMb -> previousDayEnd
                    else -> totalMb
                }
                DailyUsageEntry(
                    dateIso = dateIso,
                    dailyMb = (totalMb - base).coerceAtLeast(0.0),
                    endTotalMb = totalMb,
                    baselineMb = base,
                    sampleCount = 1
                )
            } else if (totalMb < existing.baselineMb) {
                // Billing cycle reset during the current day: treat new counter as starting from zero.
                DailyUsageEntry(
                    dateIso = dateIso,
                    dailyMb = totalMb.coerceAtLeast(0.0),
                    endTotalMb = totalMb,
                    baselineMb = 0.0,
                    sampleCount = existing.sampleCount + 1
                )
            } else {
                DailyUsageEntry(
                    dateIso = dateIso,
                    dailyMb = (totalMb - existing.baselineMb).coerceAtLeast(0.0),
                    endTotalMb = totalMb,
                    baselineMb = existing.baselineMb,
                    sampleCount = existing.sampleCount + 1
                )
            }
            map[dateIso] = updated
            return map.values.sortedBy { it.dateIso }.takeLast(maxDays)
        }
    }
}

class SharedPreferencesDailyUsageStore(
    context: Context,
    private val maxDays: Int = 31
) : DailyUsageStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _history = MutableStateFlow(loadEntries())
    override val history: StateFlow<List<DailyUsageEntry>> = _history.asStateFlow()

    @Synchronized
    override fun recordSample(timestampMs: Long, summary: MonthlyUsageSummary) {
        val totalMb = summary.usedMb ?: return
        if (totalMb < 0.0) return
        val dateIso = InMemoryDailyUsageStore.formatDateIso(timestampMs)
        val next = InMemoryDailyUsageStore.mergeSample(_history.value, dateIso, totalMb, maxDays)
        _history.value = next
        saveEntries(next)
    }

    @Synchronized
    override fun clearHistory() {
        _history.value = emptyList()
        prefs.edit { remove(KEY_ENTRIES) }
    }

    private fun loadEntries(): List<DailyUsageEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val date = o.optString("dateIso", "").trim()
                    if (date.isEmpty()) continue
                    add(
                        DailyUsageEntry(
                            dateIso = date,
                            dailyMb = o.optDouble("dailyMb", 0.0).coerceAtLeast(0.0),
                            endTotalMb = o.optDouble("endTotalMb", 0.0).coerceAtLeast(0.0),
                            baselineMb = o.optDouble("baselineMb", 0.0).coerceAtLeast(0.0),
                            sampleCount = o.optInt("sampleCount", 1).coerceAtLeast(1)
                        )
                    )
                }
            }.sortedBy { it.dateIso }.takeLast(maxDays)
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(entries: List<DailyUsageEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject()
                    .put("dateIso", entry.dateIso)
                    .put("dailyMb", entry.dailyMb)
                    .put("endTotalMb", entry.endTotalMb)
                    .put("baselineMb", entry.baselineMb)
                    .put("sampleCount", entry.sampleCount)
            )
        }
        prefs.edit { putString(KEY_ENTRIES, arr.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "airtel_daily_usage_history"
        private const val KEY_ENTRIES = "daily_entries"
    }
}
