package com.mikhail.authenticator.crypto

import java.io.ByteArrayOutputStream

/**
 * RFC 4648 Base32, the alphabet authenticator secrets use.
 *
 * Tolerant on input the way real-world secrets are: case-insensitive, padding optional,
 * whitespace and dashes ignored (people paste secrets copied from web pages).
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray {
        val clean = buildString(input.length) {
            for (c in input) {
                if (c == '=' || c == '-' || c.isWhitespace()) continue
                append(c.uppercaseChar())
            }
        }
        val out = ByteArrayOutputStream(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base32 character: '$c'" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer shr (bitsLeft - 8)) and 0xFF)
                bitsLeft -= 8
            }
        }
        // Leftover bits are padding: fewer than 8 of them cannot form a byte.
        return out.toByteArray()
    }

    /** Unpadded, uppercase — the form authenticator secrets are normally written in. */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        return sb.toString()
    }
}
