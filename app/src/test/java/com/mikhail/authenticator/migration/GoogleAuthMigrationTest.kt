package com.mikhail.authenticator.migration

import com.mikhail.authenticator.crypto.Base32
import com.mikhail.authenticator.crypto.OtpAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The first fixture is a protobuf message written out byte by byte, straight from the schema:
 * nothing in this file produced it, so a bug in the writer cannot hide a bug in the reader.
 *
 *   field 1 (otp_parameters, bytes, 15 bytes):
 *     field 1 secret   = "Hi"          0A 02 48 69
 *     field 2 name     = "X:y"         12 03 58 3A 79
 *     field 4 algorithm = 1 (SHA1)     20 01
 *     field 5 digits    = 1 (six)      28 01
 *     field 6 type      = 2 (TOTP)     30 02
 *   field 2 version = 1                10 01
 */
private val HAND_WRITTEN_PAYLOAD = byteArrayOf(
    0x0A, 0x0F,
    0x0A, 0x02, 0x48, 0x69,
    0x12, 0x03, 0x58, 0x3A, 0x79,
    0x20, 0x01,
    0x28, 0x01,
    0x30, 0x02,
    0x10, 0x01,
)

class GoogleAuthMigrationTest {

    @Test
    fun `reads a hand-written payload byte for byte`() {
        val payload = GoogleAuthMigration.parsePayload(HAND_WRITTEN_PAYLOAD)

        assertEquals(1, payload.version)
        assertEquals(1, payload.otpParameters.size)
        val parameter = payload.otpParameters.single()
        assertEquals("X:y", parameter.name)
        assertEquals(GoogleAuthMigration.Algorithm.SHA1, parameter.algorithm)
        assertEquals(GoogleAuthMigration.DigitCount.SIX, parameter.digits)
        assertEquals(GoogleAuthMigration.OtpType.TOTP, parameter.type)
        // The secret survived: the app's own decoder turns it back into the bytes we wrote.
        assertEquals("Hi", String(Base32.decode(Base32.encode(parameter.secret))))
        assertFalse(payload.isBatched)
    }

    @Test
    fun `maps a payload to accounts and skips what the app cannot use`() {
        val uri = migrationUri(
            parameters = listOf(
                sha1Six(name = "GitHub:octocat@example.com", issuer = "GitHub"),
                sha1Six(name = "Legacy:hotp", type = GoogleAuthMigration.OtpType.HOTP),
                sha1Six(name = "Old:md5", algorithm = GoogleAuthMigration.Algorithm.MD5),
                sha512Eight(name = "Bank:payments", issuer = "Bank"),
            ),
        )

        val result = MigrationImport.toAccounts(GoogleAuthMigration.parse(uri))

        assertEquals(2, result.accounts.size)
        assertEquals(1, result.skippedHotp)
        assertEquals(1, result.skippedUnsupported)

        val github = result.accounts.first()
        assertEquals("GitHub", github.issuer)
        assertEquals("octocat@example.com", github.account)
        assertEquals(OtpAlgorithm.SHA1, github.algorithm)
        assertEquals(6, github.digits)
        assertEquals(30, github.period)

        val bank = result.accounts.last()
        assertEquals("Bank", bank.issuer)
        assertEquals("payments", bank.account)
        assertEquals(OtpAlgorithm.SHA512, bank.algorithm)
        assertEquals(8, bank.digits)
    }

    @Test
    fun `encodes the twenty byte rfc key exactly as the spec says`() {
        val rfcKey = "12345678901234567890".toByteArray(Charsets.US_ASCII)
        val uri = migrationUri(parameters = listOf(sha1Six(name = "RFC:key", secret = rfcKey)))

        val account = MigrationImport.toAccounts(GoogleAuthMigration.parse(uri)).accounts.single()

        // The same key the RFC 6238 vectors use, so the Base32 path is pinned to a known string.
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", account.secret)
    }

    @Test
    fun `splits the label the way google writes it`() {
        val withoutIssuer = migrationUri(parameters = listOf(sha1Six(name = "ACME:alice@example.com", issuer = "")))
        val account = MigrationImport.toAccounts(GoogleAuthMigration.parse(withoutIssuer)).accounts.single()
        assertEquals("ACME", account.issuer)
        assertEquals("alice@example.com", account.account)

        val bare = migrationUri(parameters = listOf(sha1Six(name = "alice@example.com", issuer = "")))
        val bareAccount = MigrationImport.toAccounts(GoogleAuthMigration.parse(bare)).accounts.single()
        assertEquals("alice@example.com", bareAccount.issuer)
        assertEquals("alice@example.com", bareAccount.account)
    }

    @Test
    fun `reports a multi screenshot export as batched`() {
        val uri = migrationUri(
            parameters = listOf(sha1Six(name = "A:b")),
            batchSize = 3,
            batchIndex = 1,
            batchId = 42,
        )

        val payload = GoogleAuthMigration.parse(uri)
        assertTrue(payload.isBatched)
        assertEquals(3, payload.batchSize)
        assertEquals(1, payload.batchIndex)
        assertEquals(42, payload.batchId)
    }

