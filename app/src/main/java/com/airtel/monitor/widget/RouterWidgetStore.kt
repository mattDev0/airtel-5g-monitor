package com.airtel.monitor.widget

import android.content.Context
import androidx.core.content.edit
import com.airtel.monitor.data.model.DailyUsageEntry
import com.airtel.monitor.data.model.RouterState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WidgetSnapshot(
    val networkType: String = "--",
    val signalBars: Int = 0,
    val rsrpText: String = "--",
    val monthlyDataText: String = "--",
    val todayDataText: String = "--",
    val deviceCount: Int = 0,
    val connected: Boolean = false,
    val updatedLabel: String = "--"
)

object WidgetFormatter {
    fun buildSnapshot(
        state: RouterState,
        dailyEntries: List<DailyUsageEntry>,
        nowMillis: Long = System.currentTimeMillis()
    ): WidgetSnapshot {
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
        val todayMb = dailyEntries.firstOrNull { it.dateIso == todayIso }?.dailyMb
        val todayText = if (todayMb != null) formatMbCompact(todayMb) else "--"

        val monthText = when {
            state.monthlyUsage.usedMb != null -> formatMbCompact(state.monthlyUsage.usedMb)
            state.wan.totalFlowMb.isNotBlank() && state.wan.totalFlowMb != "-" -> "${state.wan.totalFlowMb} MB"
            else -> "--"
        }

        val rsrp = when {
            state.cellular.has5gMetrics && state.cellular.rsrp5g != "-" -> "${state.cellular.rsrp5g} dBm"
            state.cellular.rsrp4g != "-" -> "${state.cellular.rsrp4g} dBm"
            else -> "--"
        }

        val onlineDevices = state.devices.count { !it.isRouter && it.isOnline }
        val timeText = SimpleDateFormat("HH:mm", Locale.US).format(Date(nowMillis))

        return WidgetSnapshot(
            networkType = state.cellular.networkType.takeIf { it.isNotBlank() && it != "-" } ?: "--",
            signalBars = state.cellular.signalLvl.coerceIn(0, 5),
            rsrpText = rsrp,
            monthlyDataText = monthText,
            todayDataText = todayText,
            deviceCount = onlineDevices,
            connected = true,
            updatedLabel = timeText
        )
    }

    fun formatMbCompact(mb: Double): String {
        val safeMb = mb.coerceAtLeast(0.0)
        return if (safeMb >= 1024.0) {
            String.format(Locale.US, "%.1f GB", safeMb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f MB", safeMb)
        }
    }
}

class RouterWidgetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): WidgetSnapshot = WidgetSnapshot(
        networkType = prefs.getString(KEY_NET_TYPE, "--").orEmpty().ifBlank { "--" },
        signalBars = prefs.getInt(KEY_SIGNAL_BARS, 0).coerceIn(0, 5),
        rsrpText = prefs.getString(KEY_RSRP, "--").orEmpty().ifBlank { "--" },
        monthlyDataText = prefs.getString(KEY_MONTH_DATA, "--").orEmpty().ifBlank { "--" },
        todayDataText = prefs.getString(KEY_TODAY_DATA, "--").orEmpty().ifBlank { "--" },
        deviceCount = prefs.getInt(KEY_DEVICE_COUNT, 0),
        connected = prefs.getBoolean(KEY_CONNECTED, false),
        updatedLabel = prefs.getString(KEY_UPDATED, "--").orEmpty().ifBlank { "--" }
    )

    fun save(snapshot: WidgetSnapshot) {
        prefs.edit {
            putString(KEY_NET_TYPE, snapshot.networkType)
            putInt(KEY_SIGNAL_BARS, snapshot.signalBars.coerceIn(0, 5))
            putString(KEY_RSRP, snapshot.rsrpText)
            putString(KEY_MONTH_DATA, snapshot.monthlyDataText)
            putString(KEY_TODAY_DATA, snapshot.todayDataText)
            putInt(KEY_DEVICE_COUNT, snapshot.deviceCount)
            putBoolean(KEY_CONNECTED, snapshot.connected)
            putString(KEY_UPDATED, snapshot.updatedLabel)
        }
    }

    fun markDisconnected() {
        prefs.edit {
            putBoolean(KEY_CONNECTED, false)
            putString(KEY_UPDATED, SimpleDateFormat("HH:mm", Locale.US).format(Date()))
        }
    }

    companion object {
        private const val PREFS_NAME = "airtel_router_widget_snapshot"
        private const val KEY_NET_TYPE = "net_type"
        private const val KEY_SIGNAL_BARS = "signal_bars"
        private const val KEY_RSRP = "rsrp"
        private const val KEY_MONTH_DATA = "month_data"
        private const val KEY_TODAY_DATA = "today_data"
        private const val KEY_DEVICE_COUNT = "device_count"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_UPDATED = "updated"
    }
}
