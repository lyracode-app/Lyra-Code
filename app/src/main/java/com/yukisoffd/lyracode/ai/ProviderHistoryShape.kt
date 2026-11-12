package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

// Pure history-shape builders shared by the provider request paths. Store reads, settings flags,
// attachment decoding and provider-specific file replacement stay in the OpenAiAgent wrappers.

internal fun cleanGeneratedText(text: String): String {
    return text.replace(Regex("(?:null){4,}", RegexOption.IGNORE_CASE), "").trim()
}

internal fun hasUploadedAttachments(content: String): Boolean {
    return content.contains("<lyra_attachment_v1>") ||
        content.contains("用户上传媒体：") ||
        content.contains("用户上传文件：")
}

/**
 * Pure context-history shape: excludes the in-flight assistant placeholder, local request errors,
 * media messages and compressed-away turns, and prepends the compressed summary when present.
 */
internal fun selectContextHistory(
    messages: List<ChatMessage>,
    excludeMessageId: Long,
    compressedThroughMessageId: Long = 0L,
    compressedContext: String = "",
    purePromptMode: Boolean = false,
    summaryConversationId: Long = 0L,
    summaryProfileId: String = "",
    summaryModel: String = "",
    summaryCreatedAt: Long = 0L,
): List<ChatMessage> {
    val source = messages.filter {
        it.id != excludeMessageId &&
            (!purePromptMode || it.role != RUNTIME_CONTEXT_ROLE) &&
            it.id > compressedThroughMessageId &&
            it.role != MEDIA_MESSAGE_ROLE &&
            !it.isLocalRequestErrorMessage() &&
            !it.isEmptyAssistantPlaceholder()
    }
    val summary = compressedContext.trim()
    if (summary.isBlank()) return source
    val compressedContextMarker = if (summary.startsWith("LYRA_STRUCTURED_CONTEXT_V2")) {
        "LYRA_COMPRESSED_CONVERSATION_CONTEXT_V2"
    } else {
        "LYRA_COMPRESSED_CONVERSATION_CONTEXT_V1"
    }
    return listOf(
        ChatMessage(
            id = compressedThroughMessageId,
            conversationId = summaryConversationId,
            role = "user",
            content = "$compressedContextMarker\n$summary\n\nThe content above is a compressed summary of earlier conversation history. Treat it as prior conversation facts and continue the current task.",
            thinking = "",
            profileId = summaryProfileId,
            model = summaryModel,
            toolCallId = null,
            rawJson = null,
            createdAt = summaryCreatedAt,
        ),
    ) + source
}

internal fun ChatMessage.toPromptJson(
    routeVisualAttachments: Boolean,
    renderAttachments: (content: String, messageId: Long, routeVisualAttachments: Boolean) -> JSONObject,
): JSONObject {
    val raw = rawJson?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.also {
            sanitizeAssistantRaw(it)
            it.remove(GEMINI_REPLAY_PARTS_KEY)
        }
    if (raw == null && role == "user" && hasUploadedAttachments(content)) {
        return renderAttachments(content, id, routeVisualAttachments)
    }
    return raw ?: JSONObject()
        .put("role", if (role == RUNTIME_CONTEXT_ROLE) "user" else role)
        .put("content", if (role == "assistant") cleanGeneratedText(content) else content)
        .apply {
            if (role == "assistant" && thinking.isNotBlank()) {
                put("reasoning_content", cleanGeneratedText(thinking))
            }
        }
}

internal fun ChatMessage.toToolPromptJson(): JSONObject = JSONObject()
    .put("role", "tool")
    .put("tool_call_id", toolCallId)
    .put("content", content)

internal fun JSONObject.hasToolCalls(): Boolean {
    return optString("role") == "assistant" && (optJSONArray("tool_calls")?.length() ?: 0) > 0
}

