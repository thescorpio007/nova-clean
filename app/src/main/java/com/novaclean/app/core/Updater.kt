package com.novaclean.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.novaclean.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Updates from GitHub Releases: find newer build, download silently, verify SHA-256, then ask to install. */
object Updater {
    private const val REPO = "thescorpio007/nova-clean"
    private val TAG = Regex("^v?(\\d+(?:\\.\\d+)*)-build-(\\d+)$")

    data class Release(val ver: String, val build: Int, val apkUrl: String, val shaUrl: String?)
    data class Outcome(val status: String, val ready: Boolean)

    fun label(ver: String, build: Int) = "$ver · build ${"%02d".format(build)}"

    private fun parseVer(s: String) = s.split('.').map { it.toIntOrNull() ?: 0 }

    private fun isNewer(ver: String, build: Int): Boolean {
        val a = parseVer(ver)
        val b = parseVer(BuildConfig.VERSION_NAME)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return build > BuildConfig.BUILD_NO
    }

    private fun http(url: String, json: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NovaClean")
            if (json) setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun latest(): Release? {
        val c = http("https://api.github.com/repos/$REPO/releases/latest", json = true)
        if (c.responseCode != 200) return null
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val m = TAG.matchEntire(o.optString("tag_name")) ?: return null
        val assets = o.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.getString("name")
            val u = a.getString("browser_download_url")
            if (n.endsWith(".apk")) apk = u else if (n.endsWith(".apk.sha256")) sha = u
        }
        val apkUrl = apk ?: return null
        return Release(m.groupValues[1], m.groupValues[2].toInt(), apkUrl, sha)
    }

    private fun fetchText(url: String): String? {
        val c = http(url, json = false)
        return if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
    }

    private fun download(url: String, dest: File): String {
        val c = http(url, json = false)
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        val md = MessageDigest.getInstance("SHA-256")
        dest.parentFile?.mkdirs()
        c.inputStream.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Label of a downloaded and still-newer update, or null (stale files are cleaned up). */
    fun pendingReady(ctx: Context): String? {
        val p = Prefs(ctx)
        if (p.updatePath.isEmpty()) return null
        if (!File(p.updatePath).exists() || !isNewer(p.updateVer, p.updateBuild)) {
            clearPending(ctx)
            return null
        }
        return label(p.updateVer, p.updateBuild)
    }

    private fun clearPending(ctx: Context) {
        val p = Prefs(ctx)
        runCatching { if (p.updatePath.isNotEmpty()) File(p.updatePath).delete() }
        p.updatePath = ""
        p.updateVer = ""
        p.updateBuild = 0
    }

    /** Blocking: call from a background thread. */
    fun checkAndDownload(ctx: Context): Outcome {
        val prefs = Prefs(ctx)
        prefs.lastCheck = System.currentTimeMillis()
        val r: Release? = try {
            latest()
        } catch (e: Exception) {
            return Outcome("Could not reach GitHub. Check internet or VPN.", pendingReady(ctx) != null)
        }
        if (r == null) return Outcome("No release found on GitHub.", false)

        if (!isNewer(r.ver, r.build)) {
            clearPending(ctx)
            return Outcome("Up to date (${BuildConfig.BUILD_LABEL})", false)
        }
        val lbl = label(r.ver, r.build)
        val dir = File(ctx.cacheDir, "updates")
        val dest = File(dir, "NovaClean-update.apk")
        if (dest.exists() && prefs.updateVer == r.ver && prefs.updateBuild == r.build) {
            return Outcome("Update $lbl is ready to install", true)
        }
        dir.listFiles()?.forEach { it.delete() }

        val want = r.shaUrl?.let { fetchText(it) }?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
        val got = try {
            download(r.apkUrl, dest)
        } catch (e: Exception) {
            dest.delete()
            return Outcome("Download failed: ${e.message}", false)
        }
        if (want == null) {
            dest.delete()
            return Outcome("No checksum published for $lbl: update refused.", false)
        }
        if (want != got) {
            dest.delete()
            return Outcome("Checksum mismatch: update discarded.", false)
        }
        prefs.updatePath = dest.absolutePath
        prefs.updateVer = r.ver
        prefs.updateBuild = r.build
        return Outcome("Update $lbl downloaded and verified", true)
    }

    /** Opens Android's installer. Returns an error message or null. */
    fun install(ctx: Context): String? {
        val p = Prefs(ctx)
        val f = File(p.updatePath)
        if (p.updatePath.isEmpty() || !f.exists()) return "No downloaded update. Tap Check now."
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
            }
            return "Allow \"Install unknown apps\" for Nova Clean, then tap Install again."
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }
}
