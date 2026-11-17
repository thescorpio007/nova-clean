package com.novaclean.app.ai

import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.Prefs
import com.novaclean.app.core.Verdict
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AiHttpException(val code: Int, message: String) : RuntimeException(message)

/** Gemini REST client: retry on busy/connection errors, then fall back to another model. */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** One-shot JSON answer (used by the app classifier). */
    fun generate(
        apiKey: String, model: String, prompt: String,
        fallback: String? = Prefs.FALLBACK_MODEL, attempts: Int = 3,
    ): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(content("user", prompt)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))
        return withFallback(apiKey, model, body, fallback, attempts)
    }

    /** Multi-turn conversation. turns = (role, text) with role "user" or "model". */
    fun chat(
        apiKey: String, model: String, system: String,
        turns: List<Pair<String, String>>, fallback: String?,
    ): String {
        val contents = JSONArray()
        turns.forEach { (role, text) -> contents.put(content(role, text)) }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))
        return withFallback(apiKey, model, body, fallback, 3)
    }

    private fun content(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun withFallback(
        apiKey: String, model: String, body: JSONObject, fallback: String?, attempts: Int,
    ): String = try {
        call(apiKey, model, body, attempts)
    } catch (e: Exception) {
        // 400-403 means the key/request is wrong, another model will not help. 404 = unknown model.
        val authError = e is AiHttpException && e.code in 400..403
        if (fallback == null || fallback == model || authError) throw e
        call(apiKey, fallback, body, attempts)
    }

    private fun call(apiKey: String, model: String, body: JSONObject, attempts: Int): String {
        var last: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                val c = URL("$BASE$model:generateContent").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", apiKey)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw AiHttpException(code, "AI error $code: ${err.take(200)}")
                }
                val txt = c.inputStream.bufferedReader().readText()
                return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text")
            } catch (e: AiHttpException) {
                if (e.code != 429 && e.code != 503) throw e
                last = e
            } catch (e: IOException) {
                last = RuntimeException("Connection problem. Check internet or VPN.", e)
            } catch (e: JSONException) {
                throw RuntimeException("Unexpected reply from the AI.", e)
            }
            if (attempt < attempts - 1) Thread.sleep(1500L * (attempt + 1))
        }
        throw last ?: RuntimeException("AI request failed")
    }
}

class AiClassifier(
    private val apiKey: String,
    private val model: String,
    private val fallback: String? = Prefs.FALLBACK_MODEL,
    private val attempts: Int = 3,
) {

    /** Returns pkg -> (verdict, short reason). Only apps that already passed the hard protection list are sent. */
    fun classify(apps: List<AppEntry>, device: String): Map<String, Pair<Verdict, String>> {
        val out = HashMap<String, Pair<Verdict, String>>()
        apps.chunked(40).forEach { out.putAll(classifyChunk(it, device)) }
        return out
    }

    private fun classifyChunk(chunk: List<AppEntry>, device: String): Map<String, Pair<Verdict, String>> {
        val arr = JSONArray()
        chunk.forEach {
            arr.put(JSONObject()
                .put("pkg", it.pkg)
                .put("name", it.label)
                .put("last_used_min_ago", it.lastUsedMinAgo ?: JSONObject.NULL)
                .put("has_live_process", it.running))
        }
        val prompt = """
You help free memory on an Android device ($device) by force-stopping background apps WITHOUT harming the device.
For each app decide:
- SAFE: ordinary user app that restarts on demand; nothing is lost except unsaved state.
- CAUTION: could be a media player/recorder, VPN, remote-control or air-mouse helper, sync/backup, smart-home, alarm/reminder, banking/2FA, or a service other apps rely on.
- NEVER: core device function, launcher, keyboard, security, driver/firmware/remote helper, connectivity or accessibility.
Judge only by package name and app name. When unsure choose CAUTION.
Reply ONLY with a JSON array: [{"pkg":"...","verdict":"SAFE|CAUTION|NEVER","reason":"max 10 words"}]
Apps: $arr
""".trimIndent()

        val text = GeminiClient.generate(apiKey, model, prompt, fallback, attempts)
        val res = HashMap<String, Pair<Verdict, String>>()
        val allowed = chunk.map { it.pkg }.toSet()
        val json = JSONArray(text.trim().removePrefix("```json").removeSuffix("```").trim())
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            val pkg = o.optString("pkg")
            if (pkg !in allowed) continue
            val v = runCatching { Verdict.valueOf(o.optString("verdict").uppercase()) }
                .getOrDefault(Verdict.CAUTION)
            res[pkg] = v to o.optString("reason")
        }
        return res
    }
}
