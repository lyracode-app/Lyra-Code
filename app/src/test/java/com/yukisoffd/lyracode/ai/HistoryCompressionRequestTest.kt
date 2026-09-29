package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HistoryCompressionRequestTest {
    @Test fun largeHistoryGetsAnOutputBudgetProportionalToItsSize() {
        val ceiling = compressionOutputCeiling(profile(), "mimo-v2.5")
        assertEquals(131_072, ceiling)
        assertEquals(16_384, compressionOutputBudget(8_000, ceiling))
        assertEquals(32_768, compressionOutputBudget(262_144, ceiling))
        assertEquals(65_536, compressionOutputBudget(524_288, ceiling))
        assertEquals(131_072, compressionOutputBudget(1_048_576, ceiling))
        assertEquals(131_072, compressionOutputBudget(4_000_000, ceiling))
        assertEquals(1L, estimateCompressionTokens("abcd"))
        assertEquals(3L, estimateCompressionTokens("你好😀"))
    }

    @Test fun mimoUsesItsSupportedCompletionBudgetAndDisablesThinking() {
        val source = historyCompressionPayload(profile(), "mimo-v2.5", "instruction", "history", 65_536)
        assertEquals(65_536, source.getInt("max_completion_tokens"))
        assertFalse(source.has("max_tokens"))
        assertEquals("disabled", source.getJSONObject("thinking").getString("type"))
        source.put("thinking", JSONObject().put("type", "enabled")).put("max_tokens", 4096)
        val request = Request.Builder().url("https://example.invalid")
            .post(source.toString().toRequestBody("application/json".toMediaType())).build()
            .forCompressionResponse(profile(), 131_072)
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val result = JSONObject(buffer.readUtf8())
        assertEquals(131_072, result.getInt("max_completion_tokens"))
        assertFalse(result.has("max_tokens"))
        assertEquals("disabled", result.getJSONObject("thinking").getString("type"))
    }

    @Test fun timeoutGrowsWithSummaryBudgetButNeverBecomesUnlimited() {
        assertTrue(compressionRequestTimeoutSeconds(131_072) > compressionRequestTimeoutSeconds(16_384))
        assertEquals(600L, compressionRequestTimeoutSeconds(131_072))
        assertEquals(180L, compressionRequestTimeoutSeconds(4096))
    }

    private fun profile(format: String = ApiProfile.API_FORMAT_OPENAI, responses: Boolean = false) = ApiProfile(
        "test", "test", "", "https://example.invalid/v1", selectedModel = "test", savedModels = listOf("test"),
        apiFormat = format, useResponsesApi = responses,
    )

    @Test fun deepSeekFlashCompressionDisablesThinkingOnCompatibleEndpoints() {
        val chat = historyCompressionPayload(profile(), "DeepSeek-v4.1-flash", "instruction", "history", 4096)
        assertEquals("disabled", chat.getJSONObject("thinking").getString("type"))
        assertEquals(4096, chat.getInt("max_tokens"))
        assertFalse(chat.getBoolean("stream"))
        val responses = historyCompressionPayload(profile(responses = true), "deepseek-v4.1-flash", "instruction", "history", 4096)
        assertEquals("none", responses.getJSONObject("reasoning").getString("effort"))
        assertEquals("history", responses.getString("input"))
    }

    @Test fun compressionOwnsStreamingThinkingAndBudgetWithoutChangingSavedChatOptions() {
        val config = ModelRequestCustomization(body = """{"stream":true,"stream_options":{"include_usage":true},"thinking":{"type":"enabled"},"reasoning_effort":"high","max_tokens":10,"tools":[{}]}""")
        val profile = profile().copy(modelRequestOverrides = mapOf("deepseek-v4.1-flash" to config))
        val source = historyCompressionPayload(profile, "deepseek-v4.1-flash", "instruction", "history", 4096)
        val request = Request.Builder().url("https://example.invalid")
            .post(source.toString().toRequestBody("application/json".toMediaType())).build()
            .customizedFor(profile, "deepseek-v4.1-flash", pureMode = true)
            .forCompressionResponse(profile, 8192)
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val result = JSONObject(buffer.readUtf8())
        assertEquals("disabled", result.getJSONObject("thinking").getString("type"))
        assertEquals(8192, result.getInt("max_tokens"))
        assertFalse(result.getBoolean("stream"))
        assertFalse(result.has("tools"))
        assertFalse(result.has("stream_options"))
        assertFalse(result.has("reasoning_effort"))
        assertTrue(JSONObject(config.body).getBoolean("stream"))
        assertEquals("history", result.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun reasoningOnlyTokenExhaustionIsDiagnosedInsteadOfAcceptedAsSummary() {
        val response = JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":null,"reasoning_content":"unfinished reasoning"}}]}""")
        assertTrue(runCatching { extractCompressionSummary(response, profile()) }.exceptionOrNull() is CompressionOutputLimitException)
    }

    @Test fun partialSummariesAreRejectedForEveryProvider() {
        val bodies = listOf(
            """{"choices":[{"finish_reason":"length","message":{"content":"cut off"}}]}""",
            """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output_text":"cut off"}""",
            """{"stop_reason":"max_tokens","content":[{"type":"text","text":"cut off"}]}""",
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"cut off"}]}}]}""",
        )
        bodies.forEach { assertTrue(runCatching { extractCompressionSummary(JSONObject(it), profile()) }.exceptionOrNull() is CompressionOutputLimitException) }
    }

    @Test fun emptyRefusedAndPendingResponsesCannotReplaceHistory() {
        listOf(
            """{"choices":[{"finish_reason":"stop","message":{"content":"","reasoning_content":"thinking"}}]}""",
            """{"choices":[{"message":{"content":"","refusal":"cannot summarize"}}]}""",
            """{"status":"in_progress","output_text":"partial"}""",
        ).forEach { assertTrue(runCatching { extractCompressionSummary(JSONObject(it), profile()) }.isFailure) }
    }

    @Test fun geminiThinkingPartsAreNeverUsedAsSummaryText() {
        val response = JSONObject("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private thought"},{"text":"actual summary"}]}}]}""")
        assertEquals("actual summary", extractCompressionSummary(response, profile(ApiProfile.API_FORMAT_GEMINI)))
    }

    @Test fun formattingIsNormalizedLocallyWithoutDiscardingInformation() {
        val structured = "LYRA_STRUCTURED_CONTEXT_V2\ncurrent_goal:\n- finish"
        assertEquals(structured, normalizeCompressionSummary("```yaml\n$structured\n```"))
        assertEquals(structured, normalizeCompressionSummary(structured))
        val plain = normalizeCompressionSummary("goal\npath: /root/project")
        assertTrue(plain.startsWith("LYRA_STRUCTURED_CONTEXT_V2"))
        assertTrue(plain.contains("  goal\n  path: /root/project"))
    }

    @Test fun parallelChunksKeepChronologyAndLimitConcurrency() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstTwoStarted = CompletableDeferred<Unit>()
        val result = withTimeout(2_000) {
            mapCompressionChunks(listOf("first", "second", "third", "fourth")) { index, text ->
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    if (count == 2) firstTwoStarted.complete(Unit)
                    firstTwoStarted.await()
                    delay(if (index == 0) 50 else 1)
                    text
                } finally { active.decrementAndGet() }
            }
        }
        assertEquals(listOf("first", "second", "third", "fourth"), result)
        assertEquals(2, peak.get())
    }

    @Test fun failedChunkCancelsOtherWorkWithoutReturningPartialSummary() = runBlocking {
        val otherStarted = CompletableDeferred<Unit>()
        var otherCancelled = false
        val result = runCatching {
            mapCompressionChunks(listOf(0, 1)) { _, value ->
                if (value == 0) { otherStarted.await(); error("invalid summary") }
                try { otherStarted.complete(Unit); awaitCancellation() }
                finally { otherCancelled = true }
            }
        }
        assertTrue(result.isFailure)
        assertTrue(otherCancelled)
    }
}
