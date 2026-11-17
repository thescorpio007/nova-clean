package com.novaclean.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Does exactly one thing: when asked, opens the Recents screen and clears it, either by tapping
 * the device's own "Clear all" button or, where that doesn't exist (many TV launchers show a row
 * of cards with no single button), by dismissing each card individually. It reads no other app's
 * content and performs no other action. It does not navigate Home on its own: control returns to
 * Nova Clean so the result can be shown.
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

    private val clearLabels = setOf(
        "clear all", "clear", "close all", "end all", "dismiss all", "remove all", "clear recents",
    )

    /** Taps a single "Clear all" style button if one exists. Returns true if tapped. */
    fun tapClearAll(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findByLabel(root, clearLabels) ?: findClickableByIdHint(root, "clear", "dismiss")
        val ok = node?.let { clickNodeOrParent(it) } ?: false
        root.recycle()
        return ok
    }

    /** Dismisses every recent-app card found on the current screen. Returns how many were dismissed. */
    fun dismissAllCards(): Int {
        var count = 0
        var guard = 0
        while (guard < 30) {
            guard++
            val root = rootInActiveWindow ?: break
            val card = findDismissable(root)
            root.recycle()
            if (card == null) break
            val did = card.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
            if (!did) break
            count++
        }
        return count
    }

    private fun findByLabel(node: AccessibilityNodeInfo, labels: Set<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 50) return null
        val text = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()?.lowercase()
        if (text != null && labels.any { text == it || text.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByLabel(child, labels, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findClickableByIdHint(node: AccessibilityNodeInfo, vararg hints: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 50) return null
        if (node.isClickable) {
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (hints.any { id.contains(it) || desc.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableByIdHint(child, *hints, depth = depth + 1)?.let { return it }
        }
        return null
    }

    /** Any node that advertises the standard "dismiss" accessibility action (a recent-task card). */
    private fun findDismissable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 50) return null
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findDismissable(child, depth + 1)?.let { return it }
        }
        return null
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
         * Opens Recents and clears it: tries a single "Clear all" button first, and falls back to
         * dismissing every card one by one when there isn't one (common on TV launchers).
         * Returns a short result message.
         */
        suspend fun clearRecents(ctx: Context): String {
            val svc = instance ?: return "Nova Clean Recents is not enabled."
            return try {
                CrashLog.guard(ctx, "clear recents") {
                    @Suppress("DEPRECATION")
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    delay(600)
                    if (svc.tapClearAll()) {
                        "Recents cleared."
                    } else {
                        val n = svc.dismissAllCards()
                        when {
                            n > 0 -> "Recents cleared ($n app${if (n == 1) "" else "s"})."
                            else -> "Could not find a Clear all button or any dismissible card on this screen."
                        }
                    }
                } ?: "Something went wrong (saved to the log)."
            } catch (e: Exception) {
                CrashLog.logError(ctx, "clear recents", e)
                "Something went wrong (saved to the log)."
            }
        }
    }
}
