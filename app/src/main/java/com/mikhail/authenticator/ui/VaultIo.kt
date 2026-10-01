package com.mikhail.authenticator.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Reading and writing the export file through the Storage Access Framework (spec §3.В):
 * the user picks any folder — internal storage, SD card, USB stick — and the app never asks
 * for a broad storage permission.
 */
object VaultIo {

    private const val MIME_JSON = "application/json"

    const val DEFAULT_FILE_NAME = "2fa-vault.json"

    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("не удалось открыть файл")

    fun write(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("не удалось записать файл")
    }

    /** File name offered in the "create document" dialog, e.g. `2fa-vault-2026-10-01.json`. */
    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return "2fa-vault-$stamp.json"
    }

    /**
     * Human-readable name of the picked file. Read straight from the content resolver rather
     * than through androidx.documentfile — that would be one more dependency for a label.
     */
    fun displayName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: DEFAULT_FILE_NAME
    }

    fun mimeType(): String = MIME_JSON
}
