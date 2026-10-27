package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHistoryShapeTest {
    @Test
    fun `openai history keeps tool call adjacency and appends a steer turn`() {
        val groups = openAiHistoryGroups(
            source = historyWithSteer(),
            toPromptJson = { message ->
                message.toPromptJson(
                    routeVisualAttachments = false,
                    renderAttachments = { content, _, _ -> JSONObject().put("role", "user").put("content", content) },
                )
            },
            toToolPromptJson = { it.toToolPromptJson() },
            adapt = { _, json -> json },
        )
        val messages = JSONArray().also { output -> groups.forEach { group -> group.forEach(output::put) } }
        val sanitized = sanitizePromptMessageSequence(messages)

        assertEquals(3, sanitized.length())
        val assistant = sanitized.getJSONObject(0)
        assertEquals("assistant", assistant.getString("role"))
        assertTrue(assistant.hasToolCalls())
        assertEquals(setOf("call_1"), assistant.toolCallIds())
        assertEquals("call_1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))

        val tool = sanitized.getJSONObject(1)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        assertEquals("file body", tool.getString("content"))

        val steer = sanitized.getJSONObject(2)
        assertEquals("user", steer.getString("role"))
        assertEquals("steer: stop", steer.getString("content"))
    }

    @Test
    fun `openai history drops a tool call whose results are missing`() {
        val groups = openAiHistoryGroups(
            source = listOf(
                assistantToolCall(1L, "call_missing", "read_file"),
                message(2L, "user", "steer: stop"),
            ),
            toPromptJson = { it.toPromptJson(routeVisualAttachments = false) { content, _, _ ->
                JSONObject().put("role", "user").put("content", content)
            } },
            toToolPromptJson = { it.toToolPromptJson() },
            adapt = { _, json -> json },
        )
        val messages = JSONArray().also { output -> groups.forEach { group -> group.forEach(output::put) } }

        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `anthropic history keeps tool use and result adjacent before a steer turn`() {
        val messages = buildAnthropicMessages(
            source = historyWithSteer(),
            userContent = { message ->
                JSONArray().put(JSONObject().put("type", "text").put("text", message.content))
            },
        )

        assertEquals(3, messages.length())
        assertEquals(listOf("assistant", "user", "user"), messages.roles())

        val toolUse = messages.getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", toolUse.getString("type"))
        assertEquals("call_1", toolUse.getString("id"))
        assertEquals("read_file", toolUse.getString("name"))

        val toolResult = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals("call_1", toolResult.getString("tool_use_id"))
        assertEquals("file body", toolResult.getString("content"))

        assertEquals("steer: stop", messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `gemini history keeps function call and response adjacent before a steer turn`() {
        val contents = buildGeminiContents(
            source = historyWithSteer(),
            userParts = { message -> JSONArray().put(JSONObject().put("text", message.content)) },
            toolNameForToolResult = { "read_file" },
        )

        assertEquals(3, contents.length())
        assertEquals(listOf("model", "user", "user"), (0 until contents.length()).map { contents.getJSONObject(it).getString("role") })

        val functionCall = contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        assertEquals("read_file", functionCall.getString("name"))

        val functionResponse = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("read_file", functionResponse.getString("name"))
        assertEquals("file body", functionResponse.getJSONObject("response").getString("content"))

        assertEquals("steer: stop", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `steer after a tool result keeps both tool pairs adjacent across rounds`() {
        val history = listOf(
            assistantToolCall(1L, "call_1", "read_file"),
            message(2L, "tool", "first body", toolCallId = "call_1"),
            message(3L, "user", "steer: stop"),
            assistantToolCall(4L, "call_2", "read_file"),
            message(5L, "tool", "second body", toolCallId = "call_2"),
            message(6L, "assistant", "done"),
        )

        val openAi = JSONArray().also { output ->
            openAiHistoryGroups(
                source = history,
                toPromptJson = { message ->
                    message.toPromptJson(
                        routeVisualAttachments = false,
                        renderAttachments = { content, _, _ -> JSONObject().put("role", "user").put("content", content) },
                    )
                },
                toToolPromptJson = { it.toToolPromptJson() },
                adapt = { _, json -> json },
            ).forEach { group -> group.forEach(output::put) }
        }.let(::sanitizePromptMessageSequence)
        assertEquals(listOf("assistant", "tool", "user", "assistant", "tool", "assistant"), openAi.roles())
        assertEquals("call_1", openAi.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("call_1", openAi.getJSONObject(1).getString("tool_call_id"))
        assertEquals("steer: stop", openAi.getJSONObject(2).getString("content"))
        assertEquals("call_2", openAi.getJSONObject(3).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("call_2", openAi.getJSONObject(4).getString("tool_call_id"))

        val anthropic = buildAnthropicMessages(
            source = history,
            userContent = { message -> JSONArray().put(JSONObject().put("type", "text").put("text", message.content)) },
        )
        assertEquals(listOf("assistant", "user", "user", "assistant", "user", "assistant"), anthropic.roles())
        assertEquals("call_1", anthropic.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("id"))
        assertEquals("call_1", anthropic.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))
        assertEquals("steer: stop", anthropic.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("call_2", anthropic.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("id"))
        assertEquals("call_2", anthropic.getJSONObject(4).getJSONArray("content").getJSONObject(0).getString("tool_use_id"))

        val gemini = buildGeminiContents(
            source = history,
            userParts = { message -> JSONArray().put(JSONObject().put("text", message.content)) },
            toolNameForToolResult = { "read_file" },
        )
        assertEquals(listOf("model", "user", "user", "model", "user", "model"), (0 until gemini.length()).map {
            gemini.getJSONObject(it).getString("role")
        })
        assertEquals(
            "steer: stop",
            gemini.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        assertEquals(
            "first body",
            gemini.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
                .getJSONObject("functionResponse").getJSONObject("response").getString("content"),
        )
        assertEquals(
            "second body",
            gemini.getJSONObject(4).getJSONArray("parts").getJSONObject(0)
                .getJSONObject("functionResponse").getJSONObject("response").getString("content"),
        )
    }

    @Test
    fun `context history excludes the partially streamed assistant placeholder`() {
        val selected = selectContextHistory(
            messages = listOf(
                message(1L, "user", "hello"),
                message(2L, "assistant", ""),
                message(3L, "assistant", "answer", rawJson = """{"role":"assistant","content":"answer"}"""),
            ),
            excludeMessageId = 0L,
        )

        assertEquals(listOf(1L, 3L), selected.map { it.id })
    }

    @Test
    fun `context history excludes local request errors`() {
        val marked = message(
            id = 2L,
            role = "assistant",
            content = "请求中断：AI 请求失败 400",
            rawJson = JSONObject()
                .put("role", "assistant")
                .put("content", "请求中断：AI 请求失败 400")
                .put(LOCAL_REQUEST_ERROR_KEY, true)
                .toString(),
        )
        val legacy = message(3L, "assistant", "Request interrupted: AI request failed 400")

        val selected = selectContextHistory(
            messages = listOf(message(1L, "user", "hello"), marked, legacy),
            excludeMessageId = 0L,
        )

        assertEquals(listOf(1L), selected.map { it.id })
    }

    @Test
    fun `context history keeps runtime context unless pure prompt mode is on`() {
        val source = listOf(
            message(1L, "user", "hello"),
            message(2L, RUNTIME_CONTEXT_ROLE, "snapshot"),
        )

        assertEquals(listOf(1L, 2L), selectContextHistory(source, excludeMessageId = 0L).map { it.id })
        assertEquals(
            listOf(1L),
            selectContextHistory(source, excludeMessageId = 0L, purePromptMode = true).map { it.id },
        )
    }

    @Test
    fun `context history prepends the compressed summary and drops covered messages`() {
        val selected = selectContextHistory(
            messages = listOf(message(5L, "user", "old"), message(11L, "user", "new")),
            excludeMessageId = 0L,
            compressedThroughMessageId = 10L,
            compressedContext = "summary text",
            summaryConversationId = 7L,
            summaryProfileId = "profile",
            summaryModel = "model",
            summaryCreatedAt = 99L,
        )

        assertEquals(listOf(10L, 11L), selected.map { it.id })
        val summary = selected.first()
        assertEquals("user", summary.role)
        assertEquals(7L, summary.conversationId)
        assertEquals(99L, summary.createdAt)
        assertTrue(summary.content.startsWith("LYRA_COMPRESSED_CONVERSATION_CONTEXT_V1\nsummary text\n\n"))
    }

    @Test
    fun `history serializers preserve default text cleanup`() {
        assertEquals("answer", cleanGeneratedText("nullnullnullnull answer"))
        val plain = message(1L, "user", "hello").toPromptJson(
            routeVisualAttachments = false,
            renderAttachments = { content, _, _ -> JSONObject().put("role", "user").put("content", content) },
        )
        assertEquals("user", plain.getString("role"))
        assertEquals("hello", plain.getString("content"))
        assertFalse(plain.hasToolCalls())
    }

    private fun historyWithSteer(): List<ChatMessage> = listOf(
        assistantToolCall(1L, "call_1", "read_file"),
        message(2L, "tool", "file body", toolCallId = "call_1"),
        message(3L, "user", "steer: stop"),
    )

    private fun assistantToolCall(id: Long, callId: String, name: String): ChatMessage {
        val raw = JSONObject()
            .put("role", "assistant")
            .put("content", "")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", callId)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", name).put("arguments", """{"path":"note.txt"}"""),
                        ),
                ),
            )
        return message(id, "assistant", "", rawJson = raw.toString())
    }

    private fun message(
        id: Long,
        role: String,
        content: String,
        toolCallId: String? = null,
        rawJson: String? = null,
    ): ChatMessage = ChatMessage(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        thinking = "",
        profileId = "profile",
        model = "model",
        toolCallId = toolCallId,
        rawJson = rawJson,
        createdAt = id,
    )

    private fun JSONArray.roles(): List<String> = (0 until length()).map { getJSONObject(it).getString("role") }
}
