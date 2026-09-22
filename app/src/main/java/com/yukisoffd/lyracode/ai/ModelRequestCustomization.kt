package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

internal data class ModelRequestPreview(val headers: Map<String, String>, val body: String, val captured: Boolean = false)

/** In-memory native request snapshots, before user deltas; no API credentials are retained. */
internal object ModelRequestPreviews {
    private val values = linkedMapOf<String, ModelRequestPreview>()
    @Synchronized fun record(profile: ApiProfile, model: String, request: Request, body: JSONObject) {
        val headers = request.headers.names().associateWith { name ->
            val value = request.header(name).orEmpty()
            if (profile.apiKey.isNotBlank()) value.replace(profile.apiKey, "{{apiKey}}") else value
        }
        // Large conversation payloads are represented by their native field shapes in the editor.
        val preview = JSONObject(body.toString()).apply {
            listOf("messages", "input", "contents", "tools").forEach { key ->
                if (has(key)) put(key, if (opt(key) is JSONArray) JSONArray() else "")
            }
            listOf("system", "instructions", "systemInstruction").forEach { key ->
                if (has(key)) put(key, if (opt(key) is JSONObject) JSONObject() else "")
            }
        }
        val id = "${profile.id}\u0000$model"
        values.remove(id)
        values[id] = ModelRequestPreview(headers, preview.toString(), true)
        while (values.size > 16) values.remove(values.keys.first())
    }
    @Synchronized fun get(profile: ApiProfile, model: String): ModelRequestPreview = values["${profile.id}\u0000$model"] ?: defaults(profile, model)
    fun defaults(profile: ApiProfile, model: String): ModelRequestPreview {
        val headers = linkedMapOf("Content-Type" to "application/json")
        val body = JSONObject()
        when (profile.apiFormat) {
            ApiProfile.API_FORMAT_ANTHROPIC -> {
                if (profile.apiKey.isNotBlank()) headers["x-api-key"] = "{{apiKey}}"
                headers["anthropic-version"] = "2023-06-01"
                body.put("model", model).put("max_tokens", 4096).put("messages", JSONArray()).put("stream", true).put("temperature", 0.2)
            }
            ApiProfile.API_FORMAT_GEMINI -> {
                if (profile.apiKey.isNotBlank()) headers["x-goog-api-key"] = "{{apiKey}}"
                body.put("contents", JSONArray()).put("generationConfig", JSONObject().put("temperature", 0.2))
            }
            else -> {
                if (profile.apiKey.isNotBlank()) headers["Authorization"] = "Bearer {{apiKey}}"
                body.put("model", model).put("stream", true)
                if (profile.useResponsesApi) body.put("input", JSONArray()).put("store", false)
                else body.put("messages", JSONArray()).put("temperature", 0.2)
            }
        }
        return ModelRequestPreview(headers, body.toString())
    }
}

internal fun customizedModelBody(source: JSONObject, config: ModelRequestCustomization, pureMode: Boolean): JSONObject {
    val result = JSONObject(source.toString())
    config.removedBodyKeys.forEach(result::remove)
    val overrides = JSONObject(config.body)
    overrides.keys().forEach { key -> result.put(key, overrides.get(key)) }
    if (!config.replayThinking) {
        fun stripMessages(key: String) {
            val messages = result.optJSONArray(key) ?: return
            val kept = JSONArray()
            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i)
                if (message == null) { kept.put(messages.get(i)); continue }
                if (key == "input" && message.optString("type") == "reasoning") continue
                if (message.optString("role") in setOf("assistant", "model")) {
                    listOf("reasoning_content", "thinking_content", "reasoning", "thinking", "reasoning_details").forEach(message::remove)
                    for (field in listOf("content", "parts")) {
                        val blocks = message.optJSONArray(field) ?: continue
                        val clean = JSONArray()
                        for (j in 0 until blocks.length()) {
                            val block = blocks.optJSONObject(j)
                            if (block != null && (block.optString("type") in setOf("thinking", "redacted_thinking", "reasoning") || block.optBoolean("thought"))) continue
                            block?.remove("thoughtSignature")
                            block?.remove("thought_signature")
                            clean.put(block ?: blocks.get(j))
                        }
                        message.put(field, clean)
                    }
                }
                kept.put(message)
            }
            result.put(key, kept)
        }
        listOf("messages", "input", "contents").forEach(::stripMessages)
    }
    // Pure mode remains a hard guarantee even when a stored customization contains tool fields.
    if (pureMode) listOf("tools", "tool_choice", "functions", "function_call").forEach(result::remove)
    return result
}

internal fun Request.customizedFor(profile: ApiProfile, model: String, pureMode: Boolean = false): Request {
    val tagged = newBuilder().tag(ModelAuditTag::class.java, ModelAuditTag(profile.id, model)).build()
    val buffer = Buffer()
    body?.writeTo(buffer) ?: return tagged
    val original = runCatching { JSONObject(buffer.readUtf8()) }.getOrNull() ?: return tagged
    ModelRequestPreviews.record(profile, model, this, original)
    val config = profile.modelRequestOverrides[model] ?: return tagged
    val payload = customizedModelBody(original, config, pureMode)
    val builder = tagged.newBuilder()
    config.removedHeaders.forEach(builder::removeHeader)
    config.headers.forEach { (name, value) -> builder.header(name, value.replace("{{apiKey}}", profile.apiKey)) }
    val contentType = builder.build().header("Content-Type")
    return builder.method(method, payload.toString().toRequestBody(contentType?.toMediaType())).build()
}
