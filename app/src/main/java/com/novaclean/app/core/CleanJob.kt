package com.novaclean.app.core

import android.content.Context
import android.os.Build
import com.novaclean.app.ai.AiClassifier
import com.novaclean.app.engine.EngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The "do everything" action used by Clean now, the widget and the launcher shortcut. */
class CleanJob(private val ctx: Context) {

    data class Result(
        val closed: Int,
        val candidates: Int,
        val cachesCleared: Boolean?,
        val engine: String,
        val notes: List<String>,
    ) {
        fun summary(): String = buildString {
            append("Closed $closed app(s) using $engine")
            when (cachesCleared) {
                true -> append(" · caches cleared")
                false -> append(" · cache clean failed")
                null -> {}
            }
            if (notes.isNotEmpty()) append("\n").append(notes.joinToString(" "))
        }
    }

    suspend fun run(onStep: (String) -> Unit = {}): Result {
        CrashLog.action = "one-tap clean"
        val prefs = Prefs(ctx)
        val engines = EngineManager(ctx)
        val scanner = AppScanner(ctx)
        val notes = mutableListOf<String>()
        val shell = engines.bestShell()
        val engine = engines.best()
        CrashLog.engine = engine.title
        var closed = 0
        var cands = 0

        if (prefs.closeApps) {
            onStep("Scanning…")
            val running = shell?.let { s -> CrashLog.guard(ctx, "process list") { s.runningPackages() } }
            val fg = shell?.let { s -> CrashLog.guard(ctx, "foreground services") { s.foregroundServicePackages() } }
                ?: emptySet()
            val apps = withContext(Dispatchers.Default) {
                scanner.scan(running, fg, prefs.protectedPkgs, prefs.recentProtectMin)
            }
            var targets = apps.filter { !it.isProtected }
            cands = targets.size

            if (prefs.mode == Prefs.MODE_AI_SAFE) {
                if (prefs.apiKey.isBlank()) {
                    notes += "No AI key set: nothing was closed."
                    targets = emptyList()
                } else {
                    onStep("Asking AI…")
                    val res = try {
                        withContext(Dispatchers.IO) {
                            AiClassifier(prefs.apiKey, prefs.model).classify(targets, deviceInfo(ctx))
                        }
                    } catch (e: Exception) {
                        CrashLog.logError(ctx, "AI classify", e)
                        null
                    }
                    if (res == null) {
                        notes += "AI unavailable: nothing was closed."
                        targets = emptyList()
                    } else {
                        targets = targets.filter { res[it.pkg]?.first == Verdict.SAFE }
                    }
                }
            }
            for (a in targets) {
                onStep("Closing ${a.label}…")
                val ok = CrashLog.guard(ctx, "force-stop ${a.pkg}") { engine.forceStop(a.pkg) } ?: false
                if (ok) closed++
                CrashLog.guard(ctx, "background-kill ${a.pkg}") { engines.basic.forceStop(a.pkg) }
            }
        }

        var caches: Boolean? = null
        if (prefs.cleanCaches) {
            if (shell == null) {
                notes += "Cache clean needs Shizuku or root."
            } else {
                onStep("Clearing caches…")
                caches = CrashLog.guard(ctx, "trim caches") { shell.trimCaches() } ?: false
            }
        }

        val result = Result(closed, cands, caches, engine.title, notes)
        if (prefs.keepHistory) {
            CrashLog.guard(ctx, "history") {
                NovaFiles.appendHistory(
                    ctx,
                    "engine=${engine.title} | closed=$closed/$cands | caches=${caches ?: "n/a"} | ${notes.joinToString(" ")}"
                )
            }
        }
        CrashLog.action = "idle"
        return result
    }

    companion object {
        fun deviceInfo(ctx: Context): String =
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                if (ctx.packageManager.hasSystemFeature("android.software.leanback")) "TV box" else "phone/tablet"
    }
}
