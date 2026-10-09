package com.yukisoffd.lyracode.mcp

import android.content.Context
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import org.json.JSONObject

data class McpCallResult(val serverName: String, val toolName: String, val content: String)

class McpClientManager(context: Context, private val settings: AppSettings) {
    private val client = McpHttpClient()

    suspend fun testAndRefreshTools(server: McpServerConfig): Result<List<McpToolDefinition>> = runCatching {
        client.listTools(server).also { tools ->
            val current = settings.mcpServers().firstOrNull { it.id == server.id }
            require(current != null && current.copy(tools = emptyList(), enabled = true) == server.copy(tools = emptyList(), enabled = true)) {
                "MCP configuration changed while fetching tools. Fetch again using the saved configuration."
            }
            settings.updateMcpServerTools(server.id, tools)
        }
    }

    suspend fun callTool(server: McpServerConfig, tool: McpToolDefinition, arguments: JSONObject): McpCallResult =
        McpCallResult(server.name, tool.name, client.callTool(server, tool, arguments).toString())

    fun invalidate(serverId: String) { client.invalidate(serverId) }
}
