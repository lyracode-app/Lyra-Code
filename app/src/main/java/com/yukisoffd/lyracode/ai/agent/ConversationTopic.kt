package com.yukisoffd.lyracode.ai

import org.json.JSONObject

// Reasoning and visible output share the output budget on reasoning models.
// Title length is constrained separately by the prompt and sanitizer.
internal const val TOPIC_SUMMARY_MAX_OUTPUT_TOKENS = 4096

internal fun configureTextCompletionOutput(
    payload: JSONObject,
    openAiReasoningModel: Boolean,
    maxOutputTokens: Int,
    temperature: Double,
) {
    if (openAiReasoningModel) {
        payload.put("max_completion_tokens", maxOutputTokens)
    } else {
        payload.put("max_tokens", maxOutputTokens).put("temperature", temperature)
    }
}

internal fun sanitizeConversationTopic(rawTitle: String): String {
    return rawTitle.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(Regex("""^(标题|话题|主题|title|topic)\s*[:：]\s*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim().trim('"', '\'', '“', '”', '‘', '’', '。', '.', ':', '：', '#', '*').take(24).trim()
        .also { require(it.isNotBlank()) { "话题总结模型未返回有效标题" } }
}
