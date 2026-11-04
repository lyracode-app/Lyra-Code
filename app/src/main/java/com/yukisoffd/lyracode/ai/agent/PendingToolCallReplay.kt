package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ChatMessage
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Finds the final tool group without dropping the results already stored for it. */
internal object PendingToolCallReplay {
    data class Group(val calls: List<ToolCall>, val answered: Set<String>)

    fun plan(messages: List<ChatMessage>): Group? {
        val history = messages.filterNot { it.isLocalRequestErrorMessage() || it.isEmptyAssistantPlaceholder() }
        val index = history.indexOfLast { it.role != "tool" }
        if (index < 0 || history[index].role != "assistant") return null
        val saved = history[index].rawJson ?: return null
        val raw = try { JSONObject(saved) } catch (error: Exception) {
            throw IllegalStateException("保存的助手消息格式错误，继续已在重放前停止", error)
        }
        if (!raw.has("tool_calls") || raw.isNull("tool_calls")) return null
        val array = raw.optJSONArray("tool_calls") ?: error("保存的工具调用格式错误")
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: error("保存的工具调用格式错误")
            val fn = item.optJSONObject("function") ?: error("保存的工具函数格式错误")
            val id = item.optString("id")
            val name = fn.optString("name")
            require(id.isNotBlank() && name.isNotBlank() && calls.none { it.id == id }) { "保存的工具调用 ID 格式错误或重复" }
            val arguments = fn.optString("arguments")
            val parsed = try { JSONObject(arguments) } catch (error: Exception) {
                throw IllegalStateException("保存的工具参数格式错误，继续已在重放前停止", error)
            }
            calls.add(ToolCall(id, name, parsed, arguments))
        }
        if (calls.isEmpty()) return null
        val answered = history.drop(index + 1).mapNotNull { it.toolCallId }.toSet()
        return Group(calls, answered)
    }

    // Callers pass only calls already filtered by the batch's persisted call id. Fail closed:
    // return a call only when exactly one matches and it has no answer yet.
    fun uniqueUnansweredMatch(matches: List<ToolCall>, answered: Set<String>): ToolCall? =
        matches.singleOrNull()?.takeUnless { it.id in answered }

    fun storedOutput(name: String, output: String, limit: Int): String =
        if (name == "run_sub_agents") output else output.take(limit)

    suspend fun run(
        group: Group,
        replayId: String?,
        execute: suspend (ToolCall) -> String,
        interrupted: (ToolCall) -> String,
        persist: (ToolCall, String) -> Unit,
        close: (ToolCall) -> Unit,
        updated: suspend (ToolCall) -> Unit,
    ) {
        for (call in group.calls) {
            if (call.id in group.answered) continue
            currentCoroutineContext().ensureActive()
            val replay = call.id == replayId
            val result = if (replay) execute(call) else interrupted(call)
            currentCoroutineContext().ensureActive()
            persist(call, result)
            if (replay) close(call)
            updated(call)
        }
    }
}
