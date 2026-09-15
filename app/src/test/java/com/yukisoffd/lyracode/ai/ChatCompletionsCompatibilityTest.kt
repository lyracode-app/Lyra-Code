package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsCompatibilityTest {
    @Test
    fun mergesAgentAndSelectedPromptsForStrictTemplates() {
        val messages = JSONArray()
            .put(message("system", "native protocol"))
            .put(message("system", "selected prompt"))
            .put(message("user", "hi"))
            .put(message("user", "runtime snapshot"))
        val result = normalizeChatCompletionsMessages(messages)
        assertEquals(listOf("system", "user", "user"), roles(result))
        assertEquals("native protocol\n\nselected prompt", result.getJSONObject(0).getString("content"))
        assertEquals(4, messages.length())
        assertEquals("native protocol", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun preservesHistoricalContextToolPairsAndMultimodalPayloads() {
        val image = message("user", JSONArray().put(JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/png;base64,abc"))))
        val call = message("assistant", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "call1")))
        val tool = message("tool", "result").put("tool_call_id", "call1")
        val snapshot = message("system", "new snapshot")
        val source = JSONArray().put(image).put(call).put(tool).put(snapshot)
        val result = normalizeChatCompletionsMessages(source)
        assertEquals(listOf("user", "assistant", "tool", "user"), roles(result))
        for (index in 0..2) assertEquals(source.getJSONObject(index).toString(), result.getJSONObject(index).toString())
        assertEquals("new snapshot", result.getJSONObject(3).getString("content"))
        assertEquals("system", snapshot.getString("role"))
    }

    @Test
    fun handlesEmptySystemOnlyAndTextBlockPrompts() {
        assertEquals(0, normalizeChatCompletionsMessages(JSONArray()).length())
        val source = JSONArray().put(message("system", "first")).put(message("system", "second"))
        assertEquals("first\n\nsecond", normalizeChatCompletionsMessages(source).getJSONObject(0).getString("content"))
        val blocks = JSONArray().put(JSONObject().put("type", "text").put("text", "block"))
        val mixed = normalizeChatCompletionsMessages(JSONArray().put(message("system", "text")).put(message("system", blocks)))
        assertEquals(2, mixed.getJSONObject(0).getJSONArray("content").length())
        assertTrue(roles(mixed).all { it == "system" })
    }

    private fun message(role: String, content: Any) = JSONObject().put("role", role).put("content", content)
    private fun roles(messages: JSONArray) = (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
}
