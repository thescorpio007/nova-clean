package com.novaclean.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All Nova Clean configuration lives here (and is what Backup / Restore saves). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("nova_clean", Context.MODE_PRIVATE)

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putS(k: String, v: String) = sp.edit().putString(k, v).apply()

    var apiKey: String
        get() = str("api_key")
        set(v) = putS("api_key", v.filter { !it.isWhitespace() && it != '"' })

    var model: String
        get() = str("model", DEFAULT_MODEL)
        set(v) = putS("model", v.trim().ifEmpty { DEFAULT_MODEL })

    /** Second-best model from "Check API & models", used when the main model is busy. */
    var fallbackModel: String
        get() = str("fallback_model", FALLBACK_MODEL)
        set(v) = putS("fallback_model", v.trim().ifEmpty { FALLBACK_MODEL })

    var protectedPkgs: Set<String>
        get() = (sp.getStringSet("protected", emptySet()) ?: emptySet()).toSet()
        set(v) { sp.edit().putStringSet("protected", v).apply() }

    /** Apps the user deliberately unprotected (overrides automatic locks and AI NEVER verdicts). */
    var unlockedPkgs: Set<String>
        get() = (sp.getStringSet("unlocked", emptySet()) ?: emptySet()).toSet()
        set(v) { sp.edit().putStringSet("unlocked", v).apply() }

    // ---- One-tap clean (Clean now button, widget, launcher shortcut) ----
    var closeApps: Boolean
        get() = bool("close_apps", true)
        set(v) = put("close_apps", v)

    var cleanCaches: Boolean
        get() = bool("clean_caches", true)
        set(v) = put("clean_caches", v)

    /** MODE_AI_SAFE = close only what the AI marks SAFE. MODE_ALL = every unprotected app (no AI). */
    var mode: String
        get() = str("mode", MODE_AI_SAFE)
        set(v) = putS("mode", v)

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
        get() = str("widget_label", "Clean now")
        set(v) = putS("widget_label", v.trim().ifEmpty { "Clean now" }.take(20))

    /** After a clean, open Recents and tap Clear all (needs the Nova Clean Recents accessibility service). */
    var clearRecents: Boolean
        get() = bool("clear_recents", false)
        set(v) = put("clear_recents", v)

    /** Show system apps in Scan so the AI can assess them. The user approves every closing. */
    var exploreSystem: Boolean
        get() = bool("explore_system", false)
        set(v) = put("explore_system", v)

    // ---- Updates from GitHub ----
    var autoUpdate: Boolean
        get() = bool("auto_update", true)
        set(v) = put("auto_update", v)

    var updatePath: String
        get() = str("upd_path")
        set(v) = putS("upd_path", v)

    var updateVer: String
        get() = str("upd_ver")
        set(v) = putS("upd_ver", v)

    var updateBuild: Int
        get() = sp.getInt("upd_build", 0)
        set(v) { sp.edit().putInt("upd_build", v).apply() }

    var notifiedUpdate: String
        get() = str("upd_notified")
        set(v) = putS("upd_notified", v)

    var updateStatus: String
        get() = str("upd_status")
        set(v) = putS("upd_status", v)

    var lastCheck: Long
        get() = sp.getLong("upd_last", 0L)
        set(v) { sp.edit().putLong("upd_last", v).apply() }

    // ---- Backup support ----
    fun export(includeKey: Boolean): JSONObject = JSONObject().apply {
        if (includeKey) put("apiKey", apiKey)
        put("model", model)
        put("fallbackModel", fallbackModel)
        put("exploreSystem", exploreSystem)
        put("clearRecents", clearRecents)
        put("protected", JSONArray(protectedPkgs.toList()))
        put("unlocked", JSONArray(unlockedPkgs.toList()))
        put("closeApps", closeApps)
        put("cleanCaches", cleanCaches)
        put("mode", mode)
        put("toastResult", toastResult)
        put("notifyResult", notifyResult)
        put("keepHistory", keepHistory)
        put("recentProtectMin", recentProtectMin)
        put("widgetLabel", widgetLabel)
        put("autoUpdate", autoUpdate)
    }

    fun import(o: JSONObject) {
        if (o.has("apiKey")) apiKey = o.getString("apiKey")
        if (o.has("model")) model = o.getString("model")
        if (o.has("fallbackModel")) fallbackModel = o.getString("fallbackModel")
        if (o.has("exploreSystem")) exploreSystem = o.getBoolean("exploreSystem")
        if (o.has("clearRecents")) clearRecents = o.getBoolean("clearRecents")
        if (o.has("protected")) {
            val a = o.getJSONArray("protected")
            protectedPkgs = (0 until a.length()).map { a.getString(it) }.toSet()
        }
        o.optJSONArray("unlocked")?.let { u ->
            unlockedPkgs = (0 until u.length()).map { u.getString(it) }.toSet()
        }
        if (o.has("closeApps")) closeApps = o.getBoolean("closeApps")
        if (o.has("cleanCaches")) cleanCaches = o.getBoolean("cleanCaches")
        if (o.has("mode")) mode = o.getString("mode")
        if (o.has("toastResult")) toastResult = o.getBoolean("toastResult")
        if (o.has("notifyResult")) notifyResult = o.getBoolean("notifyResult")
        if (o.has("keepHistory")) keepHistory = o.getBoolean("keepHistory")
        if (o.has("recentProtectMin")) recentProtectMin = o.getInt("recentProtectMin")
        if (o.has("widgetLabel")) widgetLabel = o.getString("widgetLabel")
        if (o.has("autoUpdate")) autoUpdate = o.getBoolean("autoUpdate")
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
        const val FALLBACK_MODEL = "gemini-2.5-flash"
        const val MODE_AI_SAFE = "AI_SAFE"
        const val MODE_ALL = "ALL"
    }
}
