package com.airtel.monitor.core.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airtel.monitor.core.crypto.AesGcmCipher
import com.airtel.monitor.core.crypto.AndroidKeystoreSecretProvider
import com.airtel.monitor.core.crypto.SecretCipher
import com.airtel.monitor.core.network.RouterSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "airtel_monitor_datastore")

enum class ThemePreference { SYSTEM, LIGHT, DARK }
enum class SpeedUnitPreference { MBPS, MBS }

data class AppPreferencesSnapshot(
    val isLoaded: Boolean = false,
    val routerHost: String = CredentialMigration.DEFAULT_HOST,
    val username: String = CredentialMigration.DEFAULT_USERNAME,
    val password: String = CredentialMigration.DEFAULT_PASSWORD,
    val pollIntervalSeconds: Float = CredentialMigration.DEFAULT_POLL_INTERVAL,
    val themeMode: ThemePreference = ThemePreference.SYSTEM,
    val speedUnit: SpeedUnitPreference = SpeedUnitPreference.MBPS,
    val maskSensitive: Boolean = true,
    val hasSavedPassword: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val backgroundMonitorEnabled: Boolean = false,
    val notifyQuotaWarning: Boolean = true,
    val notifyNewSms: Boolean = true,
    val notifyNewDevice: Boolean = false,
    val notifyUnreachable: Boolean = true,
    val ussdShortcuts: List<String> = emptyList()
)

/**
 * Encrypted DataStore-backed repository for router credentials and app preferences.
 * Migrates plaintext credentials from legacy SharedPreferences on first launch and deletes the old values.
 */
