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
        recentMin: Int = 3,
        exploreSystem: Boolean = false,
        unlocked: Set<String> = emptySet(),
    ): List<AppEntry> {
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()
        val used = lastUsedMap()
        val recent = used.filter { it.value > 0 && now - it.value < recentMin * 60_000L }.keys
        val prot = Protection(ctx, userProtected, foregroundServices, recent)

        val out = ArrayList<AppEntry>()
        for (ai in pm.getInstalledApplications(0)) {
            val pkg = ai.packageName
            if (prot.isHidden(pkg, ai, exploreSystem)) continue

            val lu = used[pkg]
            val sys = prot.isSystem(ai)
            val candidate = when {
                running != null -> pkg in running
                used.isNotEmpty() -> lu != null && lu > 0 && now - lu < DAY
                else -> !sys
            }
            if (!candidate) continue

            val isUnlocked = pkg in unlocked && pkg != ctx.packageName
            out += AppEntry(
                pkg = pkg,
                label = pm.getApplicationLabel(ai).toString(),
                lastUsedMinAgo = if (lu != null && lu > 0) (now - lu) / 60_000L else null,
                running = running?.contains(pkg) ?: false,
                protectedReason = if (isUnlocked) null else prot.reason(pkg) ?: prot.coreSystemReason(pkg, sys),
                system = sys,
                unlocked = isUnlocked,
                note = if (prot.isVpn(pkg)) "VPN app: closing it disconnects the VPN. You decide." else "",
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
