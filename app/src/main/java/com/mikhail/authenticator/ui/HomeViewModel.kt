package com.mikhail.authenticator.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Предел стороны при большом увеличении: выше этого размера память и время уже не оправданы. */
const val MAX_SCALE_PIXELS = 4200
const val MAX_SCALE_FACTOR = 16

/**
 * До какого множителя увеличивать мелкую картинку: чтобы сторона не вышла за [MAX_SCALE_PIXELS].
 * Для снимка 238×236 это ×16 (3808 px) — проверенный рабочий размер; для крупных картинок
 * множитель сам становится единицей, и лишней работы не делается. Правило общее для приложения
 * и тестов, чтобы тест не проверял не тот путь, которым идёт телефон.
 */
fun bigUpscaleFactor(longest: Int): Int =
    (MAX_SCALE_PIXELS / longest.coerceAtLeast(1)).coerceIn(1, MAX_SCALE_FACTOR)

private const val TAG = "OtpImport"

/**
 * Предел для увеличения по серому: больший размер картинки после масштабирования.
 *
 * Множитель ×4 применялся безусловно, и на крупной картинке это переполняло память: снимок
 * 1080×2400 давал массив 4320×9600 — 41 Мпикс, около 166 МБ только под инты, а следом ещё
 * столько же под байты для декодера. Вариант не готовился, последовательность обрывалась, и
 * импорт заканчивался «QR не найден», хотя первый вариант код не нашёл лишь из-за размера.
 * Крупной картинке увеличение не нужно: она и так читается. Ограничиваем больший размер.
 */
