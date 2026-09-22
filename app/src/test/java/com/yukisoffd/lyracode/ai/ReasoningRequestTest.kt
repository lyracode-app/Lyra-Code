package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReasoningRequestTest {
    private fun profile(model: String, responses: Boolean = false, format: String = ApiProfile.API_FORMAT_OPENAI) =
        ApiProfile("fixture", "fixture", "", "https://example.invalid", selectedModel = model, savedModels = listOf(model), apiFormat = format, useResponsesApi = responses)

    @Test fun explicitEffortReachesDeepSeekAndAliasedModelsWithBothOpenAiProtocols() {
        for (model in listOf("deepseek-flash", "deepseek-v4-pro", "provider-custom-alias", "gpt-5")) {
            for (responses in listOf(false, true)) for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
                val profile = profile(model, responses)
                val body = JSONObject().put("model", model)
                applyReasoningDepth(body, profile, effort)
                val request = Request.Builder().url("https://example.invalid/chat")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build().customizedFor(profile, model)
                val actual = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertEquals(effort, if (responses) actual.getJSONObject("reasoning").getString("effort") else actual.getString("reasoning_effort"))
            }
        }
    }

    @Test fun autoDoesNotInjectAndCustomizationRemainsFinalAuthority() {
        val profile = profile("deepseek-flash")
        val auto = JSONObject()
        applyReasoningDepth(auto, profile, AppSettings.REASONING_AUTO)
        assertEquals(0, auto.length())
        val body = JSONObject()
        applyReasoningDepth(body, profile, "max")
        assertEquals("low", customizedModelBody(body, ModelRequestCustomization(body = """{"reasoning_effort":"low"}"""), false).getString("reasoning_effort"))
        assertFalse(customizedModelBody(body, ModelRequestCustomization(removedBodyKeys = setOf("reasoning_effort")), false).has("reasoning_effort"))
    }

    @Test fun nestedProtocolSettingsSurviveEffortInjection() {
        val responses = JSONObject("""{"reasoning":{"summary":"detailed","custom":true}}""")
        applyReasoningDepth(responses, profile("alias", true), "high")
        assertEquals("detailed", responses.getJSONObject("reasoning").getString("summary"))
        assertTrue(responses.getJSONObject("reasoning").getBoolean("custom"))
        val anthropic = JSONObject("""{"output_config":{"format":{"type":"json_schema"}}}""")
        applyReasoningDepth(anthropic, profile("deepseek-flash", format = ApiProfile.API_FORMAT_ANTHROPIC), "max")
        assertEquals("max", anthropic.getJSONObject("output_config").getString("effort"))
        assertTrue(anthropic.getJSONObject("output_config").has("format"))
    }
}
