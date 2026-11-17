package com.novaclean.app.core

import org.json.JSONObject

/**
 * Applies settings changes proposed by the AI chat. Only whitelisted keys are accepted and every
 * value is validated. The API key, permissions and engines can never be changed this way.
 */
object SettingsApplier {
    data class Applied(val summary: String, val undo: String)

    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val MODEL = Regex("[A-Za-z0-9._-]{3,60}")

    fun apply(prefs: Prefs, ch: JSONObject): Applied? {
        val before = prefs.export(includeKey = false).toString()
        val parts = mutableListOf<String>()

        fun bool(key: String, label: String, set: (Boolean) -> Unit) {
            if (ch.has(key) && ch.opt(key) is Boolean) {
                val v = ch.getBoolean(key)
                set(v)
                parts += "$label ${if (v) "ON" else "OFF"}"
            }
        }
        bool("closeApps", "Close background apps") { prefs.closeApps = it }
        bool("cleanCaches", "Clear caches") { prefs.cleanCaches = it }
        bool("toastResult", "Result message") { prefs.toastResult = it }
        bool("notifyResult", "Result notification") { prefs.notifyResult = it }
        bool("keepHistory", "History file") { prefs.keepHistory = it }
        bool("autoUpdate", "Auto update check") { prefs.autoUpdate = it }

        val mode = ch.optString("mode")
        if (mode == Prefs.MODE_AI_SAFE || mode == Prefs.MODE_ALL) {
            prefs.mode = mode
            parts += "Close mode = " + if (mode == Prefs.MODE_ALL) "every unprotected app (no AI)" else "only AI-SAFE apps"
        }
        if (ch.has("recentProtectMin") && ch.opt("recentProtectMin") is Number) {
            val v = ch.getInt("recentProtectMin").coerceIn(1, 60)
            prefs.recentProtectMin = v
            parts += "Protect recently used apps = $v min"
        }
        if (ch.has("widgetLabel") && ch.opt("widgetLabel") is String) {
            prefs.widgetLabel = ch.getString("widgetLabel")
            parts += "Widget text = \"${prefs.widgetLabel}\""
        }
        val model = ch.optString("model")
        if (model.isNotEmpty() && MODEL.matches(model)) {
            prefs.model = model
            parts += "AI model = $model"
        }

        val add = ch.optJSONArray("protectAdd")
        if (add != null) {
            val pk = (0 until add.length()).map { add.optString(it) }.filter { PKG.matches(it) }
            if (pk.isNotEmpty()) {
                prefs.protectedPkgs = prefs.protectedPkgs + pk
                parts += "Protected: ${pk.joinToString(", ")}"
            }
        }
        val rem = ch.optJSONArray("protectRemove")
        if (rem != null) {
            val pk = (0 until rem.length()).map { rem.optString(it) }.filter { PKG.matches(it) }
            if (pk.isNotEmpty()) {
                prefs.protectedPkgs = prefs.protectedPkgs - pk.toSet()
                parts += "Unprotected: ${pk.joinToString(", ")}"
            }
        }
        return if (parts.isEmpty()) null else Applied(parts.joinToString("; "), before)
    }
}
