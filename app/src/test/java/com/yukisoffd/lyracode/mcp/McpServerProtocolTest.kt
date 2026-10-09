package com.yukisoffd.lyracode.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class McpServerProtocolTest {
    private val server = McpServerProtocol(
        { JSONArray().put(JSONObject().put("name", "echo").put("description", "完整简介").put("inputSchema", JSONObject().put("type", "object"))) },
        { params -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", params.getJSONObject("arguments").getString("text")))) },
    )
    private fun modern(method: String, params: JSONObject = JSONObject(), version: String = McpProtocol.MODERN): McpServerReply {
        val body = McpProtocol.request(1, method, params, McpProtocol.MODERN)
        body.getJSONObject("params").getJSONObject("_meta").put(McpProtocol.VERSION_META, version)
        return server.handle(body.toString(), buildMap {
            put("mcp-protocol-version", version); put("mcp-method", method)
            if (params.has("name")) put("mcp-name", McpProtocol.encodeHeader(params.getString("name")))
        })
    }

    @Test fun modernRequestsAreIndependentAndDiscoverAdvertisesBothEras() {
        val tools = modern("tools/list")
        assertEquals(200, tools.status)
        assertEquals("complete", tools.body!!.getJSONObject("result").getString("resultType"))
        val discover = modern("server/discover").body!!.getJSONObject("result")
        assertEquals(McpProtocol.MODERN, discover.getJSONArray("supportedVersions").getString(0))
        assertTrue(discover.getJSONArray("supportedVersions").toString().contains(McpProtocol.LEGACY))
        assertFalse(discover.getJSONObject("capabilities").getJSONObject("tools").has("listChanged"))
        val call = modern("tools/call", JSONObject().put("name", "echo").put("arguments", JSONObject().put("text", "中文参数")))
        assertEquals("中文参数", call.body!!.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun legacyInitializeNegotiatesRequestedOlderVersionsAndUnknownVersionFallsBack() {
        (McpProtocol.legacyVersions + "unknown").forEach { version ->
            val request = McpProtocol.request(7, "initialize", JSONObject().put("protocolVersion", version), McpProtocol.LEGACY)
            val result = server.handle(request.toString(), emptyMap()).body!!.getJSONObject("result")
            assertEquals(if (version == "unknown") McpProtocol.LEGACY else version, result.getString("protocolVersion"))
            assertFalse(result.has("resultType"))
        }
        val notification = McpProtocol.request(null, "notifications/initialized", JSONObject(), McpProtocol.LEGACY)
        assertEquals(202, server.handle(notification.toString(), emptyMap()).status)
        assertNull(server.handle(notification.toString(), emptyMap()).body)
    }

    @Test fun modernMetadataMismatchAndUnsupportedVersionHaveDistinctErrors() {
        val request = McpProtocol.request(1, "tools/list", JSONObject(), McpProtocol.MODERN)
        val mismatch = server.handle(request.toString(), mapOf("mcp-protocol-version" to McpProtocol.MODERN))
        assertEquals(400, mismatch.status)
        assertEquals(-32020, mismatch.body!!.getJSONObject("error").getInt("code"))
        val unsupported = modern("tools/list", version = "2099-01-01")
        assertEquals(-32022, unsupported.body!!.getJSONObject("error").getInt("code"))
        assertEquals("2099-01-01", unsupported.body!!.getJSONObject("error").getJSONObject("data").getString("requested"))
        assertEquals(404, modern("initialize").status)
        assertEquals(404, modern("unknown/method").status)
    }

    @Test fun invalidRequestsUseJsonRpcErrorWithNullId() {
        val invalid = server.handle("{invalid", emptyMap())
        assertTrue(invalid.body!!.has("id"))
        assertTrue(invalid.body!!.isNull("id"))
        assertEquals(-32700, invalid.body!!.getJSONObject("error").getInt("code"))
        assertEquals(-32602, modern("tools/call", JSONObject().put("name", "missing")).body!!.getJSONObject("error").getInt("code"))
    }

    @Test fun httpParserReadsUtf8ByteLengthWithoutWaitingForExtraCharacters() {
        val body = """{"text":"中文与 emoji 😀"}""".toByteArray(Charsets.UTF_8)
        val headers = "POST /mcp?token=1 HTTP/1.1\r\nContent-Length: ${body.size}\r\nAuthorization: raw-key\r\n\r\n".toByteArray()
        val request = readMcpHttpRequest(ByteArrayInputStream(headers + body))
        assertEquals(String(body, Charsets.UTF_8), request.body)
        assertEquals("/mcp", request.path)
        assertEquals("raw-key", request.headers["authorization"])
    }

    @Test fun httpParserSupportsChunkedUtf8AndRejectsAmbiguousLength() {
        val body = """{"text":"中文"}""".toByteArray(Charsets.UTF_8)
        val headers = "POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()
        val chunks = "${body.size.toString(16)}\r\n".toByteArray() + body + "\r\n0\r\n\r\n".toByteArray()
        assertEquals(String(body, Charsets.UTF_8), readMcpHttpRequest(ByteArrayInputStream(headers + chunks)).body)
        val bad = "POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 10\r\n\r\n".toByteArray() + chunks
        assertTrue(runCatching { readMcpHttpRequest(ByteArrayInputStream(bad)) }.isFailure)
    }
}
