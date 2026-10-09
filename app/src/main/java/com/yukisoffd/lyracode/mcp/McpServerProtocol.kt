package com.yukisoffd.lyracode.mcp

import org.json.JSONArray
import org.json.JSONObject

internal data class McpServerReply(val body: JSONObject?, val status: Int = if (body == null) 202 else 200)

/** Request-local protocol state: modern and initialize-based clients can share one endpoint. */
internal class McpServerProtocol(
    private val tools: () -> JSONArray,
    private val callTool: (JSONObject) -> JSONObject,
) {
    fun handle(body: String, headers: Map<String, String>): McpServerReply {
        val payload = runCatching { JSONObject(body) }.getOrNull()
            ?: return McpServerReply(McpProtocol.error(null, -32700, "JSON parse error"), 400)
        val hasId = payload.has("id")
        val id = payload.opt("id")
        val method = payload.optString("method")
        if (payload.optString("jsonrpc") != "2.0" || method.isBlank() ||
            (hasId && (id == JSONObject.NULL || (id !is String && id !is Number))) ||
            (payload.has("params") && payload.optJSONObject("params") == null)
        ) return McpServerReply(McpProtocol.error(id, -32600, "Invalid JSON-RPC request"), 400)
        val params = payload.optJSONObject("params") ?: JSONObject()
        val meta = params.optJSONObject("_meta")
        val bodyVersion = meta?.optString(McpProtocol.VERSION_META).orEmpty()
        val headerVersion = headers["mcp-protocol-version"].orEmpty()
        val modern = bodyVersion.isNotBlank() || headerVersion == McpProtocol.MODERN
        val version = if (modern) bodyVersion.ifBlank { headerVersion } else headerVersion.ifBlank { "2025-03-26" }
        if (modern) {
            if (bodyVersion != headerVersion || headerVersion.isBlank() ||
                headers["mcp-method"] != method ||
                meta?.optJSONObject(McpProtocol.CLIENT_INFO_META) == null ||
                meta.optJSONObject(McpProtocol.CLIENT_CAPABILITIES_META) == null
            ) return McpServerReply(McpProtocol.error(id, -32020, "Missing or mismatched MCP request metadata"), 400)
            if (version != McpProtocol.MODERN) return McpServerReply(McpProtocol.unsupportedVersion(id, version), 400)
            if (method == "tools/call") {
                val headerName = headers["mcp-name"]?.let { runCatching { McpProtocol.decodeHeader(it) }.getOrNull() }
                if (headerName != params.optString("name")) return McpServerReply(McpProtocol.error(id, -32020, "Mcp-Name does not match params.name"), 400)
            }
        } else if (method != "initialize" && version !in McpProtocol.legacyVersions) {
            return McpServerReply(McpProtocol.unsupportedVersion(id, version), 400)
        }
        if (!hasId) return McpServerReply(null)
        val result = try {
            when (method) {
                "server/discover" -> if (modern) JSONObject()
                    .put("supportedVersions", JSONArray(McpProtocol.supportedVersions))
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("_meta", JSONObject().put("io.modelcontextprotocol/serverInfo", McpProtocol.serverInfo()))
                else return McpServerReply(McpProtocol.error(id, -32601, "Unknown method: $method"))
                "initialize" -> {
                    if (modern) return McpServerReply(McpProtocol.error(id, -32601, "2026-07-28 uses server/discover instead of initialize"), 404)
                    val requested = params.optString("protocolVersion")
                    JSONObject().put("protocolVersion", requested.takeIf { it in McpProtocol.legacyVersions } ?: McpProtocol.LEGACY)
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", McpProtocol.serverInfo())
                }
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", tools())
                "tools/call" -> {
                    if (params.optString("name").isBlank() || (params.has("arguments") && params.optJSONObject("arguments") == null)) {
                        return McpServerReply(McpProtocol.error(id, -32602, "Invalid tools/call parameters"))
                    }
                    val known = tools().let { array -> (0 until array.length()).any { array.optJSONObject(it)?.optString("name") == params.optString("name") } }
                    if (!known) return McpServerReply(McpProtocol.error(id, -32602, "Unknown tool: ${params.optString("name")}"))
                    callTool(params)
                }
                else -> return McpServerReply(McpProtocol.error(id, -32601, "Unknown method: $method"), if (modern) 404 else 200)
            }
        } catch (error: Exception) {
            return McpServerReply(McpProtocol.error(id, -32603, error.message ?: "Internal server error"))
        }
        if (modern) result.put("resultType", "complete")
        return McpServerReply(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
    }
}
