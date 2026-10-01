package com.mikhail.authenticator.migration

import com.mikhail.authenticator.crypto.Base32
import com.mikhail.authenticator.crypto.OtpAlgorithm
import com.mikhail.authenticator.otp.OtpAccount

/**
 * Turns a parsed migration payload into accounts the app can store.
 *
 * Two kinds of entries are skipped on purpose, and counted so the user is told rather than
 * silently short-changed:
 *
 *  - **HOTP** (counter-based) — this app is TOTP-only. Importing a HOTP secret would produce
 *    an account whose codes never match, which is worse than saying "3 counter-based accounts
 *    were skipped".
 *  - **MD5** — no authenticator in the wild issues MD5 codes; rather than map it to something
 *    HmacMD5-shaped, it is rejected and counted.
 */
object MigrationImport {

    data class Result(
        val accounts: List<OtpAccount>,
        val skippedHotp: Int,
        val skippedUnsupported: Int,
        val batchSize: Int,
        val batchIndex: Int,
        val batchId: Int,
    ) {
        /** True when this is one part of a multi-screenshot export. */
        val isBatched: Boolean get() = batchSize > 1
        val isLastBatch: Boolean get() = batchIndex + 1 >= batchSize
    }

    fun toAccounts(payload: GoogleAuthMigration.MigrationPayload): Result {
        val accounts = mutableListOf<OtpAccount>()
        var skippedHotp = 0
        var skippedUnsupported = 0

        for (parameter in payload.otpParameters) {
            if (parameter.type != GoogleAuthMigration.OtpType.TOTP) {
                skippedHotp++
                continue
            }
            val algorithm = when (parameter.algorithm) {
                GoogleAuthMigration.Algorithm.SHA1 -> OtpAlgorithm.SHA1
                GoogleAuthMigration.Algorithm.SHA256 -> OtpAlgorithm.SHA256
                GoogleAuthMigration.Algorithm.SHA512 -> OtpAlgorithm.SHA512
                else -> null
            }
            if (algorithm == null || parameter.secret.isEmpty()) {
                skippedUnsupported++
                continue
            }

            val (issuer, account) = splitName(parameter.name, parameter.issuer)
            accounts += OtpAccount(
                issuer = issuer,
                account = account,
                secret = Base32.encode(parameter.secret),
                algorithm = algorithm,
                digits = if (parameter.digits == GoogleAuthMigration.DigitCount.EIGHT) 8 else 6,
                period = 30,
            )
        }

        return Result(
            accounts = accounts,
            skippedHotp = skippedHotp,
            skippedUnsupported = skippedUnsupported,
            batchSize = payload.batchSize,
            batchIndex = payload.batchIndex,
            batchId = payload.batchId,
        )
    }

    /**
     * Google writes `name` as "Issuer:account" and, since 2020, fills the separate `issuer`
     * field as well. The explicit field wins; the label is the fallback; and an account like
     * "user@example.com" with an issuer of its own is left alone rather than split on a colon
     * that is not there.
     */
    private fun splitName(name: String, issuer: String): Pair<String, String> {
        val trimmedName = name.trim()
        if (issuer.isNotBlank()) {
            val account = trimmedName.substringAfter(':', trimmedName).trim()
            return issuer.trim() to account.ifEmpty { trimmedName }
        }
        if (':' in trimmedName) {
            val left = trimmedName.substringBefore(':').trim()
            val right = trimmedName.substringAfter(':').trim()
            return left to right.ifEmpty { left }
        }
        return trimmedName to trimmedName
    }
}
