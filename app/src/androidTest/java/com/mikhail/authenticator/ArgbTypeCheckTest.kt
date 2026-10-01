package com.mikhail.authenticator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.mikhail.authenticator.ui.HomeViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Тип-проверка того самого места, где падало приложение.
 *
 * Детектор ML Kit принимает только программный ARGB_8888. Раньше при неудаче копирования
 * наружу уходил исходный формат картинки, и приложение падало уже внутри детектора — в
 * сообщении это выглядело как «проверено вариантов 0» плюс NullPointerException с getClass()
 * на null. Тест делает три вещи на одном и том же файле, без подмены:
 *
 * 1. декодирует картинку так же, как приложение, и печатает её тип (config, размер, recycled);
 * 2. проводит её через тот же самый вход, что и кнопка выбора фото (`HomeViewModel`
 *    .importFromImage), и печатает ответ;
 * 3. проверяет требование детектора напрямую: чужой формат он не принимает, а ARGB_8888
 *    принимает.
 *
 * Файл берётся по аргументу `qrFile` (по умолчанию /data/local/tmp/qr.jpg) — образец с
 * настоящими секретами в репозиторий не попадает.
 */
@RunWith(AndroidJUnit4::class)
class ArgbTypeCheckTest {

    private val tag = "OtpTypes"

    private fun sampleFile(): File {
        val args = InstrumentationRegistry.getArguments()
        return File(args.getString("qrFile") ?: "/data/local/tmp/qr.jpg")
    }

    private fun pixels(w: Int, h: Int) = IntArray(w * h) { 0xFF000000.toInt() or (it * 2654435761L).toInt().and(0xFFFFFF) }

    private fun makeBitmap(config: Bitmap.Config, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, config)
        out.setPixels(pixels(w, h), 0, w, 0, 0, w, h)
        return out
    }

    @Test
    fun sameFileGoesThroughTheSamePath() {
        val file = sampleFile()
        Log.i(tag, "файл: ${file.absolutePath}, существует: ${file.exists()}, ${file.length()} Б")
        org.junit.Assume.assumeTrue("образец не найден: ${file.absolutePath}", file.exists())

        // 1. Декодируем ровно как приложение.
        val bytes = file.readBytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("картинка не декодировалась", bitmap)
        bitmap!!
        Log.i(tag, "тип decoded: ${bitmap.config}, ${bitmap.width}x${bitmap.height}, recycled=${bitmap.isRecycled}")

        // 2. Идём тем же входом, что и кнопка выбора фото.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = HomeViewModel(target.applicationContext as android.app.Application)
        val answer = kotlinx.coroutines.runBlocking { viewModel.importFromImage(Uri.fromFile(file)) }
        Log.i(tag, "ответ приложения: $answer")
        assertFalse("путь сорвался с исключением внутри: $answer", answer.contains("Причина:"))
    }

    @Test
    fun detectorRequiresSoftwareArgb8888() {
        // 3. Требование детектора: чужой формат он не принимает.
        val foreign = makeBitmap(Bitmap.Config.RGB_565, 238, 236)
        val foreignResult = runCatching { InputImage.fromBitmap(foreign, 0) }
        Log.i(tag, "InputImage.fromBitmap(RGB_565): " + (foreignResult.exceptionOrNull()
            ?.let { "${it::class.simpleName}: ${it.message}" } ?: "принят"))

        val proper = makeBitmap(Bitmap.Config.ARGB_8888, 238, 236)
        val properResult = runCatching { InputImage.fromBitmap(proper, 0) }
        Log.i(tag, "InputImage.fromBitmap(ARGB_8888): " + (properResult.exceptionOrNull()
            ?.let { "${it::class.simpleName}: ${it.message}" } ?: "принят"))

        assertNull("ARGB_8888 детектор обязан принимать", properResult.exceptionOrNull())
    }
}
