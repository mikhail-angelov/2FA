package com.mikhail.authenticator.migration

import java.net.URLDecoder
import java.util.Base64

/**
 * Reader for `otpauth-migration://offline?data=…` — the export format of Google Authenticator.
 *
 * The payload is a base64-encoded protobuf message:
 *
 *     message MigrationPayload {
 *       repeated OtpParameters otp_parameters = 1;
 *       int32 version = 2; int32 batch_size = 3; int32 batch_index = 4; int32 batch_id = 5;
 *     }
 *     message OtpParameters {
 *       bytes secret = 1; string name = 2; string issuer = 3;
 *       Algorithm algorithm = 4; DigitCount digits = 5; OtpType type = 6; int64 counter = 7;
 *     }
 *
 * Field numbers and enum values are taken from the schema shipped in
 * `app/src/main/proto/google_auth_migration.proto`.
 *
 * Pure JVM code: no android.net.Uri, so the whole parsing path is unit-testable.
 */
object GoogleAuthMigration {

    const val URI_SCHEME = "otpauth-migration"
    private const val DATA_PARAMETER = "data"

    enum class Algorithm(val id: Int) {
        UNSPECIFIED(0), SHA1(1), SHA256(2), SHA512(3), MD5(4);

        companion object {
            fun from(id: Int): Algorithm = entries.firstOrNull { it.id == id } ?: UNSPECIFIED
        }
    }

    enum class DigitCount(val id: Int) {
        UNSPECIFIED(0), SIX(1), EIGHT(2);

        companion object {
            fun from(id: Int): DigitCount = entries.firstOrNull { it.id == id } ?: UNSPECIFIED
        }
    }

    enum class OtpType(val id: Int) {
        UNSPECIFIED(0), HOTP(1), TOTP(2);

        companion object {
            fun from(id: Int): OtpType = entries.firstOrNull { it.id == id } ?: UNSPECIFIED
        }
    }

    class OtpParameters(
        val secret: ByteArray,
        val name: String,
        val issuer: String,
        val algorithm: Algorithm,
        val digits: DigitCount,
        val type: OtpType,
        val counter: Long,
    ) {
        override fun toString(): String =
            "OtpParameters(name=$name, issuer=$issuer, algorithm=$algorithm, digits=$digits, type=$type, secret=${secret.size} bytes)"

        override fun equals(other: Any?): Boolean = other is OtpParameters &&
            name == other.name && issuer == other.issuer && algorithm == other.algorithm &&
            digits == other.digits && type == other.type && counter == other.counter &&
            secret.contentEquals(other.secret)

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + issuer.hashCode()
            result = 31 * result + algorithm.hashCode()
            result = 31 * result + digits.hashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + counter.hashCode()
            return 31 * result + secret.contentHashCode()
        }
    }

    class MigrationPayload(
        val otpParameters: List<OtpParameters>,
        val version: Int,
        val batchSize: Int,
        val batchIndex: Int,
        val batchId: Int,
    ) {
        /** True when Google split the export across several QR codes. */
        val isBatched: Boolean get() = batchSize > 1
    }

    /**
     * Parses the whole `otpauth-migration://…` URI.
     *
     * @throws IllegalArgumentException when the URI is not a migration URI, carries no payload,
     *   or the payload is not readable base64/protobuf.
     */
    fun parse(uri: String): MigrationPayload {
        require(uri.startsWith("$URI_SCHEME://")) { "this is not a Google Authenticator transfer link" }
        val data = queryParameter(uri, DATA_PARAMETER)
            ?: throw IllegalArgumentException("the link has no data parameter")
        return parsePayload(decodeBase64(data))
    }

    /** Parses just the protobuf message, for callers that already hold the raw bytes. */
    fun parsePayload(protoBytes: ByteArray): MigrationPayload {
        val parameters = mutableListOf<OtpParameters>()
        var version = 0
        var batchSize = 1
        var batchIndex = 0
        var batchId = 0

        val reader = ProtobufReader(protoBytes)
        while (reader.hasMore()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 2 -> parameters += parseParameters(reader.readBytes())
                field == 2 && wireType == 0 -> version = reader.readVarint().toInt()
                field == 3 && wireType == 0 -> batchSize = reader.readVarint().toInt()
                field == 4 && wireType == 0 -> batchIndex = reader.readVarint().toInt()
                field == 5 && wireType == 0 -> batchId = reader.readVarint().toInt()
                else -> reader.skip(wireType)
            }
        }
        return MigrationPayload(
            otpParameters = parameters,
            version = version,
            batchSize = batchSize.coerceAtLeast(1),
            batchIndex = batchIndex.coerceAtLeast(0),
            batchId = batchId,
        )
    }

    private fun parseParameters(bytes: ByteArray): OtpParameters {
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithm = Algorithm.UNSPECIFIED
        var digits = DigitCount.UNSPECIFIED
        var type = OtpType.UNSPECIFIED
        var counter = 0L

        val reader = ProtobufReader(bytes)
        while (reader.hasMore()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 2 -> secret = reader.readBytes()
                field == 2 && wireType == 2 -> name = reader.readString()
                field == 3 && wireType == 2 -> issuer = reader.readString()
                field == 4 && wireType == 0 -> algorithm = Algorithm.from(reader.readVarint().toInt())
                field == 5 && wireType == 0 -> digits = DigitCount.from(reader.readVarint().toInt())
                field == 6 && wireType == 0 -> type = OtpType.from(reader.readVarint().toInt())
                field == 7 && wireType == 0 -> counter = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }
        return OtpParameters(secret, name, issuer, algorithm, digits, type, counter)
    }

    /** Standard base64 first, URL-safe as a fallback — both turn up in the wild. */
    private fun decodeBase64(value: String): ByteArray {
        val trimmed = value.trim()
        return runCatching { Base64.getDecoder().decode(padded(trimmed)) }
            .recoverCatching { Base64.getUrlDecoder().decode(padded(trimmed)) }
            .getOrElse { throw IllegalArgumentException("data is not base64: ${it.message}") }
    }

    private fun padded(value: String): String {
        val missing = (4 - value.length % 4) % 4
        return if (missing == 0) value else value + "=".repeat(missing)
    }

    /**
     * Pulls one query parameter out of the URI without touching the others.
     *
     * Percent-decoding is done by hand and *only* for `%XX` sequences: the payload is base64,
     * where `+` is a valid character, so a decoder that also maps `+` to a space would corrupt
     * most real payloads (`URLDecoder` does exactly that).
     */
    private fun queryParameter(uri: String, name: String): String? {
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=', "")
            if (key == name) {
                return percentDecode(pair.substringAfter('=', ""))
            }
        }
        return null
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    index += 3
                    continue
                }
            }
            out.append(char)
            index++
        }
        return out.toString()
    }
}
