package com.novaclean.app.core

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash and error recorder.
 *  - FATAL crashes are caught by a global handler and written before the app closes.
 *  - NON-FATAL errors are caught by guard() around every action, so one failure never takes the app down.
 * Everything goes to  Download/NovaClean/Logs/crash-log.txt  (and a private copy used by the report).
 */
object CrashLog {
    const val SUB = "Logs"
    const val FILE = "crash-log.txt"

    @Volatile var action: String = "idle"
    @Volatile var engine: String = "unknown"

    fun internalFile(ctx: Context): File =
        File(ctx.filesDir, "logs/$FILE").also { it.parentFile?.mkdirs() }

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                app.getSharedPreferences("nova_clean", Context.MODE_PRIVATE)
                    .edit().putBoolean("crashed", true).commit()
                record(app, "FATAL CRASH", t.name, e, full = true)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** True once after a fatal crash, so the app can tell the user the crash was saved. */
    fun consumeCrashFlag(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("nova_clean", Context.MODE_PRIVATE)
        val v = sp.getBoolean("crashed", false)
        if (v) sp.edit().putBoolean("crashed", false).apply()
        return v
    }

    fun logError(ctx: Context, where: String, e: Throwable) {
        try {
            record(ctx, "NON-FATAL ERROR ($where)", Thread.currentThread().name, e, full = false)
        } catch (_: Throwable) {
        }
    }

    /** Runs block; any failure is logged and null is returned instead of crashing. */
    suspend fun <T> guard(ctx: Context, where: String, block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        logError(ctx, where, t)
        null
    }

    private fun record(ctx: Context, kind: String, thread: String, e: Throwable, full: Boolean) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val text = buildString {
            appendLine("=== NOVA CLEAN $kind ===")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("thread: $thread")
            appendLine("last action: $action")
            if (full) append(DeviceInfo.text(ctx)) else appendLine("info: ${DeviceInfo.compact()}")
            appendLine("exception: ${e.javaClass.name}: ${e.message}")
            appendLine(sw.toString().take(6000))
            appendLine()
        }
        val f = internalFile(ctx)
        var all = (if (f.exists()) f.readText() else "") + text
        if (all.length > 300_000) all = all.takeLast(200_000)
        f.writeText(all)
        NovaFiles.write(ctx, SUB, FILE, all.toByteArray(), "text/plain")
    }

    fun readAll(ctx: Context): String =
        internalFile(ctx).takeIf { it.exists() }?.readText().orEmpty()

    fun clear(ctx: Context) {
        internalFile(ctx).delete()
        NovaFiles.delete(ctx, SUB, FILE)
    }
}
