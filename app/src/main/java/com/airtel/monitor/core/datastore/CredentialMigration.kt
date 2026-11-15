package com.airtel.monitor.core.datastore

import com.airtel.monitor.core.crypto.SecretCipher

/** Plaintext credential snapshot from legacy SharedPreferences. */
data class LegacyPreferencesSnapshot(
    val routerHost: String?,
    val username: String?,
    val password: String?,
    val pollIntervalSeconds: Float?
)

interface LegacyPreferencesSource {
    fun hasLegacyData(): Boolean
    fun readLegacy(): LegacyPreferencesSnapshot
    fun deleteLegacyCredentials()
}

data class MigratedCredentials(
    val routerHost: String,
    val username: String,
    val encryptedPassword: String,
    val pollIntervalSeconds: Float
)

/**
 * Pure migration helper: reads plaintext legacy prefs, encrypts the password via [SecretCipher],
 * writes via [saveToTarget], and only then deletes the plaintext legacy values.
 */
object CredentialMigration {
    const val DEFAULT_HOST = "192.168.1.1"
    const val DEFAULT_USERNAME = "root"
    const val DEFAULT_PASSWORD = "admin"
    const val DEFAULT_POLL_INTERVAL = 2.0f

    suspend fun migrateIfNeeded(
        alreadyMigrated: Boolean,
        legacySource: LegacyPreferencesSource,
        cipher: SecretCipher,
        saveToTarget: suspend (MigratedCredentials?) -> Unit
    ): Boolean {
        if (alreadyMigrated) return false

        if (!legacySource.hasLegacyData()) {
            saveToTarget(null)
            return false
        }

        val snapshot = legacySource.readLegacy()
        val host = snapshot.routerHost?.trim().orEmpty().ifEmpty { DEFAULT_HOST }
        val user = snapshot.username?.trim().orEmpty().ifEmpty { DEFAULT_USERNAME }
        val plainPassword = snapshot.password ?: DEFAULT_PASSWORD
        val encryptedPassword = cipher.encrypt(plainPassword)
        val interval = (snapshot.pollIntervalSeconds ?: DEFAULT_POLL_INTERVAL).coerceIn(0.5f, 10.0f)

        saveToTarget(
            MigratedCredentials(
                routerHost = host,
                username = user,
                encryptedPassword = encryptedPassword,
                pollIntervalSeconds = interval
            )
        )
        legacySource.deleteLegacyCredentials()
        return true
    }
}
