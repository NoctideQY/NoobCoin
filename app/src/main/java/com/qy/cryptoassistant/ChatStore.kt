package com.qy.cryptoassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val error: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: String = "chat",
    val tonePreset: String,
    val analysisPrompt: String,
    val advicePrompt: String,
    val context: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
)

object ChatStore {
    private const val PREFS = "ai_chat_store"
    private const val KEY = "sessions"

    fun load(context: Context): List<ChatSession> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { parseSession(array.optJSONObject(it)) }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun save(context: Context, sessions: List<ChatSession>) {
        val array = JSONArray()
        sessions.take(30).forEach { session ->
            array.put(JSONObject().apply {
                put("id", session.id)
                put("title", session.title)
                put("kind", session.kind)
                put("tonePreset", session.tonePreset)
                put("analysisPrompt", session.analysisPrompt)
                put("advicePrompt", session.advicePrompt)
                put("context", session.context)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)
                put("messages", JSONArray().apply {
                    session.messages.takeLast(200).forEach { message ->
                        put(JSONObject().apply {
                            put("id", message.id)
                            put("role", message.role)
                            put("content", message.content)
                            put("error", message.error)
                            put("createdAt", message.createdAt)
                        })
                    }
                })
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun parseSession(json: JSONObject?): ChatSession? {
        if (json == null) return null
        val messages = json.optJSONArray("messages")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ChatMessage(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    role = item.optString("role", "user"),
                    content = item.optString("content"),
                    error = item.optBoolean("error", false),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                )
            }
        }.orEmpty()
        val now = System.currentTimeMillis()
        return ChatSession(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = json.optString("title").ifBlank { "新会话" },
            kind = json.optString("kind", "chat"),
            tonePreset = json.optString("tonePreset", "默认"),
            analysisPrompt = json.optString("analysisPrompt"),
            advicePrompt = json.optString("advicePrompt"),
            context = json.optString("context"),
            createdAt = json.optLong("createdAt", now),
            updatedAt = json.optLong("updatedAt", now),
            messages = messages,
        )
    }
}
