package com.novaclean.app.ai

import android.content.Context
import com.novaclean.app.core.DeviceInfo
import com.novaclean.app.core.NovaFiles
import com.novaclean.app.core.Prefs
import org.json.JSONObject

/** Free conversation with the AI about Nova Clean. The AI may change settings once the user agrees. */
object ChatAi {
    data class Reply(val text: String, val changes: JSONObject?)

    fun systemPrompt(ctx: Context, prefs: Prefs, engines: List<String>): String {
        val h = NovaFiles.historyFile(ctx)
        val history = if (h.exists()) h.readLines().takeLast(15).joinToString("\n") else "none"
        return """
You are the built-in assistant of Nova Clean, an Android app (phones and TV boxes) that closes background apps and clears app caches without root. The user is the owner and is customising the app together with you.
Talk freely in the user's language: answer questions, explain trade-offs, suggest settings and ask what they want.

SETTINGS YOU CAN CHANGE (JSON keys):
closeApps (true/false): one-tap clean closes background apps
cleanCaches (true/false): one-tap clean clears caches (needs Shizuku or root)
mode ("AI_SAFE" or "ALL"): AI_SAFE closes only apps the AI marks SAFE; ALL closes every unprotected app with no AI judgement (risky)
toastResult, notifyResult, keepHistory, autoUpdate (true/false)
recentProtectMin (1-60): apps used in the last N minutes are never closed
widgetLabel (text up to 20 characters): text on the home-screen widget button
model (Gemini model name)
protectAdd / protectRemove (lists of Android package names): apps that must never be closed
You cannot change anything else: not the API key, permissions, engines or files.

RULES
- Keep "changes" null while you are still discussing or the user has not clearly agreed to a specific change.
- When the user clearly agrees ("yes", "do it", "apply", "go"), put exactly those changes in "changes" and say in "reply" what you changed. The app applies them and the user can undo.
- Warn clearly before suggesting mode ALL.
- Never invent package names; ask the user or use names shown in the history below.
- Be concise and practical.

OUTPUT: reply ONLY with JSON: {"reply":"text for the user","changes":null}  or  {"reply":"...","changes":{"key":value}}

CURRENT SETTINGS
closeApps=${prefs.closeApps}, cleanCaches=${prefs.cleanCaches}, mode=${prefs.mode}
toastResult=${prefs.toastResult}, notifyResult=${prefs.notifyResult}, keepHistory=${prefs.keepHistory}, autoUpdate=${prefs.autoUpdate}
recentProtectMin=${prefs.recentProtectMin}, widgetLabel="${prefs.widgetLabel}", model=${prefs.model}
protected by user: ${prefs.protectedPkgs.sorted().joinToString(", ").ifEmpty { "none" }}

ENGINES
${engines.joinToString("\n")}

DEVICE
${DeviceInfo.text(ctx)}
RECENT CLEAN HISTORY
$history
""".trimIndent()
    }

    fun ask(
        apiKey: String, model: String, fallback: String?,
        system: String, turns: List<Pair<String, String>>,
    ): Reply = parse(GeminiClient.chat(apiKey, model, system, turns, fallback))

    fun parse(raw: String): Reply {
        val t = raw.trim().removePrefix("```json").removeSuffix("```").trim()
        return try {
            val o = JSONObject(t)
            Reply(o.optString("reply", t), o.optJSONObject("changes"))
        } catch (e: Exception) {
            Reply(raw.trim(), null)
        }
    }
}
