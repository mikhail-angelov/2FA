package com.mikhail.authenticator.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Проверка на живом образце — том самом скриншоте QR, который не читался в приложении.
 *
 * Файл берётся по переменной окружения `OTP_SAMPLE` и **в репозиторий не кладётся**: в QR
 * лежат настоящие секреты. Куда выгрузить подготовленные варианты, задаёт `OTP_SAMPLE_OUT`
 * (по умолчанию временный каталог) — их потом можно прогнать внешним декодером.
 *
 * Без `OTP_SAMPLE` тест пропускается, поэтому обычный прогон и CI остаются зелёными.
 */
class ImagePrepSampleTest {

    private class Sample(val pixels: IntArray, val width: Int, val height: Int, val name: String)

    private fun loadSample(): Sample? {
        val path = System.getenv("OTP_SAMPLE") ?: return null
        val file = File(path)
        if (!file.isFile) return null
        val image = ImageIO.read(file) ?: return null
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return Sample(pixels, image.width, image.height, file.name)
    }

    private fun dump(name: String, gray: IntArray, w: Int, h: Int) {
        val dir = File(System.getenv("OTP_SAMPLE_OUT") ?: System.getProperty("java.io.tmpdir"))
        dir.mkdirs()
        val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, w, h, gray, 0, w)
        ImageIO.write(image, "png", File(dir, name))
    }

    /** Увеличение делается здесь так же, как в приложении (бикубика через Bitmap). */
    private fun upscale(gray: IntArray, w: Int, h: Int, scale: Float): Triple<IntArray, Int, Int> {
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy = (y / scale).toInt().coerceIn(0, h - 1)
            for (x in 0 until nw) {
                val sx = (x / scale).toInt().coerceIn(0, w - 1)
                out[y * nw + x] = gray[sy * w + sx]
            }
        }
        return Triple(out, nw, nh)
    }

    @Test
    fun `подготовка живого скриншота даёт невырожденные варианты`() {
        val sample = loadSample()
        assumeTrue("нет OTP_SAMPLE — тест пропущен", sample != null)
        val src = sample!!

        println("ОБРАЗЕЦ: ${src.name}, ${src.width}x${src.height}, ${src.pixels.size} пикселей")

        val gray = ImagePrep.luminance(src.pixels)
        val histogram = IntArray(256)
        for (v in gray) histogram[v]++
        val distinct = histogram.count { it > 0 }
        println("ТОНОВ В КАРТИНКЕ: $distinct")
        assertTrue("картинка должна содержать больше одного тона, а не плашку", distinct > 2)

        val threshold = ImagePrep.otsuThreshold(gray)
        val binary = ImagePrep.binarize(gray, threshold)
        val black = binary.count { it == ImagePrep.BLACK }
        val white = binary.count { it == ImagePrep.WHITE }
        println("ПОРОГ ОЦУ: $threshold | чёрных $black, белых $white")
        assertEquals("бинаризация не должна терять пиксели", src.pixels.size, black + white)
        assertTrue("должны быть и чёрные, и белые модули", black > 10 && white > 10)

        val scale = ImagePrep.upscaleTargets(maxOf(src.width, src.height)).first()
        println("НАИМЕНЬШИЙ КОЭФФИЦИЕНТ УВЕЛИЧЕНИЯ: $scale")
        assertTrue("мелкий снимок должен увеличиваться больше чем вдвое", scale > 2f)

        // Ровно те три варианта, что уходят в детектор, — их и выгружаем для внешней проверки.
        val (big, bw, bh) = upscale(gray, src.width, src.height, scale)
        val pad = maxOf(8, minOf(bw, bh) / 12)
        val padded = ImagePrep.addWhiteBorder(big, bw, bh, pad)
        val prepared = ImagePrep.binarize(padded, ImagePrep.otsuThreshold(padded))
        val pw = bw + pad * 2
        val ph = bh + pad * 2

        dump("sample-as-big.png", big, bw, bh)
        dump("sample-padded.png", padded, pw, ph)
        dump("sample-prepared.png", prepared, pw, ph)
        println("ВЫГРУЖЕНО: sample-as-big.png (${bw}x${bh}), sample-padded.png и sample-prepared.png (${pw}x${ph})")

        // Варианты обязаны отличаться друг от друга — иначе подготовка бесполезна.
        assertTrue("поле должно менять картинку", !padded.contentEquals(big))
        assertTrue("бинаризация должна менять картинку", !prepared.contentEquals(padded))
        assertEquals("поле добавляет ровно рамку", pw * ph, padded.size)
    }

    /**
     * Главная проверка на живом образце: мелкий код **не читается как есть**, но читается после
     * большого увеличения. Именно на этом живом случае (238×236, 853 знака) найдено, что решает
     * не интерполяция, а итоговый масштаб: при ×16 модуль занимает около 30 пикселей, и блоки
     * бинаризатора 8×8 целиком попадают внутрь модуля.
     *
     * Тест идёт только при заданном `OTP_SAMPLE`: в QR живые секреты, в репозиторий он не попадает.
     */
    @Test
    fun `мелкий живой образец читается только после большого увеличения`() {
        val sample = loadSample()
        assumeTrue("нет OTP_SAMPLE — тест пропущен", sample != null)
        val src = sample!!
        val gray = ImagePrep.luminance(src.pixels)

        val raw = MigrationQrDecoder.decode(MigrationQrDecoder.toGrayBytes(gray), src.width, src.height)
        println("ЖИВОЙ ОБРАЗЕЦ ${src.width}x${src.height}: без подготовки ${if (raw == null) "не читается" else "ЧИТАЕТСЯ"}")
        assertEquals("живой образец должен быть трудным: как есть код не читается", null, raw)

        val factor = bigUpscaleFactor(maxOf(src.width, src.height))
        val big = ImagePrep.upscaleBicubicBytes(gray, src.width, src.height, factor)
        val read = MigrationQrDecoder.decode(big, src.width * factor, src.height * factor)
        val length = read?.substringAfter("data=")?.length ?: 0
        println("ЖИВОЙ ОБРАЗЕЦ: после ×$factor (${src.width * factor} px) — знаков нагрузки $length")
        assertNotNull("живой образец не распознан даже после увеличения ×$factor", read)
        assertTrue("распознан не миграционный код", read!!.startsWith("otpauth-migration://"))
    }
}
