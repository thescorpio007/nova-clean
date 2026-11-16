package com.novaclean.app.core

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process

class AppScanner(private val ctx: Context) {

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun lastUsedMap(): Map<String, Long> {
        if (!hasUsageAccess()) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return usm.queryAndAggregateUsageStats(now - DAY, now)
            .mapValues { it.value.lastTimeUsed }
    }

    /**
     * @param running packages with a live process (needs a shell engine) or null if unknown
     */
    fun scan(
        running: Set<String>?,
        foregroundServices: Set<String>,
        userProtected: Set<String>,
    ): List<AppEntry> {
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()
        val used = lastUsedMap()
        val recent = used.filter { it.value > 0 && now - it.value < 3 * 60_000L }.keys
        val prot = Protection(ctx, userProtected, foregroundServices, recent)

        val out = ArrayList<AppEntry>()
        for (ai in pm.getInstalledApplications(0)) {
            val pkg = ai.packageName
            if (prot.isHidden(pkg, ai)) continue

            val lu = used[pkg]
            val candidate = when {
                running != null -> pkg in running
                used.isNotEmpty() -> lu != null && lu > 0 && now - lu < DAY
                else -> (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            if (!candidate) continue

            out += AppEntry(
                pkg = pkg,
                label = pm.getApplicationLabel(ai).toString(),
                lastUsedMinAgo = if (lu != null && lu > 0) (now - lu) / 60_000L else null,
                running = running?.contains(pkg) ?: false,
                protectedReason = prot.reason(pkg),
            )
        }
        return out.sortedWith(
            compareBy<AppEntry> { it.isProtected }.thenBy { it.lastUsedMinAgo ?: Long.MAX_VALUE }
        )
    }

    private companion object {
        const val DAY = 24 * 3600_000L
    }
}
