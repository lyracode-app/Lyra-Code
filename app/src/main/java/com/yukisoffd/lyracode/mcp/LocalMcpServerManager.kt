package com.yukisoffd.lyracode.mcp

import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.LocalMcpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class LocalMcpServerStatus(
    val running: Boolean,
    val host: String,
    val port: Int,
    val url: String,
    val lanUrls: List<String>,
    val startedAt: Long,
    val message: String,
)

class LocalMcpServerManager(private val settings: AppSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var currentConfig: LocalMcpServerConfig = settings.localMcpServerConfig()
    @Volatile private var agent: OpenAiAgent? = null
    @Volatile private var startedAt: Long = 0L
    @Volatile private var message: String = "未启动"

    fun attachAgent(agent: OpenAiAgent) {
        this.agent = agent
    }

    fun status(): LocalMcpServerStatus {
        val config = currentConfig
        return LocalMcpServerStatus(
            running = running.get(),
            host = config.host,
            port = config.port,
            url = serviceUrl(config),
            lanUrls = lanUrls(config.port),
            startedAt = startedAt,
            message = message,
        )
    }

    fun statusJson(): JSONObject {
        val status = status()
        return JSONObject()
            .put("running", status.running)
            .put("host", status.host)
            .put("port", status.port)
            .put("url", status.url)
            .put("lanUrls", JSONArray(status.lanUrls))
            .put("startedAt", status.startedAt)
            .put("message", status.message)
    }

    @Synchronized
    fun syncWithSettings(): LocalMcpServerStatus {
        val config = settings.localMcpServerConfig()
        return if (config.enabled) start(config) else stop(saveDisabled = false)
    }

    @Synchronized
    fun start(config: LocalMcpServerConfig = settings.localMcpServerConfig()): LocalMcpServerStatus {
        if (running.get() && currentConfig == config) return status()
        stop(saveDisabled = false)
        val normalized = config.copy(
            host = config.host.ifBlank { AppSettings.DEFAULT_LOCAL_MCP_SERVER_HOST },
            port = config.port.coerceIn(1, 65535),
            enabled = true,
        )
        currentConfig = normalized
        return runCatching {
            val socket = ServerSocket(normalized.port, 50, java.net.InetAddress.getByName(normalized.host))
            serverSocket = socket
            running.set(true)
            startedAt = System.currentTimeMillis()
            message = "运行中"
            settings.saveLocalMcpServerConfig(normalized)
            scope.launch {
                while (running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { handleClient(client, normalized) }
                }
            }
            status()
        }.getOrElse { error ->
            running.set(false)
            message = error.message ?: "启动失败"
            status()
        }
    }

    @Synchronized
    fun stop(saveDisabled: Boolean = true): LocalMcpServerStatus {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        message = "已停止"
        if (saveDisabled) {
            val disabled = settings.localMcpServerConfig().copy(enabled = false)
            currentConfig = disabled
            settings.saveLocalMcpServerConfig(disabled)
        }
        return status()
    }

    fun close() {
        stop(saveDisabled = false)
        scope.cancel()
    }

    private fun handleClient(socket: Socket, config: LocalMcpServerConfig) {
        socket.use { client ->
            client.soTimeout = 30_000
            val request = runCatching { readMcpHttpRequest(client.getInputStream().buffered()) }.getOrElse {
                writeJson(client.getOutputStream(), rpcError(null, -32600, it.message ?: "Invalid HTTP request"), 400)
                return
            }
            val output = client.getOutputStream()
            when {
                request.method == "OPTIONS" -> writeResponse(output, 204, "")
                !authorized(config, request.headers) -> writeJson(output, rpcError(null, -32001, "未授权"), 401)
                request.method == "GET" && request.path in setOf("/", "/status") -> writeJson(output, statusJson())
                request.path == "/mcp" && request.method != "POST" -> writeJson(output, rpcError(null, -32600, "Only POST /mcp is supported"), 405)
                request.method == "POST" && request.path == "/mcp" -> {
                    val reply = McpServerProtocol(::toolsForMcp, ::callTool).handle(request.body, request.headers)
                    if (reply.body == null) writeResponse(output, reply.status, "")
                    else writeJson(output, reply.body, reply.status)
                }
                else -> writeJson(output, rpcError(null, -32600, "Only POST /mcp is supported"), 404)
            }
        }
    }
    private fun toolsForMcp(): JSONArray {
        val definitions = agent?.localMcpToolDefinitions() ?: JSONArray()
        val tools = JSONArray()
        for (index in 0 until definitions.length()) {
            val function = definitions.optJSONObject(index)?.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("inputSchema", function.optJSONObject("parameters") ?: JSONObject().put("type", "object")),
            )
        }
        return tools
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name.isBlank()) return toolResult("缺少工具名称", isError = true)
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val output = runBlocking(Dispatchers.IO) {
            agent?.executeLocalMcpTool(name, args) ?: """{"ok":false,"error":"本机 MCP 服务端尚未绑定 Agent"}"""
        }
        val failed = runCatching { JSONObject(output).optBoolean("ok", true).not() }.getOrDefault(false)
        return toolResult(output, failed)
    }

    private fun toolResult(text: String, isError: Boolean): JSONObject {
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", isError)
    }

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject = McpProtocol.error(id, code, message)
    private fun authorized(config: LocalMcpServerConfig, headers: Map<String, String>): Boolean {
        val key = config.authKey.trim()
        if (key.isBlank()) return true
        val authorization = headers["authorization"].orEmpty()
        val bearerKey = authorization.substringAfter(' ', "").trim().takeIf {
            authorization.substringBefore(' ').equals("Bearer", ignoreCase = true)
        }.orEmpty()
        val xKey = headers["x-lyra-mcp-key"].orEmpty()
        val xApiKey = headers["x-api-key"].orEmpty()
        val apiKey = headers["api-key"].orEmpty()
        return authorization == key ||
            bearerKey == key ||
            xKey == key ||
            xApiKey == key ||
            apiKey == key
    }

    private fun writeJson(output: OutputStream, json: JSONObject, status: Int = 200) {
        writeResponse(output, status, json.toString())
    }

    private fun writeResponse(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            405 -> "Method Not Allowed"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "OK"
        }
        output.write(
            buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Lyra-MCP-Key, X-API-Key, Api-Key, Mcp-Protocol-Version, Mcp-Method, Mcp-Name\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                if (status == 405) append("Allow: POST, OPTIONS\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.UTF_8),
        )
        output.write(bytes)
        output.flush()
    }

    private fun serviceUrl(config: LocalMcpServerConfig): String {
        val host = when (config.host) {
            "0.0.0.0", "::" -> "127.0.0.1"
            else -> config.host
        }
        return "http://$host:${config.port}/mcp"
    }

    private fun lanUrls(port: Int): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .map { "http://${it.hostAddress}:$port/mcp" }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
