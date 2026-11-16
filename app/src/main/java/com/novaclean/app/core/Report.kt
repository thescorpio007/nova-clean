package com.novaclean.app.core

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One plain-text file with everything needed to analyse a problem. No API key, no personal data. */
object Report {

    fun build(ctx: Context, prefs: Prefs, engines: List<String>): String = buildString {
        appendLine("===== NOVA CLEAN REPORT =====")
        appendLine("generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("(Paste or attach this whole file in Claude for analysis.)")
        appendLine()
        appendLine("## 1. DEVICE AND APP")
        append(DeviceInfo.text(ctx))
        appendLine()
        appendLine("## 2. ENGINES")
        engines.forEach { appendLine(it) }
        appendLine()
        appendLine("## 3. SETTINGS (no secrets)")
        appendLine("ai key set: ${if (prefs.apiKey.isNotBlank()) "yes" else "no"}, model: ${prefs.model}")
        appendLine("one-tap: closeApps=${prefs.closeApps}, cleanCaches=${prefs.cleanCaches}, mode=${prefs.mode}")
        appendLine("recent-protect minutes: ${prefs.recentProtectMin}")
        appendLine("result toast=${prefs.toastResult}, notification=${prefs.notifyResult}, history=${prefs.keepHistory}")
        appendLine("auto update=${prefs.autoUpdate}, last update status: ${prefs.updateStatus.ifEmpty { "none" }}")
        appendLine("protected by user (${prefs.protectedPkgs.size}): ${prefs.protectedPkgs.sorted().joinToString(", ")}")
        appendLine()
        appendLine("## 4. CRASHES AND ERRORS (oldest first)")
        val crashes = CrashLog.readAll(ctx)
        appendLine(if (crashes.isBlank()) "none recorded" else crashes.takeLast(60_000))
        appendLine()
        appendLine("## 5. CLEAN HISTORY (last 100)")
        val h = NovaFiles.historyFile(ctx)
        val hist = if (h.exists()) h.readLines().takeLast(100) else emptyList()
        appendLine(if (hist.isEmpty()) "none recorded" else hist.joinToString("\n"))
    }

    /** Saves the report to Download/NovaClean/Logs and returns a shareable Uri plus the text. */
    fun write(ctx: Context, prefs: Prefs, engines: List<String>): Pair<Uri, String> {
        val text = build(ctx, prefs, engines)
        NovaFiles.write(ctx, CrashLog.SUB, "NovaClean-report.txt", text.toByteArray(), "text/plain")
        val f = File(ctx.cacheDir, "report/NovaClean-report.txt")
        f.parentFile?.mkdirs()
        f.writeText(text)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) to text
    }
}
