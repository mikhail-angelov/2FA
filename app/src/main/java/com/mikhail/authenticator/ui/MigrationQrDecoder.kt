package com.mikhail.authenticator.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.mikhail.authenticator.migration.GoogleAuthMigration

/**
 * Второй (после ML Kit) декодер QR — ZXing.
 *
 * Зачем второй, если ML Kit уже в проекте: ML Kit на 17.3.0 сдаётся на мелком коде. На
 * проверенном образце (238×236, 853 знака в коде — около трёх пикселей на модуль) он не
 * находит код ни в одном из вариантов подготовки, тогда как ZXing находит сразу, если ему
 * отдать не цветную картинку, а серую, увеличенную ×4 бикубикой ([ImagePrep.upscaleBicubic]).
 * Проверяется это тестом на живом образце (см. `MigrationSampleTest`), а не на словах.
 *
 * Здесь нет ничего Android-специфичного — только ZXing и Kotlin, поэтому класс работает и в
 * обычных JVM-тестах, без эмулятора.
 */
object MigrationQrDecoder {

    /**
     * Ищет в серых пикселях QR переноса Google Authenticator.
     * @param gray яркость пикселей (0..255), записанная как байты беззнаково
     * @return строка `otpauth-migration://…` или null, если кода нет или он не тот
     */
    fun decode(gray: ByteArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0) { "нужны размеры картинки" }
        require(gray.size == width * height) { "размер массива не совпадает с $width x $height" }

        val source = PlanarYUVLuminanceSource(gray, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        return try {
            val text = MultiFormatReader().decode(bitmap, hints).text
            if (text.startsWith(GoogleAuthMigration.URI_SCHEME)) text else null
        } catch (e: NotFoundException) {
            null
        }
    }

    /** Приводит яркость (0..255) к байтам для декодера. */
    fun toGrayBytes(gray: IntArray): ByteArray = ByteArray(gray.size) { gray[it].toByte() }
}
