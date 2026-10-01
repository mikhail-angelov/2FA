package com.mikhail.authenticator.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Base32Test {

    @Test
    fun `encodes known values`() {
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXQ", Base32.encode("fo".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    }

    @Test
    fun `round trips arbitrary bytes`() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()),
            "Héllo, мир!".toByteArray(Charsets.UTF_8),
            ByteArray(64) { (it * 7).toByte() },
        )
        for (bytes in samples) {
            assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)))
        }
    }

    @Test
    fun `decoding tolerates how secrets are actually pasted`() {
        val expected = Base32.decode("MZXW6YTBOI")
        assertEquals(expected.toList(), Base32.decode("mzxw6ytboi").toList())          // lowercase
        assertEquals(expected.toList(), Base32.decode("MZXW 6YTB OI").toList())        // spaces
        assertEquals(expected.toList(), Base32.decode("MZXW-6YTB-OI").toList())        // dashes
        assertEquals(expected.toList(), Base32.decode("MZXW6YTBOI====").toList())      // padding
        assertEquals(expected.toList(), Base32.decode("  MZXW6YTBOI\n").toList())      // whitespace
    }

    @Test
    fun `rejects characters outside the alphabet`() {
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZXW6YTB0I") } // digit zero
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZXW6YTB!I") }
    }

    @Test
    fun `empty input decodes to empty bytes`() {
        assertArrayEquals(ByteArray(0), Base32.decode(""))
        assertArrayEquals(ByteArray(0), Base32.decode("   "))
        assertEquals("", Base32.encode(ByteArray(0)))
    }
}
