package com.mikhail.authenticator.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mikhail.authenticator.crypto.OtpAlgorithm
import com.mikhail.authenticator.otp.OtpAccount

/**
 * One authenticator account.
 *
 * `secretEncrypted` holds base64 of `iv || AES-GCM ciphertext`, produced by [SecretCipher]
 * with a key that lives in the Android Keystore and never leaves it. The Base32 secret is
 * therefore never written to disk in the clear — the point of the whole storage design.
 */
@Entity(tableName = "accounts")
data class OtpEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val issuer: String,
    val account: String,
    val secretEncrypted: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val sortOrder: Int = 0,
)

/** Decrypted, ready to be turned into a code. Lives in memory only. */
data class StoredAccount(
    val id: Long,
    val issuer: String,
    val account: String,
    val secret: String,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val period: Int,
) {
    fun toOtpAccount(): OtpAccount =
        OtpAccount(issuer, account, secret, algorithm, digits, period)
}
