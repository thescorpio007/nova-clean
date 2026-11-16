package com.novaclean.app.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens an app's "App info" page, taps "Force stop" and confirms the dialog.
 * It only reacts while a force-stop request from Nova Clean is pending.
 */
class NovaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NovaAccessibilityService? = null

        private val FORCE_STOP_TEXTS = listOf(
            "force stop", "إيقاف إجباري", "forcer l'arrêt", "beenden erzwingen",
            "forzar detención", "forçar paragem", "forçar parada",
        )
    }

    @Volatile private var step = 0                     // 0 idle, 1 find button, 2 confirm dialog
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    suspend fun forceStop(pkg: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        step = 1
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            step = 0; pending = null
            return false
        }
        val ok = withTimeoutOrNull(7000) { d.await() } ?: false
        step = 0
        pending = null
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(300)
        return ok
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val d = pending ?: return
        val root = rootInActiveWindow ?: return
        when (step) {
            1 -> {
                val btn = findForceStop(root) ?: return
                if (!btn.isEnabled) {            // greyed out = app is already stopped
                    d.complete(true)
                    return
                }
                if (clickNode(btn)) step = 2
            }
            2 -> {
                val ok = root.findAccessibilityNodeInfosByViewId("android:id/button1")
                    .firstOrNull() ?: return
                if (clickNode(ok)) d.complete(true)
            }
        }
    }

    private fun findForceStop(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("com.android.settings:id/force_stop_button")
            .firstOrNull()?.let { return it }
        for (t in FORCE_STOP_TEXTS) {
            root.findAccessibilityNodeInfosByText(t)
                .firstOrNull { it.text?.toString()?.trim().equals(t, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null) {
            if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = c.parent
        }
        return false
    }
}
