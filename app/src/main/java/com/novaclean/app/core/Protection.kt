package com.novaclean.app.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationManagerCompat

/**
 * The hard safety net. Anything listed here can NEVER be selected for closing,
 * no matter what the AI says. The AI only ever sees apps that pass this filter.
 */
class Protection(
    private val ctx: Context,
    private val userProtected: Set<String>,
    private val foregroundServices: Set<String>,
    private val recentlyUsed: Set<String>,
) {
    private val pm = ctx.packageManager

    private val hardPrefixes = listOf(
        "android", "com.android", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.inputmethod", "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher", "com.google.android.permissioncontroller",
        "com.google.android.webview", "com.google.android.trichromelibrary",
        "moe.shizuku", "rikka.shizuku",
    )

    private val dynamic: Map<String, String> = HashMap<String, String>().apply {
        put(ctx.packageName, "Nova Clean itself")

        runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).forEach { put(it.activityInfo.packageName, "Launcher") }
        }
        runCatching {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.forEach { put(it.packageName, "Keyboard") }
        }
        runCatching {
            (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { s -> s.resolveInfo?.serviceInfo?.packageName?.let { put(it, "Accessibility service") } }
        }
        runCatching {
            (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .activeAdmins?.forEach { put(it.packageName, "Device admin") }
        }
        runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(ctx)
                .forEach { put(it, "Notification listener") }
        }
    }

    private fun matchesHardPrefix(pkg: String) =
        hardPrefixes.any { pkg == it || pkg.startsWith("$it.") }

    private fun isPureSystem(ai: ApplicationInfo) =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    /** True = do not even show this app in the list (core Android / vendor system). */
    fun isHidden(pkg: String, ai: ApplicationInfo, exploreSystem: Boolean = false): Boolean =
        if (exploreSystem) false else pkg !in dynamic && (matchesHardPrefix(pkg) || isPureSystem(ai))

    fun isSystem(ai: ApplicationInfo) = isPureSystem(ai)

    private val coreWords = listOf(
        "launcher", "systemui", "settings", "provider", "telephony", "telecom", "dialer", "phone",
        "bluetooth", "nfc", "wifi", "inputmethod", "keyboard", "ims", "setupwizard", "packageinstaller",
        "permission", "webview", "security", "mediatek", "qualcomm", "framework", "overlay", "remote",
        "bootstrap", "backup", "certinstaller", "networkstack", "location", "sim", "cellbroadcast",
    )

    /** Extra lock for system apps: anything that looks like a core component can never be closed. */
    fun coreSystemReason(pkg: String, system: Boolean): String? =
        if (matchesHardPrefix(pkg)) "Core Android / Google (locked)"
        else if (system && coreWords.any { pkg.lowercase().contains(it) }) "Core system component (locked)"
        else null

    /** Non-null = the app is shown greyed out and can never be closed. */
    fun reason(pkg: String): String? {
        if (pkg in userProtected) return "Protected by you"
        dynamic[pkg]?.let { return it }
        if (pkg in foregroundServices) return "Running a foreground service (playing / recording)"
        if (pkg in recentlyUsed) return "Used very recently"
        if (isVpn(pkg)) return "VPN app"
        return null
    }

    private fun isVpn(pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services
            ?.any { it.permission == "android.permission.BIND_VPN_SERVICE" } == true
    } catch (e: Exception) {
        false
    }
}
