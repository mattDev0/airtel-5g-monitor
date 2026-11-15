package com.airtel.monitor.data.local

import android.content.Context
import androidx.core.content.edit
import com.airtel.monitor.domain.MonitorState

interface MonitorStateStore {
    fun read(): MonitorState
    fun write(state: MonitorState)
}

class InMemoryMonitorStateStore(
    initial: MonitorState = MonitorState()
) : MonitorStateStore {
    private var state = initial
    override fun read(): MonitorState = state
    override fun write(state: MonitorState) {
        this.state = state
    }
}

class SharedPreferencesMonitorStateStore(context: Context) : MonitorStateStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): MonitorState = MonitorState(
        lastQuotaAlertCycleKey = prefs.getString(KEY_QUOTA_CYCLE, "").orEmpty(),
        lastNotifiedSmsUnreadCount = prefs.getInt(KEY_SMS_UNREAD, -1),
        deviceBaselineInitialized = prefs.getBoolean(KEY_DEVICE_BASELINE_INIT, false),
        knownDeviceMacs = prefs.getStringSet(KEY_KNOWN_MACS, emptySet()).orEmpty().toSet(),
        consecutiveUnreachableCount = prefs.getInt(KEY_UNREACHABLE_COUNT, 0),
        isUnreachableAlertActive = prefs.getBoolean(KEY_UNREACHABLE_ACTIVE, false)
    )

    override fun write(state: MonitorState) {
        prefs.edit {
            putString(KEY_QUOTA_CYCLE, state.lastQuotaAlertCycleKey)
            putInt(KEY_SMS_UNREAD, state.lastNotifiedSmsUnreadCount)
            putBoolean(KEY_DEVICE_BASELINE_INIT, state.deviceBaselineInitialized)
            putStringSet(KEY_KNOWN_MACS, state.knownDeviceMacs)
            putInt(KEY_UNREACHABLE_COUNT, state.consecutiveUnreachableCount)
            putBoolean(KEY_UNREACHABLE_ACTIVE, state.isUnreachableAlertActive)
        }
    }

    companion object {
        private const val PREFS_NAME = "airtel_monitor_worker_state"
        private const val KEY_QUOTA_CYCLE = "last_quota_cycle_key"
        private const val KEY_SMS_UNREAD = "last_sms_unread"
        private const val KEY_DEVICE_BASELINE_INIT = "device_baseline_init"
        private const val KEY_KNOWN_MACS = "known_device_macs"
        private const val KEY_UNREACHABLE_COUNT = "consecutive_unreachable"
        private const val KEY_UNREACHABLE_ACTIVE = "unreachable_alert_active"
    }
}
