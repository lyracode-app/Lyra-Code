package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

internal class CompressionOutputLimitException : IllegalStateException(
    "会话历史压缩输出达到 token 上限，未得到完整摘要；原上下文已保留。请增加分段块数或更换压缩模型。",
)

internal fun isMiMoCompressionModel(profile: ApiProfile, model: String): Boolean =
    profile.presetId == "xiaomimimo" || model.contains("mimo-", ignoreCase = true)

internal fun compressionOutputCeiling(profile: ApiProfile, model: String): Int =
    if (isMiMoCompressionModel(profile, model) || model.contains("deepseek", ignoreCase = true) || isDeepSeekApiProfile(profile)) 131_072 else 32_768

/** Linear estimate for budgeting only: avoid running a full tokenizer over megabytes again. */
internal fun estimateCompressionTokens(text: String): Long {
    var quarters = 0L
    text.codePoints().forEach { codePoint -> quarters += if (codePoint < 128) 1 else 4 }
    return (quarters + 3) / 4
}

internal fun compressionOutputBudget(inputTokens: Long, ceiling: Int): Int =
    ((inputTokens + 7) / 8).coerceIn(16_384L, ceiling.toLong()).toInt()

internal fun compressionRequestTimeoutSeconds(outputBudget: Int): Long =
    (60L + outputBudget / 128L).coerceIn(180L, 600L)

/** Chat customizations may request SSE or a tiny output budget; compression owns these fields. */
internal fun Request.forCompressionResponse(profile: ApiProfile, budget: Int): Request {
    val buffer = Buffer()
    body!!.writeTo(buffer)
    val payload = JSONObject(buffer.readUtf8())
    if (profile.apiFormat != ApiProfile.API_FORMAT_GEMINI) payload.put("stream", false)
    payload.remove("stream_options")
    payload.remove("background")
    if (profile.apiFormat == ApiProfile.API_FORMAT_OPENAI &&
        (isDeepSeekApiProfile(profile) || payload.optString("model").contains("deepseek", ignoreCase = true) ||
            isMiMoCompressionModel(profile, payload.optString("model")))) {
        if (profile.useResponsesApi) payload.put("reasoning", JSONObject().put("effort", "none"))
        else {
            payload.put("thinking", JSONObject().put("type", "disabled"))
            payload.remove("reasoning_effort")
        }
    }
    when (profile.apiFormat) {
        ApiProfile.API_FORMAT_GEMINI -> {
            val config = payload.optJSONObject("generationConfig") ?: JSONObject()
            payload.put("generationConfig", config.put("maxOutputTokens", budget))
        }
        ApiProfile.API_FORMAT_ANTHROPIC -> payload.put("max_tokens", budget)
        else -> if (profile.useResponsesApi) payload.put("max_output_tokens", budget) else {
            if (payload.has("max_completion_tokens")) {
                payload.remove("max_tokens")
                payload.put("max_completion_tokens", budget)
            } else payload.put("max_tokens", budget)
        }
    }
    return newBuilder().post(payload.toString().toRequestBody("application/json".toMediaType())).build()
}

internal fun historyCompressionPayload(profile: ApiProfile, model: String, instruction: String, input: String, budget: Int): JSONObject {
    val reasoningModel = listOf("o1", "o3", "o4", "gpt-5").any { model.lowercase().contains(it) }
    val deepSeek = isDeepSeekApiProfile(profile) || model.contains("deepseek", ignoreCase = true)
    val mimo = isMiMoCompressionModel(profile, model)
    return when (profile.apiFormat) {
        ApiProfile.API_FORMAT_ANTHROPIC -> JSONObject().put("model", model).put("max_tokens", budget)
            .put("temperature", 0.1).put("system", instruction).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", input)))
        ApiProfile.API_FORMAT_GEMINI -> JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", input)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", budget))
        else -> if (profile.useResponsesApi) {
            JSONObject().put("model", model).put("instructions", instruction).put("input", input)
                .put("max_output_tokens", budget).put("store", false).put("stream", false)
                .apply {
                    if (deepSeek) put("reasoning", JSONObject().put("effort", "none"))
                    else if (reasoningModel) put("reasoning", JSONObject().put("effort", "low"))
                    else put("temperature", 0.1)
                }
        } else {
            JSONObject().put("model", model).put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", instruction))
                    .put(JSONObject().put("role", "user").put("content", input)))
                .apply {
                    configureTextCompletionOutput(this, reasoningModel || mimo, budget, 0.1)
                    if (deepSeek || mimo) put("thinking", JSONObject().put("type", "disabled"))
                    else if (reasoningModel) put("reasoning_effort", "low")
                }
        }
    }
}

internal fun extractCompressionSummary(root: JSONObject, profile: ApiProfile): String {
    root.optJSONObject("response")?.let { return extractCompressionSummary(it, profile) }
    root.optJSONObject("data")?.let { return extractCompressionSummary(it, profile) }
    root.optJSONObject("error")?.let { error(it.optString("message").ifBlank { it.toString() }) }
    val choice = root.optJSONArray("choices")?.optJSONObject(0)
    val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
    val reason = choice?.optString("finish_reason").orEmpty().ifBlank {
        root.optString("stop_reason").ifBlank {
            candidate?.optString("finishReason").orEmpty().ifBlank {
                root.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
            }
        }
    }
    if (reason.lowercase() in setOf("length", "max_tokens", "max_output_tokens")) throw CompressionOutputLimitException()
    val refusal = choice?.optJSONObject("message")?.optString("refusal").orEmpty()
    require(refusal.isBlank() || refusal == "null") { "压缩模型拒绝生成摘要：$refusal；原上下文已保留" }
    require(root.optString("status") !in setOf("failed", "incomplete", "queued", "in_progress")) {
        "压缩模型未完成摘要（${root.optString("status")} / $reason），原上下文已保留"
    }
    val text = extractModelResponseText(root, profile.apiFormat, profile.useResponsesApi).trim()
    require(text.isNotBlank()) {
        "压缩模型没有返回摘要正文（结束原因：${reason.ifBlank { "未提供" }}）。可能只返回了思考内容或被拦截；原上下文已保留。"
    }
    return text
}

internal fun normalizeCompressionSummary(summary: String): String {
    var text = summary.trim()
    if (text.startsWith("```") && text.endsWith("```")) {
        text = text.substringAfter('\n').removeSuffix("```").trim()
    }
    require(text.isNotBlank()) { "压缩模型没有返回摘要正文，原上下文已保留" }
    return if (text.startsWith("LYRA_STRUCTURED_CONTEXT_V2")) text else
        "LYRA_STRUCTURED_CONTEXT_V2\npreserved_unstructured_context: |\n" + text.lineSequence().joinToString("\n") { "  $it" }
}

internal suspend fun <T, R> mapCompressionChunks(items: List<T>, transform: suspend (Int, T) -> R): List<R> = coroutineScope {
    val permits = Semaphore(2)
    items.mapIndexed { index, item -> async { permits.withPermit { transform(index, item) } } }.awaitAll()
}
