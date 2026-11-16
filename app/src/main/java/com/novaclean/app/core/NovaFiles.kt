package com.novaclean.app.core

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All Nova Clean data files live in  Download/NovaClean/  (Backups/ and history.txt).
 * Android 10+ uses MediaStore (no permission needed); Android 7-9 writes the folder directly.
 */
object NovaFiles {
    const val FOLDER = "Download/NovaClean"
    const val BACKUPS = "Backups"
    const val HISTORY = "history.txt"

    fun pathOf(sub: String = "") = if (sub.isEmpty()) FOLDER else "$FOLDER/$sub"

    fun write(ctx: Context, sub: String, name: String, bytes: ByteArray, mime: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) writeQ(ctx, sub, name, bytes, mime) else writeLegacy(sub, name, bytes)
        true
    } catch (e: Exception) {
        false
    }

    fun read(ctx: Context, sub: String, name: String): ByteArray? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            findQ(ctx, sub, name)?.let { ctx.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }
        } else {
            File(legacyDir(sub), name).takeIf { it.exists() }?.readBytes()
        }
    } catch (e: Exception) {
        null
    }

    fun appendHistory(ctx: Context, line: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val old = read(ctx, "", HISTORY)?.toString(Charsets.UTF_8).orEmpty()
        val lines = (old.lines().filter { it.isNotBlank() } + "$stamp | $line").takeLast(500)
        write(ctx, "", HISTORY, (lines.joinToString("\n") + "\n").toByteArray(), "text/plain")
    }

    // ---- Android 10+ ----
    private fun rel(sub: String) = if (sub.isEmpty()) FOLDER else "$FOLDER/$sub"

    @RequiresApi(29)
    private fun findQ(ctx: Context, sub: String, name: String): android.net.Uri? {
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return ctx.contentResolver.query(
            coll, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, rel(sub) + "/"), null
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(coll, it.getLong(0)) else null }
    }

    @RequiresApi(29)
    private fun writeQ(ctx: Context, sub: String, name: String, bytes: ByteArray, mime: String) {
        val res = ctx.contentResolver
        val uri = findQ(ctx, sub, name) ?: res.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel(sub))
            }
        ) ?: error("insert failed")
        res.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    // ---- Android 7-9 ----
    private fun legacyDir(sub: String): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, if (sub.isEmpty()) "NovaClean" else "NovaClean/$sub")
    }

    private fun writeLegacy(sub: String, name: String, bytes: ByteArray) {
        val dir = legacyDir(sub)
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
    }
}