internal fun JSONObject.toolCallIds(): Set<String> {
    val calls = optJSONArray("tool_calls") ?: return emptySet()
    return buildSet {
        for (index in 0 until calls.length()) {
            calls.optJSONObject(index)?.optString("id").orEmpty().takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}

internal fun sanitizeAssistantRaw(raw: JSONObject) {
    if (raw.optString("role") != "assistant") return
    if (raw.has("content") && !raw.isNull("content")) {
        raw.put("content", cleanGeneratedText(raw.optString("content")))
    }
    if (raw.has("reasoning_content") && !raw.isNull("reasoning_content")) {
        raw.put("reasoning_content", cleanGeneratedText(raw.optString("reasoning_content")))
    }
}

internal fun sanitizePromptMessageSequence(messages: JSONArray): JSONArray {
    val output = mutableListOf<JSONObject>()
    val pendingToolIds = linkedSetOf<String>()
    var pendingAssistantIndex = -1

    fun dropPendingAssistant() {
        if (pendingAssistantIndex >= 0 && pendingAssistantIndex < output.size) {
            while (output.size > pendingAssistantIndex) output.removeAt(output.lastIndex)
        }
        pendingAssistantIndex = -1
        pendingToolIds.clear()
    }

    for (index in 0 until messages.length()) {
        val message = messages.getJSONObject(index)
        when (message.optString("role")) {
            "tool" -> {
                val id = message.optString("tool_call_id")
                if (id.isNotBlank() && id in pendingToolIds) {
                    output += message
                    pendingToolIds -= id
                }
            }
            "assistant" -> {
                if (pendingToolIds.isNotEmpty()) dropPendingAssistant()
                output += message
                if (message.hasToolCalls()) {
                    pendingAssistantIndex = output.lastIndex
                    pendingToolIds += message.toolCallIds()
                } else {
                    pendingAssistantIndex = -1
                    pendingToolIds.clear()
                }
            }
            else -> {
                if (pendingToolIds.isNotEmpty()) dropPendingAssistant()
                output += message
                pendingAssistantIndex = -1
                pendingToolIds.clear()
            }
        }
    }
    if (pendingToolIds.isNotEmpty()) dropPendingAssistant()
    return JSONArray().also { array -> output.forEach { array.put(it) } }
}

internal fun openAiHistoryGroups(
    source: List<ChatMessage>,
    toPromptJson: (ChatMessage) -> JSONObject,
    toToolPromptJson: (ChatMessage) -> JSONObject,
    adapt: (ChatMessage, JSONObject) -> JSONObject,
): List<List<JSONObject>> {
    val groups = mutableListOf<List<JSONObject>>()
    var index = 0
    while (index < source.size) {
        val message = source[index]
        if (message.role == "tool") {
            index++
            continue
        }
        val json = adapt(message, toPromptJson(message))
        if (json.hasToolCalls()) {
            val requiredToolIds = json.toolCallIds()
            val toolMessages = mutableListOf<JSONObject>()
            var next = index + 1
            while (next < source.size && source[next].role == "tool") {
                val tool = source[next]
                val toolCallId = tool.toolCallId.orEmpty()
                if (toolCallId in requiredToolIds) {
                    toolMessages.add(toToolPromptJson(tool))
                }
                next++
            }
            val returnedToolIds = toolMessages.map { it.optString("tool_call_id") }.toSet()
            if (requiredToolIds.isNotEmpty() && returnedToolIds.containsAll(requiredToolIds)) {
                groups.add(listOf(json) + toolMessages)
            }
            index = next
        } else {
            groups.add(listOf(json))
            index++
        }
    }
    return groups
}

internal fun buildAnthropicMessages(
    source: List<ChatMessage>,
    userContent: (ChatMessage) -> JSONArray,
): JSONArray {
    val output = JSONArray()
    var index = 0
    while (index < source.size) {
        val message = source[index]
        when (message.role) {
            "user", RUNTIME_CONTEXT_ROLE -> output.put(
                JSONObject().put("role", "user").put("content", userContent(message)),
            )
            "assistant" -> {
                val toolUseIds = message.anthropicToolUseIds()
                if (toolUseIds.isEmpty()) {
                    output.put(JSONObject().put("role", "assistant").put("content", anthropicAssistantContent(message)))
                } else {
                    val toolResults = JSONArray()
                    val returned = mutableSetOf<String>()
                    var next = index + 1
                    while (next < source.size && source[next].role == "tool") {
                        val tool = source[next]
                        val id = tool.toolCallId.orEmpty()
                        if (id in toolUseIds) {
                            toolResults.put(anthropicToolResult(tool))
                            returned += id
                        }
                        next++
                    }
                    if (returned.containsAll(toolUseIds)) {
                        output.put(JSONObject().put("role", "assistant").put("content", anthropicAssistantContent(message)))
                        output.put(JSONObject().put("role", "user").put("content", toolResults))
                    }
                    index = next
                    continue
                }
            }
            "tool" -> Unit
        }
        index++
    }
    return output
}

internal fun anthropicAssistantContent(message: ChatMessage): JSONArray {
    val raw = message.rawJson?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    return JSONArray().also { output ->
        val text = cleanGeneratedText(message.content)
        if (text.isNotBlank()) output.put(JSONObject().put("type", "text").put("text", text))
        val calls = raw?.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function") ?: JSONObject()
            val args = runCatching { JSONObject(function.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
            output.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", call.optString("id").ifBlank { "tool_$index" })
                    .put("name", function.optString("name"))
                    .put("input", args),
            )
        }
        if (output.length() == 0) output.put(JSONObject().put("type", "text").put("text", " "))
    }
}

internal fun anthropicToolResult(message: ChatMessage): JSONObject {
    return JSONObject()
        .put("type", "tool_result")
        .put("tool_use_id", message.toolCallId.orEmpty())
        .put("content", message.content)
}

internal fun ChatMessage.anthropicToolUseIds(): Set<String> {
    val raw = rawJson?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptySet()
    val calls = raw.optJSONArray("tool_calls") ?: return emptySet()
    return buildSet {
        for (index in 0 until calls.length()) {
            calls.optJSONObject(index)?.optString("id").orEmpty().takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}

internal fun buildGeminiContents(
    source: List<ChatMessage>,
    userParts: (ChatMessage) -> JSONArray,
    toolNameForToolResult: (ChatMessage) -> String,
): JSONArray {
    val output = JSONArray()
    source.forEach { message ->
        when (message.role) {
            "user", RUNTIME_CONTEXT_ROLE -> output.put(
                JSONObject().put("role", "user").put("parts", userParts(message)),
            )
            "assistant" -> output.put(
                JSONObject().put("role", "model").put("parts", geminiAssistantParts(cleanGeneratedText(message.content), message.rawJson)),
            )
            "tool" -> output.put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray().put(geminiFunctionResponse(message, toolNameForToolResult(message))),
                ),
            )
        }
    }
    return output
}

internal fun geminiFunctionResponse(message: ChatMessage, toolName: String): JSONObject {
    return JSONObject()
        .put(
            "functionResponse",
            JSONObject()
                .put("name", toolName)
                .put("response", JSONObject().put("content", message.content)),
        )
}
