package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

/** Keep a single system prompt at index zero for strict chat templates. */
internal fun normalizeChatCompletionsMessages(messages: JSONArray): JSONArray {
    val history = mutableListOf<JSONObject>()
    val leading = mutableListOf<JSONObject>()
    var inHistory = false
    for (index in 0 until messages.length()) {
        val message = messages.getJSONObject(index)
        if (message.optString("role") == "system" && !inHistory) {
            leading += message
            continue
        }
        inHistory = true
        // Preserve historical context at its original turn instead of moving
        // newer snapshots ahead of older turns into the static prompt prefix.
        history += if (message.optString("role") == "system") {
            JSONObject(message.toString()).put("role", "user")
        } else message
    }
    return JSONArray().apply {
        if (leading.isNotEmpty()) {
            val system = JSONObject(leading.first().toString())
            if (leading.size > 1) {
                val contents = leading.map { it.opt("content") }
                system.put("content", if (contents.all { it is String }) {
                    contents.joinToString("\n\n")
                } else {
                    JSONArray().apply {
                        contents.forEach { content ->
                            if (content is JSONArray) {
                                for (part in 0 until content.length()) put(content.get(part))
                            } else if (content is String) {
                                put(JSONObject().put("type", "text").put("text", content))
                            }
                        }
                    }
                })
            }
            put(system)
        }
        history.forEach { put(it) }
    }
}
