package com.nutriops.app.unit_tests.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.security.DatabaseKeyManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct tests for [DatabaseKeyManager]. Uses a real SharedPreferences
 * instance (via Robolectric) and the Android Keystore shadow for wrapping —
 * no mocking of the manager itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseKeyManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Ensure a clean slate across tests — rotation test in particular
        // depends on starting from "no key yet".
        context.getSharedPreferences("nutriops_db_key_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `getOrCreateDatabaseKey returns non-null, non-empty key`() {
        val key = DatabaseKeyManager.getOrCreateDatabaseKey(context)
        assertThat(key).isNotEmpty()
        // 32 random bytes base64-encoded → ~44 characters (padded)
        assertThat(key.length).isAtLeast(40)
    }

    @Test
    fun `getOrCreateDatabaseKey is deterministic across repeated calls`() {
        val k1 = DatabaseKeyManager.getOrCreateDatabaseKey(context)
        val k2 = DatabaseKeyManager.getOrCreateDatabaseKey(context)
        val k3 = DatabaseKeyManager.getOrCreateDatabaseKey(context)
        assertThat(k2).isEqualTo(k1)
        assertThat(k3).isEqualTo(k1)
    }

    @Test
    fun `rotating the stored key produces a different value`() {
        val original = DatabaseKeyManager.getOrCreateDatabaseKey(context)

        // "Rotate" by clearing the stored wrapped-key blob — simulates a key
        // rotation procedure where the encrypted key in SharedPreferences is
        // invalidated. Next call must generate and persist a fresh key.
        context.getSharedPreferences("nutriops_db_key_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()

        val rotated = DatabaseKeyManager.getOrCreateDatabaseKey(context)
        assertThat(rotated).isNotEqualTo(original)
        assertThat(rotated).isNotEmpty()
    }

    @Test
    fun `key is persisted across instances via SharedPreferences`() {
        val key = DatabaseKeyManager.getOrCreateDatabaseKey(context)

        // Simulate a fresh process lifetime by re-obtaining context
        val sameContext = ApplicationProvider.getApplicationContext<Context>()
        val reloaded = DatabaseKeyManager.getOrCreateDatabaseKey(sameContext)

        assertThat(reloaded).isEqualTo(key)
    }

    @Test
    fun `stored key is encrypted - raw prefs do not contain the plaintext key`() {
        val plaintextKey = DatabaseKeyManager.getOrCreateDatabaseKey(context)

        val prefs = context.getSharedPreferences("nutriops_db_key_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getString("encrypted_db_key", null)
        val iv = prefs.getString("db_key_iv", null)

        assertThat(stored).isNotNull()
        assertThat(iv).isNotNull()
        // The encrypted blob must not equal the plaintext key
        assertThat(stored).isNotEqualTo(plaintextKey)
    }
}
