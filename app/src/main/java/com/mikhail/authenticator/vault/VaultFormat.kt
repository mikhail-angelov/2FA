package com.mikhail.authenticator.vault

import com.mikhail.authenticator.crypto.OtpAlgorithm
import com.mikhail.authenticator.otp.OtpAccount
import kotlinx.serialization.Serializable

/**
 * Export file layout.
 *
 * The envelope is readable on purpose (kdf, iterations, cipher — so a human can tell what
 * the file is and a future version can still open it), while the entries themselves live
 * encrypted inside `data` as base64 of `salt || iv || ciphertext+tag`.
 *
 * PBKDF2-HMAC-SHA256 + AES-256-GCM are both `javax.crypto` primitives, so the whole path
 * needs no third-party crypto dependency. Note this is deliberately *not* bit-compatible
 * with the Aegis vault format, which uses scrypt — matching it would mean pulling in
 * BouncyCastle, against the minimal-dependency goal. Importing Aegis files is a separate
 * task (see README).
 */
@Serializable
data class VaultFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val kdf: String = KDF,
    val iterations: Int = DEFAULT_ITERATIONS,
    val cipher: String = CIPHER,
    val data: String,
) {
    companion object {
        const val FORMAT = "2fa-vault"
        const val KDF = "PBKDF2-HMAC-SHA256"
        const val CIPHER = "AES-256-GCM"
        const val DEFAULT_ITERATIONS = 210_000
    }
}

@Serializable
data class VaultEntry(
    val issuer: String,
    val account: String,
    val secret: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
) {
    fun toAccount(): OtpAccount = OtpAccount(
        issuer = issuer,
        account = account,
        secret = secret,
        algorithm = OtpAlgorithm.fromOtpAuth(algorithm),
        digits = digits,
        period = period,
    )

    companion object {
        fun from(account: OtpAccount): VaultEntry = VaultEntry(
            issuer = account.issuer,
            account = account.account,
            secret = account.secret,
            algorithm = account.algorithm.otpauthName,
            digits = account.digits,
            period = account.period,
        )
    }
}

@Serializable
data class VaultPayload(
    val entries: List<VaultEntry>,
)
