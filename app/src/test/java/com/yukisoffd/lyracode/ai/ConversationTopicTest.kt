package com.yukisoffd.lyracode.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationTopicTest {
    @Test
    fun acceptsLeadingBlankLinesAndCleansTitle() {
        assertEquals("修复会话标题", sanitizeConversationTopic("\n \n 标题： “修复会话标题”。\n解释"))
        assertEquals("Fix conversation titles", sanitizeConversationTopic("\nTitle: Fix conversation titles"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyOutput() {
        sanitizeConversationTopic("\n \t\n")
    }

    @Test
    fun reasoningRequestsUseCompletionBudgetWithoutTemperature() {
        val payload = JSONObject()
        configureTextCompletionOutput(payload, true, 4096, 0.2)
        assertEquals(4096, payload.getInt("max_completion_tokens"))
        assertFalse(payload.has("max_tokens"))
        assertFalse(payload.has("temperature"))
    }

    @Test
    fun ordinaryRequestsRetainCompatibleParameters() {
        val payload = JSONObject()
        configureTextCompletionOutput(payload, false, 4096, 0.2)
        assertEquals(4096, payload.getInt("max_tokens"))
        assertEquals(0.2, payload.getDouble("temperature"), 0.0)
        assertFalse(payload.has("max_completion_tokens"))
    }
}
