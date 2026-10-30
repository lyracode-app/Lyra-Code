package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

/** Explicit, conversation-bound capability set. Never inherited by MCP or sub-agents. */
internal interface ScopedAgentTools {
    val conversationId: Long
    val systemPrompt: String
    val includesNativeTools: Boolean get() = false
    val finished: Boolean
    /** User-facing result, persisted after the finishing tool's result. */
    val completionSummary: String? get() = null
    fun definitions(): JSONArray
    fun checkRound()
    suspend fun execute(name: String, arguments: JSONObject): String
}
