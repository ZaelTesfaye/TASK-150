package com.nutriops.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nutriops.app.logging.AppLogger
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the SQLCipher database encryption passphrase via Android Keystore.
 *
 * On first install a random 32-byte passphrase is generated, encrypted with a
 * Keystore-backed AES-256-GCM key, and persisted in private SharedPreferences.
 * On subsequent launches the encrypted blob is decrypted and returned.
 *
 * If the Keystore is unavailable or corrupted the call throws
 * [IllegalStateException] — no static fallback is used.
 */
object DatabaseKeyManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "nutriops_db_key_wrapper"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PREFS_NAME = "nutriops_db_key_prefs"
    private const val PREF_ENCRYPTED_KEY = "encrypted_db_key"
    private const val PREF_IV = "db_key_iv"
    private const val DB_KEY_LENGTH_BYTES = 32

    fun getOrCreateDatabaseKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingEncryptedKey = prefs.getString(PREF_ENCRYPTED_KEY, null)
        val existingIv = prefs.getString(PREF_IV, null)

        return if (existingEncryptedKey != null && existingIv != null) {
            decryptKey(existingEncryptedKey, existingIv)
        } else {
            val newKey = generateRandomPassphrase()
            val (encryptedKey, iv) = encryptKey(newKey)
            prefs.edit()
                .putString(PREF_ENCRYPTED_KEY, encryptedKey)
                .putString(PREF_IV, iv)
                .apply()
            AppLogger.info("DatabaseKeyManager", "New database encryption key generated and stored")
            newKey
        }
    }

    private fun generateRandomPassphrase(): String {
        val bytes = ByteArray(DB_KEY_LENGTH_BYTES)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun ensureKeystoreKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getKeystoreKey(): SecretKey {
        ensureKeystoreKey()
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private fun encryptKey(plainKey: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKeystoreKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainKey.toByteArray(Charsets.UTF_8))
        val ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
        val encBase64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        return encBase64 to ivBase64
    }

    private fun decryptKey(encryptedBase64: String, ivBase64: String): String {
        val iv = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP)
        val encrypted = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getKeystoreKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}
