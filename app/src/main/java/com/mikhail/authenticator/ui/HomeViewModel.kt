package com.mikhail.authenticator.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.mikhail.authenticator.data.AccountRepository
import com.mikhail.authenticator.data.AppDatabase
import com.mikhail.authenticator.data.StoredAccount
import com.mikhail.authenticator.migration.GoogleAuthMigration
import com.mikhail.authenticator.migration.MigrationImport
import com.mikhail.authenticator.otp.OtpAccount
import com.mikhail.authenticator.vault.VaultCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "OtpImport"

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AccountRepository(AppDatabase.get(application).otpDao())

    val searchQuery = MutableStateFlow("")

    private val allAccounts: StateFlow<List<StoredAccount>> =
        repository.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Accounts after the search box: matches issuer, account or the group each belongs to. */
    val accounts: StateFlow<List<StoredAccount>> =
        combine(allAccounts, searchQuery) { list, query ->
            val q = query.trim()
            if (q.isEmpty()) list
            else list.filter {
                it.issuer.contains(q, ignoreCase = true) || it.account.contains(q, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(account: OtpAccount) {
        viewModelScope.launch { repository.add(account) }
    }

    fun delete(entry: StoredAccount) {
        viewModelScope.launch { repository.delete(entry) }
    }

    /** Encrypts every account into the export file text (spec §3.В). */
    suspend fun export(password: CharArray): String =
        VaultCodec.encode(repository.exportEntries(), password)

    /** Decrypts an export file and merges it in. Returns how many accounts were added. */
    suspend fun import(fileText: String, password: CharArray): Int =
        repository.import(VaultCodec.decode(fileText, password))

    // ── Google Authenticator import from a screenshot ────────────────────────────────────────
    //
    // Google exports more than a handful of accounts as a *set* of QR codes ("1 of 3"), so a
    // single screenshot is often only part of the transfer. Parts are collected here until the
    // last one arrives, and only then written to the database — a half-imported set would be
    // worse than none, because the duplicates it creates are invisible to the user.

    private var pendingBatchId: Int? = null
    private val pendingParts = sortedMapOf<Int, List<OtpAccount>>()
    private var pendingSkippedHotp = 0
    private var pendingSkippedUnsupported = 0

    /**
     * Reads a Google Authenticator transfer QR out of a picked image and imports it.
     * @return the message to show the user.
     *
     * Вызов приходит из UI обычным `launch` на главном потоке, поэтому исключение отсюда
     * раньше роняло приложение целиком — в момент, когда пользователь только выбрал файл.
     * Теперь наружу всегда уходит сообщение с причиной, а причина остаётся в logcat.
     */
    suspend fun importFromImage(uri: Uri): String = try {
        importPickedImage(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "импорт сорвался на $uri", t)
        "Импорт не удался: ${t::class.simpleName}: ${t.message}"
    }

    private suspend fun importPickedImage(uri: Uri): String {
        val context = getApplication<Application>()

        // Изображение читаем сами и в байтах. Провайдер, отдавший картинку, нам не подчиняется:
        // он может не дать поток, отдать не картинку или файл, который декодер не осилит.
        // Раньше все эти случаи превращались в одну фразу без причины — теперь причина видна
        // и в сообщении, и в logcat (тег OtpImport).
        val bytes = try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "не удалось прочитать поток $uri", e)
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "провайдер не отдал данные: $uri")
            return "Не удалось открыть изображение: провайдер не отдал данные"
        }

        val bitmap = try {
            withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        } catch (e: Exception) {
            Log.w(TAG, "декодер упал на ${bytes.size} Б", e)
            null
        }
        if (bitmap == null) {
            val head = bytes.take(8).joinToString(" ") { "%02x".format(it) }
            Log.w(TAG, "не распознано как картинка: ${bytes.size} Б, начало $head")
            return "Не удалось открыть изображение: файл не распознан как картинка (${bytes.size} Б)"
        }

        // Подготовка вариантов — самая хрупкая часть: увеличение картинки требует памяти,
        // а детектор принимает только ARGB_8888 и бросает исключение прямо при создании
        // объекта, то есть до своих слушателей. Раньше это уходило мимо всех проверок и
        // роняло приложение в момент выбора файла. Теперь причина видна в logcat (тег OtpImport)
        // и в сообщении, а подготовка идёт вне главного потока.
        var tried = 0
        var raw: String? = null
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.Default) {
                val iterator = variantSequence(bitmap).iterator()
                while (iterator.hasNext()) {
                    val image = try {
                        iterator.next()
                    } catch (t: Throwable) {
                        failure = t
                        Log.w(TAG, "вариант ${tried + 1} не подготовился", t)
                        break
                    }
                    tried++
                    try {
                        raw = scanForMigrationUri(image)
                    } catch (t: Throwable) {
                        failure = t
                        Log.w(TAG, "вариант $tried сорвался", t)
                        continue
                    }
                    Log.i(TAG, "вариант $tried: ${if (raw == null) "QR не найден" else "найден QR переноса"}")
                    if (raw != null) break
                }
            }
        } catch (t: Throwable) {
            failure = t
            Log.w(TAG, "подготовка вариантов сорвалась", t)
        }
        Log.i(TAG, "картинка ${bitmap.width}x${bitmap.height}, проверено вариантов $tried")
        if (raw == null) {
            val reason = failure
            val why = reason?.let { " Причина: ${it::class.simpleName}: ${it.message}" } ?: ""
            return "QR-код переноса не найден: картинка ${bitmap.width}x${bitmap.height}, " +
                "проверено вариантов $tried.$why Нужен исходный скриншот без пересылки в мессенджере"
        }

        val payload = runCatching { GoogleAuthMigration.parse(raw) }
            .getOrElse { return "QR-код переноса не разобрался: ${it::class.simpleName}: ${it.message}" }

        val part = MigrationImport.toAccounts(payload)
        pendingSkippedHotp += part.skippedHotp
        pendingSkippedUnsupported += part.skippedUnsupported

        if (!part.isBatched) {
            val message = report(repository.importAccounts(part.accounts), part.batchSize, part.batchIndex)
            resetBatch()
            return message
        }

        if (pendingBatchId != part.batchId) {
            resetBatch()
            pendingBatchId = part.batchId
        }
        pendingParts[part.batchIndex] = part.accounts

        if (pendingParts.size < part.batchSize) {
            return "Импортирована часть ${part.batchIndex + 1} из ${part.batchSize}. Загрузите следующий скриншот"
        }

        val merged = pendingParts.values.flatten()
        val outcome = repository.importAccounts(merged)
        val batchSize = part.batchSize
        val batchIndex = part.batchIndex
        resetBatch()
        return report(outcome, batchSize, batchIndex)
    }

    private fun resetBatch() {
        pendingBatchId = null
        pendingParts.clear()
        pendingSkippedHotp = 0
        pendingSkippedUnsupported = 0
    }

    private fun report(outcome: AccountRepository.ImportOutcome, batchSize: Int, batchIndex: Int): String {
        val parts = if (batchSize > 1) " (частей: $batchSize, последняя ${batchIndex + 1})" else ""
        return buildString {
            append("Импортировано аккаунтов: ${outcome.added}$parts")
            if (outcome.duplicates > 0) append(". Уже были: ${outcome.duplicates}")
            if (pendingSkippedHotp > 0) append(". Пропущено счётчиковых (HOTP): $pendingSkippedHotp")
            if (pendingSkippedUnsupported > 0) append(". Пропущено неподдерживаемых: $pendingSkippedUnsupported")
        }
    }

    /**
     * Готовит варианты одной картинки для детектора — последовательно, по одному в памяти.
     *
     * Экранные снимки приходят мелкими (проверенный образец — 238×236), размытыми и
     * обрезанными в край. Поэтому предлагаем: как есть; каждый целевой размер с белым полем
     * (без него детектор не находит углы кода); то же после бинаризации по Оцу. Порядок — от
     * дешёвого к обработанному: если код читается сразу, лишняя работа не делается.
     *
     * Формат пикселей обязан быть ARGB_8888 — ML Kit отвергает bitmap в другом формате,
     * а декодер JPEG отдаёт RGB_565. Последовательность, а не список, потому что каждый
     * увеличенный вариант — это мегабайты пикселей, и держать их все разом незачем.
     */
    private fun variantSequence(bitmap: Bitmap): Sequence<InputImage> = sequence {
        val upright = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        }

        yield(InputImage.fromBitmap(upright, 0))

        val longest = maxOf(upright.width, upright.height).coerceAtLeast(1)
        for (scale in ImagePrep.upscaleTargets(longest)) {
            val base = if (scale > 1f) {
                Bitmap.createScaledBitmap(
                    upright,
                    (upright.width * scale).toInt().coerceAtLeast(1),
                    (upright.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                upright
            }

            val pixels = IntArray(base.width * base.height)
            base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)

            // Увеличенный, но необработанный вариант: на проверенном образце выигрыш дало
            // именно чистое ×4 без белого поля, поэтому пробуем и его.
            yield(InputImage.fromBitmap(base, 0))

            val gray = ImagePrep.luminance(pixels)
            val pad = maxOf(8, minOf(base.width, base.height) / 12)
            val padded = ImagePrep.addWhiteBorder(gray, base.width, base.height, pad)
            val pw = base.width + pad * 2
            val ph = base.height + pad * 2

            fun asImage(px: IntArray): InputImage {
                val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                out.setPixels(px, 0, pw, 0, 0, pw, ph)
                return InputImage.fromBitmap(out, 0)
            }

            yield(asImage(padded))
            yield(asImage(ImagePrep.binarize(padded, ImagePrep.otsuThreshold(padded))))
        }
    }

    /** Runs the ML Kit scan over a still image and returns the migration URI, if there is one. */
    private suspend fun scanForMigrationUri(image: InputImage): String? {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        return try {
            suspendCancellableCoroutine { continuation ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val value = barcodes
                            .firstOrNull { it.rawValue?.startsWith(GoogleAuthMigration.URI_SCHEME) == true }
                            ?.rawValue
                        if (continuation.isActive) continuation.resume(value)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "детектор отказал", error)
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } finally {
            scanner.close()
        }
    }
}
