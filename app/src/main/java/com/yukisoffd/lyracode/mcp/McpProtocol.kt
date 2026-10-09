package com.yukisoffd.lyracode.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Locale

internal object McpProtocol {
    const val LEGACY = "2025-11-25"
    const val MODERN = "2026-07-28"
    const val VERSION_META = "io.modelcontextprotocol/protocolVersion"
    const val CLIENT_INFO_META = "io.modelcontextprotocol/clientInfo"
    const val CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities"
    val legacyVersions = listOf(LEGACY, "2025-06-18", "2025-03-26", "2024-11-05")
    val supportedVersions = listOf(MODERN) + legacyVersions

    fun clientInfo() = JSONObject().put("name", "Lyra Code Android").put("version", "1")
    fun serverInfo() = JSONObject().put("name", "Lyra Code").put("version", "1")

    fun request(id: Long?, method: String, params: JSONObject, version: String): JSONObject {
        val copy = JSONObject(params.toString())
        if (version == MODERN) {
            val meta = copy.optJSONObject("_meta") ?: JSONObject()
            meta.put(VERSION_META, version)
                .put(CLIENT_INFO_META, clientInfo())
                .put(CLIENT_CAPABILITIES_META, JSONObject())
            copy.put("_meta", meta)
        }
        return JSONObject().put("jsonrpc", "2.0").apply { if (id != null) put("id", id) }
            .put("method", method).put("params", copy)
    }

    fun error(id: Any?, code: Int, message: String, data: JSONObject? = null): JSONObject = JSONObject()
        .put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message).apply { if (data != null) put("data", data) })

    fun unsupportedVersion(id: Any?, requested: String) = error(id, -32022, "Unsupported protocol version", JSONObject()
        .put("requested", requested).put("supported", JSONArray(supportedVersions)))

    fun encodeHeader(value: String): String = if (value != value.trim() ||
        value.any { it.code !in 0x20..0x7e && it != '\t' } ||
        (value.startsWith("=?base64?") && value.endsWith("?="))
    ) "=?base64?${Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))}?=" else value

    fun decodeHeader(value: String): String = if (value.startsWith("=?base64?") && value.endsWith("?=")) {
        String(Base64.getDecoder().decode(value.removePrefix("=?base64?").removeSuffix("?=")), Charsets.UTF_8)
    } else value

    private data class HeaderParameter(val name: String, val path: List<String>, val type: String)

    private fun headerParameters(schema: JSONObject): List<HeaderParameter> {
        val parameters = mutableListOf<HeaderParameter>()
        val names = mutableSetOf<String>()
        fun visit(value: Any?, path: List<String>, reachable: Boolean) {
            when (value) {
                is JSONObject -> {
                    if (value.has("x-mcp-header")) {
                        val name = value.get("x-mcp-header")
                        val type = value.optString("type")
                        require(reachable && path.isNotEmpty() && name is String && name.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) &&
                            type in setOf("string", "integer", "boolean") && names.add(name.lowercase(Locale.US))) { "Invalid x-mcp-header annotation." }
                        parameters += HeaderParameter(name, path, type)
                    }
                    value.keys().forEach { key ->
                        val child = value.opt(key)
                        if (key == "properties" && child is JSONObject) {
                            child.keys().forEach { property -> visit(child.opt(property), path + property, reachable) }
                        } else if (key in setOf("items", "prefixItems", "oneOf", "anyOf", "allOf", "not", "if", "then", "else", "\$defs", "definitions", "additionalProperties", "patternProperties")) {
                            visit(child, path, false)
                        }
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), path, false)
            }
        }
        visit(schema, emptyList(), true)
        return parameters
    }

    fun validToolHeaders(schema: JSONObject): Boolean = runCatching { headerParameters(schema) }.isSuccess

    fun toolHeaders(schema: JSONObject, arguments: JSONObject): Map<String, String> = buildMap {
        headerParameters(schema).forEach { parameter ->
            var value: Any? = arguments
            parameter.path.forEach { field -> value = (value as? JSONObject)?.opt(field) }
            if (value == null || value == JSONObject.NULL) return@forEach
            val text = when (parameter.type) {
                "string" -> { require(value is String); value.toString() }
                "boolean" -> { require(value is Boolean); value.toString() }
                else -> {
                    require(value is Number)
                    val number = (value as Number).toDouble()
                    require(number.isFinite() && number % 1.0 == 0.0 && kotlin.math.abs(number) <= 9007199254740991.0)
                    (value as Number).toLong().toString()
                }
            }
            put("Mcp-Param-${parameter.name}", encodeHeader(text))
        }
    }
}
