package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelToolPayloadTest {
    @Test fun pureModeOmitsToolFieldsAndNeverBuildsSchemas() {
        for (automatic in listOf(true, false)) {
            val request = JSONObject().put("model", "local")
            request.addModelTools(true, automatic) { error("Must not build any tool schemas") }
            assertFalse(request.has("tools"))
            assertFalse(request.has("tool_choice"))
            assertEquals("local", request.getString("model"))
        }
    }
    @Test fun normalModePreservesProviderToolFormatAndChoice() {
        for (automatic in listOf(true, false)) {
            val tools = JSONArray().put(JSONObject().put("name", "fixture"))
            val request = JSONObject()
            request.addModelTools(false, automatic) { tools }
            assertEquals(tools.toString(), request.getJSONArray("tools").toString())
            assertEquals(automatic, request.has("tool_choice"))
            if (automatic) assertEquals("auto", request.getString("tool_choice"))
        }
    }
}
