package com.mikhail.authenticator.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for the export file.
 *
 * PBKDF2WithHmacSHA256 (210 000 iterations, NIST-grade for this purpose) derives a 256-bit
 * key from the user's export password; AES-256-GCM authenticates as well as encrypts, so a
 * wrong password and a tampered file both fail loudly (AEADBadTagException) instead of
 * producing garbage.
 *
 * Blob layout: `salt(16) || iv(12) || ciphertext+tag`.
 *
 * Everything here is plain `javax.crypto`, which is why it is unit-tested on the JVM.
 */
object VaultCrypto {

    const val DEFAULT_ITERATIONS = VaultFile.DEFAULT_ITERATIONS

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF = "PBKDF2WithHmacSHA256"

    fun encrypt(plaintext: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(password.isNotEmpty()) { "export password must not be empty" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return salt + iv + cipher.doFinal(plaintext)
    }

    /**
     * @throws javax.crypto.AEADBadTagException when the password is wrong or the file was
     *   modified — callers surface that as "неверный пароль или файл повреждён".
     */
    fun decrypt(blob: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(blob.size > SALT_BYTES + IV_BYTES) { "vault payload is too short" }
        val salt = blob.copyOfRange(0, SALT_BYTES)
        val iv = blob.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val ciphertext = blob.copyOfRange(SALT_BYTES + IV_BYTES, blob.size)
        val key = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
