package com.mikhail.authenticator.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
     */
    suspend fun importFromImage(uri: Uri): String {
        val context = getApplication<Application>()
        val image = runCatching { withContext(Dispatchers.IO) { loadImage(context, uri) } }
            .getOrElse { return "Не удалось открыть изображение" }

        val raw = scanForMigrationUri(image)
            ?: return "На изображении не найден QR-код переноса Google Authenticator"

        val payload = runCatching { GoogleAuthMigration.parse(raw) }
            .getOrElse { return "QR-код переноса не разобрался: ${it.message}" }

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
     * Decodes the picked image here instead of calling [InputImage.fromFilePath]. The file-path
     * variant resolves a `content://` URI through MediaStore's `DATA` column, which the system
     * PhotoPicker deliberately does not populate — it fails on exactly the screenshots this
     * feature exists for. Reading the stream ourselves works for any provider the user picks from.
     *
     * A small crop also loses the code: below roughly 600 px the detector starts missing QRs, so
     * a small bitmap is scaled up instead of handed over as is.
     */
    private fun loadImage(context: Context, uri: Uri): InputImage {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("изображение не читается")
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest in 1 until 600) {
            val k = 600f / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).toInt(), (bitmap.height * k).toInt(), true)
        } else {
            bitmap
        }
        return InputImage.fromBitmap(scaled, 0)
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
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            }
        } finally {
            scanner.close()
        }
    }
}
