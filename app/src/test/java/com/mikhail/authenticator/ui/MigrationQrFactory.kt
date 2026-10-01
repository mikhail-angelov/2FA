package com.mikhail.authenticator.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

/**
 * Синтетический «трудный» образец для тестов распознавания — без секретов и без чужих картинок.
 *
 * Зачем он нужен: настоящий образец (мелкий, пережатый, с кодом на 853 знака) в репозиторий не
 * кладут — он содержит живые секреты. Поэтому тест собирает такой же сам: пишет настоящий
 * `otpauth-migration` с выдуманными аккаунтами, рисует крупный QR, уменьшает его до 238 пикселей
 * и пережимает в JPEG — то есть воспроизводит ту же трудность (около трёх пикселей на модуль).
 *
 * Здесь только ZXing-кодировщик и стандартный Java2D — ничего Android.
 */
object MigrationQrFactory {

    /** Заполнитель настоящей нагрузки: `otpauth-migration://offline?data=…` (без секретов). */
    fun migrationUri(accounts: Int = 9, batchSize: Int = 1, batchIndex: Int = 0, batchId: Int = 424242): String {
        val payload = ByteArrayOutputStream()
        for (i in 1..accounts) {
            val otp = ByteArrayOutputStream()
            bytesField(otp, 1, "JBSWY3DPEHPK3PXP".toByteArray(Charsets.US_ASCII)) // выдуманный секрет
            bytesField(otp, 2, "synthetic-$i@example.org".toByteArray(Charsets.UTF_8))
            bytesField(otp, 3, "Synthetic $i".toByteArray(Charsets.UTF_8))
            intField(otp, 4, 1) // SHA1
            intField(otp, 5, 1) // 6 цифр
            intField(otp, 6, 2) // TOTP
            bytesField(payload, 1, otp.toByteArray())
        }
        intField(payload, 2, 1) // version
        intField(payload, 3, batchSize.toLong())
        intField(payload, 4, batchIndex.toLong())
        intField(payload, 5, batchId.toLong())

        val data = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "otpauth-migration://offline?data=$data"
    }

    /** Рисует QR заданного размера (крупно, без потерь). */
    fun render(uri: String, size: Int): BufferedImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 6,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix: BitMatrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        return image
    }

    /**
     * Уменьшает картинку до [longest] пикселей по длинной стороне и пишет JPEG с указанным
     * качеством — так получается мелкий пережатый образец, на котором слабые декодеры сдаются.
     *
     * Уменьшение идёт **ступенями не больше чем вдвое**. Одним шагом билинейной интерполяции
     * при пятикратном сжатии нельзя: она берёт пробу вместо усреднения, и модули кода
     * размазываются — образец становится труднее настоящего фото экрана (проверено: из-за этого
     * тест не проходил, хотя живая картинка читалась). Ступенчатое уменьшение усредняет соседние
     * пиксели, как и оптика при съёмке экрана.
     */
    fun downscaleToJpeg(
        source: BufferedImage,
        longest: Int,
        quality: Float,
        target: File,
        blur: Boolean = false,
    ) {
        val scale = longest.toDouble() / maxOf(source.width, source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)

        var current = source
        while (current.width / 2 >= w && current.height / 2 >= h) {
            val nw = current.width / 2
            val nh = current.height / 2
            val step = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
            val sg = step.createGraphics()
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            sg.drawImage(current, 0, 0, nw, nh, null)
            sg.dispose()
            current = step
        }

        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = small.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(current, 0, 0, w, h, null)
        g.dispose()

        // Настоящая картинка — снимок экрана: модули в ней размыты оптикой и матрицей телефона.
        // Без этого сглаживания синтетический код получается резче живого, и увеличение даёт
        // «звон» на границах модулей, которого на живом снимке нет.
        val final = if (blur) {
            val k = java.awt.image.Kernel(3, 3, FloatArray(9) { 1f / 9f })
            java.awt.image.ConvolveOp(k, java.awt.image.ConvolveOp.EDGE_NO_OP, null).filter(small, null)
        } else {
            small
        }

        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        FileImageOutputStream(target).use { out ->
            writer.output = out
            writer.write(null, IIOImage(final, null, null), params)
        }
        writer.dispose()
    }

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private fun bytesField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        out.write((field shl 3) or 2)
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun intField(out: ByteArrayOutputStream, field: Int, value: Long) {
        out.write(field shl 3)
        varint(out, value)
    }
}
