package com.airtel.monitor.data.local

import android.content.Context
import com.airtel.monitor.core.datastore.AppPreferencesRepository
import com.airtel.monitor.core.datastore.AppPreferencesSnapshot
import com.airtel.monitor.core.datastore.CredentialMigration
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.datastore.ThemePreference
import com.airtel.monitor.core.network.RouterSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Compatibility facade over [AppPreferencesRepository] (encrypted DataStore + Keystore AES-GCM).
 */
class PreferencesRepository private constructor(
    private val store: AppPreferencesRepository
) : RouterSettings {

    constructor(context: Context) : this(AppPreferencesRepository(context))

    val preferencesFlow: StateFlow<AppPreferencesSnapshot>
        get() = store.preferencesFlow

    override var routerHost: String
        get() = store.routerHost
        set(value) { store.routerHost = value }

    override var username: String
        get() = store.username
        set(value) { store.username = value }

    override var password: String
        get() = store.password
        set(value) { store.password = value }

    var pollIntervalSeconds: Float
        get() = store.pollIntervalSeconds
        set(value) { store.pollIntervalSeconds = value }

    suspend fun ensureInitialized() = store.ensureInitialized()

    fun updateSettings(
        host: String? = null,
        user: String? = null,
        pass: String? = null,
        interval: Float? = null,
        themeMode: ThemePreference? = null,
        speedUnit: SpeedUnitPreference? = null,
        maskSensitive: Boolean? = null,
        onboardingCompleted: Boolean? = null,
        backgroundMonitorEnabled: Boolean? = null,
        notifyQuotaWarning: Boolean? = null,
        notifyNewSms: Boolean? = null,
        notifyNewDevice: Boolean? = null,
        notifyUnreachable: Boolean? = null
    ) = store.updateSettings(
        host = host,
        user = user,
        pass = pass,
        interval = interval,
        themeMode = themeMode,
        speedUnit = speedUnit,
        maskSensitive = maskSensitive,
        onboardingCompleted = onboardingCompleted,
        backgroundMonitorEnabled = backgroundMonitorEnabled,
        notifyQuotaWarning = notifyQuotaWarning,
        notifyNewSms = notifyNewSms,
        notifyNewDevice = notifyNewDevice,
        notifyUnreachable = notifyUnreachable
    )

    companion object {
        const val DEFAULT_HOST = CredentialMigration.DEFAULT_HOST
        const val DEFAULT_USERNAME = CredentialMigration.DEFAULT_USERNAME
        const val DEFAULT_PASSWORD = CredentialMigration.DEFAULT_PASSWORD
        const val DEFAULT_POLL_INTERVAL = CredentialMigration.DEFAULT_POLL_INTERVAL
    }
}
