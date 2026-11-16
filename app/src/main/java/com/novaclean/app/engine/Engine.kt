package com.novaclean.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class EngineStatus(val ready: Boolean, val hint: String)

data class ExecResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
}

interface Engine {
    val id: String
    val title: String
    val canTrimCaches: Boolean
    suspend fun status(): EngineStatus
    suspend fun forceStop(pkg: String): Boolean
    suspend fun trimCaches(): Boolean = false
    /** Packages that currently have a live process, or null if this engine cannot tell. */
    suspend fun runningPackages(): Set<String>? = null
    suspend fun foregroundServicePackages(): Set<String> = emptySet()
}

private val PKG_RE = Regex("[A-Za-z0-9._]+")
fun isSafePkg(p: String) = PKG_RE.matches(p)

internal suspend fun collect(p: Process): ExecResult = coroutineScope {
    val o = async(Dispatchers.IO) { p.inputStream.bufferedReader().readText() }
    val e = async(Dispatchers.IO) { p.errorStream.bufferedReader().readText() }
    val code = withContext(Dispatchers.IO) { p.waitFor() }
    ExecResult(code, o.await(), e.await())
}

/** Shared logic for the two engines that run real shell commands (root / Shizuku). */
abstract class ShellEngine : Engine {
    override val canTrimCaches = true

    protected abstract suspend fun exec(cmd: String): ExecResult

    override suspend fun forceStop(pkg: String): Boolean =
        isSafePkg(pkg) && exec("am force-stop $pkg").ok

    // Frees cache of ALL apps in one go (Android 6+).
    override suspend fun trimCaches(): Boolean = exec("pm trim-caches 999G").ok

    override suspend fun runningPackages(): Set<String>? {
        // Android 8+: "ps -A -o NAME"; Android 7 falls back to plain "ps" (name = last column)
        val r = exec("ps -A -o NAME 2>/dev/null || ps")
        if (r.out.isBlank()) return null
        return r.out.lineSequence()
            .map { it.trim().substringAfterLast(' ').substringBefore(':') }
            .filter { it.contains('.') }
            .toSet()
    }

    override suspend fun foregroundServicePackages(): Set<String> {
        val r = exec("dumpsys activity services")
        val re = Regex("ServiceRecord\\{[0-9a-f]+ u\\d+ ([A-Za-z0-9._]+)/")
        var cur: String? = null
        val out = HashSet<String>()
        for (line in r.out.lineSequence()) {
            re.find(line)?.let { cur = it.groupValues[1] }
            if (line.contains("isForeground=true")) cur?.let { out += it }
        }
        return out
    }
}
