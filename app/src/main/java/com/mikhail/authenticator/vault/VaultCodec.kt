package com.mikhail.authenticator.vault

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Turns a list of accounts into the exported file text and back.
 *
 * Pure JVM code (`java.util.Base64`, kotlinx-serialization, javax.crypto), so the whole
 * export/import path is unit-tested without an emulator: round trip, wrong password,
 * and tampered payload are covered in `VaultCodecTest`.
 */
object VaultCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(
        entries: List<VaultEntry>,
        password: CharArray,
        iterations: Int = VaultCrypto.DEFAULT_ITERATIONS,
    ): String {
        val payload = json.encodeToString(VaultPayload.serializer(), VaultPayload(entries))
        val blob = VaultCrypto.encrypt(payload.toByteArray(Charsets.UTF_8), password, iterations)
        val file = VaultFile(iterations = iterations, data = Base64.getEncoder().encodeToString(blob))
        return json.encodeToString(VaultFile.serializer(), file)
    }

    /**
     * @throws IllegalArgumentException when the text is not a vault file of this format.
     * @throws javax.crypto.AEADBadTagException when the password is wrong or the file was
     *   modified — callers surface that as "неверный пароль или файл повреждён".
     */
    fun decode(fileText: String, password: CharArray): List<VaultEntry> {
        val file = runCatching { json.decodeFromString(VaultFile.serializer(), fileText) }
            .getOrElse { throw IllegalArgumentException("это не файл экспорта 2FA", it) }
        require(file.format == VaultFile.FORMAT) {
            "неизвестный формат файла: ${file.format}"
        }
        require(file.cipher == VaultFile.CIPHER && file.kdf == VaultFile.KDF) {
            "неподдерживаемый способ шифрования: ${file.kdf}/${file.cipher}"
        }
        val blob = runCatching { Base64.getDecoder().decode(file.data) }
            .getOrElse { throw IllegalArgumentException("повреждённое поле data", it) }
        val plaintext = VaultCrypto.decrypt(blob, password, file.iterations)
        return json.decodeFromString(VaultPayload.serializer(), String(plaintext, Charsets.UTF_8)).entries
    }
}
