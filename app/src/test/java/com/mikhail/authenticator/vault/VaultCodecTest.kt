package com.mikhail.authenticator.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * The export/import path end to end, in pure JVM code.
 *
 * Iterations are turned down here (the production default is 210 000) so the suite stays
 * fast; the code path is identical.
 */
class VaultCodecTest {

    private val fastIterations = 1_000

    private val entries = listOf(
        VaultEntry("GitHub", "octocat@example.com", "JBSWY3DPEHPK3PXP"),
        VaultEntry("Yandex", "user@ya.ru", "MZXW6YTBOI", "SHA256", 8, 60),
    )

    @Test
    fun `round trips entries and their parameters`() {
        val file = VaultCodec.encode(entries, "correct horse battery".toCharArray(), fastIterations)
        val decoded = VaultCodec.decode(file, "correct horse battery".toCharArray())

        assertEquals(entries.size, decoded.size)
        assertEquals(entries[0].issuer, decoded[0].issuer)
        assertEquals(entries[0].secret, decoded[0].secret)
        assertEquals("SHA256", decoded[1].algorithm)
        assertEquals(8, decoded[1].digits)
        assertEquals(60, decoded[1].period)
    }

    @Test
    fun `the envelope is readable but the secrets are not`() {
        val file = VaultCodec.encode(entries, "passphrase123".toCharArray(), fastIterations)

        // Header is plaintext on purpose: a human can tell what the file is.
        assertTrue(file.contains(VaultFile.FORMAT))
        assertTrue(file.contains(VaultFile.KDF))
        assertTrue(file.contains(VaultFile.CIPHER))
        // No secret may appear in the clear — that is the whole point of the format.
        assertTrue(!file.contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!file.contains("MZXW6YTBOI"))
        assertTrue(!file.contains("octocat@example.com"))
    }

    @Test
    fun `wrong password fails loudly instead of decoding to garbage`() {
        val file = VaultCodec.encode(entries, "right-password".toCharArray(), fastIterations)
        assertThrows(AEADBadTagException::class.java) {
            VaultCodec.decode(file, "wrong-password".toCharArray())
        }
    }

    @Test
    fun `tampered payload is rejected`() {
        val file = VaultCodec.encode(entries, "passphrase123".toCharArray(), fastIterations)
        val marker = "\"data\": \""
        val start = file.indexOf(marker) + marker.length
        val end = file.indexOf('"', start)
        val data = file.substring(start, end)
        // Flip one character in the middle of the base64 payload.
        val middle = data.length / 2
        val flipped = if (data[middle] == 'A') 'B' else 'A'
        val tampered = file.replaceFirst(data, data.substring(0, middle) + flipped + data.substring(middle + 1))

        assertThrows(Exception::class.java) {
            VaultCodec.decode(tampered, "passphrase123".toCharArray())
        }
    }

    @Test
    fun `two exports of the same data differ`() {
        val first = VaultCodec.encode(entries, "passphrase123".toCharArray(), fastIterations)
        val second = VaultCodec.encode(entries, "passphrase123".toCharArray(), fastIterations)
        // Random salt and IV per export: identical ciphertext would leak that nothing changed.
        assertNotEquals(first, second)
    }

    @Test
    fun `an empty account list is a valid vault`() {
        val file = VaultCodec.encode(emptyList(), "passphrase123".toCharArray(), fastIterations)
        assertEquals(0, VaultCodec.decode(file, "passphrase123".toCharArray()).size)
    }

    @Test
    fun `foreign files are rejected with a clear error`() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultCodec.decode("{\"hello\":\"world\"}", "passphrase123".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultCodec.decode("not json at all", "passphrase123".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultCodec.decode(
                "{\"format\":\"aegis\",\"version\":1,\"data\":\"AAAA\"}",
                "passphrase123".toCharArray(),
            )
        }
    }
}
