package com.mikhail.authenticator.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Hash algorithms allowed by the otpauth URI scheme. */
enum class OtpAlgorithm(val macName: String, val otpauthName: String) {
    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512");

    companion object {
        fun fromOtpAuth(value: String?): OtpAlgorithm = when (value?.trim()?.uppercase()) {
            "SHA256" -> SHA256
            "SHA512" -> SHA512
            else -> SHA1
        }
    }
}

/**
 * RFC 4226 (HOTP) and RFC 6238 (TOTP) in plain Kotlin over `javax.crypto` — no third-party
 * crypto, no code-generation quirks.
 *
 * Note on the arithmetic: RFC 4226 §5.3 dynamic truncation yields a 31-bit unsigned integer.
 * The reference is computed with integer maths only (`Int` shift/or, `% 10^digits`); going
 * through `Double` — as some samples do — is a needless precision hazard.
 */
object Totp {

    const val DEFAULT_PERIOD = 30
    const val DEFAULT_DIGITS = 6

    fun generate(
        secretBase32: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = DEFAULT_PERIOD,
        digits: Int = DEFAULT_DIGITS,
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    ): String {
        require(period > 0) { "period must be positive, was $period" }
        require(digits in 6..8) { "digits must be 6..8, was $digits" }
        val key = Base32.decode(secretBase32)
        require(key.isNotEmpty()) { "secret decoded to zero bytes" }
        return hotp(key, timeSeconds / period, digits, algorithm)
    }

    /** Seconds until the current code rolls over: 1..period. Drives the card's progress ring. */
    fun secondsRemaining(
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = DEFAULT_PERIOD,
    ): Int {
        require(period > 0)
        val remaining = period - (timeSeconds % period).toInt()
        return if (remaining == period) period else remaining
    }

    /** HOTP with an explicit counter — the shared core of TOTP, and directly testable. */
    fun hotp(key: ByteArray, counter: Long, digits: Int, algorithm: OtpAlgorithm = OtpAlgorithm.SHA1): String {
        require(digits in 6..8) { "digits must be 6..8, was $digits" }
        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            message[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }

        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, "RAW"))
        val hash = mac.doFinal(message)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val modulus = pow10(digits)
        return (binary % modulus).toString().padStart(digits, '0')
    }

    private fun pow10(digits: Int): Int {
        var m = 1
        repeat(digits) { m *= 10 }
        return m
    }
}
