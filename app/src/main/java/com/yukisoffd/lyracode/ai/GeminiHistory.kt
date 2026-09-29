package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

internal const val GEMINI_REPLAY_PARTS_KEY = "lyra_gemini_parts"

/** Preserve part boundaries and opaque signatures, including signatures on text parts. */
internal fun storeGeminiParts(raw: JSONObject, parts: JSONArray) {
    if (parts.length() > 0) raw.put(GEMINI_REPLAY_PARTS_KEY, JSONArray(parts.toString()))
}

internal fun copyGeminiParts(source: JSONObject, destination: JSONObject) {
    source.optJSONArray(GEMINI_REPLAY_PARTS_KEY)?.let { storeGeminiParts(destination, it) }
}

internal fun geminiAssistantParts(content: String, rawJson: String?): JSONArray {
    val raw = rawJson?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    raw?.optJSONArray(GEMINI_REPLAY_PARTS_KEY)?.takeIf { it.length() > 0 }?.let {
        return JSONArray(it.toString())
    }
    // Older messages and messages from other providers have only the common format.
    return JSONArray().also { output ->
        val text = content
        if (text.isNotBlank()) output.put(JSONObject().put("text", text))
        val calls = raw?.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function") ?: JSONObject()
            val args = runCatching { JSONObject(function.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
            output.put(JSONObject().put("functionCall", JSONObject().put("name", function.optString("name")).put("args", args)))
        }
        if (output.length() == 0) output.put(JSONObject().put("text", " "))
    }
}
