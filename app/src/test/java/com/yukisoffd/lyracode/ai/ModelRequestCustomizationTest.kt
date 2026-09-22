package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelRequestCustomizationTest {
    private fun request() = Request.Builder().url("https://example.invalid/chat/completions")
        .header("Authorization", "Bearer secret").header("X-Remove", "old").header("Content-Type", "application/json")
        .post("""{"model":"a","temperature":0.2,"messages":[{"role":"assistant","content":"answer","reasoning_content":"reason"}]}""".toRequestBody("application/json".toMediaType())).build()
    private fun body(request: Request) = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    private fun profile(config: ModelRequestCustomization? = null) = ApiProfile("fixture", "Fixture", "secret", "https://example.invalid", selectedModel = "a", savedModels = listOf("a", "b"), modelRequestOverrides = config?.let { mapOf("a" to it) }.orEmpty())

    @Test fun headersReplaceCaseInsensitivelyAndModelsRemainIsolated() {
        val config = ModelRequestCustomization(headers = mapOf("authorization" to "Token {{apiKey}}", "X-New" to "new"), removedHeaders = setOf("x-remove"), body = """{"temperature":0.7,"extra":{"enabled":true},"nullable":null}""")
        val result = request().customizedFor(profile(config), "a")
        assertEquals(listOf("Token secret"), result.headers.values("Authorization"))
        assertNull(result.header("X-Remove")); assertEquals("new", result.header("X-New"))
        assertEquals(0.7, body(result).getDouble("temperature"), 0.001)
        assertTrue(body(result).getJSONObject("extra").getBoolean("enabled"))
        assertTrue(body(result).isNull("nullable"))
        assertEquals(0.2, body(request().customizedFor(profile(config), "b")).getDouble("temperature"), 0.001)
    }
    @Test fun replayDefaultsOnAndDisablingOnlyStripsHistoryReasoning() {
        val payload = JSONObject("""{"thinking":{"type":"enabled"},"messages":[{"role":"assistant","reasoning_content":"private","content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"answer"},{"type":"tool_use","input":{"thinking":"user argument"}}]}],"input":[{"type":"reasoning","encrypted_content":"opaque"},{"type":"message","role":"assistant","content":"answer"}],"contents":[{"role":"model","parts":[{"thought":true,"text":"private"},{"text":"answer","thoughtSignature":"opaque"}]}]}""")
        assertTrue(customizedModelBody(payload, ModelRequestCustomization(), false).getJSONArray("messages").getJSONObject(0).has("reasoning_content"))
        val stripped = customizedModelBody(payload, ModelRequestCustomization(replayThinking = false), false)
        val message = stripped.getJSONArray("messages").getJSONObject(0)
        assertFalse(message.has("reasoning_content")); assertEquals(2, message.getJSONArray("content").length())
        assertEquals("user argument", message.getJSONArray("content").getJSONObject(1).getJSONObject("input").getString("thinking"))
        assertEquals(1, stripped.getJSONArray("input").length())
        assertEquals(1, stripped.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").length())
        assertTrue(stripped.has("thinking")); assertTrue(payload.getJSONArray("messages").getJSONObject(0).has("reasoning_content"))
    }
    @Test fun explicitRemovalsAndPureModeWinWhileJsonNullSurvives() {
        val config = ModelRequestCustomization(body = """{"tools":[{}],"tool_choice":"auto","nullable":null}""", removedBodyKeys = setOf("temperature"))
        val result = body(request().customizedFor(profile(config), "a", true))
        assertFalse(result.has("temperature")); assertFalse(result.has("tools")); assertFalse(result.has("tool_choice")); assertTrue(result.isNull("nullable"))
        assertEquals("reason", result.getJSONArray("messages").getJSONObject(0).getString("reasoning_content"))
    }
    @Test fun configurationRoundTripsAndRestoreLeavesNativeRequestUntouched() {
        val values = mapOf("a" to ModelRequestCustomization(mapOf("X-Test" to "a"), setOf("X-Old"), "{\"extra\":true}", setOf("temperature"), false))
        assertEquals(values, ModelRequestCustomization.decodeMap(ModelRequestCustomization.encodeMap(values)))
        val original = request()
        val tagged = original.customizedFor(profile(), "a")
        assertEquals(original.headers, tagged.headers)
        assertSame(original.body, tagged.body)
        assertEquals("a", tagged.tag(ModelAuditTag::class.java)?.model)
        val preview = ModelRequestPreviews.get(profile(), "a")
        assertFalse(preview.headers.values.any { it.contains("secret") })
        assertEquals("Bearer {{apiKey}}", preview.headers["Authorization"])
    }
}
