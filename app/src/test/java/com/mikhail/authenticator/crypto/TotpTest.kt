package com.mikhail.authenticator.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RFC 6238 Appendix B test vectors.
 *
 * The keys are built from the ASCII bytes the RFC specifies, then fed through our own
 * Base32 encoder — so a single test proves both the TOTP arithmetic and the Base32 codec.
 */
class TotpTest {

    private val sha1Key = "12345678901234567890".toByteArray(Charsets.US_ASCII)                 // 20 bytes
    private val sha256Key = "12345678901234567890123456789012".toByteArray(Charsets.US_ASCII)   // 32 bytes
    private val sha512Key = ("1234567890123456789012345678901234567890" +
        "123456789012345678901234").toByteArray(Charsets.US_ASCII)                              // 64 bytes

    private fun totp(key: ByteArray, time: Long, algorithm: OtpAlgorithm): String =
        Totp.generate(Base32.encode(key), timeSeconds = time, period = 30, digits = 8, algorithm = algorithm)

    @Test
    fun `rfc6238 sha1 vectors`() {
        assertEquals("94287082", totp(sha1Key, 59L, OtpAlgorithm.SHA1))
        assertEquals("07081804", totp(sha1Key, 1111111109L, OtpAlgorithm.SHA1))
        assertEquals("14050471", totp(sha1Key, 1111111111L, OtpAlgorithm.SHA1))
        assertEquals("89005924", totp(sha1Key, 1234567890L, OtpAlgorithm.SHA1))
        assertEquals("69279037", totp(sha1Key, 2000000000L, OtpAlgorithm.SHA1))
        assertEquals("65353130", totp(sha1Key, 20000000000L, OtpAlgorithm.SHA1))
    }

    @Test
    fun `rfc6238 sha256 vectors`() {
        assertEquals("46119246", totp(sha256Key, 59L, OtpAlgorithm.SHA256))
        assertEquals("68084774", totp(sha256Key, 1111111109L, OtpAlgorithm.SHA256))
        assertEquals("67062674", totp(sha256Key, 1111111111L, OtpAlgorithm.SHA256))
        assertEquals("91819424", totp(sha256Key, 1234567890L, OtpAlgorithm.SHA256))
        assertEquals("90698825", totp(sha256Key, 2000000000L, OtpAlgorithm.SHA256))
        assertEquals("77737706", totp(sha256Key, 20000000000L, OtpAlgorithm.SHA256))
    }

    @Test
    fun `rfc6238 sha512 vectors`() {
        assertEquals("90693936", totp(sha512Key, 59L, OtpAlgorithm.SHA512))
        assertEquals("25091201", totp(sha512Key, 1111111109L, OtpAlgorithm.SHA512))
        assertEquals("99943326", totp(sha512Key, 1111111111L, OtpAlgorithm.SHA512))
        assertEquals("93441116", totp(sha512Key, 1234567890L, OtpAlgorithm.SHA512))
        assertEquals("38618901", totp(sha512Key, 2000000000L, OtpAlgorithm.SHA512))
        assertEquals("47863826", totp(sha512Key, 20000000000L, OtpAlgorithm.SHA512))
    }

    @Test
    fun `six digit codes are zero padded`() {
        // The '0' in 07081804 must survive the 8→6 digit path: 081804 stays 6 characters wide.
        val six = Totp.generate(Base32.encode(sha1Key), 1111111109L, period = 30, digits = 6)
        assertEquals(6, six.length)
        assertEquals("081804", six)
    }

    @Test
    fun `counter rolls over with the period`() {
        val key = Base32.encode(sha1Key)
        assertEquals(
            Totp.generate(key, 30L, period = 30, digits = 6),
            Totp.generate(key, 59L, period = 30, digits = 6),
        )
        assertThrows(IllegalArgumentException::class.java) {
            Totp.generate(key, 59L, period = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Totp.generate(key, 59L, digits = 5)
        }
    }

    @Test
    fun `seconds remaining counts down within the period`() {
        assertEquals(30, Totp.secondsRemaining(0L, period = 30))
        assertEquals(1, Totp.secondsRemaining(29L, period = 30))
        assertEquals(30, Totp.secondsRemaining(30L, period = 30))
        assertEquals(5, Totp.secondsRemaining(55L, period = 30))
    }
}
