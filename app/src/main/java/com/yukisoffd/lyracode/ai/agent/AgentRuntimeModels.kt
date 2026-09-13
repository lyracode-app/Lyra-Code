package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.SubAgentConfig
import org.json.JSONObject

internal data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
    val rawArguments: String,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", rawArguments),
            )
    }
}

internal data class StreamingResult(
    val content: String,
    val thinking: String,
    val rawMessage: JSONObject,
    val toolCalls: List<ToolCall>,
    val tokensPerSecond: Double = 0.0,
    val deepSeekCacheHitRate: Double? = null,
    val fromCache: Boolean = false,
)

internal class ToolCallBuilder {
    var id: String = ""
    var name: String = ""
    val arguments = StringBuilder()

    fun toToolCall(index: Int): ToolCall? {
        if (name.isBlank()) return null
        val raw = arguments.toString().ifBlank { "{}" }
        val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return ToolCall(id.ifBlank { "tool_$index" }, name, parsed, raw)
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments.toString()),
            )
    }
}

internal class AnthropicBlockBuilder {
    var type: String = ""
    var id: String = ""
    var name: String = ""
    val text = StringBuilder()
    val thinking = StringBuilder()
    val input = StringBuilder()
}

internal data class ToolExecution(
    val content: String,
    val fileChanges: List<FileDiff> = emptyList(),
    val ok: Boolean = true,
)

internal data class SubAgentExecutionContext(
    val owner: SubAgentWriteOwner,
    val agent: SubAgentConfig,
    val readOnly: Boolean,
    val writePaths: Set<String>,
)

