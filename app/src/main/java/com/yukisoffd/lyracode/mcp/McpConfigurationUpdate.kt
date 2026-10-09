package com.yukisoffd.lyracode.mcp

import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.McpServerConfig
import org.json.JSONObject

/** Used by manage_app_config. An explicitly supplied raw_json replaces the entire document. */
internal fun mcpConfigurationUpdate(existing: McpServerConfig?, args: JSONObject): McpServerConfig {
    val initial = existing ?: McpServerConfig(
        args.optString("id").ifBlank { AppSettings.newId() }, "MCP Server", "", "",
        AppSettings.MCP_TRANSPORT_STREAMABLE_HTTP, 30, true, "{}", emptyList(),
    )
    val suppliedJson = args.has("raw_json") || args.has("config_json")
    val raw = if (suppliedJson) {
        when {
            args.has("config_json") -> args.getJSONObject("config_json").toString(2)
            else -> args.getString("raw_json").also { require(it.isNotBlank()) { "raw_json must be a complete JSON object." } }
        }
    } else {
        var json = McpJsonConfig.draft(initial)
        listOf("name" to "name", "url" to "url", "base_url" to "url", "transport" to "transport",
            "auth_key" to "authKey", "api_key" to "authKey", "key" to "authKey", "protocol_version" to "protocolVersion").forEach { (argument, field) ->
            if (args.has(argument)) {
                val value = args.getString(argument)
                json = McpJsonConfig(json).patch(field, if (field == "transport") McpJsonConfig.normalizeTransport(value) else value)
            }
        }
        json
    }
    val parsed = McpJsonConfig(raw)
    parsed.validateEndpoint()
    return parsed.project(initial).copy(
        rawJson = raw,
        timeoutSeconds = args.optInt("timeout_seconds", initial.timeoutSeconds).coerceIn(5, 300),
        enabled = args.optBoolean("enabled", initial.enabled),
        tools = if (raw == initial.rawJson) initial.tools else emptyList(),
    )
}
