package com.novaclean.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Does exactly one thing: when asked, opens the Recents screen and taps the device's own
 * "Clear all" button, then returns Home. It reads no other app's content and performs no
 * other action. Off by default; the user turns it on in Settings -> 4.
 */
class RecentsService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Finds and taps "Clear all" in the currently open Recents screen. Returns true if tapped. */
    fun tapClearAll(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findClearAll(root) ?: return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        root.recycle()
        return ok
    }

    private fun findClearAll(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val text = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()?.lowercase()
        if (text != null && (text == "clear all" || text == "clear" || text.contains("clear all"))) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClearAll(child, depth + 1)?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile private var instance: RecentsService? = null

        fun isEnabled(ctx: Context): Boolean = try {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: ""
            enabled.contains("${ctx.packageName}/${RecentsService::class.java.name}")
        } catch (e: Exception) {
            false
        }

        /** Opens Recents, waits briefly, taps Clear all, then goes Home. Safe no-op if not enabled. */
        suspend fun clearRecents(ctx: Context): Boolean {
            val svc = instance ?: return false
            return try {
                CrashLog.guard(ctx, "clear recents") {
                    @Suppress("DEPRECATION")
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    kotlinx.coroutines.delay(450)
                    val ok = svc.tapClearAll()
                    kotlinx.coroutines.delay(250)
                    @Suppress("DEPRECATION")
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    ok
                } ?: false
            } catch (e: Exception) {
                CrashLog.logError(ctx, "clear recents", e)
                false
            }
        }
    }
}
