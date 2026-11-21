package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DeepSeekTurnCompletionTest {
    @Test fun messagesBudgetMatchesHarnessAndPreservesOtherEndpoints() {
        val direct = ApiProfile(
            id = "api", name = "test", apiKey = "", baseUrl = "https://api.deepseek.com/anthropic/v1",
            apiFormat = ApiProfile.API_FORMAT_ANTHROPIC, selectedModel = "deepseek-flash", savedModels = emptyList(),
        )
        val other = direct.copy(baseUrl = "https://example.org/v1")
        val harness = other.copy(id = "deepseek-harness:test")
        for ((profile, expected) in listOf(direct to 256_000, harness to 256_000, other to 4096)) {
            assertEquals(expected, anthropicChatMaxTokens(profile))
            val preview = ModelRequestPreviews.defaults(profile, profile.selectedModel)
            assertEquals(expected, JSONObject(preview.body).getInt("max_tokens"))
        }
    }

    @Test fun reasoningOnlyTurnRetriesAndAcceptsNextAnswer() = runBlocking {
        var attempts = 0
        executeModelRequestWithRetry(retryDelayMillis = 0) {
            attempts++
            requireDeepSeekTurnComplete("end_turn", if (attempts == 1) "" else "answer", false)
        }
        assertEquals(2, attempts)
    }

    @Test fun missingOrInterruptedFinishRetriesEvenWithPartialOutput() {
        for (reason in listOf(null, "aborted", "insufficient_system_resource")) {
            assertThrows(IOException::class.java) { requireDeepSeekTurnComplete(reason, "partial", true) }
        }
    }

    @Test fun limitsAndRefusalsStopBeforeToolDispatchWithoutRetrying() {
        for (reason in listOf("length", "max_tokens", "max_output_tokens", "refusal", "content_filter")) {
            assertThrows(IllegalStateException::class.java) { requireDeepSeekTurnComplete(reason, "partial", true) }
        }
    }

    @Test fun toolOnlyTurnsAreValidWhenTheToolIsPresent() {
        for (reason in listOf("tool_calls", "tool_use")) {
            requireDeepSeekTurnComplete(reason, "", true)
            assertThrows(IOException::class.java) { requireDeepSeekTurnComplete(reason, "preface", false) }
        }
    }

    @Test fun responsesIncompleteReasonSurvivesTerminalEvent() {
        val response = JSONObject("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}""")
        assertEquals("max_output_tokens", deepSeekResponseFinishReason(response))
        assertEquals("incomplete", deepSeekResponseFinishReason(JSONObject(), "response.incomplete"))
        assertEquals("completed", deepSeekResponseFinishReason(JSONObject("""{"status":"completed"}""")))
    }
}
