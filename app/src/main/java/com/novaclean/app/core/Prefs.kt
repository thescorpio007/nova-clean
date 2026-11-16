package com.novaclean.app.core

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nova_clean", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply() }

    var protectedPkgs: Set<String>
        get() = (sp.getStringSet("protected", emptySet()) ?: emptySet()).toSet()
        set(v) { sp.edit().putStringSet("protected", v).apply() }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
        const val FALLBACK_MODEL = "gemini-2.5-flash"
    }
}