private const val MAX_UPSCALE_SIDE = 2400

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AccountRepository(AppDatabase.get(application).otpDao())

    private val allAccounts: StateFlow<List<StoredAccount>> =
        repository.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Все аккаунты, как есть.
     *
     * Поиска здесь больше нет: в приложении на десяток ключей список виден целиком, а поле
     * поиска только съедало верхнюю часть экрана и сбивало с толку («Ничего не найдено» вместо
     * списка, когда строка оставалась непустой).
     */
    val accounts: StateFlow<List<StoredAccount>> = allAccounts

    fun add(account: OtpAccount) {
        viewModelScope.launch { repository.add(account) }
    }

    fun delete(entry: StoredAccount) {
        viewModelScope.launch { repository.delete(entry) }
    }

    /** Поднять аккаунт в начало списка — это и есть его приоритет. */
    fun moveToTop(entry: StoredAccount) {
        viewModelScope.launch { repository.moveToTop(entry) }
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
        Log.w(TAG, "import failed on $uri", t)
        "Import failed: ${t::class.simpleName}: ${t.message}"
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
            Log.w(TAG, "could not read the stream $uri", e)
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "provider returned no data: $uri")
            return "Could not open the image: the provider returned no data"
        }

        val bitmap = try {
            withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        } catch (e: Exception) {
            Log.w(TAG, "decoder crashed on ${bytes.size} B", e)
            null
        }
        if (bitmap == null) {
            val head = bytes.take(8).joinToString(" ") { "%02x".format(it) }
            Log.w(TAG, "not recognised as a picture: ${bytes.size} B, head $head")
            return "Could not open the image: the file is not recognised as a picture (${bytes.size} B)"
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
                    val prepared = try {
                        iterator.next()
                    } catch (t: Throwable) {
                        failure = t
                        Log.w(TAG, "variant ${tried + 1} was not prepared", t)
                        break
                    }
                    tried++
                    var foundBy = "ML Kit"
                    try {
                        // Крупные варианты готовятся только для ZXing: объект для ML Kit там null.
                        raw = prepared.image?.let { scanForMigrationUri(it) }
                    } catch (t: Throwable) {
                        failure = t
                        Log.w(TAG, "variant $tried failed in ML Kit", t)
                        raw = null
                    }
                    // Второй декодер на тех же пикселях: на мелком коде ML Kit не находит
                    // ничего, а ZXing находит — поэтому пробуем оба, не готовя вариант заново.
                    if (raw == null) {
                        foundBy = "ZXing"
                        raw = try {
                            MigrationQrDecoder.decode(prepared.gray, prepared.width, prepared.height)
                        } catch (t: Throwable) {
                            Log.w(TAG, "variant $tried failed in ZXing", t)
                            null
                        }
                    }
                    Log.i(TAG, "variant $tried: ${if (raw == null) "QR not found" else "transfer QR found ($foundBy)"}")
                    if (raw != null) break
                }
            }
        } catch (t: Throwable) {
            failure = t
            Log.w(TAG, "preparing variants failed", t)
        }
        Log.i(TAG, "image ${bitmap.width}x${bitmap.height} (${bitmap.config}), $tried variants tried")
        if (raw == null) {
            val reason = failure
            // Место сбоя обязательно: без него сообщение называет только класс исключения, и
            // искать причину приходится заново. Первой строки стека для этого достаточно.
            val where = reason?.stackTrace?.firstOrNull()?.let { " at $it" } ?: ""
            val why = reason?.let { " Reason: ${it::class.simpleName}: ${it.message}$where" } ?: ""
            return "Transfer QR not found: image ${bitmap.width}x${bitmap.height}, " +
                "format ${bitmap.config}, $tried variants tried.$why " +
                "Use the original screenshot, not one re-sent through a messenger"
        }

        val payload = runCatching { GoogleAuthMigration.parse(raw) }
            .getOrElse { return "Transfer QR could not be parsed: ${it::class.simpleName}: ${it.message}" }

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
            return "Part ${part.batchIndex + 1} of ${part.batchSize} imported. Load the next screenshot"
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
        val parts = if (batchSize > 1) " (parts: $batchSize, last ${batchIndex + 1})" else ""
        return buildString {
            append("Accounts imported: ${outcome.added}$parts")
            if (outcome.duplicates > 0) append(". Already there: ${outcome.duplicates}")
            if (pendingSkippedHotp > 0) append(". Skipped counter-based (HOTP): $pendingSkippedHotp")
            if (pendingSkippedUnsupported > 0) append(". Skipped unsupported: $pendingSkippedUnsupported")
        }
    }

    /**
     * Приводит картинку к программному ARGB_8888 — единственному формату, который принимает
     * детектор.
     *
     * Раньше здесь стояло «bitmap.copy(...) ?: bitmap»: при неудаче копирования наружу уходил
     * исходный bitmap неподходящего формата, и приложение падало уже внутри детектора, в самом
     * первом варианте (в сообщении это видно как «проверено вариантов 0», а причина —
     * NullPointerException с getClass() на null). Теперь копия не удалась — рисуем пиксели в
     * новый bitmap через Canvas: этот путь не возвращает null.
     */
    private fun toArgb8888(source: Bitmap): Bitmap {
        if (!source.isRecycled && source.config == Bitmap.Config.ARGB_8888) return source
        val width = source.width.coerceAtLeast(1)
        val height = source.height.coerceAtLeast(1)
        val copied = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (copied != null) return copied
        Log.w(TAG, "copy ${source.config} → ARGB_8888 failed, drawing the pixels again")
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, 0f, 0f, null)
        return out
    }

    /** Один подготовленный вариант: картинка для ML Kit и те же пиксели для ZXing. */
    private class Prepared(
        val image: InputImage?,
        val gray: ByteArray,
        val width: Int,
        val height: Int,
    )

    /** Серые пиксели → картинка ARGB, которую принимает детектор. */
    private fun toImage(gray: IntArray, width: Int, height: Int): InputImage {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(gray, 0, width, 0, 0, width, height)
        return InputImage.fromBitmap(out, 0)
    }

    /**
     * Готовит варианты одной картинки для детекторов — последовательно, по одному в памяти.
     *
     * Порядок — от дешёвого к обработанному, а внутри обработки первым идёт рецепт, который на
     * живом образце сработал: **серое, увеличенное ×4 бикубикой**. Именно его не хватало:
     * по цветной картинке ни один декодер код не находил, по этим пикселям находит сразу и
     * ZXing, и (на стенде) ML Kit. Затем тот же вариант с белым полем и с бинаризацией по Оцу,
     * и лишь потом прежний путь с билинейным увеличением через `Bitmap.createScaledBitmap` —
     * он выручает, когда картинка крупнее и сглаживания достаточно.
     *
     * Формат пикселей обязан быть ARGB_8888 — ML Kit отвергает bitmap в другом формате,
     * а декодер JPEG отдаёт RGB_565. Последовательность, а не список, потому что каждый
     * увеличенный вариант — это мегабайты пикселей, и держать их все разом незачем.
     */
    private fun variantSequence(bitmap: Bitmap): Sequence<Prepared> = sequence {
        val upright = toArgb8888(bitmap)
        val w0 = upright.width.coerceAtLeast(1)
        val h0 = upright.height.coerceAtLeast(1)

        val pixels0 = IntArray(w0 * h0)
        upright.getPixels(pixels0, 0, w0, 0, 0, w0, h0)
        val gray0 = ImagePrep.luminance(pixels0)

        // 1) как есть — самый дешёвый вариант, для крупных и контрастных картинок
        yield(Prepared(InputImage.fromBitmap(upright, 0), MigrationQrDecoder.toGrayBytes(gray0), w0, h0))

        // 2) серое увеличение — рецепт, найденный на живом образце.
        //    Множитель ограничен сверху и по размеру картинки: ×4 над снимком 1080×2400 —
        //    это 166 МБ под инты, и подготовка варианта падала по памяти. Ограничение
        //    MAX_UPSCALE_SIDE оставляет запас для мелкого кода и снимает переполнение
        //    на крупном, которому увеличение не нужно.
        val factor = (MAX_UPSCALE_SIDE / maxOf(w0, h0)).coerceIn(1, 4)
        val gw = w0 * factor
        val gh = h0 * factor
        val big = if (factor > 1) ImagePrep.upscaleBicubic(gray0, w0, h0, factor) else gray0
        yield(Prepared(toImage(big, gw, gh), MigrationQrDecoder.toGrayBytes(big), gw, gh))

        // 3) то же с белым полем: детектору нужна граница, чтобы найти углы кода
        val pad = maxOf(8, minOf(gw, gh) / 12)
        val padded = ImagePrep.addWhiteBorder(big, gw, gh, pad)
        val pw = gw + pad * 2
        val ph = gh + pad * 2
        yield(Prepared(toImage(padded, pw, ph), MigrationQrDecoder.toGrayBytes(padded), pw, ph))

        // 4) то же с полем и бинаризацией по Оцу
        val thresholded = ImagePrep.binarize(padded, ImagePrep.otsuThreshold(padded))
        yield(Prepared(toImage(thresholded, pw, ph), MigrationQrDecoder.toGrayBytes(thresholded), pw, ph))

        // 5) прежний путь: билинейное увеличение целевыми размерами (кроме уже сделанного ×4)
        for (scale in ImagePrep.upscaleTargets(maxOf(w0, h0))) {
            if (scale <= 1f || scale.toInt() == factor) continue
            val base = Bitmap.createScaledBitmap(
                upright,
                (w0 * scale).toInt().coerceAtLeast(1),
                (h0 * scale).toInt().coerceAtLeast(1),
                true,
            )
            val bw = base.width.coerceAtLeast(1)
            val bh = base.height.coerceAtLeast(1)
            val pixels = IntArray(bw * bh)
            base.getPixels(pixels, 0, bw, 0, 0, bw, bh)
            val gray = ImagePrep.luminance(pixels)
            yield(Prepared(InputImage.fromBitmap(base, 0), MigrationQrDecoder.toGrayBytes(gray), bw, bh))

            val p = maxOf(8, minOf(bw, bh) / 12)
            val pd = ImagePrep.addWhiteBorder(gray, bw, bh, p)
            val w = bw + p * 2
            val h = bh + p * 2
            yield(Prepared(toImage(pd, w, h), MigrationQrDecoder.toGrayBytes(pd), w, h))

            val b = ImagePrep.binarize(pd, ImagePrep.otsuThreshold(pd))
            yield(Prepared(toImage(b, w, h), MigrationQrDecoder.toGrayBytes(b), w, h))
        }

        // 6) Большое увеличение бикубикой — для мелких пережатых картинок.
        //
        // Проверено на живом образце 238×236: код читается только при ×16 (3808 px), а ×4, ×6,
        // ×8 и ×12 не читаются, причём «×4 бикубика + дешёвое увеличение» при том же итоговом
        // размере тоже не работает. Причина — бинаризатор ZXing смотрит блоками 8×8 пикселей:
        // при мелких модулях блок ложится на границу чёрного и белого, и порог получается
        // смазанным. Здесь модуль выходит около 30 пикселей, и блоки целиком попадают внутрь
        // модуля. Картинку такого размера в память для ML Kit не тянем (57 МБ на ARGB):
        // хватает байтов яркости (14 МБ), их и отдаём декодеру.
        val bigFactor = bigUpscaleFactor(maxOf(w0, h0))
        if (bigFactor > factor) {
            val huge = ImagePrep.upscaleBicubicBytes(gray0, w0, h0, bigFactor)
            yield(Prepared(null, huge, w0 * bigFactor, h0 * bigFactor))
        }
    }

    /**
     * Runs the ML Kit scan over a still image and returns the migration URI, if there is one.
     */
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
                        Log.w(TAG, "detector refused", error)
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } finally {
            scanner.close()
        }
    }
}
