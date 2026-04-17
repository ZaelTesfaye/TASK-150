package com.nutriops.app.unit_tests.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutriops.app.security.EncryptionManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.AEADBadTagException

/**
 * Direct tests for [EncryptionManager]. The Android Keystore backing it is
 * exercised through Robolectric's JVM-side shadow — the tests do not mock the
 * manager, and each round-trip goes through real AES-GCM cipher operations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EncryptionManagerTest {

    private lateinit var context: Context
    private lateinit var manager: EncryptionManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = EncryptionManager(context)
    }

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        val plaintext = "sensitive payload $%^&*()"

        val ciphertext = manager.encrypt(plaintext)
        val decrypted = manager.decrypt(ciphertext)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `encrypting same input twice produces different ciphertext due to IV randomness`() {
        val plaintext = "same input"

        val c1 = manager.encrypt(plaintext)
        val c2 = manager.encrypt(plaintext)

        assertThat(c1).isNotEqualTo(c2)
        // But both must decrypt back to the same plaintext
        assertThat(manager.decrypt(c1)).isEqualTo(plaintext)
        assertThat(manager.decrypt(c2)).isEqualTo(plaintext)
    }

    @Test
    fun `ciphertext is not equal to plaintext - encryption actually transforms data`() {
        val plaintext = "plain"
        val ciphertext = manager.encrypt(plaintext)
        assertThat(ciphertext).doesNotContain(plaintext)
    }

    @Test
    fun `empty string round-trips correctly`() {
        val ciphertext = manager.encrypt("")
        assertThat(ciphertext).isNotEmpty()
        assertThat(manager.decrypt(ciphertext)).isEmpty()
    }

    @Test
    fun `decrypt rejects malformed ciphertext missing IV separator`() {
        val thrown = runCatching { manager.decrypt("no-separator-here") }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test(expected = Exception::class)
    fun `decrypt rejects tampered ciphertext`() {
        val ciphertext = manager.encrypt("original")
        val parts = ciphertext.split(":")
        // Flip a bit in the encrypted portion — GCM tag will fail verification
        val tampered = parts[0] + ":" + parts[1].reversed()
        // GCM tag verification throws — most commonly AEADBadTagException but
        // may wrap as IllegalBlockSizeException / GeneralSecurityException.
        manager.decrypt(tampered)
    }

    @Test
    fun `ciphertext format is IV colon encrypted-bytes, both base64`() {
        val ciphertext = manager.encrypt("payload")
        val parts = ciphertext.split(":")
        assertThat(parts).hasSize(2)
        // Both parts are valid base64 (decodeToByteArray would throw otherwise)
        android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
    }

    @Test
    fun `independent EncryptionManager instances share the keystore alias`() {
        // Two instances built against the same Context must use the same
        // keystore-backed key and therefore be mutually compatible.
        val managerB = EncryptionManager(context)
        val ciphertext = manager.encrypt("shared")
        assertThat(managerB.decrypt(ciphertext)).isEqualTo("shared")
    }
}
