package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.ai.customizedFor
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.uiText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.io.IOException


internal class AgentReachabilityService(
    private val client: OkHttpClient,
    private val reachabilityClient: OkHttpClient,
) {
    fun fetchModels(profile: ApiProfile): Result<List<String>> = runCatching {

        if (profile.apiFormat == ApiProfile.API_FORMAT_ANTHROPIC) {
            return@runCatching fetchAnthropicModels(profile)
        }
        if (profile.apiFormat == ApiProfile.API_FORMAT_GEMINI) {
            return@runCatching fetchGeminiModels(profile)
        }
        val request = Request.Builder()
            .url(profile.modelsEndpoint)
            .apply { if (profile.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${profile.apiKey}") }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("获取模型失败 ${response.code}: ${body.take(500)}")
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: JSONArray()
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.getJSONObject(index)
                    val id = item.optString("id")
                    if (id.isNotBlank()) add(id)
                }
            }.distinct().sorted()
        }
    }

    fun checkReachability(profile: ApiProfile, models: List<String>): ProviderReachabilityReport {

        val targets = models
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(profile.selectedModel) }
            .distinct()
        val providerResult = checkProviderReachability(profile)
        val modelResults = targets.map { model -> checkModelReachability(profile, model) }
        return ProviderReachabilityReport(
            providerAvailable = providerResult.available,
            providerLatencyMs = providerResult.latencyMs,
            providerMessage = providerResult.message,
            modelResults = modelResults,
        )
    }

    fun checkProviderReachability(profile: ApiProfile): ProviderReachabilityResult {

        val probe = executeReachabilityProbe(providerReachabilityRequest(profile))
        return ProviderReachabilityResult(
            available = probe.available,
            latencyMs = probe.latencyMs,
            message = probe.message,
            statusCode = probe.statusCode,
        )
    }

    fun checkModelReachability(profile: ApiProfile, model: String): ModelReachabilityResult {

        val target = model.trim().ifBlank { profile.selectedModel }
        val probe = executeReachabilityProbe(modelReachabilityRequest(profile, target))
        return ModelReachabilityResult(
            model = target,
            available = probe.available,
            latencyMs = probe.latencyMs,
            message = probe.message,
            statusCode = probe.statusCode,
        )
    }

    private fun providerReachabilityRequest(profile: ApiProfile): Request {
        val builder = Request.Builder().url(profile.modelsEndpoint).get()
        when (profile.apiFormat) {
            ApiProfile.API_FORMAT_ANTHROPIC -> builder
                .apply { if (profile.apiKey.isNotBlank()) addHeader("x-api-key", profile.apiKey) }
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
            ApiProfile.API_FORMAT_GEMINI -> builder.apply { if (profile.apiKey.isNotBlank()) addHeader("x-goog-api-key", profile.apiKey) }
            else -> builder.apply { if (profile.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${profile.apiKey}") }
        }
        return builder.build()
    }

    private fun modelReachabilityRequest(profile: ApiProfile, model: String): Request {
        return when (profile.apiFormat) {
            ApiProfile.API_FORMAT_ANTHROPIC -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 1)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                Request.Builder()
                    .url(profile.chatEndpoint)
                    .apply { if (profile.apiKey.isNotBlank()) addHeader("x-api-key", profile.apiKey) }
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
            }
            ApiProfile.API_FORMAT_GEMINI -> {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "ping")))))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 1).put("temperature", 0.0))
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                Request.Builder()
                    .url(profile.geminiGenerateContentEndpoint(model))
                    .apply { if (profile.apiKey.isNotBlank()) addHeader("x-goog-api-key", profile.apiKey) }
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
            }
            else -> {
                val requestJson = if (profile.useResponsesApi) {
                    JSONObject()
                        .put("model", model)
                        .put("input", "ping")
                        .put("max_output_tokens", 1)
                        .put("stream", false)
                } else {
                    JSONObject()
                        .put("model", model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                        .put("stream", false)
                        .also { requestJson ->
                            if (modelLooksReasoningCapable(model)) {
                                requestJson.put("max_completion_tokens", 1)
                            } else {
                                requestJson.put("max_tokens", 1)
                            }
                        }
                }
                val body = requestJson
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                Request.Builder()
                    .url(if (profile.useResponsesApi) profile.responsesEndpoint else profile.chatEndpoint)
                    .apply { if (profile.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${profile.apiKey}") }
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
            }
        }.customizedFor(profile, model)
    }

    private fun executeReachabilityProbe(request: Request): ReachabilityProbe {
        val startedAtNanos = System.nanoTime()
        return try {
            reachabilityClient.newCall(request).execute().use { response ->
                val latencyMs = elapsedMs(startedAtNanos)
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ReachabilityProbe(true, response.code, latencyMs, uiText(R.string.label_available))
                } else {
                    ReachabilityProbe(false, response.code, latencyMs, "HTTP ${response.code}: ${body.cleanProbeMessage()}")
                }
            }
        } catch (error: IOException) {
            ReachabilityProbe(false, null, elapsedMs(startedAtNanos), error.message.orEmpty().ifBlank { uiText(R.string.ui_network_unreachable_or_connection_timed_out) })
        } catch (error: Throwable) {
            ReachabilityProbe(false, null, elapsedMs(startedAtNanos), error.message.orEmpty().ifBlank { uiText(R.string.ui_check_failed) })
        }
    }

    private data class ReachabilityProbe(
        val available: Boolean,
        val statusCode: Int?,
        val latencyMs: Long,
        val message: String,
    )

    private fun fetchAnthropicModels(profile: ApiProfile): List<String> {
        val requests = listOf(
            Request.Builder()
                .url(profile.modelsEndpoint)
                .apply { if (profile.apiKey.isNotBlank()) addHeader("x-api-key", profile.apiKey) }
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build(),
            Request.Builder()
                .url(profile.modelsEndpoint)
                .apply { if (profile.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${profile.apiKey}") }
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build(),
        )
        requests.forEach { request ->
            runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("获取 Claude 模型失败 ${response.code}: ${body.take(500)}")
                    val models = parseModelIds(body).takeIf { it.isNotEmpty() } ?: error("模型列表为空")
                    models
                }
            }.getOrNull()?.let { return it }
        }
        return anthropicFallbackModels(profile)
    }

    private fun fetchGeminiModels(profile: ApiProfile): List<String> {
        val request = Request.Builder()
            .url(profile.modelsEndpoint)
            .apply { if (profile.apiKey.isNotBlank()) addHeader("x-goog-api-key", profile.apiKey) }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("获取 Gemini 模型失败 ${response.code}: ${body.take(500)}")
            val data = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val models = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val name = item.optString("name").removePrefix("models/")
                    name.takeIf { it.isNotBlank() }?.let {
                        add(it)
                    }
                }
            }.distinct().sorted()
            return models
        }
    }

    private fun parseModelIds(body: String): List<String> {
        val root = JSONObject(body)
        val arrays = listOfNotNull(root.optJSONArray("data"), root.optJSONArray("models"))
        return buildList {
            arrays.forEach { data ->
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index)
                    val id = item?.optString("id").orEmpty()
                        .ifBlank { item?.optString("name").orEmpty().removePrefix("models/") }
                    if (id.isNotBlank()) add(id)
                }
            }
            root.optString("model").takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct().sorted()
    }

    private fun anthropicFallbackModels(profile: ApiProfile): List<String> {
        return buildList {
            profile.selectedModel.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(profile.savedModels.filter { it.isNotBlank() })
            add("claude-opus-4-20250514")
            add("claude-sonnet-4-20250514")
            add("claude-3-7-sonnet-latest")
            add("claude-3-5-sonnet-latest")
            add("claude-3-5-haiku-latest")
            add("claude-3-opus-latest")
        }.distinct()
    }


    private fun modelLooksReasoningCapable(model: String): Boolean {
        val clean = model.lowercase(Locale.US)
        return listOf("o1", "o3", "o4", "gpt-5", "reason", "reasoner", "r1", "qwen3", "glm-4.5", "glm-5")
            .any { clean.contains(it) }
    }

    private fun elapsedMs(startedAtNanos: Long): Long {
        return ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
    }
    private fun String.cleanProbeMessage(): String {
        return replace(Regex("\\s+"), " ").trim().take(300).ifBlank { uiText(R.string.ui_request_failed) }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}


