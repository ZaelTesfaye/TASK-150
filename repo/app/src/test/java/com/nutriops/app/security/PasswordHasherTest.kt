package com.nutriops.app.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun `hash produces salted output`() {
        val hash = PasswordHasher.hash("testPassword123")
        assertThat(hash).contains(":")
        assertThat(hash.split(":")).hasSize(2)
    }

    @Test
    fun `verify correct password returns true`() {
        val password = "secureP@ssw0rd"
        val hash = PasswordHasher.hash(password)
        assertThat(PasswordHasher.verify(password, hash)).isTrue()
    }

    @Test
    fun `verify wrong password returns false`() {
        val hash = PasswordHasher.hash("correctPassword")
        assertThat(PasswordHasher.verify("wrongPassword", hash)).isFalse()
    }

    @Test
    fun `different passwords produce different hashes`() {
        val hash1 = PasswordHasher.hash("password1")
        val hash2 = PasswordHasher.hash("password2")
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `same password produces different hashes due to salt`() {
        val hash1 = PasswordHasher.hash("samePassword")
        val hash2 = PasswordHasher.hash("samePassword")
        assertThat(hash1).isNotEqualTo(hash2)
        // But both verify correctly
        assertThat(PasswordHasher.verify("samePassword", hash1)).isTrue()
        assertThat(PasswordHasher.verify("samePassword", hash2)).isTrue()
    }

    @Test
    fun `verify with malformed hash returns false`() {
        assertThat(PasswordHasher.verify("test", "malformed")).isFalse()
        assertThat(PasswordHasher.verify("test", "")).isFalse()
        assertThat(PasswordHasher.verify("test", "a:b:c")).isFalse()
    }

    @Test
    fun `empty password hashes without error`() {
        val hash = PasswordHasher.hash("")
        assertThat(hash).isNotEmpty()
        assertThat(PasswordHasher.verify("", hash)).isTrue()
    }
}
