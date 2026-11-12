package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

/** Both the main chat and floating chat resolve tools from their persisted calls. */
internal fun enrichToolRecords(records: List<ChatRecord>): List<ChatRecord> {
    val calls = mutableMapOf<String, Pair<String, String>>()
    return records.map { record ->
        if (record.role == "assistant") {
            runCatching { JSONObject(record.rawJson.orEmpty()) }.getOrNull()
                ?.optJSONArray("tool_calls")?.let { array ->
                    for (index in 0 until array.length()) {
                        val call = array.optJSONObject(index) ?: continue
                        val id = call.optString("id")
                        val function = call.optJSONObject("function") ?: continue
                        if (id.isNotBlank()) {
                            calls[id] = function.optString("name") to prettyToolJson(function.optString("arguments"))
                        }
                    }
                }
            record
        } else if (record.role == "tool") {
            val details = calls[record.toolCallId]
            record.copy(toolName = details?.first.orEmpty(), toolInput = details?.second.orEmpty())
        } else record
    }
}

private fun prettyToolJson(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return "{}"
    return runCatching { JSONObject(trimmed).toString(2) }
        .recoverCatching { JSONArray(trimmed).toString(2) }
        .getOrDefault(trimmed)
}
