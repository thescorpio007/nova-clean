package com.novaclean.app.ai

import com.novaclean.app.core.AppEntry
import com.novaclean.app.core.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Same procedure as Nova Enhance: list the models the key can use, test each with a fixed sample,
 * score 1-10, and pick the fastest model within 1 point of the top score.
 */
object ModelTester {

    data class Result(val model: String, val ok: Boolean, val score: Int, val ms: Long, val note: String)

    private val SAMPLE: List<Pair<AppEntry, Set<Verdict>>> = listOf(
        AppEntry("com.supercell.clashofclans", "Clash of Clans", 120, false) to setOf(Verdict.SAFE),
        AppEntry("com.facebook.katana", "Facebook", 45, false) to setOf(Verdict.SAFE),
        AppEntry("net.openvpn.openvpn", "OpenVPN", 5, true) to setOf(Verdict.CAUTION, Verdict.NEVER),
        AppEntry("com.google.android.inputmethod.latin", "Gboard", 1, true) to setOf(Verdict.NEVER),
        AppEntry("com.google.android.calculator", "Calculator", 300, false) to setOf(Verdict.SAFE),
        AppEntry("com.mxair.remote.service", "Air mouse remote service", 2, true) to setOf(Verdict.CAUTION, Verdict.NEVER),
    )

    private val EXCLUDE = listOf(
        "embedding", "tts", "image", "imagen", "live", "audio", "robotics",
        "computer", "aqa", "veo", "learnlm", "gemma", "-latest", "vision",
    )

    private fun rank(n: String) = when {
        n.contains("lite") -> 0
        n.contains("flash") -> 1
        n.contains("pro") -> 3
        else -> 2
    }

    private fun filter(all: List<String>): List<String> =
        all.filter { n -> n.startsWith("gemini") && EXCLUDE.none { n.contains(it) } }
            .distinct()
            .sortedWith(compareBy<String>({ rank(it) }, { it }))
            .take(10)

    /** Text models this key can call. Throws AiHttpException(400/403) when the key is rejected. */
    fun list(apiKey: String): List<String> {
        val out = mutableListOf<String>()
        var token: String? = null
        for (page in 0 until 3) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" +
                (token?.let { "&pageToken=$it" } ?: "")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("x-goog-api-key", apiKey)
            val code = c.responseCode
            if (code !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.readText().orEmpty()
                val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body)
                throw AiHttpException(code, msg.take(160))
            }
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var gen = false
                for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") gen = true
                if (gen) out += m.getString("name").removePrefix("models/")
            }
            token = o.optString("nextPageToken").ifEmpty { null }
            if (token == null) break
        }
        return filter(out)
    }

    fun test(apiKey: String, model: String): Result {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return try {
            val res = AiClassifier(apiKey, model, fallback = null, attempts = 1)
                .classify(SAMPLE.map { it.first }, "Android phone")
            val correct = SAMPLE.count { (a, expected) ->
                val v = res[a.pkg]?.first
                v != null && v in expected
            }
            Result(model, true, Math.round(correct * 10f / SAMPLE.size), ms(), "")
        } catch (e: AiHttpException) {
            Result(model, false, 0, ms(), if (e.code == 429) "quota/busy (429)" else "HTTP ${e.code}")
        } catch (e: Exception) {
            Result(model, false, 0, ms(), e.message?.take(60) ?: "failed")
        }
    }

    /** Fastest model within 1 point of the top score, and the next best as the fallback. */
    fun pickBest(results: List<Result>): Pair<Result?, Result?> {
        val good = results.filter { it.ok && it.score > 0 }
        if (good.isEmpty()) return null to null
        val top = good.maxOf { it.score }
        val ranked = good.filter { it.score >= top - 1 }.sortedBy { it.ms }
        val best = ranked.first()
        val second = ranked.getOrNull(1) ?: good.filter { it.model != best.model }.minByOrNull { it.ms }
        return best to second
    }
}
