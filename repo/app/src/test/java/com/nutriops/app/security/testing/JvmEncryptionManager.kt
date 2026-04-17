package com.nutriops.app.security.testing

import com.nutriops.app.security.EncryptionManager
import io.mockk.mockk
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Test-only [EncryptionManager] that performs real AES-256-GCM encryption
 * with a deterministic in-memory key instead of the Android Keystore.
 *
 * Use this in integration tests that exercise repositories depending on
 * encryption — the production class cannot be instantiated on a pure JVM
 * because `AndroidKeyStore` is an Android-only security provider.
 *
 * The ciphertext format matches the production implementation
 * (`<iv_base64>:<ciphertext_base64>`) so encrypted columns round-trip
 * identically under test and in production.
 */
class JvmEncryptionManager(
    key: SecretKey = default256BitKey()
) : EncryptionManager(context = mockk(relaxed = true)) {

    private val secretKey: SecretKey = key

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val encB64 = Base64.getEncoder().encodeToString(encrypted)
        return "$ivB64$IV_SEPARATOR$encB64"
    }

    override fun decrypt(ciphertext: String): String {
        val parts = ciphertext.split(IV_SEPARATOR)
        require(parts.size == 2) { "Invalid encrypted value format" }
        val iv = Base64.getDecoder().decode(parts[0])
        val encrypted = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val IV_SEPARATOR = ":"

        /**
         * Deterministic in-memory 256-bit AES key. Not for production use.
         */
        fun default256BitKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
