package com.novaclean.app.ai

import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.Prefs
import com.novaclean.app.core.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gemini REST client with retry on busy/connection errors, then fallback to another model. */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun generate(apiKey: String, model: String, prompt: String): String {
        return try {
            call(apiKey, model, prompt)
        } catch (e: Exception) {
            if (model == Prefs.FALLBACK_MODEL) throw e
            call(apiKey, Prefs.FALLBACK_MODEL, prompt)
        }
    }

    private fun call(apiKey: String, model: String, prompt: String): String {
        var lastErr: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = URL("$BASE$model:generateContent").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", apiKey)
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts",
                        JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("temperature", 0.1))
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code == 429 || code == 503) throw RuntimeException("AI busy ($code)")
                if (code !in 200..299) {
                    val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw IllegalStateException("AI error $code: ${err.take(200)}")
                }
                val txt = c.inputStream.bufferedReader().readText()
                return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text")
            } catch (e: java.io.IOException) {
                lastErr = RuntimeException("Connection problem. Check internet or VPN.", e)
            } catch (e: RuntimeException) {
                lastErr = e
                if (e is IllegalStateException) throw e
            }
            Thread.sleep(1500L * (attempt + 1))
        }
        throw lastErr ?: RuntimeException("AI request failed")
    }
}

class AiClassifier(private val apiKey: String, private val model: String) {

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

        val text = GeminiClient.generate(apiKey, model, prompt)
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
