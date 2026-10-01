package com.mikhail.authenticator.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the Base32 secret of every account with an AES-256-GCM key that lives in the
 * Android Keystore (hardware-backed where the device provides it and never exportable).
 *
 * Why not the two options the spec named:
 *  - `androidx.security:security-crypto` (EncryptedSharedPreferences) is deprecated upstream
 *    and would add a dependency to do what Keystore already does.
 *  - SQLCipher would encrypt the whole file but drags in a large native library
 *    (`.so` per ABI) against the "minimal dependencies" goal.
 * Encrypting just the secret column keeps the dependency count at zero and the blast radius
 * small: what is sensitive in this database is the secret, and nothing else on disk is.
 *
 * Payload layout: base64 of `iv(12) || ciphertext+tag`.
 */
class SecretCipher(private val alias: String = KEY_ALIAS) {

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "encrypted secret is truncated" }
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "2fa-account-secret-key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
