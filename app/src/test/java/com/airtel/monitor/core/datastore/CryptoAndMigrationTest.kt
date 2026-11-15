package com.airtel.monitor.core.datastore

import com.airtel.monitor.core.crypto.AesGcmCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class CryptoAndMigrationTest {

    private fun testKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `AES-GCM cipher round-trips secrets and never stores plaintext`() {
        val key = testKey()
        val cipher = AesGcmCipher { key }
        val encoded = cipher.encrypt("superSecret#2026")
        assertTrue(encoded.startsWith("v1:"))
        assertFalse(encoded.contains("superSecret"))
        assertEquals("superSecret#2026", cipher.decrypt(encoded))
    }

    @Test
    fun `migration encrypts legacy password, saves to target, and deletes plaintext source`() = runTest {
        val key = testKey()
        val cipher = AesGcmCipher { key }

        var legacyHost: String? = "192.168.1.1"
        var legacyUser: String? = "root"
        var legacyPass: String? = "myRouterPass"
        var legacyInterval: Float? = 1.5f
        var deleted = false

        val legacySource = object : LegacyPreferencesSource {
            override fun hasLegacyData() = legacyPass != null || legacyHost != null
            override fun readLegacy() = LegacyPreferencesSnapshot(legacyHost, legacyUser, legacyPass, legacyInterval)
            override fun deleteLegacyCredentials() {
                legacyHost = null
                legacyUser = null
                legacyPass = null
                legacyInterval = null
                deleted = true
            }
        }

        var saved: MigratedCredentials? = null
        val didMigrate = CredentialMigration.migrateIfNeeded(
            alreadyMigrated = false,
            legacySource = legacySource,
            cipher = cipher
        ) { saved = it }

        assertTrue(didMigrate)
        assertTrue(deleted)
        assertNull(legacyPass)
        val target = saved!!
        assertEquals("192.168.1.1", target.routerHost)
        assertEquals("root", target.username)
        assertNotEquals("myRouterPass", target.encryptedPassword)
        assertEquals("myRouterPass", cipher.decrypt(target.encryptedPassword))
        assertEquals(1.5f, target.pollIntervalSeconds, 0.001f)
    }

    @Test
    fun `migration is skipped when already migrated`() = runTest {
        val key = testKey()
        val cipher = AesGcmCipher { key }
        var called = false
        val legacySource = object : LegacyPreferencesSource {
            override fun hasLegacyData() = true
            override fun readLegacy() = LegacyPreferencesSnapshot("192.168.1.1", "root", "pass", 2f)
            override fun deleteLegacyCredentials() { called = true }
        }
        val didMigrate = CredentialMigration.migrateIfNeeded(true, legacySource, cipher) { called = true }
        assertFalse(didMigrate)
        assertFalse(called)
    }
}
