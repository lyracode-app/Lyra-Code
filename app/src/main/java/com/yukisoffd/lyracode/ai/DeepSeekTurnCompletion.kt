package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

internal fun ApiProfile.usesOfficialDeepSeekEndpoint(): Boolean =
    isDeepSeekHarness || chatEndpoint.toHttpUrlOrNull()?.host == "api.deepseek.com"

internal fun anthropicChatMaxTokens(profile: ApiProfile): Int =
    // Match the bundled Harness Messages client. Thinking and answer share this budget.
    if (profile.usesOfficialDeepSeekEndpoint()) 256_000 else 4096

internal fun deepSeekResponseFinishReason(response: JSONObject, eventType: String? = null): String? {
    val status = response.stringFieldOrNull("status")
    if (status == "incomplete" || eventType == "response.incomplete") {
        return response.optJSONObject("incomplete_details")?.stringFieldOrNull("reason") ?: "incomplete"
    }
    return status
}

internal fun requireDeepSeekTurnComplete(reason: String?, content: String, hasToolCalls: Boolean) {
    when (reason) {
        "length", "max_tokens", "max_output_tokens" ->
            error("DeepSeek 达到单次输出 token 上限，回复已截断（$reason）")
        "refusal", "content_filter" -> error("DeepSeek 已拒绝生成回复（$reason）")
        "tool_use", "tool_calls" -> if (!hasToolCalls) throw IOException("DeepSeek 未返回完整的工具调用")
        "stop", "end_turn", "stop_sequence", "completed" -> Unit
        else -> throw IOException("DeepSeek 回复未完成（${reason ?: "缺少结束原因"}）")
    }
    if (content.isBlank() && !hasToolCalls) throw IOException("DeepSeek 在返回正文或工具调用前结束了回复")
}
