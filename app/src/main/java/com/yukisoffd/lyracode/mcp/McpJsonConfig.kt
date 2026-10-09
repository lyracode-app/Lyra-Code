package com.yukisoffd.lyracode.mcp

import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.McpServerConfig
import org.json.JSONObject
import java.util.Locale

/** The stored JSON is the connection source of truth; form fields are only its projection. */
internal class McpJsonConfig(rawJson: String) {
    val root = JSONObject(rawJson.ifBlank { "{}" })
    val serverKey: String
    val node: JSONObject

    init {
        val servers = root.optJSONObject("mcpServers")
        require(!root.has("mcpServers") || servers != null) { "mcpServers must be a JSON object." }
        if (servers != null) {
            require(servers.length() == 1) { "Each MCP configuration must contain exactly one server in mcpServers." }
            serverKey = servers.keys().next()
            node = servers.getJSONObject(serverKey)
        } else {
            serverKey = ""
            node = root
        }
    }

    val name: String get() = node.optString("name").ifBlank { serverKey }
    val url: String get() {
        val url = node.optString("url").ifBlank { if (node !== root) root.optString("url") else "" }
        val baseUrl = node.optString("baseUrl").ifBlank { if (node !== root) root.optString("baseUrl") else "" }
        require(url.isBlank() || baseUrl.isBlank() || url == baseUrl) { "url and baseUrl must match. Keep only the endpoint field required by your server." }
        return url.ifBlank { baseUrl }
    }
    val transport: String get() = normalizeTransport(node.optString("type").ifBlank { node.optString("transport") })
    val headers: Map<String, String> get() {
        val result = linkedMapOf<String, String>()
        fun add(owner: JSONObject) {
            require(!owner.has("headers") || owner.optJSONObject("headers") != null) { "headers must be a JSON object." }
            owner.optJSONObject("headers")?.let { headers ->
                headers.keys().forEach { key ->
                    val value = headers.get(key)
                    require(value is String) { "Header $key must be a string." }
                    result.keys.firstOrNull { it.equals(key, true) }?.let(result::remove)
                    result[key] = value
                }
            }
        }
        if (node !== root && !node.has("headers")) add(root)
        add(node)
        return result
    }
    val authKey: String get() = headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value.orEmpty()
    val protocolVersion: String get() = node.optString("protocolVersion")
        .ifBlank { root.optString("protocolVersion") }
        .ifBlank { headers.entries.firstOrNull { it.key.equals("MCP-Protocol-Version", true) }?.value.orEmpty() }
        .also { version ->
            val header = headers.entries.firstOrNull { it.key.equals("MCP-Protocol-Version", true) }?.value
            require(header == null || header == version) { "protocolVersion and MCP-Protocol-Version must match." }
        }

    fun validateEndpoint() {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "MCP URL must use http:// or https://." }
        val builder = okhttp3.Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        require(transport == AppSettings.MCP_TRANSPORT_STREAMABLE_HTTP || protocolVersion != McpProtocol.MODERN) {
            "2026-07-28 requires Streamable HTTP. Use a legacy protocol version for HTTP+SSE."
        }
    }

    fun project(server: McpServerConfig): McpServerConfig = server.copy(
        name = name.ifBlank { server.name.ifBlank { "MCP Server" } },
        url = url,
        authKey = authKey,
        transport = transport,
    )

    /** Patch only an explicitly edited form field, leaving unrelated extensions intact. */
    fun patch(field: String, value: String): String {
        when (field) {
            "url" -> {
                val keys = listOf("url", "baseUrl").filter(node::has).ifEmpty { listOf("url") }
                keys.forEach { node.put(it, value) }
                if (node !== root) listOf("url", "baseUrl").filter(root::has).forEach { root.put(it, value) }
            }
            "authKey" -> {
                val headers = node.optJSONObject("headers") ?: JSONObject(this.headers)
                headers.keys().asSequence().filter { it.equals("Authorization", true) }.toList().forEach(headers::remove)
                if (value.isNotEmpty()) headers.put("Authorization", value)
                node.put("headers", headers)
            }
            "transport" -> {
                node.put("type", if (value == AppSettings.MCP_TRANSPORT_SSE) "sse" else "streamableHttp")
                if (node.has("transport")) node.put("transport", value)
            }
            "protocolVersion" -> {
                root.remove("protocolVersion")
                node.remove("protocolVersion")
                listOf(root, node).distinct().forEach { owner ->
                    owner.optJSONObject("headers")?.let { headers ->
                        headers.keys().asSequence().filter { it.equals("MCP-Protocol-Version", true) }.toList().forEach(headers::remove)
                    }
                }
                if (value.isNotBlank()) node.put("protocolVersion", value)
            }
            else -> node.put(field, value)
        }
        return root.toString(2)
    }

    companion object {
        fun normalizeTransport(value: String): String = when (value.lowercase(Locale.US).replace("-", "_").replace(" ", "")) {
            "sse" -> AppSettings.MCP_TRANSPORT_SSE
            "", "http", "streamablehttp", "streamable_http" -> AppSettings.MCP_TRANSPORT_STREAMABLE_HTTP
            else -> error("Unsupported MCP transport: $value. This app supports Streamable HTTP and HTTP+SSE.")
        }

        fun draft(server: McpServerConfig): String {
            val parsed = McpJsonConfig(server.rawJson)
            if (parsed.url.isNotBlank()) return server.rawJson
            var raw = parsed.patch("url", server.url)
            raw = McpJsonConfig(raw).patch("name", server.name)
            raw = McpJsonConfig(raw).patch("transport", server.transport)
            if (!parsed.node.has("headers") && !parsed.root.has("headers") && server.authKey.isNotBlank()) {
                raw = McpJsonConfig(raw).patch("authKey", legacyAuthorization(server.authKey))
            }
            return raw
        }

        fun legacyAuthorization(key: String): String = if (key.startsWith("Bearer ", true)) key else "Bearer $key"
    }
}