    @Test
    fun `accepts a percent encoded payload and does not turn plus into space`() {
        // Base64 with the '+' character is the case a naive URLDecoder breaks on.
        val parameters = listOf(sha1Six(name = "Plus:account", secret = ByteArray(20) { 0xFB.toByte() }))
        val raw = Base64.getEncoder().encodeToString(encodePayload(parameters, 1, 0, 0))
        assertTrue("нужен '+' в фикстуре", raw.contains('+'))

        val encoded = raw.replace("+", "%2B").replace("=", "%3D")
        val percentEncoded = GoogleAuthMigration.parse("otpauth-migration://offline?data=$encoded")
        val plain = GoogleAuthMigration.parse("otpauth-migration://offline?data=$raw")

        assertEquals(1, percentEncoded.otpParameters.size)
        assertEquals(1, plain.otpParameters.size)
        assertTrue(percentEncoded.otpParameters.single().secret.contentEquals(plain.otpParameters.single().secret))
    }

    @Test
    fun `rejects everything that is not a migration uri`() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleAuthMigration.parse("otpauth://totp/Test:a?secret=JBSWY3DPEHPK3PXP")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleAuthMigration.parse("otpauth-migration://offline")            // no data
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleAuthMigration.parse("otpauth-migration://offline?data=***не-base64***")
        }
    }

    @Test
    fun `a truncated payload fails instead of returning half an import`() {
        val truncated = HAND_WRITTEN_PAYLOAD.copyOfRange(0, HAND_WRITTEN_PAYLOAD.size - 6)
        assertThrows(IllegalArgumentException::class.java) {
            GoogleAuthMigration.parsePayload(truncated)
        }
    }

    @Test
    fun `unknown fields are skipped, not fatal`() {
        // A newer Google build adding field 9 must not break the import.
        val withUnknown = HAND_WRITTEN_PAYLOAD + byteArrayOf(0x4A, 0x03, 0x01, 0x02, 0x03)
        val payload = GoogleAuthMigration.parsePayload(withUnknown)
        assertEquals(1, payload.otpParameters.size)
    }
}

// ── test-side protobuf writer (independent of the reader under test) ─────────────────────────

private fun migrationUri(
    parameters: List<ByteArray>,
    batchSize: Int = 1,
    batchIndex: Int = 0,
    batchId: Int = 0,
): String {
    val body = encodePayload(parameters, batchSize, batchIndex, batchId)
    return "otpauth-migration://offline?data=" + Base64.getEncoder().encodeToString(body)
}

private fun encodePayload(parameters: List<ByteArray>, batchSize: Int, batchIndex: Int, batchId: Int): ByteArray {
    val out = mutableListOf<Byte>()
    for (parameter in parameters) {
        out += tag(1, 2) + varint(parameter.size.toLong()) + parameter.toList()
    }
    out += tag(2, 0) + varint(1)
    out += tag(3, 0) + varint(batchSize.toLong())
    out += tag(4, 0) + varint(batchIndex.toLong())
    out += tag(5, 0) + varint(batchId.toLong())
    return out.toByteArray()
}

private fun sha1Six(
    name: String,
    issuer: String = "GitHub",
    secret: ByteArray = "12345678901234567890".toByteArray(Charsets.US_ASCII),
    algorithm: GoogleAuthMigration.Algorithm = GoogleAuthMigration.Algorithm.SHA1,
    type: GoogleAuthMigration.OtpType = GoogleAuthMigration.OtpType.TOTP,
): ByteArray = encodeParameters(secret, name, issuer, algorithm, GoogleAuthMigration.DigitCount.SIX, type)

private fun sha512Eight(
    name: String,
    issuer: String = "Bank",
    secret: ByteArray = ByteArray(64) { it.toByte() },
): ByteArray = encodeParameters(
    secret, name, issuer,
    GoogleAuthMigration.Algorithm.SHA512, GoogleAuthMigration.DigitCount.EIGHT, GoogleAuthMigration.OtpType.TOTP,
)

private fun encodeParameters(
    secret: ByteArray,
    name: String,
    issuer: String,
    algorithm: GoogleAuthMigration.Algorithm,
    digits: GoogleAuthMigration.DigitCount,
    type: GoogleAuthMigration.OtpType,
): ByteArray {
    val out = mutableListOf<Byte>()
    out += tag(1, 2) + varint(secret.size.toLong()) + secret.toList()
    if (name.isNotEmpty()) {
        val nameBytes = name.toByteArray(Charsets.UTF_8).toList()
        out += tag(2, 2) + varint(nameBytes.size.toLong()) + nameBytes
    }
    if (issuer.isNotEmpty()) {
        val issuerBytes = issuer.toByteArray(Charsets.UTF_8).toList()
        out += tag(3, 2) + varint(issuerBytes.size.toLong()) + issuerBytes
    }
    out += tag(4, 0) + varint(algorithm.id.toLong())
    out += tag(5, 0) + varint(digits.id.toLong())
    out += tag(6, 0) + varint(type.id.toLong())
    return out.toByteArray()
}

private fun tag(field: Int, wireType: Int): List<Byte> = varint(((field shl 3) or wireType).toLong())

private fun varint(value: Long): List<Byte> {
    var remaining = value
    val out = mutableListOf<Byte>()
    while (true) {
        if (remaining and 0x7FL.inv() == 0L) {
            out += remaining.toByte()
            return out
        }
        out += ((remaining and 0x7F) or 0x80).toByte()
        remaining = remaining ushr 7
    }
}
