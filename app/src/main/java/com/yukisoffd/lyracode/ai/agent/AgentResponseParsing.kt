package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import org.json.JSONArray
import org.json.JSONObject

internal fun extractModelResponseText(root: JSONObject, apiFormat: String, useResponsesApi: Boolean = false): String {
    val primary = when (apiFormat) {
        ApiProfile.API_FORMAT_ANTHROPIC -> extractAnthropicResponseText(root)
        ApiProfile.API_FORMAT_GEMINI -> extractGeminiResponseText(root)
        else -> if (useResponsesApi) extractResponsesApiText(root) else extractOpenAiChatText(root)
    }
    if (primary.isNotBlank()) return primary
    val fallbacks = when (apiFormat) {
        ApiProfile.API_FORMAT_ANTHROPIC -> listOf(extractOpenAiChatText(root), extractResponsesApiText(root))
        ApiProfile.API_FORMAT_GEMINI -> listOf(extractOpenAiChatText(root), extractResponsesApiText(root))
        else -> if (useResponsesApi) listOf(extractOpenAiChatText(root)) else listOf(extractResponsesApiText(root))
    }
    fallbacks.firstOrNull { it.isNotBlank() }?.let { return it }
    root.optJSONObject("response")?.let { wrapped ->
        extractModelResponseText(wrapped, apiFormat, useResponsesApi).takeIf { it.isNotBlank() }?.let { return it }
    }
    root.optJSONObject("data")?.let { wrapped ->
        extractModelResponseText(wrapped, apiFormat, useResponsesApi).takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

private fun extractAnthropicResponseText(root: JSONObject): String {
    return extractVisibleText(root.opt("content")).ifBlank { root.optString("completion") }
}

private fun extractGeminiResponseText(root: JSONObject): String {
    val candidates = root.optJSONArray("candidates") ?: return ""
    return buildList {
        for (index in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(index) ?: continue
            val content = candidate.optJSONObject("content")
            extractVisibleText(content?.opt("parts")).takeIf { it.isNotBlank() }?.let(::add)
            candidate.optString("output").takeIf { it.isNotBlank() }?.let(::add)
            candidate.optString("text").takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct().joinToString("\n")
}

private fun extractOpenAiChatText(root: JSONObject): String {
    val choices = root.optJSONArray("choices") ?: return ""
    return buildList {
        for (index in 0 until choices.length()) {
            val choice = choices.optJSONObject(index) ?: continue
            val message = choice.optJSONObject("message")
            extractVisibleText(message?.opt("content")).takeIf { it.isNotBlank() }?.let(::add)
            choice.optString("text").takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct().joinToString("\n")
}

private fun extractResponsesApiText(root: JSONObject): String {
    root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
    val output = root.optJSONArray("output") ?: return ""
    return buildList {
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") == "reasoning") continue
            extractVisibleText(item.opt("content")).takeIf { it.isNotBlank() }?.let(::add)
            extractVisibleText(item.opt("text")).takeIf { it.isNotBlank() }?.let(::add)
        }
    }.distinct().joinToString("\n")
}

private fun extractVisibleText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is String -> value.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
    is JSONArray -> buildList {
        for (index in 0 until value.length()) {
            extractVisibleText(value.opt(index)).takeIf { it.isNotBlank() }?.let(::add)
        }
    }.joinToString("\n")
    is JSONObject -> {
        when (value.optString("type")) {
            "reasoning", "thinking", "function_call", "tool_call" -> ""
            else -> listOf("text", "output_text", "content", "value")
                .asSequence()
                .map { extractVisibleText(value.opt(it)) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }
    }
    else -> ""
}

