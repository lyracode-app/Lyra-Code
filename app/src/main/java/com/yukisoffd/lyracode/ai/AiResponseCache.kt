package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class AiCachedResponse(
    val content: String,
    val thinking: String,
    val rawMessage: String,
)

class AiResponseCache(
    rootDir: File,
    private val ttlMillis: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxEntries: Int = 500,
) {
    private val dir = File(rootDir, "ai_response_cache")

    @Synchronized
    fun get(profile: ApiProfile, request: JSONObject, conversationScope: String): AiCachedResponse? {
        val key = cacheKey(profile, request, conversationScope)
        val file = File(dir, "$key.json")
        if (!file.isFile) return null
        val now = System.currentTimeMillis()
        val entry = runCatching { JSONObject(file.readText()) }.getOrNull() ?: run {
            file.delete()
            return null
        }
        val createdAt = entry.optLong("created_at", 0L)
        if (createdAt <= 0L || now - createdAt > ttlMillis) {
            file.delete()
            return null
        }
        file.setLastModified(now)
        return AiCachedResponse(
            content = entry.optString("content"),
            thinking = entry.optString("thinking"),
            rawMessage = entry.optString("raw_message"),
        ).takeIf { it.rawMessage.isNotBlank() }
    }

    @Synchronized
    fun put(profile: ApiProfile, request: JSONObject, conversationScope: String, response: AiCachedResponse) {
        if (response.rawMessage.isBlank()) return
        dir.mkdirs()
        val key = cacheKey(profile, request, conversationScope)
        val now = System.currentTimeMillis()
        val entry = JSONObject()
            .put("created_at", now)
            .put("last_accessed_at", now)
            .put("key", key)
            .put("content", response.content)
            .put("thinking", response.thinking)
            .put("raw_message", response.rawMessage)
        val target = File(dir, "$key.json")
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(entry.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        prune(now)
    }

    fun cacheKey(profile: ApiProfile, request: JSONObject, conversationScope: String = ""): String {
        val normalized = JSONObject()
            .put("cache_version", CACHE_VERSION)
            // Older DeepSeek entries may have been saved after a truncated turn.
            .apply { if (profile.usesOfficialDeepSeekEndpoint()) put("validated_deepseek_turn", true) }
            .put("conversation_scope", conversationScope.trim())
            .put("endpoint", normalizeEndpoint(profile.chatEndpoint))
            .put("model", request.optString("model").trim())
            .put("temperature", request.opt("temperature") ?: JSONObject.NULL)
            .put("reasoning_effort", request.opt("reasoning_effort") ?: JSONObject.NULL)
            .put("reasoning", request.opt("reasoning") ?: JSONObject.NULL)
            .put("output_config", request.opt("output_config") ?: JSONObject.NULL)
            .put("tool_choice", request.opt("tool_choice") ?: JSONObject.NULL)
            .put("messages", normalizeMessages(request.optJSONArray("messages") ?: JSONArray()))
            .put("tools", normalizeTools(request.optJSONArray("tools") ?: JSONArray()))
        return sha256(canonicalJson(normalized))
    }

    private fun normalizeMessages(messages: JSONArray): JSONArray {
        val output = JSONArray()
        val toolIdMap = mutableMapOf<String, String>()
        var toolCounter = 0
        for (index in 0 until messages.length()) {
            val raw = messages.optJSONObject(index) ?: continue
            val role = raw.optString("role")
            val item = JSONObject().put("role", role)
            if (raw.has("content")) {
                item.put("content", normalizeText(raw.optString("content")))
            }
            when (role) {
                "assistant" -> {
                    val calls = raw.optJSONArray("tool_calls")
                    if (calls != null && calls.length() > 0) {
                        val normalizedCalls = JSONArray()
                        for (callIndex in 0 until calls.length()) {
                            val call = calls.optJSONObject(callIndex) ?: continue
                            val oldId = call.optString("id")
                            val newId = "tool_${toolCounter++}"
                            if (oldId.isNotBlank()) toolIdMap[oldId] = newId
                            normalizedCalls.put(normalizeToolCall(call, newId))
                        }
                        item.put("tool_calls", normalizedCalls)
                    }
                }
                "tool" -> {
                    val oldId = raw.optString("tool_call_id")
                    item.put("tool_call_id", toolIdMap[oldId] ?: oldId.ifBlank { "tool_unknown" })
                }
            }
            output.put(item)
        }
        return output
    }

    private fun normalizeToolCall(call: JSONObject, id: String): JSONObject {
        val function = call.optJSONObject("function") ?: JSONObject()
        return JSONObject()
            .put("id", id)
            .put("type", call.optString("type").ifBlank { "function" })
            .put(
                "function",
                JSONObject()
                    .put("name", function.optString("name").trim())
                    .put("arguments", normalizeJsonText(function.optString("arguments"))),
            )
    }

    private fun normalizeTools(tools: JSONArray): JSONArray {
        val normalized = mutableListOf<JSONObject>()
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)?.let { normalized += sortedJsonObject(it) }
        }
        return JSONArray().apply {
            normalized.sortedBy { it.optJSONObject("function")?.optString("name").orEmpty() }.forEach { put(it) }
        }
    }

    private fun normalizeJsonText(text: String): String {
        val trimmed = normalizeText(text).trim()
        if (trimmed.isBlank()) return "{}"
        return runCatching { canonicalJson(JSONObject(trimmed)) }
            .recoverCatching { canonicalJson(JSONArray(trimmed)) }
            .getOrElse { trimmed }
    }

    private fun sortedJsonObject(source: JSONObject): JSONObject {
        val output = JSONObject()
        source.keys().asSequence().sorted().forEach { key ->
            if (key == "stream") return@forEach
            output.put(key, normalizeJsonValue(source.opt(key)))
        }
        return output
    }

    private fun normalizeJsonValue(value: Any?): Any {
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> sortedJsonObject(value)
            is JSONArray -> JSONArray().apply {
                for (index in 0 until value.length()) put(normalizeJsonValue(value.opt(index)))
            }
            is String -> normalizeText(value)
            else -> value
        }
    }

    private fun canonicalJson(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> sortedJsonObject(value).toString()
            is JSONArray -> JSONArray().apply {
                for (index in 0 until value.length()) put(normalizeJsonValue(value.opt(index)))
            }.toString()
            is String -> JSONObject.quote(normalizeText(value))
            else -> value.toString()
        }
    }

    private fun normalizeText(value: String): String {
        return value.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
    }

    private fun normalizeEndpoint(url: String): String {
        return url.trim().trimEnd('/').lowercase(Locale.US)
    }

    private fun prune(now: Long) {
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        files.filter { now - it.lastModified() > ttlMillis }.forEach { it.delete() }
        files.filter { it.exists() }
            .sortedByDescending { it.lastModified() }
            .drop(maxEntries)
            .forEach { it.delete() }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val CACHE_VERSION = 6
    }
}
