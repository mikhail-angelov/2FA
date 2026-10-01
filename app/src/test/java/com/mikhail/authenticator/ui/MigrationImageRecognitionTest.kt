package com.mikhail.authenticator.ui

import com.mikhail.authenticator.migration.GoogleAuthMigration
import com.mikhail.authenticator.migration.MigrationImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Распознавание миграционного QR из мелкой пережатой картинки — на **синтетическом** образце.
 *
 * Тест строит код сам ([MigrationQrFactory]): настоящий `otpauth-migration` с выдуманными
 * аккаунтами, крупный QR, потом ступенчатое уменьшение до 238 пикселей, размытие и JPEG-пережатие
 * — так выглядит снимок экрана телефона. Секретов и чужих картинок здесь нет, поэтому тест идёт в
 * общем прогоне и на CI.
 *
 * Зачем он появился: до него проверялась только арифметика подготовки, а сам путь «картинка →
 * распознанный код» не проверялся ничем. Из-за этого импорт не работал на телефоне три версии
 * подряд — сначала падал на инициализации детектора, потом не находил код в мелком изображении.
 *
 * **Что здесь проверяется, а что нет.** Синтетический образец держит **трудность**: код в нём
 * мелкий и без подготовки не читается — это воспроизведение исходной проблемы. Само лечение
 * (после большого увеличения код распознаётся) проверяется на **живом** образце, в
 * `ImagePrepSampleTest`: синтетический код резче снимка экрана и белого поля вокруг него меньше,
 * поэтому большой множитель его не берёт. Разбор нагрузки проверяется здесь же — без декодера,
 * чтобы эта часть пути не зависела от того, прочитался ли синтетический код.
 */
class MigrationImageRecognitionTest {

    /**
     * Трудный образец: 238 пикселей по длинной стороне (как у снимка экрана), девять аккаунтов
     * (около 850 знаков нагрузки — как в живом случае), JPEG 0.7 и размытие, которое даёт оптика
     * телефона. Меньше аккаунтов — образец легчает и читается как есть; без размытия
     * синтетический код выходит резче живого.
     */
    private fun hardSample(accounts: Int = 9, longest: Int = 238, quality: Float = 0.7f): Pair<String, File> {
        val uri = MigrationQrFactory.migrationUri(accounts = accounts)
        val big = MigrationQrFactory.render(uri, 1200)
        val file = File.createTempFile("migration-sample-", ".jpg")
        file.deleteOnExit()
        MigrationQrFactory.downscaleToJpeg(big, longest, quality, file, blur = true)
        return uri to file
    }

    @Test
    fun `без подготовки код в мелком образце не читается`() {
        // Это и есть исходная проблема: картинка есть, кода в ней декодер не видит.
        // Проверка держит трудность образца — если однажды он окажется слишком лёгким
        // (например, код станет крупнее), тест об этом скажет.
        val (_, file) = hardSample()

        val image = ImageIO.read(file)
        assertNotNull("образец не прочитался", image)
        assertTrue(
            "образец должен быть мелким, получено ${image.width} x ${image.height}",
            maxOf(image.width, image.height) <= 260,
        )

        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val gray = ImagePrep.luminance(pixels)

        val readAsIs = MigrationQrDecoder.decode(
            MigrationQrDecoder.toGrayBytes(gray),
            image.width,
            image.height,
        )

        assertEquals("образец перестал быть трудным: код читается без подготовки", null, readAsIs)
    }

    @Test
    fun `большое увеличение даёт картинку ожидаемого размера`() {
        // Множитель берётся из [bigUpscaleFactor] — ровно тот, что выберет приложение, иначе тест
        // проверял бы не тот путь, которым идёт телефон.
        val (_, file) = hardSample()
        val image = ImageIO.read(file)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val gray = ImagePrep.luminance(pixels)

        val factor = bigUpscaleFactor(maxOf(image.width, image.height))
        assertTrue("для мелкого снимка множитель должен быть большим, получен $factor", factor > 8)

        val enlarged = ImagePrep.upscaleBicubicBytes(gray, image.width, image.height, factor)
        assertEquals(
            "размер увеличенной картинки не совпал",
            image.width * factor * image.height * factor,
            enlarged.size,
        )
        val tones = enlarged.toSortedSet().size
        assertTrue("после увеличения картинка должна остаться невырожденной, тонов $tones", tones > 2)
    }

    @Test
    fun `синтетическая нагрузка разбирается в те же аккаунты`() {
        // Разбор нагрузки проверяется без декодера: сам разбор — тоже часть пути, и он не должен
        // зависеть от того, прочитал ли декодер синтетический код.
        val (uri, _) = hardSample(accounts = 5)

        val payload = GoogleAuthMigration.parse(uri)
        val part = MigrationImport.toAccounts(payload)

        assertEquals("аккаунтов должно быть столько же, сколько закодировано", 5, part.accounts.size)
        assertTrue("имена аккаунтов потерялись", part.accounts.any { it.account.contains("synthetic-1@") })
    }
}
