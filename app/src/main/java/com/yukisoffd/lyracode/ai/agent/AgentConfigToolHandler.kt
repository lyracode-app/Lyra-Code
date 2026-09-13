package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.EmailServerConfig
import com.yukisoffd.lyracode.data.FileTransferServerConfig
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.SkillPack
import com.yukisoffd.lyracode.data.SshServerConfig
import com.yukisoffd.lyracode.data.WebDavServerConfig
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.webdav.WebDavClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal class AgentConfigToolHandler(
    private val settings: AppSettings,
    private val mcpClientManager: McpClientManager,
    private val webDavClient: WebDavClient,
    private val fileTransferClient: FileTransferClient,
    private val client: OkHttpClient,
    private val configurableAgentTools: List<String>,
    private val onConfigChanged: suspend () -> Unit,
) {
    suspend fun manageAppConfig(args: JSONObject): String {
        val target = args.optString("target").trim().lowercase(Locale.US).replace("-", "_")
        val action = args.optString("action").trim().lowercase(Locale.US).replace("-", "_")
        require(target.isNotBlank()) { "target is required: all, mcp_server, ssh_server, email_server, webdav_server, file_transfer_server, skill, or agent_tool." }
        require(action.isNotBlank()) { "action is required: list, add, update, enable, disable, or delete." }
        val result = when (target) {
            "all", "config", "configs", "inventory" -> {
                require(action == "list") { "target=$target supports only action=list." }
                configInventoryJson().toString()
            }
            "mcp", "mcp_server", "mcp_servers" -> manageMcpConfig(action, args)
            "ssh", "ssh_server", "ssh_servers" -> manageSshConfig(action, args)
            "email", "mail", "email_server", "email_servers", "imap", "smtp" -> manageEmailConfig(action, args)
            "webdav", "webdav_server", "webdav_servers" -> manageWebDavConfig(action, args)
            "file_transfer", "file_transfer_server", "file_transfer_servers", "ftp", "ftps", "sftp" -> manageFileTransferConfig(target, action, args)
            "skill", "skills" -> manageSkillConfig(action, args)
            "agent", "agent_tool", "tool", "tools" -> manageAgentToolConfig(action, args)
            else -> error("Unknown configuration target: $target. Use target=all action=list to inspect supported targets.")
        }
        if (action != "list") {
            onConfigChanged()
        }
        return result
    }
    
    suspend fun manageMcpConfig(action: String, args: JSONObject): String {
        if (action == "list") return configResult("mcp_servers", mcpServersJson()).toString()
        val existing = resolveMcpServerForConfig(args.optString("id").ifBlank { args.optString("name") }.ifBlank { args.optString("url") })
        when (action) {
            "delete", "remove" -> {
                val target = existing ?: error("MCP server to delete was not found. List configured servers and use an exact id or name.")
                settings.deleteMcpServer(target.id)
                return configResult("mcp_server_deleted", JSONObject().put("id", target.id).put("name", target.name)).toString()
            }
            "enable", "disable" -> {
                val target = existing ?: error("MCP server to $action was not found. List configured servers and use an exact id or name.")
                settings.setMcpServerEnabled(target.id, action == "enable")
                return configResult("mcp_server_${action}d", mcpServerJson(target.copy(enabled = action == "enable"))).toString()
            }
        }
    
                require(action in setOf("add", "create", "update", "modify", "upsert")) { "MCP does not support action=$action." }
        val rawJson = args.optString("raw_json").ifBlank { existing?.rawJson.orEmpty() }
        val parsed = parseMcpRawJson(rawJson)
        val url = args.optString("url")
            .ifBlank { args.optString("base_url") }
            .ifBlank { parsed?.url.orEmpty() }
            .ifBlank { existing?.url.orEmpty() }
            .trim()
                require(url.isNotBlank()) { "MCP url is required. If authentication data is missing, ask the user for the key or complete raw_json." }
        val name = args.optString("name")
            .ifBlank { parsed?.name.orEmpty() }
            .ifBlank { existing?.name.orEmpty() }
            .ifBlank { "MCP Server" }
        val authKey = args.optString("auth_key")
            .ifBlank { args.optString("api_key") }
            .ifBlank { args.optString("key") }
            .ifBlank { parsed?.authKey.orEmpty() }
            .ifBlank { existing?.authKey.orEmpty() }
        val transport = normalizeMcpTransport(
            args.optString("transport")
                .ifBlank { parsed?.transport.orEmpty() }
                .ifBlank { existing?.transport.orEmpty() },
        )
        val timeout = args.optInt("timeout_seconds", existing?.timeoutSeconds ?: 30).coerceIn(5, 300)
        val enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing?.enabled ?: true
        val server = McpServerConfig(
            id = existing?.id ?: args.optString("id").ifBlank { AppSettings.newId() },
            name = name,
            url = url,
            authKey = authKey,
            transport = transport,
            timeoutSeconds = timeout,
            enabled = enabled,
            rawJson = buildMcpRawJson(rawJson, name, url, authKey, transport),
            tools = existing?.tools.orEmpty(),
        )
        settings.upsertMcpServer(server)
        val refresh = if (enabled) {
            runCatching { mcpClientManager.testAndRefreshTools(server).getOrThrow() }
        } else {
            Result.success(server.tools)
        }
        val saved = settings.mcpServers().firstOrNull { it.id == server.id } ?: server
        return configResult(
            "mcp_server_saved",
            JSONObject()
                .put("server", mcpServerJson(saved))
                .put("tools_count", saved.tools.size)
                .put("refresh_ok", refresh.isSuccess)
                    .put("message", refresh.exceptionOrNull()?.message.orEmpty().ifBlank { "MCP server saved and tools refreshed." }),
        ).toString()
    }
    
    fun manageSshConfig(action: String, args: JSONObject): String {
        if (action == "list") return configResult("ssh_servers", sshServersJson()).toString()
        val existing = resolveSshServerForConfig(args.optString("id").ifBlank { args.optString("host") }.ifBlank { args.optString("name") })
        when (action) {
            "delete", "remove" -> {
                val target = existing ?: error("SSH server to delete was not found. List configured servers and use an exact id or name.")
                settings.deleteSshServer(target.id)
                return configResult("ssh_server_deleted", JSONObject().put("id", target.id).put("host", target.host)).toString()
            }
            "enable", "disable" -> {
                val target = existing ?: error("SSH server to $action was not found. List configured servers and use an exact id or name.")
                settings.setSshServerEnabled(target.id, action == "enable")
                return configResult("ssh_server_${action}d", sshServerJson(target.copy(enabled = action == "enable"))).toString()
            }
        }
                require(action in setOf("add", "create", "update", "modify", "upsert")) { "SSH does not support action=$action." }
        val host = args.optString("host").ifBlank { existing?.host.orEmpty() }.trim()
        val username = args.optString("username").ifBlank { args.optString("user") }.ifBlank { existing?.username.orEmpty() }.trim()
                require(host.isNotBlank()) { "SSH host is required." }
                require(username.isNotBlank()) { "SSH username is required." }
        val authType = when (args.optString("auth_type").ifBlank { existing?.authType.orEmpty() }.lowercase(Locale.US)) {
            "key", "private_key", "ssh_key" -> AppSettings.SSH_AUTH_KEY
            else -> AppSettings.SSH_AUTH_PASSWORD
        }
        val server = SshServerConfig(
            id = existing?.id ?: args.optString("id").ifBlank { AppSettings.newId() },
            name = args.optString("name").ifBlank { existing?.name.orEmpty() }.ifBlank { host },
            host = host,
            port = args.optInt("port", existing?.port ?: 22).coerceIn(1, 65535),
            username = username,
            authType = authType,
            password = args.optString("password").ifBlank { existing?.password.orEmpty() },
            privateKey = args.optString("private_key").ifBlank { existing?.privateKey.orEmpty() },
            passphrase = args.optString("passphrase").ifBlank { existing?.passphrase.orEmpty() },
            timeoutSeconds = args.optInt("timeout_seconds", existing?.timeoutSeconds ?: 60).coerceIn(5, 600),
            enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing?.enabled ?: true,
        )
                require(server.authType != AppSettings.SSH_AUTH_PASSWORD || server.password.isNotBlank()) { "Password authentication requires password. Ask the user if it was not provided." }
                require(server.authType != AppSettings.SSH_AUTH_KEY || server.privateKey.isNotBlank()) { "Key authentication requires private_key. Ask the user if it was not provided." }
        settings.upsertSshServer(server)
        return configResult("ssh_server_saved", sshServerJson(server)).toString()
    }
    
    fun manageEmailConfig(action: String, args: JSONObject): String {
        if (action == "list") return configResult("email_servers", emailServersJson()).toString()
        val key = args.optString("id").ifBlank { args.optString("email_address") }.ifBlank { args.optString("name") }
        val existing = settings.emailServers().firstOrNull {
            it.id == key || it.name == key || it.stableId.equals(key, ignoreCase = true)
        }
        when (action) {
            "delete", "remove" -> {
                val target = existing ?: error("Email account to delete was not found. List configured accounts and use an exact id or address.")
                settings.deleteEmailServer(target.id)
                return configResult("email_server_deleted", JSONObject().put("id", target.id).put("email_address", target.emailAddress)).toString()
            }
            "enable", "disable" -> {
                val target = existing ?: error("Email account to $action was not found. List configured accounts and use an exact id or address.")
                settings.setEmailServerEnabled(target.id, action == "enable")
                return configResult("email_server_${action}d", emailServerJson(target.copy(enabled = action == "enable"))).toString()
            }
        }
        require(action in setOf("add", "create", "update", "modify", "upsert")) { "Email configuration does not support action=$action." }
        val address = args.optString("email_address").ifBlank { existing?.emailAddress.orEmpty() }.trim()
        val username = args.optString("username").ifBlank { existing?.username.orEmpty() }.ifBlank { address }.trim()
        val password = args.optString("password").ifBlank { existing?.password.orEmpty() }
        val imapHost = args.optString("imap_host").ifBlank { existing?.imapHost.orEmpty() }.trim()
        val smtpHost = args.optString("smtp_host").ifBlank { existing?.smtpHost.orEmpty() }.trim()
        require(address.contains('@') && address.substringAfter('@').contains('.')) { "A valid email_address is required." }
        require(username.isNotBlank() && password.isNotBlank()) { "Email username and password/app password are required. Ask the user; never invent credentials." }
        require(imapHost.isNotBlank() && smtpHost.isNotBlank()) { "Both imap_host and smtp_host are required." }
        val server = EmailServerConfig(
            id = existing?.id ?: args.optString("id").ifBlank { AppSettings.newId() },
            name = args.optString("name").ifBlank { existing?.name.orEmpty() }.ifBlank { address },
            emailAddress = address,
            username = username,
            password = password,
            imapHost = imapHost,
            imapPort = args.optInt("imap_port", existing?.imapPort ?: 993).coerceIn(1, 65535),
            imapSecurity = AppSettings.normalizeEmailSecurity(args.optString("imap_security").ifBlank { existing?.imapSecurity.orEmpty() }),
            smtpHost = smtpHost,
            smtpPort = args.optInt("smtp_port", existing?.smtpPort ?: 465).coerceIn(1, 65535),
            smtpSecurity = AppSettings.normalizeEmailSecurity(args.optString("smtp_security").ifBlank { existing?.smtpSecurity.orEmpty() }),
            enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing?.enabled ?: true,
        )
        settings.upsertEmailServer(server)
        return configResult("email_server_saved", emailServerJson(server)).toString()
    }
    
    fun manageWebDavConfig(action: String, args: JSONObject): String {
        if (action == "list") return configResult("webdav_servers", webDavServersJson()).toString()
        val existing = resolveWebDavServerForConfig(
            args.optString("id")
                .ifBlank { args.optString("url") }
                .ifBlank { args.optString("name") },
        )
        when (action) {
            "delete", "remove" -> {
                val target = existing ?: error("WebDAV server to delete was not found. List configured servers and use an exact id or name.")
                settings.deleteWebDavServer(target.id)
                return configResult("webdav_server_deleted", JSONObject().put("id", target.id).put("name", target.name)).toString()
            }
            "enable", "disable" -> {
                val target = existing ?: error("WebDAV server to $action was not found. List configured servers and use an exact id or name.")
                settings.setWebDavServerEnabled(target.id, action == "enable")
                return configResult("webdav_server_${action}d", webDavServerJson(target.copy(enabled = action == "enable"))).toString()
            }
        }
                require(action in setOf("add", "create", "update", "modify", "upsert")) { "WebDAV does not support action=$action." }
        val url = args.optString("url").ifBlank { args.optString("base_url") }.ifBlank { existing?.url.orEmpty() }.trim()
                require(url.isNotBlank()) { "WebDAV url is required." }
                require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "WebDAV url must use http:// or https://." }
        val server = WebDavServerConfig(
            id = existing?.id ?: args.optString("id").ifBlank { AppSettings.newId() },
            name = args.optString("name").ifBlank { existing?.name.orEmpty() }.ifBlank { runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "WebDAV" } },
            url = url,
            username = args.optString("username").ifBlank { args.optString("user") }.ifBlank { existing?.username.orEmpty() },
            password = args.optString("password").ifBlank { existing?.password.orEmpty() },
            userAgent = args.optString("user_agent").ifBlank { existing?.userAgent.orEmpty() },
            initialPath = args.optString("initial_path").ifBlank { args.optString("path") }.ifBlank { existing?.initialPath.orEmpty() }.ifBlank { "/" },
            note = args.optString("note").ifBlank { existing?.note.orEmpty() },
            trustAllCertificates = if (args.has("trust_all_certificates")) args.optBoolean("trust_all_certificates") else existing?.trustAllCertificates ?: false,
            multiThread = if (args.has("multi_thread")) args.optBoolean("multi_thread") else existing?.multiThread ?: true,
            hideAddressInDrawer = if (args.has("hide_address")) args.optBoolean("hide_address") else existing?.hideAddressInDrawer ?: false,
            enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing?.enabled ?: true,
        )
        settings.upsertWebDavServer(server)
        val test = if (server.enabled) webDavClient.test(server) else Result.success(emptyList())
        return configResult(
            "webdav_server_saved",
            JSONObject()
                .put("server", webDavServerJson(server))
                .put("test_ok", test.isSuccess)
                    .put("message", test.exceptionOrNull()?.message.orEmpty().ifBlank { if (server.url.startsWith("http://", true)) "Saved. Warning: plain HTTP is insecure." else "WebDAV saved and connection test passed." }),
        ).toString()
    }
    
    fun manageFileTransferConfig(target: String, action: String, args: JSONObject): String {
        if (action == "list") return configResult("file_transfer_servers", fileTransferServersJson()).toString()
        val protocolHint = when (target) {
            "ftp", "ftps", "sftp" -> target
            else -> ""
        }
        val existing = resolveFileTransferServerForConfig(
            args.optString("id")
                .ifBlank { args.optString("host") }
                .ifBlank { args.optString("name") },
        )
        when (action) {
            "delete", "remove" -> {
                val targetServer = existing ?: error("File-transfer server to delete was not found. List configured servers and use an exact id or name.")
                settings.deleteFileTransferServer(targetServer.id)
                return configResult("file_transfer_server_deleted", JSONObject().put("id", targetServer.id).put("name", targetServer.name)).toString()
            }
            "enable", "disable" -> {
                val targetServer = existing ?: error("File-transfer server to $action was not found. List configured servers and use an exact id or name.")
                settings.setFileTransferServerEnabled(targetServer.id, action == "enable")
                return configResult("file_transfer_server_${action}d", fileTransferServerJson(targetServer.copy(enabled = action == "enable"))).toString()
            }
        }
                require(action in setOf("add", "create", "update", "modify", "upsert")) { "File-transfer server does not support action=$action." }
        val protocol = AppSettings.normalizeFileTransferProtocol(
            args.optString("protocol")
                .ifBlank { protocolHint }
                .ifBlank { existing?.protocol.orEmpty() }
                .ifBlank { AppSettings.FILE_TRANSFER_SFTP },
        )
        val host = args.optString("host").ifBlank { args.optString("url") }.ifBlank { existing?.host.orEmpty() }.trim()
                require(host.isNotBlank()) { "File-transfer server host is required." }
        val username = args.optString("username").ifBlank { args.optString("user") }.ifBlank { existing?.username.orEmpty() }.trim()
                if (protocol == AppSettings.FILE_TRANSFER_SFTP) require(username.isNotBlank()) { "SFTP requires username. Ask the user if it was not provided." }
        val usePrivateKey = if (args.has("use_private_key")) args.optBoolean("use_private_key") else existing?.usePrivateKey ?: false
        val server = FileTransferServerConfig(
            id = existing?.id ?: args.optString("id").ifBlank { AppSettings.newId() },
            name = args.optString("name").ifBlank { existing?.name.orEmpty() }.ifBlank { "${protocol.uppercase(Locale.US)} $host" },
            protocol = protocol,
            host = host,
            port = args.optInt("port", existing?.port ?: AppSettings.defaultFileTransferPort(protocol)).coerceIn(1, 65535),
            username = username.ifBlank { if (protocol == AppSettings.FILE_TRANSFER_SFTP) "" else "anonymous" },
            password = args.optString("password").ifBlank { existing?.password.orEmpty() },
            usePrivateKey = usePrivateKey,
            privateKey = args.optString("private_key").ifBlank { existing?.privateKey.orEmpty() },
            passphrase = args.optString("passphrase").ifBlank { existing?.passphrase.orEmpty() },
            initialPath = args.optString("initial_path").ifBlank { args.optString("path") }.ifBlank { existing?.initialPath.orEmpty() }.ifBlank { "/" },
            note = args.optString("note").ifBlank { existing?.note.orEmpty() },
            encoding = args.optString("encoding").ifBlank { existing?.encoding.orEmpty() }.ifBlank { "UTF-8" },
            passiveMode = if (args.has("passive_mode")) args.optBoolean("passive_mode") else existing?.passiveMode ?: true,
            explicitFtps = if (args.has("explicit_ftps")) args.optBoolean("explicit_ftps") else existing?.explicitFtps ?: true,
            multiThread = if (args.has("multi_thread")) args.optBoolean("multi_thread") else existing?.multiThread ?: true,
            syncPermissions = if (args.has("sync_permissions")) args.optBoolean("sync_permissions") else existing?.syncPermissions ?: false,
            hideAddressInDrawer = if (args.has("hide_address")) args.optBoolean("hide_address") else existing?.hideAddressInDrawer ?: false,
            enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing?.enabled ?: true,
        )
                require(!server.usePrivateKey || server.privateKey.isNotBlank()) { "Key authentication requires private_key. Ask the user if it was not provided." }
        settings.upsertFileTransferServer(server)
        val test = if (server.enabled) fileTransferClient.test(server) else Result.success(emptyList())
        return configResult(
            "file_transfer_server_saved",
            JSONObject()
                .put("server", fileTransferServerJson(server))
                .put("test_ok", test.isSuccess)
                .put("message", test.exceptionOrNull()?.message.orEmpty().ifBlank {
                        if (server.protocol == AppSettings.FILE_TRANSFER_FTP) "Saved. Warning: FTP is plaintext; prefer SFTP or FTPS." else "File-transfer server saved and connection test passed."
                }),
        ).toString()
    }
    
    fun manageSkillConfig(action: String, args: JSONObject): String {
        if (action == "list") return configResult("skills", skillsJson()).toString()
        val existing = resolveSkillForConfig(args.optString("id").ifBlank { args.optString("name") })
        when (action) {
            "add", "create", "install", "import" -> {
                val url = args.optString("zip_url").ifBlank { args.optString("url") }.trim()
                require(url.isNotBlank()) { "Installing a Skill requires zip_url. If the user provided a web page, read it and locate the actual zip URL." }
                val download = downloadBytes(url)
                val skill = settings.importSkillZipBytes(args.optString("name").ifBlank { download.first }, download.second).getOrThrow()
                args.optString("description").takeIf { it.isNotBlank() }?.let { settings.updateSkillMeta(skill.id, description = it) }
                return configResult("skill_installed", skillJson(settings.installedSkills().firstOrNull { it.id == skill.id } ?: skill)).toString()
            }
            "delete", "remove", "uninstall" -> {
                val target = existing ?: error("Skill to delete was not found. List configured Skills and use an exact id or name.")
                settings.deleteSkill(target.id)
                return configResult("skill_deleted", JSONObject().put("id", target.id).put("name", target.name)).toString()
            }
            "enable", "disable" -> {
                val target = existing ?: error("Skill to $action was not found. List configured Skills and use an exact id or name.")
                settings.setSkillEnabled(target.id, action == "enable")
                return configResult("skill_${action}d", skillJson(target.copy(enabled = action == "enable"))).toString()
            }
            "update", "modify", "rename" -> {
                val target = existing ?: error("Skill to update was not found. List configured Skills and use an exact id or name.")
                settings.updateSkillMeta(target.id, args.optString("name").ifBlank { null }, args.optString("description").ifBlank { null })
                if (args.has("enabled")) settings.setSkillEnabled(target.id, args.optBoolean("enabled"))
                val updated = settings.installedSkills().firstOrNull { it.id == target.id } ?: target
                return configResult("skill_updated", skillJson(updated)).toString()
            }
            else -> error("Skill does not support action=$action.")
        }
    }
    
    fun manageAgentToolConfig(action: String, args: JSONObject): String {
        if (action == "list") return configResult("agent_tools", agentToolsJson()).toString()
        val toolName = args.optString("tool_name").ifBlank { args.optString("name") }.ifBlank { args.optString("id") }.trim()
        require(toolName.isNotBlank()) { "Managing an Agent tool requires tool_name." }
        require(toolName != "manage_app_config") { "manage_app_config is protected and cannot be disabled or deleted." }
        return when (action) {
            "enable" -> {
                settings.setToolEnabled(toolName, true)
                configResult("agent_tool_enabled", JSONObject().put("tool_name", toolName)).toString()
            }
            "disable" -> {
                settings.setToolEnabled(toolName, false)
                configResult("agent_tool_disabled", JSONObject().put("tool_name", toolName)).toString()
            }
            "update", "modify" -> {
                require(args.has("enabled")) { "Agent tools can only be updated with enabled=true or enabled=false." }
                settings.setToolEnabled(toolName, args.optBoolean("enabled"))
                configResult("agent_tool_updated", JSONObject().put("tool_name", toolName).put("enabled", args.optBoolean("enabled"))).toString()
            }
            "delete", "remove" -> error("Built-in Agent tools cannot be deleted; enable or disable them instead.")
            else -> error("Agent tools do not support action=$action.")
        }
    }
    
    fun configResult(type: String, payload: Any): JSONObject {
        return JSONObject()
            .put("schema", "lyra_config_management_result_v1")
            .put("type", type)
            .put("payload", payload)
    }
    
    fun configInventoryJson(): JSONObject {
        return configResult(
            "config_inventory",
            JSONObject()
                .put("agent_tools", agentToolsJson())
                .put("mcp_servers", mcpServersJson())
                .put("ssh_servers", sshServersJson())
                .put("email_servers", emailServersJson())
                .put("webdav_servers", webDavServersJson())
                .put("file_transfer_servers", fileTransferServersJson())
                .put("skills", skillsJson())
                .put("disabled_summary", disabledConfigSummaryJson())
                .put("instruction", "Before enabling an item, confirm its id, name, or tool_name from disabled_summary or the matching list. Use the corresponding target for MCP, SSH, email, WebDAV, file-transfer, Skill, or Agent-tool configuration."),
        )
    }
    
    fun disabledConfigSummaryJson(): JSONObject {
        val disabledTools = settings.disabledTools()
        val mcpServers = settings.mcpServers()
        return JSONObject()
            .put("agent_tools", JSONArray().also { array ->
                agentToolNamesForConfig().filter { it != "manage_app_config" && it in disabledTools }.sorted().forEach { array.put(it) }
            })
            .put("mcp_servers", JSONArray().also { array ->
                mcpServers.filterNot { it.enabled }.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
            })
            .put("mcp_tools_unavailable", JSONArray().also { array ->
                mcpServers.forEach { server ->
                    server.tools.forEach { tool ->
                        val functionName = settings.mcpToolFunctionName(server, tool)
                        if (!server.enabled || functionName in disabledTools) {
                            array.put(
                                JSONObject()
                                    .put("tool_name", functionName)
                                    .put("server_id", server.id)
                                    .put("server_name", server.name)
                                    .put("mcp_tool", tool.name)
                                    .put("server_enabled", server.enabled)
                                    .put("tool_enabled", functionName !in disabledTools),
                            )
                        }
                    }
                }
            })
            .put("ssh_servers", JSONArray().also { array ->
                settings.sshServers().filterNot { it.enabled }.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)) }
            })
            .put("email_servers", JSONArray().also { array ->
                settings.emailServers().filterNot { it.enabled }.forEach {
                    array.put(JSONObject().put("id", it.id).put("name", it.name).put("email_address", it.emailAddress))
                }
            })
            .put("webdav_servers", JSONArray().also { array ->
                settings.webDavServers().filterNot { it.enabled }.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
            })
            .put("file_transfer_servers", JSONArray().also { array ->
                settings.fileTransferServers().filterNot { it.enabled }.forEach {
                    array.put(JSONObject().put("id", it.id).put("name", it.name).put("protocol", it.protocol).put("host", it.host))
                }
            })
            .put("skills", JSONArray().also { array ->
                settings.installedSkills().filterNot { it.enabled }.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)) }
            })
    }
    
    private data class ParsedMcpRawConfig(
        val name: String,
        val url: String,
        val authKey: String,
        val transport: String,
        val serverKey: String,
    )
    
    private fun parseMcpRawJson(rawJson: String): ParsedMcpRawConfig? = runCatching {
        if (rawJson.isBlank()) return@runCatching null
        val root = JSONObject(rawJson)
        val servers = root.optJSONObject("mcpServers")
        val serverKey = servers?.keys()?.asSequence()?.firstOrNull().orEmpty()
        val node = if (serverKey.isNotBlank()) servers?.optJSONObject(serverKey) else root
        node ?: return@runCatching null
        val headers = node.optJSONObject("headers") ?: root.optJSONObject("headers")
        val auth = headers?.optString("Authorization").orEmpty().removePrefix("Bearer ").trim()
        val rawType = node.optString("type").ifBlank { node.optString("transport") }
        ParsedMcpRawConfig(
            name = node.optString("name").ifBlank { serverKey.ifBlank { root.optString("name") } },
            url = node.optString("baseUrl").ifBlank { node.optString("url").ifBlank { root.optString("baseUrl").ifBlank { root.optString("url") } } },
            authKey = auth,
            transport = normalizeMcpTransport(rawType),
            serverKey = serverKey.ifBlank { node.optString("id").ifBlank { "mcp_server" } },
        )
    }.getOrNull()
    
    fun buildMcpRawJson(rawJson: String, name: String, url: String, authKey: String, transport: String): String {
        val parsed = parseMcpRawJson(rawJson)
        val serverKey = parsed?.serverKey?.takeIf { it.isNotBlank() } ?: configKeyPart(name).ifBlank { "mcp_server" }
        val root = runCatching { JSONObject(rawJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val servers = root.optJSONObject("mcpServers") ?: JSONObject()
        val node = servers.optJSONObject(serverKey) ?: JSONObject()
        node.put("type", if (transport == AppSettings.MCP_TRANSPORT_SSE) "sse" else "streamableHttp")
        node.put("name", name)
        node.put("baseUrl", url)
        val headers = node.optJSONObject("headers") ?: JSONObject()
        if (authKey.isNotBlank()) {
            headers.put("Authorization", if (authKey.startsWith("Bearer ", ignoreCase = true)) authKey else "Bearer $authKey")
        }
        node.put("headers", headers)
        servers.put(serverKey, node)
        root.put("mcpServers", servers)
        if (!root.has("protocolVersion")) root.put("protocolVersion", "2025-06-18")
        return root.toString()
    }
    
    fun normalizeMcpTransport(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "sse" -> AppSettings.MCP_TRANSPORT_SSE
            else -> AppSettings.MCP_TRANSPORT_STREAMABLE_HTTP
        }
    }
    
    fun configKeyPart(value: String): String {
        return value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
    }
    
    fun resolveMcpServerForConfig(identifier: String): McpServerConfig? {
        val clean = identifier.trim()
        if (clean.isBlank()) return null
        return settings.mcpServers().firstOrNull { it.id == clean || it.name == clean || it.url == clean }
    }
    
    fun resolveSshServerForConfig(identifier: String): SshServerConfig? {
        val clean = identifier.trim()
        if (clean.isBlank()) return null
        return settings.sshServers().firstOrNull { it.id == clean || it.stableId == clean || it.host == clean || it.name == clean }
    }
    
    fun resolveWebDavServerForConfig(identifier: String): WebDavServerConfig? {
        val clean = identifier.trim().trimEnd('/')
        if (clean.isBlank()) return null
        return settings.webDavServers().firstOrNull {
            it.id == clean || it.name == clean || it.stableId == clean || it.url.trimEnd('/') == clean
        }
    }
    
    fun resolveSkillForConfig(identifier: String): SkillPack? {
        val clean = identifier.trim()
        if (clean.isBlank()) return null
        return settings.installedSkills().firstOrNull { it.id == clean || it.name == clean }
    }
    
    fun downloadBytes(url: String): Pair<String, ByteArray> {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "Download URL must use http:// or https://." }
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: error("Download response has no body.")
            if (!response.isSuccessful) error("Download failed with HTTP ${response.code}: ${body.string().take(500)}")
            val bytes = body.bytes()
            require(bytes.isNotEmpty()) { "Downloaded file is empty." }
            require(bytes.size <= 16 * 1024 * 1024) { "Downloaded file exceeds the 16 MB Skill-import limit." }
            val fileName = response.header("Content-Disposition")
                ?.substringAfter("filename=", "")
                ?.trim('"', '\'')
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { URI(url).path.substringAfterLast('/') }.getOrNull().orEmpty().ifBlank { "Skill.zip" }
            return fileName to bytes
        }
    }
    
    fun mcpServersJson(): JSONArray = JSONArray().also { array ->
        settings.mcpServers().forEach { array.put(mcpServerJson(it)) }
    }
    
    fun mcpServerJson(server: McpServerConfig): JSONObject = JSONObject()
        .put("id", server.id)
        .put("name", server.name)
        .put("url", server.url)
        .put("transport", server.transport)
        .put("timeout_seconds", server.timeoutSeconds)
        .put("enabled", server.enabled)
        .put("tools", JSONArray().also { tools ->
            val disabled = settings.disabledTools()
            server.tools.forEach { tool ->
                val functionName = settings.mcpToolFunctionName(server, tool)
                tools.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("function_name", functionName)
                        .put("description", tool.description)
                        .put("enabled", server.enabled && functionName !in disabled)
                        .put("server_enabled", server.enabled)
                        .put("tool_enabled", functionName !in disabled),
                )
            }
        })
    
    fun sshServersJson(): JSONArray = JSONArray().also { array ->
        settings.sshServers().forEach { array.put(sshServerJson(it)) }
    }
    
    fun sshServerJson(server: SshServerConfig): JSONObject = JSONObject()
        .put("id", server.id)
        .put("stable_id", server.stableId)
        .put("name", server.name)
        .put("host", server.host)
        .put("port", server.port)
        .put("username", server.username)
        .put("auth_type", server.authType)
        .put("timeout_seconds", server.timeoutSeconds)
        .put("enabled", server.enabled)
        .put("has_password", server.password.isNotBlank())
        .put("has_private_key", server.privateKey.isNotBlank())
    
    fun emailServersJson(): JSONArray = JSONArray().also { array ->
        settings.emailServers().forEach { array.put(emailServerJson(it)) }
    }
    
    fun emailServerJson(server: EmailServerConfig): JSONObject = JSONObject()
        .put("id", server.id)
        .put("stable_id", server.stableId)
        .put("name", server.name)
        .put("email_address", server.emailAddress)
        .put("username", server.username)
        .put("imap_host", server.imapHost)
        .put("imap_port", server.imapPort)
        .put("imap_security", server.imapSecurity)
        .put("smtp_host", server.smtpHost)
        .put("smtp_port", server.smtpPort)
        .put("smtp_security", server.smtpSecurity)
        .put("enabled", server.enabled)
        .put("has_password", server.password.isNotBlank())
    
    fun webDavServersJson(): JSONArray = JSONArray().also { array ->
        settings.webDavServers().forEach { array.put(webDavServerJson(it)) }
    }
    
    fun webDavServerJson(server: WebDavServerConfig): JSONObject = JSONObject()
        .put("id", server.id)
        .put("stable_id", server.stableId)
        .put("name", server.name)
        .put("url", server.url)
        .put("username", server.username)
        .put("initial_path", server.initialPath)
        .put("note", server.note)
        .put("enabled", server.enabled)
        .put("trust_all_certificates", server.trustAllCertificates)
        .put("multi_thread", server.multiThread)
        .put("hide_address", server.hideAddressInDrawer)
        .put("has_password", server.password.isNotBlank())
    
    fun fileTransferServersJson(): JSONArray = JSONArray().also { array ->
        settings.fileTransferServers().forEach { array.put(fileTransferServerJson(it)) }
    }
    
    fun resolveFileTransferServerForConfig(key: String): FileTransferServerConfig? {
        val clean = key.trim()
        if (clean.isBlank()) return null
        return settings.fileTransferServers().firstOrNull {
            it.id == clean ||
                it.name.equals(clean, ignoreCase = true) ||
                it.host.equals(clean, ignoreCase = true) ||
                it.stableId.equals(clean, ignoreCase = true)
        }
    }
    
    fun fileTransferServerJson(server: FileTransferServerConfig): JSONObject = JSONObject()
        .put("id", server.id)
        .put("stable_id", server.stableId)
        .put("name", server.name)
        .put("protocol", server.protocol)
        .put("host", server.host)
        .put("port", server.port)
        .put("username", server.username)
        .put("initial_path", server.initialPath)
        .put("note", server.note)
        .put("encoding", server.encoding)
        .put("enabled", server.enabled)
        .put("use_private_key", server.usePrivateKey)
        .put("passive_mode", server.passiveMode)
        .put("explicit_ftps", server.explicitFtps)
        .put("multi_thread", server.multiThread)
        .put("sync_permissions", server.syncPermissions)
        .put("hide_address", server.hideAddressInDrawer)
        .put("has_password", server.password.isNotBlank())
        .put("has_private_key", server.privateKey.isNotBlank())
    
    fun skillsJson(): JSONArray = JSONArray().also { array ->
        settings.installedSkills().forEach { array.put(skillJson(it)) }
    }
    
    fun skillJson(skill: SkillPack): JSONObject = JSONObject()
        .put("id", skill.id)
        .put("name", skill.name)
        .put("description", skill.description)
        .put("enabled", skill.enabled)
        .put("file_count", skill.fileCount)
    
    fun agentToolsJson(): JSONArray {
        val disabled = settings.disabledTools()
        val mcpToolMeta = allMcpToolMetaForConfig()
        val names = agentToolNamesForConfig()
        return JSONArray().also { array ->
            names.forEach { name ->
                val mcpMeta = mcpToolMeta[name]
                val serverEnabled = mcpMeta?.first ?: true
                val item = JSONObject()
                    .put("name", name)
                    .put("enabled", name == "manage_app_config" || (name !in disabled && serverEnabled))
                    .put("deletable", false)
                    .put("protected", name == "manage_app_config")
                item.apply {
                    mcpMeta?.let { (mcpServerEnabled, serverName, toolName) ->
                        put("source", "mcp")
                        put("server_enabled", mcpServerEnabled)
                        put("server_name", serverName)
                        put("mcp_tool", toolName)
                        put("tool_enabled", name !in disabled)
                        put("available_in_prompt", mcpServerEnabled && name !in disabled)
                    } ?: put("source", "local")
                }
                array.put(item)
            }
        }
    }
    
    fun agentToolNamesForConfig(): List<String> {
        return (configurableAgentTools + allMcpToolMetaForConfig().keys)
            .distinct()
            .sorted()
    }
    
    fun allMcpToolMetaForConfig(): Map<String, Triple<Boolean, String, String>> {
        return buildMap {
            settings.mcpServers().forEach { server ->
                server.tools.forEach { tool ->
                    put(settings.mcpToolFunctionName(server, tool), Triple(server.enabled, server.name, tool.name))
                }
            }
        }
    }
}
