package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.AgentConfigToolHandler
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.mcp.LocalMcpServerManager
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.mcp.McpProtocol
import com.yukisoffd.lyracode.webdav.WebDavClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class LocalMcpServerInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun storedJsonProjectsConnectionFieldsForExistingRecords() {
        val settings = AppSettings(context)
        val id = "mcp-existing-json-test"
        val raw = """{"url":"https://example.com/mcp","headers":{"Authorization":"RAW"}}"""
        try {
            settings.upsertMcpServer(McpServerConfig(id, "Existing", "", "stale-key", "streamable_http", 30, true, raw,
                listOf(McpToolDefinition("echo", "", "{}"))))
            val read = settings.mcpServers().first { it.id == id }
            assertEquals("https://example.com/mcp", read.url)
            assertEquals("RAW", read.authKey)
            assertEquals(raw, read.rawJson)
            assertTrue(settings.enabledMcpTools().any { it.first.id == id })
        } finally { settings.deleteMcpServer(id) }
    }

    @Test fun localEndpointServesModernAndLegacyClientsAndValidatesHeaders() {
        val settings = AppSettings(context)
        val original = settings.localMcpServerConfig()
        val manager = LocalMcpServerManager(settings)
        val port = ServerSocket(0).use { it.localPort }
        val client = OkHttpClient()
        try {
            val status = manager.start(original.copy(host = "127.0.0.1", port = port, authKey = "local-key", enabled = true))
            assertTrue(status.message, status.running)
            fun request(method: String, version: String, params: JSONObject = JSONObject(), mismatched: Boolean = false): Pair<Int, JSONObject> {
                val body = McpProtocol.request(1, method, params, version)
                val builder = Request.Builder().url(status.url).header("Authorization", "local-key")
                    .header("MCP-Protocol-Version", version).post(body.toString().toRequestBody("application/json".toMediaType()))
                if (version == McpProtocol.MODERN) builder.header("Mcp-Method", if (mismatched) "wrong" else method)
                client.newCall(builder.build()).execute().use { response ->
                    assertNull(response.header("Mcp-Session-Id"))
                    return response.code to JSONObject(response.body!!.string())
                }
            }
            val direct = request("tools/list", McpProtocol.MODERN)
            assertEquals(200, direct.first)
            assertEquals("complete", direct.second.getJSONObject("result").getString("resultType"))
            val discover = request("server/discover", McpProtocol.MODERN).second.getJSONObject("result")
            assertTrue(discover.getJSONArray("supportedVersions").toString().contains(McpProtocol.LEGACY))
            val old = request("initialize", McpProtocol.LEGACY, JSONObject().put("protocolVersion", McpProtocol.LEGACY))
            assertEquals(McpProtocol.LEGACY, old.second.getJSONObject("result").getString("protocolVersion"))
            assertFalse(request("tools/list", McpProtocol.LEGACY).second.getJSONObject("result").has("resultType"))
            val mismatch = request("tools/list", McpProtocol.MODERN, mismatched = true)
            assertEquals(400, mismatch.first)
            assertEquals(-32020, mismatch.second.getJSONObject("error").getInt("code"))
            client.newCall(Request.Builder().url(status.url).header("Authorization", "local-key").get().build()).execute().use { assertEquals(405, it.code) }
            client.newCall(Request.Builder().url(status.url).post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(401, it.code) }
        } finally {
            manager.close()
            settings.saveLocalMcpServerConfig(original)
        }
    }

    @Test fun manageAppConfigListsAndReplacesCompleteMcpJson() = runBlocking {
        val settings = AppSettings(context)
        var changed = 0
        val handler = AgentConfigToolHandler(settings, McpClientManager(context, settings), WebDavClient(), FileTransferClient(context), OkHttpClient(), emptyList()) { changed++ }
        val id = "mcp-ai-json-test"
        try {
            val raw = """{
              "protocolVersion":"2026-07-28",
              "mcpServers":{"special":{"url":"https://example.com/mcp","headers":{"Authorization":"EXACT"},"vendor":{"custom":true}}}
            }"""
            handler.manageAppConfig(JSONObject().put("target", "mcp_server").put("action", "add").put("id", id).put("raw_json", raw).put("enabled", false))
            assertEquals(raw, settings.mcpServers().first { it.id == id }.rawJson)
            val listed = JSONObject(handler.manageAppConfig(JSONObject().put("target", "mcp_server").put("action", "list"))).toString()
            assertTrue(listed.contains("raw_json"))
            assertEquals(raw, handler.mcpServerJson(settings.mcpServers().first { it.id == id }).getString("raw_json"))
            val replacement = """{"url":"https://new.example/mcp","headers":{},"vendor":[1,2,3]}"""
            handler.manageAppConfig(JSONObject().put("target", "mcp_server").put("action", "update").put("id", id).put("raw_json", replacement))
            val saved = settings.mcpServers().first { it.id == id }
            assertEquals(replacement, saved.rawJson)
            assertEquals("https://new.example/mcp", saved.url)
            assertEquals("", saved.authKey)
            assertEquals(2, changed)
        } finally { settings.deleteMcpServer(id) }
    }
}
