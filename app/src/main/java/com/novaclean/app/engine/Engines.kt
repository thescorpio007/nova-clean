package com.novaclean.app.engine

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** 1) Root (only if the device already has su). */
class RootEngine : ShellEngine() {
    override val id = "root"
    override val title = "Root (su)"
    private var cached: Boolean? = null

    override suspend fun status(): EngineStatus {
        val ok = cached ?: probe().also { cached = it }
        return EngineStatus(ok, if (ok) "Ready" else "No root on this device (normal)")
    }

    private suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText()
            p.waitFor()
            t.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun exec(cmd: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            collect(ProcessBuilder("su", "-c", cmd).start())
        } catch (e: Exception) {
            ExecResult(-1, "", e.message ?: "su failed")
        }
    }
}

/** 2) Shizuku: shell-level power without root. Android 11+ (Wireless debugging) or 7-10 (ADB). */
class ShizukuEngine : ShellEngine() {
    override val id = "shizuku"
    override val title = "Shizuku / ADB shell"

    override suspend fun status(): EngineStatus = try {
        if (!Shizuku.pingBinder()) {
            EngineStatus(false, "Shizuku is not running. Install Shizuku and start it (see README).")
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            EngineStatus(true, "Ready")
        } else {
            EngineStatus(false, "Shizuku is running. Tap 'Grant Shizuku'.")
        }
    } catch (e: Throwable) {
        EngineStatus(false, "Shizuku not available")
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(1001)
        } catch (_: Throwable) {
        }
    }

    // Shizuku.newProcess is private in API 13; reflection is the documented workaround.
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    override suspend fun exec(cmd: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            collect(newProcess(arrayOf("sh", "-c", cmd)))
        } catch (e: Throwable) {
            ExecResult(-1, "", e.message ?: "shizuku failed")
        }
    }
}

/** 3) Accessibility: taps Android's own "Force stop" button. Works everywhere, slower. */
class AccessibilityEngine : Engine {
    override val id = "access"
    override val title = "Accessibility automation"
    override val canTrimCaches = false

    override suspend fun status(): EngineStatus {
        val on = NovaAccessibilityService.instance != null
        return EngineStatus(
            on,
            if (on) "Ready (slower: taps Android's Force stop button)"
            else "Enable Nova Clean in Accessibility settings"
        )
    }

    override suspend fun forceStop(pkg: String): Boolean =
        NovaAccessibilityService.instance?.forceStop(pkg) ?: false
}

/** 4) Basic: killBackgroundProcesses(). No setup. Only ends cached/background processes. */
class BasicEngine(private val ctx: Context) : Engine {
    override val id = "basic"
    override val title = "Basic (no setup)"
    override val canTrimCaches = false

    override suspend fun status() = EngineStatus(true, "Always available")

    override suspend fun forceStop(pkg: String): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pkg)
        return true
    }
}

class EngineManager(ctx: Context) {
    val root = RootEngine()
    val shizuku = ShizukuEngine()
    val access = AccessibilityEngine()
    val basic = BasicEngine(ctx.applicationContext)
    val all: List<Engine> = listOf(root, shizuku, access, basic)

    suspend fun statuses(): List<Pair<Engine, EngineStatus>> = all.map { it to it.status() }

    /** Strongest engine that is ready right now. */
    suspend fun best(): Engine = all.firstOrNull { it.status().ready } ?: basic

    /** Strongest shell engine (needed for process list and cache trimming). */
    suspend fun bestShell(): Engine? = listOf(root, shizuku).firstOrNull { it.status().ready }
}
