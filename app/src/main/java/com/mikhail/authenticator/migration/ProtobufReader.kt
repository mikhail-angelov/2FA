package com.mikhail.authenticator.migration

/**
 * Minimal protobuf reader: enough for the Google Authenticator migration message and nothing
 * more.
 *
 * Deliberately not `protobuf-javalite` + the `protoc` Gradle plugin. That pair would add a
 * runtime dependency, a build plugin and downloaded binaries to decode one frozen message with
 * seven fields. The wire format is stable (it is the migration format of a shipping app), the
 * reader below is auditable in one sitting, and it keeps the dependency count where the project
 * wants it. The schema itself is kept as `app/src/main/proto/google_auth_migration.proto`
 * so the format of record is still in the repository.
 *
 * Wire types used by this message: 0 (varint) and 2 (length-delimited).
 */
internal class ProtobufReader(private val bytes: ByteArray) {

    private var position = 0

    fun hasMore(): Boolean = position < bytes.size

    /** Returns field number to wire type for the next field. */
    fun readTag(): Pair<Int, Int> {
        val key = readVarint()
        return (key ushr 3).toInt() to (key and 0x07L).toInt()
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(position < bytes.size) { "truncated varint" }
            val byte = bytes[position++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint longer than 64 bits" }
        }
    }

    /** Length-delimited payload: returns a copy, never a view onto the input. */
    fun readBytes(): ByteArray {
        val length = readVarint()
        require(length >= 0 && length <= Int.MAX_VALUE) { "negative field length" }
        val end = position + length.toInt()
        require(end <= bytes.size) { "field goes past the end of the message" }
        return bytes.copyOfRange(position, end).also { position = end }
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    /** Unknown fields must be skippable, otherwise a newer Google build breaks the import. */
    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> skipFixed(8)
            2 -> readBytes()
            5 -> skipFixed(4)
            else -> throw IllegalArgumentException("unknown field type: $wireType")
        }
    }

    private fun skipFixed(count: Int) {
        require(position + count <= bytes.size) { "field goes past the end of the message" }
        position += count
    }
}
