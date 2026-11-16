package com.novaclean.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All Nova Clean configuration lives here (and is what Backup / Restore saves). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("nova_clean", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply() }

    var protectedPkgs: Set<String>
        get() = (sp.getStringSet("protected", emptySet()) ?: emptySet()).toSet()
        set(v) { sp.edit().putStringSet("protected", v).apply() }

    // ---- One-tap clean (Clean now button, widget, launcher shortcut) ----
    var closeApps: Boolean
        get() = bool("close_apps", true)
        set(v) = put("close_apps", v)

    var cleanCaches: Boolean
        get() = bool("clean_caches", true)
        set(v) = put("clean_caches", v)

    /** MODE_AI_SAFE = close only what the AI marks SAFE. MODE_ALL = every unprotected app (no AI). */
    var mode: String
        get() = sp.getString("mode", MODE_AI_SAFE) ?: MODE_AI_SAFE
        set(v) { sp.edit().putString("mode", v).apply() }

    var toastResult: Boolean
        get() = bool("toast", true)
        set(v) = put("toast", v)

    var notifyResult: Boolean
        get() = bool("notify", true)
        set(v) = put("notify", v)

    var keepHistory: Boolean
        get() = bool("history", true)
        set(v) = put("history", v)

    var recentProtectMin: Int
        get() = sp.getInt("recent_min", 3)
        set(v) { sp.edit().putInt("recent_min", v.coerceIn(1, 60)).apply() }

    var widgetLabel: String
        get() = sp.getString("widget_label", "Clean now") ?: "Clean now"
        set(v) { sp.edit().putString("widget_label", v.trim().ifEmpty { "Clean now" }.take(20)).apply() }

    // ---- Backup support ----
    fun export(includeKey: Boolean): JSONObject = JSONObject().apply {
        if (includeKey) put("apiKey", apiKey)
        put("model", model)
        put("protected", JSONArray(protectedPkgs.toList()))
        put("closeApps", closeApps)
        put("cleanCaches", cleanCaches)
        put("mode", mode)
        put("toastResult", toastResult)
        put("notifyResult", notifyResult)
        put("keepHistory", keepHistory)
        put("recentProtectMin", recentProtectMin)
        put("widgetLabel", widgetLabel)
    }

    fun import(o: JSONObject) {
        if (o.has("apiKey")) apiKey = o.getString("apiKey")
        if (o.has("model")) model = o.getString("model")
        if (o.has("protected")) {
            val a = o.getJSONArray("protected")
            protectedPkgs = (0 until a.length()).map { a.getString(it) }.toSet()
        }
        if (o.has("closeApps")) closeApps = o.getBoolean("closeApps")
        if (o.has("cleanCaches")) cleanCaches = o.getBoolean("cleanCaches")
        if (o.has("mode")) mode = o.getString("mode")
        if (o.has("toastResult")) toastResult = o.getBoolean("toastResult")
        if (o.has("notifyResult")) notifyResult = o.getBoolean("notifyResult")
        if (o.has("keepHistory")) keepHistory = o.getBoolean("keepHistory")
        if (o.has("recentProtectMin")) recentProtectMin = o.getInt("recentProtectMin")
        if (o.has("widgetLabel")) widgetLabel = o.getString("widgetLabel")
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
        const val FALLBACK_MODEL = "gemini-2.5-flash"
        const val MODE_AI_SAFE = "AI_SAFE"
        const val MODE_ALL = "ALL"
    }
}
