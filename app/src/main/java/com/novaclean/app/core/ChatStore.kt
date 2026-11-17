package com.novaclean.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** role: "user", "ai" or "sys". applied/undo are set when the AI changed settings in that message. */
data class ChatMsg(
    val role: String,
    val text: String,
    val applied: String = "",
    val undo: String = "",
    val undone: Boolean = false,
)

object ChatStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "logs/chat.json").also { it.parentFile?.mkdirs() }

    fun load(ctx: Context): List<ChatMsg> = try {
        val f = file(ctx)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ChatMsg(o.getString("role"), o.getString("text"), o.optString("applied"), o.optString("undo"), o.optBoolean("undone"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(ctx: Context, msgs: List<ChatMsg>) {
        try {
            val a = JSONArray()
            msgs.takeLast(60).forEach {
                a.put(JSONObject().put("role", it.role).put("text", it.text)
                    .put("applied", it.applied).put("undo", it.undo).put("undone", it.undone))
            }
            file(ctx).writeText(a.toString())
        } catch (e: Exception) {
        }
    }
}
