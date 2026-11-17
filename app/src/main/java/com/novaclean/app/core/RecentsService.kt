package com.novaclean.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Does exactly one thing: when asked, opens the Recents screen and taps the device's own
 * "Clear all" button. It reads no other app's content and performs no other action.
 * It no longer navigates Home on its own: if the tap fails, control returns to Nova Clean
 * so the result can be shown instead of the app appearing to just close.
 * Off by default; the user turns it on in Settings -> 4.
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
        val node = findClearAll(root) ?: findClickableNearText(root)
        val ok = node?.let { clickNodeOrParent(it) } ?: false
        root.recycle()
        return ok
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private val labels = setOf(
        "clear all", "clear", "close all", "end all", "dismiss all", "remove all", "clear recents",
    )

    private fun findClearAll(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 50) return null
        val text = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()?.lowercase()
        if (text != null && labels.any { text == it || text.contains(it) }) return node
        val viewId = node.viewIdResourceName?.lowercase()
        if (viewId != null && (viewId.contains("clear_all") || viewId.contains("clearall") || viewId.contains("dismiss_all"))) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClearAll(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** Some launchers use an icon-only button (a trash/broom icon) with no matching text: fall back
     * to any clickable node whose id/description hints at "clear" without an exact label match. */
    private fun findClickableNearText(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 50) return null
        if (node.isClickable) {
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (id.contains("clear") || id.contains("dismiss") || desc.contains("clear")) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableNearText(child, depth + 1)?.let { return it }
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

        /**
         * Opens Recents and tries to tap Clear all, retrying once after a longer wait if the
         * first attempt finds nothing. Does NOT navigate Home: on success the Recents screen
         * closes on its own; on failure it is left open so the user can see it and close it
         * normally, and Nova Clean stays in the foreground to report the result.
         */
        suspend fun clearRecents(ctx: Context): Boolean {
            val svc = instance ?: return false
            return try {
                CrashLog.guard(ctx, "clear recents") {
                    @Suppress("DEPRECATION")
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    delay(550)
                    var ok = svc.tapClearAll()
                    if (!ok) {
                        delay(500)
                        ok = svc.tapClearAll()
                    }
                    ok
                } ?: false
            } catch (e: Exception) {
                CrashLog.logError(ctx, "clear recents", e)
                false
            }
        }
    }
}
