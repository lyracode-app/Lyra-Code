package com.yukisoffd.lyracode.data

import org.json.JSONArray
import org.json.JSONObject

/** Per-model request deltas. JSON null is a value; removals are stored separately. */
data class ModelRequestCustomization(
    val headers: Map<String, String> = emptyMap(),
    val removedHeaders: Set<String> = emptySet(),
    val body: String = "{}",
    val removedBodyKeys: Set<String> = emptySet(),
    val replayThinking: Boolean = true,
) {
    fun toJson() = JSONObject().put("headers", JSONObject(headers)).put("removedHeaders", JSONArray(removedHeaders.toList()))
        .put("body", JSONObject(body)).put("removedBodyKeys", JSONArray(removedBodyKeys.toList())).put("replayThinking", replayThinking)
    companion object {
        fun fromJson(value: JSONObject): ModelRequestCustomization {
            fun set(key: String): Set<String> = value.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val headers = value.optJSONObject("headers") ?: JSONObject()
            return ModelRequestCustomization(headers.keys().asSequence().associateWith { headers.getString(it) }, set("removedHeaders"),
                (value.optJSONObject("body") ?: JSONObject()).toString(), set("removedBodyKeys"), value.optBoolean("replayThinking", true))
        }
        fun encodeMap(values: Map<String, ModelRequestCustomization>) = JSONObject().apply { values.forEach { (model, config) -> put(model, config.toJson()) } }
        fun decodeMap(value: JSONObject?): Map<String, ModelRequestCustomization> = value?.keys()?.asSequence()?.mapNotNull { model ->
            runCatching { model to fromJson(value.getJSONObject(model)) }.getOrNull()
        }?.toMap().orEmpty()
    }
}
