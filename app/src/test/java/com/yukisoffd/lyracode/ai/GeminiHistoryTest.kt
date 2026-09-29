package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ModelRequestCustomization
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiHistoryTest {
    private fun responseParts() = JSONArray("""[
        {"text":"Checking both cities.","thoughtSignature":"text+/signature=="},
        {"functionCall":{"name":"weather","args":{"city":"Paris"}},"thoughtSignature":"call+/signature=="},
        {"functionCall":{"name":"weather","args":{"city":"London"}}}
    ]""")

    @Test fun signedPartsSurviveRetryMergeStorageAndToolContinuation() {
        val parts = responseParts()
        val parsed = JSONObject().put("role", "assistant")
        storeGeminiParts(parsed, parts)
        val merged = JSONObject().put("role", "assistant").put("content", "Checking both cities.")
        copyGeminiParts(parsed, merged)
        val restored = JSONObject(merged.toString())
        val replayed = geminiAssistantParts("Checking both cities.", restored.toString())
        val contents = JSONArray().put(JSONObject().put("role", "model").put("parts", replayed))
            .put(JSONObject().put("role", "user").put("parts", JSONArray("""[
                {"functionResponse":{"name":"weather","response":{"temperature":20}}},
                {"functionResponse":{"name":"weather","response":{"temperature":15}}}
            ]""")))
        for (replayThinking in listOf(true, false)) {
            val request = customizedModelBody(JSONObject().put("contents", contents), ModelRequestCustomization(replayThinking = replayThinking), false)
            assertEquals(parts.toString(), request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").toString())
            assertEquals(2, request.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").length())
        }
        assertFalse(replayed.getJSONObject(2).has("thoughtSignature"))
    }

    @Test fun disablingThinkingKeepsSignedPartsIntactButRemovesUnsignedThoughts() {
        val parts = JSONArray("""[
            {"thought":true,"text":"unsigned summary"},
            {"thought":true,"text":"signed summary","thoughtSignature":"opaque=="},
            {"functionCall":{"name":"clock","args":{}},"thought_signature":"other+/=="},
            {"text":"" ,"thoughtSignature":"empty-text=="}
        ]""")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "model").put("parts", parts)))
        val result = customizedModelBody(body, ModelRequestCustomization(replayThinking = false), false)
            .getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(3, result.length())
        for (i in 0 until result.length()) assertEquals(parts.getJSONObject(i + 1).toString(), result.getJSONObject(i).toString())
        assertEquals(4, parts.length())
    }

    @Test fun snapshotsAndReplaysDoNotMutateStoredParts() {
        val parts = responseParts()
        val expected = parts.toString()
        val raw = JSONObject()
        storeGeminiParts(raw, parts)
        parts.getJSONObject(1).put("thoughtSignature", "changed")
        val copy = JSONObject()
        copyGeminiParts(raw, copy)
        raw.getJSONArray(GEMINI_REPLAY_PARTS_KEY).getJSONObject(1).put("thoughtSignature", "changed again")
        val replay = geminiAssistantParts("", copy.toString())
        assertEquals(expected, replay.toString())
        replay.getJSONObject(1).remove("thoughtSignature")
        assertEquals(expected, copy.getJSONArray(GEMINI_REPLAY_PARTS_KEY).toString())
    }

    @Test fun legacyToolCallsAndTextRemainSupportedWithoutInventingSignatures() {
        val raw = """{"tool_calls":[{"id":"old","function":{"name":"clock","arguments":"{\"zone\":\"UTC\"}"}}]}"""
        val parts = geminiAssistantParts("Checking time.", raw)
        assertEquals(2, parts.length())
        assertEquals("Checking time.", parts.getJSONObject(0).getString("text"))
        assertEquals("UTC", parts.getJSONObject(1).getJSONObject("functionCall").getJSONObject("args").getString("zone"))
        assertFalse(parts.getJSONObject(1).has("thoughtSignature"))
    }

    @Test fun missingEmptyOrMalformedHistoryUsesTextFallback() {
        for (raw in listOf(null, "broken", "{}", """{"lyra_gemini_parts":[]}""")) {
            assertEquals("answer", geminiAssistantParts("answer", raw).getJSONObject(0).getString("text"))
            assertEquals(" ", geminiAssistantParts("", raw).getJSONObject(0).getString("text"))
        }
    }
}
