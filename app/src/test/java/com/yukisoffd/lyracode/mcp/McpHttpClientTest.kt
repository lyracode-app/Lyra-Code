package com.yukisoffd.lyracode.mcp

import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class McpHttpClientTest {
    private data class Reply(val text: String, val code: Int = 200, val headers: Map<String, String> = emptyMap(), val keepOpen: Boolean = false)
    private class Fixture(val handler: (McpHttpRequest, Socket) -> Reply) : Closeable {
        private val listener = ServerSocket(0)
        private val stop = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<McpHttpRequest>())
        private val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val url = "http://127.0.0.1:${listener.localPort}/mcp"
        init {
            thread(isDaemon = true, name = "mcp-test-listener") {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        socket.use {
                            try {
                                val request = readMcpHttpRequest(socket.getInputStream().buffered())
                                requests += request
                                val reply = handler(request, socket)
                                val bytes = reply.text.toByteArray(Charsets.UTF_8)
                                socket.getOutputStream().apply {
                                    write(buildString {
                                        append("HTTP/1.1 ${reply.code} Test\r\n")
                                        append("Content-Type: ${reply.headers["Content-Type"] ?: "application/json"}\r\n")
                                        reply.headers.filterKeys { it != "Content-Type" }.forEach { (name, value) -> append("$name: $value\r\n") }
                                        if (!reply.keepOpen) append("Content-Length: ${bytes.size}\r\nConnection: close\r\n")
                                        append("\r\n")
                                    }.toByteArray())
                                    write(bytes); flush()
                                }
                                if (reply.keepOpen) stop.await()
                            } catch (error: Throwable) { failures += error }
                        }
                    }
                }
            }
        }
        override fun close() { listener.close(); stop.countDown(); if (failures.isNotEmpty()) throw AssertionError("Mock server failed", failures.first()) }
    }

    private fun config(url: String, version: String = "", headers: JSONObject = JSONObject(), type: String = "streamableHttp") = McpServerConfig(
        "server", "Test", "https://stale.example/mcp", "stale-key", "streamable_http", 5, true,
        JSONObject().put("url", url).put("headers", headers).put("type", type).apply { if (version.isNotBlank()) put("protocolVersion", version) }.toString(), emptyList(),
    )
    private fun rpcReply(request: McpHttpRequest, result: JSONObject, headers: Map<String, String> = emptyMap()): Reply = Reply(
        JSONObject().put("jsonrpc", "2.0").put("id", JSONObject(request.body).get("id")).put("result", result).toString(), headers = headers,
    )
    private fun rpcError(request: McpHttpRequest, code: Int, status: Int = 200, data: JSONObject? = null): Reply = Reply(
        McpProtocol.error(JSONObject(request.body).get("id"), code, "Test error", data).toString(), status,
    )
    private val tool = McpToolDefinition("echo", "complete description", """{"type":"object"}""")

    @Test fun modernLifecycleIsStatelessAndAuthorizationIsVerbatim() = runBlocking {
        Fixture { request, _ ->
            val payload = JSONObject(request.body)
            val method = payload.getString("method")
            assertEquals("Raw-KEY", request.headers["authorization"])
            assertEquals("custom", request.headers["x-special"])
            assertEquals("application/json", request.headers["content-type"])
            assertFalse(request.headers.containsKey("mcp-session-id"))
            assertEquals(McpProtocol.MODERN, request.headers["mcp-protocol-version"])
            assertEquals(method, request.headers["mcp-method"])
            val meta = payload.getJSONObject("params").getJSONObject("_meta")
            assertEquals(McpProtocol.MODERN, meta.getString(McpProtocol.VERSION_META))
            assertTrue(meta.has(McpProtocol.CLIENT_INFO_META))
            assertEquals(0, meta.getJSONObject(McpProtocol.CLIENT_CAPABILITIES_META).length())
            when (method) {
                "server/discover" -> rpcReply(request, JSONObject().put("supportedVersions", JSONArray().put(McpProtocol.MODERN)).put("resultType", "complete"), mapOf("Mcp-Session-Id" to "must-be-ignored"))
                "tools/list" -> rpcReply(request, JSONObject().put("tools", JSONArray().put(JSONObject().put("name", tool.name).put("description", tool.description).put("inputSchema", JSONObject(tool.inputSchema)))))
                else -> { assertEquals("echo", request.headers["mcp-name"]); rpcReply(request, JSONObject().put("content", JSONArray()).put("resultType", "complete")) }
            }
        }.use { server ->
            val client = McpHttpClient()
            val config = config(server.url, headers = JSONObject().put("authorization", "Raw-KEY").put("X-Special", "custom"))
            assertEquals(listOf(tool), client.listTools(config))
            client.callTool(config, tool, JSONObject())
            client.callTool(config, tool, JSONObject())
            assertEquals(listOf("server/discover", "tools/list", "tools/call", "tools/call"), server.requests.map { JSONObject(it.body).getString("method") })
        }
    }

    @Test fun autoFallbackNegotiatesOlderSessionAndNotificationAcceptsEmpty202() = runBlocking {
        Fixture { request, _ ->
            val payload = JSONObject(request.body)
            when (payload.getString("method")) {
                "server/discover" -> rpcError(request, -32601)
                "initialize" -> {
                    assertEquals(McpProtocol.LEGACY, payload.getJSONObject("params").getString("protocolVersion"))
                    assertFalse(request.headers.containsKey("mcp-session-id"))
                    rpcReply(request, JSONObject().put("protocolVersion", "2025-06-18"), mapOf("Mcp-Session-Id" to "legacy-session"))
                }
                "notifications/initialized" -> { assertEquals("legacy-session", request.headers["mcp-session-id"]); Reply("", 202) }
                else -> {
                    assertEquals("2025-06-18", request.headers["mcp-protocol-version"])
                    assertEquals("legacy-session", request.headers["mcp-session-id"])
                    assertFalse(payload.getJSONObject("params").has("_meta"))
                    rpcReply(request, JSONObject().put("tools", JSONArray()))
                }
            }
        }.use { server ->
            val client = McpHttpClient()
            val config = config(server.url)
            client.listTools(config)
            client.callTool(config, tool, JSONObject())
            assertEquals(1, server.requests.count { JSONObject(it.body).optString("method") == "initialize" })
            assertEquals(1, server.requests.count { JSONObject(it.body).optString("method") == "notifications/initialized" })
        }
    }

    @Test fun legacyServerWithoutSessionIdIsInitializedOnlyOnce() = runBlocking {
        Fixture { request, _ ->
            when (JSONObject(request.body).getString("method")) {
                "initialize" -> rpcReply(request, JSONObject().put("protocolVersion", McpProtocol.LEGACY))
                "notifications/initialized" -> Reply("", 202)
                else -> rpcReply(request, JSONObject().put("tools", JSONArray()))
            }
        }.use { server ->
            val client = McpHttpClient()
            val config = config(server.url, McpProtocol.LEGACY)
            client.listTools(config); client.callTool(config, tool, JSONObject()); client.callTool(config, tool, JSONObject())
            assertEquals(1, server.requests.count { JSONObject(it.body).optString("method") == "initialize" })
            assertFalse(server.requests.any { it.headers.containsKey("mcp-session-id") })
        }
    }

    @Test fun explicitModernVersionAndAuthenticationFailuresNeverFallBack() = runBlocking {
        for ((code, status, pin) in listOf(Triple(-32601, 200, McpProtocol.MODERN), Triple(-32001, 401, ""), Triple(-32020, 400, ""), Triple(-32021, 400, ""))) {
            Fixture { request, _ -> rpcError(request, code, status) }.use { server ->
                assertTrue(runCatching { McpHttpClient().listTools(config(server.url, pin)) }.isFailure)
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test fun unsupportedModernVersionChoosesAdvertisedLegacyVersion() = runBlocking {
        Fixture { request, _ ->
            when (JSONObject(request.body).getString("method")) {
                "server/discover" -> rpcError(request, -32022, 400, JSONObject().put("supported", JSONArray().put(McpProtocol.LEGACY)))
                "initialize" -> rpcReply(request, JSONObject().put("protocolVersion", McpProtocol.LEGACY))
                "notifications/initialized" -> Reply("", 202)
                else -> rpcReply(request, JSONObject().put("tools", JSONArray()))
            }
        }.use { server ->
            McpHttpClient().listTools(config(server.url))
            assertEquals(McpProtocol.LEGACY, server.requests.last().headers["mcp-protocol-version"])
        }
    }

    @Test fun configChangeDropsSessionAndRemovedAuthIsNotInherited() = runBlocking {
        Fixture { request, _ ->
            when (JSONObject(request.body).getString("method")) {
                "server/discover" -> rpcReply(request, JSONObject().put("supportedVersions", JSONArray().put(McpProtocol.MODERN)))
                else -> rpcReply(request, JSONObject().put("tools", JSONArray()))
            }
        }.use { server ->
            val client = McpHttpClient()
            client.listTools(config(server.url, headers = JSONObject().put("Authorization", "old-raw-key")))
            client.callTool(config(server.url), tool, JSONObject())
            assertEquals(2, server.requests.count { JSONObject(it.body).optString("method") == "server/discover" })
            assertFalse(server.requests.last().headers.containsKey("authorization"))
        }
    }

    @Test fun paginationAndSseNotificationsDoNotTruncateToolList() = runBlocking {
        Fixture { request, _ ->
            val payload = JSONObject(request.body)
            if (payload.getString("method") == "server/discover") rpcReply(request, JSONObject().put("supportedVersions", JSONArray().put(McpProtocol.MODERN)))
            else {
                val next = payload.getJSONObject("params").optString("cursor") == "second"
                val result = JSONObject().put("tools", JSONArray().put(JSONObject().put("name", if (next) "second" else "first").put("description", "x".repeat(4000)).put("inputSchema", JSONObject())))
                if (!next) result.put("nextCursor", "second")
                val response = rpcReply(request, result).text
                Reply(": keepalive\r\n\r\nevent: message\r\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\r\n\r\nevent: message\r\ndata: $response\r\n\r\n", headers = mapOf("Content-Type" to "text/event-stream"))
            }
        }.use { server ->
            val tools = McpHttpClient().listTools(config(server.url))
            assertEquals(listOf("first", "second"), tools.map { it.name })
            assertTrue(tools.all { it.description.length == 4000 })
        }
    }

    @Test fun httpSseTransportUsesEndpointEventAndMatchingResponseId() = runBlocking {
        var stream: OutputStream? = null
        Fixture { request, socket ->
            if (request.method == "GET") {
                stream = socket.getOutputStream()
                Reply("event: endpoint\ndata: /messages?session=old\n\n", headers = mapOf("Content-Type" to "text/event-stream"), keepOpen = true)
            } else {
                assertEquals("/messages", request.path)
                val payload = JSONObject(request.body)
                if (payload.has("id")) {
                    val result = if (payload.getString("method") == "initialize") JSONObject().put("protocolVersion", "2024-11-05") else JSONObject().put("tools", JSONArray())
                    stream!!.write("event: message\ndata: ${rpcReply(request, result).text}\n\n".toByteArray()); stream!!.flush()
                }
                Reply("", 202)
            }
        }.use { server ->
            val client = McpHttpClient()
            client.listTools(config(server.url, "2024-11-05", type = "sse"))
            client.callTool(config(server.url, "2024-11-05", type = "sse"), tool, JSONObject())
            assertEquals(1, server.requests.count { it.method == "GET" })
            client.invalidate("server")
        }
    }

    @Test fun modernToolParameterHeadersEncodeUnicodeAndValidateSchema() = runBlocking {
        val schema = """{"type":"object","properties":{"nested":{"type":"object","properties":{"tenant":{"type":"string","x-mcp-header":"Tenant"}}}}}"""
        Fixture { request, _ ->
            if (JSONObject(request.body).getString("method") == "server/discover") rpcReply(request, JSONObject().put("supportedVersions", JSONArray().put(McpProtocol.MODERN)))
            else {
                assertEquals("中文", McpProtocol.decodeHeader(request.headers.getValue("mcp-param-tenant")))
                assertEquals("中文工具", McpProtocol.decodeHeader(request.headers.getValue("mcp-name")))
                rpcReply(request, JSONObject().put("resultType", "complete"))
            }
        }.use { server ->
            McpHttpClient().callTool(config(server.url), McpToolDefinition("中文工具", "", schema), JSONObject().put("nested", JSONObject().put("tenant", "中文")))
        }
        assertFalse(McpProtocol.validToolHeaders(JSONObject("""{"type":"object","properties":{"a":{"type":"number","x-mcp-header":"A"}}}""")))
        assertFalse(McpProtocol.validToolHeaders(JSONObject("""{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"A"},"b":{"type":"string","x-mcp-header":"a"}}}""")))
    }
}
