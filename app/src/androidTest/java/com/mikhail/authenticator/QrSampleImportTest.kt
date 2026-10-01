package com.mikhail.authenticator

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikhail.authenticator.ui.HomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Прогоняет живой скриншот QR через **тот же код**, что и кнопка «Импорт из скриншота»:
 * чтение байтов, декодирование, подготовка вариантов, детектор ML Kit, разбор protobuf
 * и запись в базу. Это проверка конца в конец, а не только арифметики.
 *
 * Картинка в репозиторий не кладётся (в QR настоящие секреты), поэтому путь передаётся
 * аргументом инструментации:
 *
 *     adb shell am instrument -w \
 *       -e qrFile /data/local/tmp/qr.jpg \
 *       -e class com.mikhail.authenticator.QrSampleImportTest \
 *       com.mikhail.authenticator.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Без аргумента тест пропускается. Подробности разбора попадают в logcat с тегом `OtpImport`
 * и `OtpImportTest` — это и есть «посмотреть логи».
 */
@RunWith(AndroidJUnit4::class)
class QrSampleImportTest {

    @Test
    fun importsAccountsFromSampleScreenshot() {
        val path = InstrumentationRegistry.getArguments().getString("qrFile")
        assumeTrue("нет аргумента qrFile — тест пропущен", path != null)

        val file = File(path!!)
        assumeTrue("файла нет: $path", file.exists())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = HomeViewModel(context.applicationContext as Application)

        Log.i("OtpImportTest", "образец: ${file.absolutePath}, ${file.length()} Б")
        val message = runBlocking { viewModel.importFromImage(Uri.fromFile(file)) }
        Log.i("OtpImportTest", "результат: $message")
        println("РЕЗУЛЬТАТ ИМПОРТА: $message")

        assertTrue(
            "код импорта не должен доходить до ветки «не удалось открыть»: $message",
            !message.contains("Не удалось открыть изображение"),
        )
        assertTrue(
            "ожидали импорт аккаунтов из образца, получили: $message",
            message.contains("Импортировано аккаунтов"),
        )
    }
}