class AppPreferencesRepository(
    context: Context,
    private val cipher: SecretCipher = AesGcmCipher { AndroidKeystoreSecretProvider.getOrCreateKey() }
) : RouterSettings {

    private val appContext = context.applicationContext
    private val dataStore = appContext.appDataStore
    private val legacyPrefs: SharedPreferences =
        appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

    private val _preferences = MutableStateFlow(AppPreferencesSnapshot())
    val preferencesFlow: StateFlow<AppPreferencesSnapshot> = _preferences.asStateFlow()

    init {
        scope.launch {
            ensureInitialized()
        }
    }

    suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            val current = dataStore.data.first()
            val alreadyMigrated = current[KEY_MIGRATED] == true

            val legacySource = object : LegacyPreferencesSource {
                override fun hasLegacyData(): Boolean =
                    legacyPrefs.contains(LEGACY_HOST) ||
                        legacyPrefs.contains(LEGACY_USERNAME) ||
                        legacyPrefs.contains(LEGACY_PASSWORD) ||
                        legacyPrefs.contains(LEGACY_POLL_INTERVAL)

                override fun readLegacy(): LegacyPreferencesSnapshot = LegacyPreferencesSnapshot(
                    routerHost = legacyPrefs.getString(LEGACY_HOST, null),
                    username = legacyPrefs.getString(LEGACY_USERNAME, null),
                    password = legacyPrefs.getString(LEGACY_PASSWORD, null),
                    pollIntervalSeconds = if (legacyPrefs.contains(LEGACY_POLL_INTERVAL)) {
                        legacyPrefs.getFloat(LEGACY_POLL_INTERVAL, CredentialMigration.DEFAULT_POLL_INTERVAL)
                    } else null
                )

                override fun deleteLegacyCredentials() {
                    legacyPrefs.edit()
                        .remove(LEGACY_PASSWORD)
                        .remove(LEGACY_HOST)
                        .remove(LEGACY_USERNAME)
                        .remove(LEGACY_POLL_INTERVAL)
                        .commit()
                }
            }

            CredentialMigration.migrateIfNeeded(
                alreadyMigrated = alreadyMigrated,
                legacySource = legacySource,
                cipher = cipher
            ) { migrated ->
                dataStore.edit { prefs ->
                    if (migrated != null) {
                        prefs[KEY_ROUTER_HOST] = migrated.routerHost
                        prefs[KEY_USERNAME] = migrated.username
                        prefs[KEY_ENCRYPTED_PASSWORD] = migrated.encryptedPassword
                        prefs[KEY_HAS_SAVED_PASSWORD] = true
                        prefs[KEY_POLL_INTERVAL] = migrated.pollIntervalSeconds
                        if (prefs[KEY_ONBOARDING_COMPLETED] == null) {
                            prefs[KEY_ONBOARDING_COMPLETED] = true
                        }
                    }
                    prefs[KEY_MIGRATED] = true
                }
            }

            // Upgrading v3 users who already migrated credentials before KEY_ONBOARDING_COMPLETED existed
            // should not be forced back through the first-run wizard.
            val postMigrate = dataStore.data.first()
            if (postMigrate[KEY_MIGRATED] == true &&
                postMigrate[KEY_ONBOARDING_COMPLETED] == null &&
                postMigrate[KEY_HAS_SAVED_PASSWORD] == true
            ) {
                dataStore.edit { prefs ->
                    prefs[KEY_ONBOARDING_COMPLETED] = true
                }
            }

            val updated = dataStore.data.first()
            _preferences.value = decodeSnapshot(updated)
            initialized = true
        }
    }

    private fun ensureLoadedBlocking() {
        if (!initialized) {
            runBlocking(Dispatchers.IO) { ensureInitialized() }
        }
    }

    private fun decodeSnapshot(prefs: Preferences): AppPreferencesSnapshot {
        val host = prefs[KEY_ROUTER_HOST]?.trim().orEmpty().ifEmpty { CredentialMigration.DEFAULT_HOST }
        val user = prefs[KEY_USERNAME]?.trim().orEmpty().ifEmpty { CredentialMigration.DEFAULT_USERNAME }
        val password = if (prefs[KEY_HAS_SAVED_PASSWORD] == true) {
            val enc = prefs[KEY_ENCRYPTED_PASSWORD].orEmpty()
            runCatching { cipher.decrypt(enc) }.getOrDefault(CredentialMigration.DEFAULT_PASSWORD)
        } else {
            CredentialMigration.DEFAULT_PASSWORD
        }
        val interval = (prefs[KEY_POLL_INTERVAL] ?: CredentialMigration.DEFAULT_POLL_INTERVAL).coerceIn(0.5f, 10.0f)
        val theme = prefs[KEY_THEME_MODE]?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
            ?: ThemePreference.SYSTEM
        val unit = prefs[KEY_SPEED_UNIT]?.let { runCatching { SpeedUnitPreference.valueOf(it) }.getOrNull() }
            ?: SpeedUnitPreference.MBPS
        val mask = prefs[KEY_MASK_SENSITIVE] ?: true
        val savedPassword = prefs[KEY_HAS_SAVED_PASSWORD] == true
        val onboarded = prefs[KEY_ONBOARDING_COMPLETED] ?: false
        val bgMonitor = prefs[KEY_BG_MONITOR_ENABLED] ?: false
        val notifyQuota = prefs[KEY_NOTIFY_QUOTA] ?: true
        val notifySms = prefs[KEY_NOTIFY_SMS] ?: true
        val notifyDevice = prefs[KEY_NOTIFY_DEVICE] ?: false
        val notifyUnreachable = prefs[KEY_NOTIFY_UNREACHABLE] ?: true
        val shortcutsRaw = prefs[KEY_USSD_SHORTCUTS].orEmpty()
        val shortcuts = shortcutsRaw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        return AppPreferencesSnapshot(
            isLoaded = true,
            routerHost = host,
            username = user,
            password = password,
            pollIntervalSeconds = interval,
            themeMode = theme,
            speedUnit = unit,
            maskSensitive = mask,
            hasSavedPassword = savedPassword,
            onboardingCompleted = onboarded,
            backgroundMonitorEnabled = bgMonitor,
            notifyQuotaWarning = notifyQuota,
            notifyNewSms = notifySms,
            notifyNewDevice = notifyDevice,
            notifyUnreachable = notifyUnreachable,
            ussdShortcuts = shortcuts
        )
    }

    override var routerHost: String
        get() {
            ensureLoadedBlocking()
            return _preferences.value.routerHost
        }
        set(value) {
            val clean = value.trim().ifEmpty { CredentialMigration.DEFAULT_HOST }
            updateSettings(host = clean)
        }

    override var username: String
        get() {
            ensureLoadedBlocking()
            return _preferences.value.username
        }
        set(value) {
            val clean = value.trim().ifEmpty { CredentialMigration.DEFAULT_USERNAME }
            updateSettings(user = clean)
        }

    override var password: String
        get() {
            ensureLoadedBlocking()
            return _preferences.value.password
        }
        set(value) {
            updateSettings(pass = value)
        }

    var pollIntervalSeconds: Float
        get() {
            ensureLoadedBlocking()
            return _preferences.value.pollIntervalSeconds
        }
        set(value) {
            updateSettings(interval = value.coerceIn(0.5f, 10.0f))
        }

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
        notifyUnreachable: Boolean? = null,
        ussdShortcuts: List<String>? = null
    ) {
        val current = _preferences.value
        val next = current.copy(
            routerHost = host?.trim()?.ifEmpty { CredentialMigration.DEFAULT_HOST } ?: current.routerHost,
            username = user?.trim()?.ifEmpty { CredentialMigration.DEFAULT_USERNAME } ?: current.username,
            password = pass ?: current.password,
            pollIntervalSeconds = interval?.coerceIn(0.5f, 10.0f) ?: current.pollIntervalSeconds,
            themeMode = themeMode ?: current.themeMode,
            speedUnit = speedUnit ?: current.speedUnit,
            maskSensitive = maskSensitive ?: current.maskSensitive,
            hasSavedPassword = current.hasSavedPassword || pass != null,
            onboardingCompleted = onboardingCompleted ?: current.onboardingCompleted,
            backgroundMonitorEnabled = backgroundMonitorEnabled ?: current.backgroundMonitorEnabled,
            notifyQuotaWarning = notifyQuotaWarning ?: current.notifyQuotaWarning,
            notifyNewSms = notifyNewSms ?: current.notifyNewSms,
            notifyNewDevice = notifyNewDevice ?: current.notifyNewDevice,
            notifyUnreachable = notifyUnreachable ?: current.notifyUnreachable,
            ussdShortcuts = ussdShortcuts ?: current.ussdShortcuts
        )
        _preferences.value = next

        scope.launch {
            ensureInitialized()
            val enc = if (pass != null) cipher.encrypt(pass) else null
            dataStore.edit { prefs ->
                if (host != null) prefs[KEY_ROUTER_HOST] = next.routerHost
                if (user != null) prefs[KEY_USERNAME] = next.username
                if (enc != null) {
                    prefs[KEY_ENCRYPTED_PASSWORD] = enc
                    prefs[KEY_HAS_SAVED_PASSWORD] = true
                }
                if (interval != null) prefs[KEY_POLL_INTERVAL] = next.pollIntervalSeconds
                if (themeMode != null) prefs[KEY_THEME_MODE] = next.themeMode.name
                if (speedUnit != null) prefs[KEY_SPEED_UNIT] = next.speedUnit.name
                if (maskSensitive != null) prefs[KEY_MASK_SENSITIVE] = next.maskSensitive
                if (onboardingCompleted != null) prefs[KEY_ONBOARDING_COMPLETED] = next.onboardingCompleted
                if (backgroundMonitorEnabled != null) prefs[KEY_BG_MONITOR_ENABLED] = next.backgroundMonitorEnabled
                if (notifyQuotaWarning != null) prefs[KEY_NOTIFY_QUOTA] = next.notifyQuotaWarning
                if (notifyNewSms != null) prefs[KEY_NOTIFY_SMS] = next.notifyNewSms
                if (notifyNewDevice != null) prefs[KEY_NOTIFY_DEVICE] = next.notifyNewDevice
                if (notifyUnreachable != null) prefs[KEY_NOTIFY_UNREACHABLE] = next.notifyUnreachable
                if (ussdShortcuts != null) {
                    prefs[KEY_USSD_SHORTCUTS] = next.ussdShortcuts
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString("\n")
                }
                prefs[KEY_MIGRATED] = true
            }
        }
    }

    companion object {
        const val LEGACY_PREFS_NAME = "airtel_monitor_prefs"
        private const val LEGACY_HOST = "router_host"
        private const val LEGACY_USERNAME = "router_username"
        private const val LEGACY_PASSWORD = "router_password"
        private const val LEGACY_POLL_INTERVAL = "poll_interval_seconds"

        private val KEY_ROUTER_HOST = stringPreferencesKey("router_host")
        private val KEY_USERNAME = stringPreferencesKey("router_username")
        private val KEY_ENCRYPTED_PASSWORD = stringPreferencesKey("encrypted_password")
        private val KEY_HAS_SAVED_PASSWORD = booleanPreferencesKey("has_saved_password")
        private val KEY_POLL_INTERVAL = floatPreferencesKey("poll_interval_seconds")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SPEED_UNIT = stringPreferencesKey("speed_unit")
        private val KEY_MASK_SENSITIVE = booleanPreferencesKey("mask_sensitive")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_BG_MONITOR_ENABLED = booleanPreferencesKey("bg_monitor_enabled")
        private val KEY_NOTIFY_QUOTA = booleanPreferencesKey("notify_quota_warning")
        private val KEY_NOTIFY_SMS = booleanPreferencesKey("notify_new_sms")
        private val KEY_NOTIFY_DEVICE = booleanPreferencesKey("notify_new_device")
        private val KEY_NOTIFY_UNREACHABLE = booleanPreferencesKey("notify_unreachable")
        private val KEY_USSD_SHORTCUTS = stringPreferencesKey("ussd_shortcuts")
        private val KEY_MIGRATED = booleanPreferencesKey("migrated_from_legacy_prefs")
    }
}
