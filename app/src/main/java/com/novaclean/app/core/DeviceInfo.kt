package com.novaclean.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.novaclean.app.BuildConfig
import java.util.Locale

/** General device information attached to every crash and report. Contains no personal data. */
object DeviceInfo {

    fun compact(): String =
        "${BuildConfig.VERSION_NAME} b${BuildConfig.BUILD_NO} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private inline fun safe(block: () -> String): String = try { block() } catch (e: Throwable) { "n/a" }

    private fun mb(b: Long) = "${b / 1_048_576} MB"

    fun text(ctx: Context): String = buildString {
        appendLine("app: Nova Clean ${BuildConfig.BUILD_LABEL} (${ctx.packageName})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, brand ${Build.BRAND})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${safe { Build.VERSION.SECURITY_PATCH }}")
        appendLine("rom build: ${Build.DISPLAY}")
        appendLine("cpu abis: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("form factor: ${safe { if (ctx.packageManager.hasSystemFeature("android.software.leanback")) "TV box" else "phone/tablet" }}")
        appendLine("locale: ${Locale.getDefault()}")
        appendLine("uptime: ${safe { "${SystemClock.elapsedRealtime() / 60000} min" }}")
        appendLine("ram: ${safe {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            "free ${mb(mi.availMem)} of ${mb(mi.totalMem)}${if (mi.lowMemory) " (LOW MEMORY)" else ""}"
        }}")
        appendLine("storage: ${safe {
            val s = StatFs(Environment.getDataDirectory().path)
            "free ${mb(s.availableBytes)} of ${mb(s.totalBytes)}"
        }}")
        appendLine("permissions: ${safe {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val parts = mutableListOf(
                "battery-unrestricted=${pm.isIgnoringBatteryOptimizations(ctx.packageName)}",
                "overlay=${Settings.canDrawOverlays(ctx)}",
                "usage-access=${AppScanner(ctx).hasUsageAccess()}",
            )
            if (Build.VERSION.SDK_INT >= 26) parts += "install-apps=${ctx.packageManager.canRequestPackageInstalls()}"
            parts.joinToString(", ")
        }}")
        appendLine("engine: ${CrashLog.engine}")
    }
}
