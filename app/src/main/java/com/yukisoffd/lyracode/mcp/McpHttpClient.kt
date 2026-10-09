package com.yukisoffd.lyracode.mcp

import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class McpRpcException(val response: JSONObject, val httpStatus: Int = 200) : IOException(
    response.optJSONObject("error")?.toString() ?: "HTTP $httpStatus: ${response.toString().take(1000)}",
) {
    val code: Int get() = response.optJSONObject("error")?.optInt("code") ?: 0
}

/** Both protocol eras share transport code, but only legacy connections hold a session. */
internal class McpHttpClient {
    private class Connection(val config: McpServerConfig) {
        val json = McpJsonConfig(config.rawJson)
        val endpoint = json.url.ifBlank { config.url }
        val transport = if (json.url.isBlank()) config.transport else json.transport
        val client = OkHttpClient.Builder()
            .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        var version = json.protocolVersion.ifBlank { McpProtocol.MODERN }
        var sessionId: String? = null
        var initialized = false
        var sseResponse: Response? = null
        var sseReader: BufferedReader? = null
        var postEndpoint: String? = null

        fun close() { sseResponse?.close(); client.connectionPool.evictAll() }
    }

    private val ids = AtomicLong(1)
    private val connections = ConcurrentHashMap<String, Connection>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun listTools(server: McpServerConfig): List<McpToolDefinition> = withConnection(server, refresh = true) { connection ->
        val tools = mutableListOf<McpToolDefinition>()
        val cursors = mutableSetOf<String>()
        var cursor = ""
        do {
            val params = JSONObject().apply { if (cursor.isNotBlank()) put("cursor", cursor) }
            val result = rpc(connection, "tools/list", params)
            val items = result.optJSONArray("tools")
            if (items != null) for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val schema = item.optJSONObject("inputSchema") ?: JSONObject()
                if (item.optString("name").isNotBlank() && (connection.version != McpProtocol.MODERN || McpProtocol.validToolHeaders(schema))) {
                    tools += McpToolDefinition(item.getString("name"), item.optString("description"), schema.toString())
                }
            }
            cursor = result.optString("nextCursor")
            require(cursor.isBlank() || cursors.add(cursor)) { "MCP server returned a repeated tools/list cursor." }
        } while (cursor.isNotBlank())
        tools
    }

    suspend fun callTool(server: McpServerConfig, tool: McpToolDefinition, arguments: JSONObject): JSONObject = withConnection(server) { connection ->
        val params = JSONObject().put("name", tool.name).put("arguments", arguments)
        val headers = if (connection.version == McpProtocol.MODERN) McpProtocol.toolHeaders(JSONObject(tool.inputSchema), arguments) else emptyMap()
        rpc(connection, "tools/call", params, headers)
    }

    fun invalidate(id: String) { connections.remove(id)?.close() }

    private suspend fun <T> withConnection(server: McpServerConfig, refresh: Boolean = false, block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        locks.getOrPut(server.id) { Mutex() }.withLock {
            val identity = server.copy(tools = emptyList(), enabled = true)
            var connection = connections[server.id]
            if (refresh || connection?.config != identity) {
                invalidate(server.id)
                connection = Connection(identity)
                connections[server.id] = connection
            }
            val active = requireNotNull(connection)
            try {
                if (!active.initialized) connect(active)
                block(active)
            } catch (error: Exception) {
                // Never replay tools/call: a transport failure can occur after its side effects.
                invalidate(server.id)
                throw error
            }
        }
    }

    private fun connect(connection: Connection) {
        require(connection.endpoint.startsWith("http://", true) || connection.endpoint.startsWith("https://", true)) { "MCP URL must use http:// or https://." }
        val pin = connection.json.protocolVersion
        require(pin.isBlank() || pin in McpProtocol.supportedVersions) { "Unsupported MCP protocol version: $pin" }
        if (connection.transport == AppSettings.MCP_TRANSPORT_SSE) {
            require(pin != McpProtocol.MODERN) { "2026-07-28 requires Streamable HTTP." }
            connection.version = pin.ifBlank { McpProtocol.LEGACY }
            openLegacySse(connection)
        } else if (pin.isBlank() || pin == McpProtocol.MODERN) {
            try {
                val discovered = rpc(connection, "server/discover", JSONObject())
                val versions = discovered.optJSONArray("supportedVersions") ?: error("server/discover did not return supportedVersions.")
                val offered = (0 until versions.length()).map { versions.getString(it) }
                if (McpProtocol.MODERN in offered) {
                    connection.initialized = true
                    return
                }
                require(pin.isBlank()) { "The server does not support $pin." }
                connection.version = McpProtocol.legacyVersions.firstOrNull { it in offered } ?: error("No compatible MCP protocol version.")
            } catch (error: McpRpcException) {
                if (pin.isNotBlank()) throw error
                if (error.code == -32022) {
                    val supported = error.response.optJSONObject("error")?.optJSONObject("data")?.optJSONArray("supported")
                    val offered = supported?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
                    connection.version = McpProtocol.legacyVersions.firstOrNull { it in offered } ?: throw error
                } else {
                    if (error.httpStatus in setOf(401, 403) || error.code in -32099..-32020 ||
                        (error.code !in setOf(-32601, -32600, -32000, -32002) && error.httpStatus !in setOf(400, 404, 405))) throw error
                    connection.version = McpProtocol.LEGACY
                }
            }
        }

        val params = JSONObject().put("protocolVersion", connection.version)
            .put("capabilities", JSONObject()).put("clientInfo", McpProtocol.clientInfo())
        val initialized = try {
            rpc(connection, "initialize", params)
        } catch (error: McpRpcException) {
            if (pin.isNotBlank() || connection.transport == AppSettings.MCP_TRANSPORT_SSE || error.httpStatus !in setOf(400, 404, 405) || error.code in -32099..-32020) throw error
            openLegacySse(connection)
            rpc(connection, "initialize", params)
        }
        val negotiated = initialized.optString("protocolVersion")
        require(negotiated in McpProtocol.legacyVersions && (pin.isBlank() || negotiated == pin)) { "MCP server negotiated an unsupported protocol version: $negotiated" }
        connection.version = negotiated
        execute(connection, McpProtocol.request(null, "notifications/initialized", JSONObject(), negotiated))
        connection.initialized = true
    }

    private fun rpc(connection: Connection, method: String, params: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val response = execute(connection, McpProtocol.request(ids.getAndIncrement(), method, params, connection.version), headers)
        if (response.has("error")) throw McpRpcException(response)
        val result = response.optJSONObject("result") ?: error("MCP response has no result.")
        require(result.optString("resultType", "complete") == "complete") { "MCP server requires a client capability that Lyra Code does not advertise: ${result.optString("resultType")}" }
        return result
    }

    private fun request(connection: Connection, url: String, body: JSONObject? = null, extra: Map<String, String> = emptyMap()): Request {
        val builder = Request.Builder().url(url).header("Accept", "application/json, text/event-stream")
        connection.json.headers.forEach { (name, value) -> builder.header(name, value) }
        // Compatibility for old saved records without a JSON endpoint. Authoritative JSON never inherits a key.
        if (connection.json.url.isBlank() && connection.json.headers.keys.none { it.equals("Authorization", true) } && connection.config.authKey.isNotBlank()) {
            builder.header("Authorization", McpJsonConfig.legacyAuthorization(connection.config.authKey))
        }
        if (body == null) return builder.get().build()
        val contentType = connection.json.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/json"
        builder.post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()))
        builder.header("MCP-Protocol-Version", connection.version)
        if (connection.version == McpProtocol.MODERN) {
            require(connection.json.headers.keys.none { it.equals("Mcp-Session-Id", true) }) { "2026-07-28 does not support Mcp-Session-Id." }
            extra.plus("Mcp-Method" to body.getString("method")).toMutableMap().apply {
                body.optJSONObject("params")?.optString("name")?.takeIf(String::isNotEmpty)?.let { put("Mcp-Name", McpProtocol.encodeHeader(it)) }
            }.forEach { (name, value) ->
                val configured = connection.json.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                require(configured == null || configured == value) { "Configured $name does not match the MCP request." }
                builder.header(name, value)
            }
        } else connection.sessionId?.let { builder.header("Mcp-Session-Id", it) }
        return builder.build()
    }

    private fun execute(connection: Connection, body: JSONObject, extra: Map<String, String> = emptyMap()): JSONObject {
        connection.client.newCall(request(connection, connection.postEndpoint ?: connection.endpoint, body, extra)).execute().use { response ->
            if (connection.version != McpProtocol.MODERN) response.header("Mcp-Session-Id")?.takeIf(String::isNotBlank)?.let { connection.sessionId = it }
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject().put("message", text.take(1000))
                throw McpRpcException(parsed, response.code)
            }
            if (!body.has("id")) return JSONObject()
            val expectedId = body.get("id").toString()
            val rpcResponse = when {
                connection.sseReader != null && response.code == 202 -> {
                    connection.sseResponse?.body?.source()?.timeout()?.deadline(connection.config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    try { readSseResponse(requireNotNull(connection.sseReader), expectedId) }
                    finally { connection.sseResponse?.body?.source()?.timeout()?.clearDeadline() }
                }
                response.header("Content-Type").orEmpty().contains("text/event-stream", true) -> {
                    response.body?.source()?.timeout()?.deadline(connection.config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    readSseResponse(response.body!!.charStream().buffered(), expectedId)
                }
                else -> JSONObject(response.body?.string().orEmpty())
            }
            require(rpcResponse.opt("id")?.toString() == expectedId || (rpcResponse.has("error") && !rpcResponse.has("id"))) { "MCP response id does not match request $expectedId." }
            return rpcResponse
        }
    }

    private fun openLegacySse(connection: Connection) {
        val response = connection.client.newCall(request(connection, connection.endpoint)).execute()
        try {
            require(response.isSuccessful && response.header("Content-Type").orEmpty().contains("text/event-stream", true)) { "MCP server did not open an HTTP+SSE stream (HTTP ${response.code})." }
            val reader = response.body!!.charStream().buffered()
            response.body!!.source().timeout().deadline(connection.config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            var endpoint: String? = null
            while (endpoint == null) {
                val event = readSseEvent(reader) ?: error("MCP SSE stream ended before its endpoint event.")
                if (event.first == "endpoint") endpoint = event.second
            }
            val source = response.request.url
            val target = source.resolve(endpoint) ?: error("Invalid MCP SSE endpoint.")
            require(source.scheme == target.scheme && source.host == target.host && source.port == target.port) { "MCP SSE endpoint must use the configured server origin." }
            connection.postEndpoint = target.toString()
            connection.sseResponse = response
            connection.sseReader = reader
            response.body!!.source().timeout().clearDeadline()
        } catch (error: Exception) { response.close(); throw error }
    }
}

internal fun readSseEvent(reader: BufferedReader): Pair<String, String>? {
    var event = "message"
    val data = mutableListOf<String>()
    while (true) {
        val line = reader.readLine() ?: return if (data.isEmpty()) null else event to data.joinToString("\n")
        if (line.isEmpty()) {
            if (data.isNotEmpty()) return event to data.joinToString("\n")
            event = "message"
        } else if (!line.startsWith(":")) {
            val name = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            if (name == "event") event = value
            if (name == "data") data += value
        }
    }
}

internal fun readSseResponse(reader: BufferedReader, expectedId: String): JSONObject {
    while (true) {
        val event = readSseEvent(reader) ?: error("MCP SSE stream ended without a response to request $expectedId.")
        if (event.second == "[DONE]") continue
        val message = JSONObject(event.second)
        if (message.opt("id")?.toString() == expectedId && (message.has("result") || message.has("error"))) return message
    }
}
