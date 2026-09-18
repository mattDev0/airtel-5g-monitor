package com.airtel.monitor.data.local

import android.content.Context
import android.content.SharedPreferences
import com.airtel.monitor.data.remote.RouterSettings

class PreferencesRepository(context: Context) : RouterSettings {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("airtel_monitor_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ROUTER_HOST = "router_host"
        private const val KEY_USERNAME = "router_username"
        private const val KEY_PASSWORD = "router_password"
        private const val KEY_POLL_INTERVAL = "poll_interval_seconds"

        const val DEFAULT_HOST = "192.168.1.1"
        const val DEFAULT_USERNAME = "root"
        const val DEFAULT_PASSWORD = "admin"
        const val DEFAULT_POLL_INTERVAL = 2.0f
    }

    override var routerHost: String
        get() = prefs.getString(KEY_ROUTER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_ROUTER_HOST, value.trim()).apply()

    override var username: String
        get() = prefs.getString(KEY_USERNAME, DEFAULT_USERNAME) ?: DEFAULT_USERNAME
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    override var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var pollIntervalSeconds: Float
        get() = prefs.getFloat(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL)
        set(value) = prefs.edit().putFloat(KEY_POLL_INTERVAL, value.coerceIn(0.5f, 10.0f)).apply()
}
