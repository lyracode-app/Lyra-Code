package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.BackupOptions
import com.yukisoffd.lyracode.data.EmailServerConfig
import com.yukisoffd.lyracode.data.FileTransferServerConfig
import com.yukisoffd.lyracode.data.WebDavServerConfig
import com.yukisoffd.lyracode.email.EmailClient
import com.yukisoffd.lyracode.email.EmailComposeRequest
import com.yukisoffd.lyracode.email.OutgoingAttachment
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

internal class AgentRemoteToolHandler(
    private val settings: AppSettings,
    private val nativeFileManager: NativeFileManager,
    private val globalFileManager: GlobalFileManager,
    private val emailClient: EmailClient,
    private val sshExecutor: SshExecutor,
    private val webDavClient: WebDavClient,
    private val fileTransferClient: FileTransferClient,
    private val backupManager: BackupManager,
    private val onConfigChanged: suspend () -> Unit,
) {
    val toolNames: Set<String> = setOf(
        "list_email_accounts",
        "list_email_folders",
        "list_emails",
        "read_email",
        "set_email_flags",
        "download_email_attachment",
        "record_email_attachment_scan",
        "save_email_draft",
        "send_email",
        "list_ssh_servers",
        "ssh_exec",
        "list_webdav_servers",
        "webdav_list",
        "webdav_search",
        "webdav_download_to_workspace",
        "webdav_upload_from_workspace",
        "list_file_transfer_servers",
        "file_transfer_list",
        "file_transfer_search",
        "file_transfer_download_to_workspace",
        "file_transfer_upload_from_workspace",
        "export_backup",
        "import_backup",
    )

    suspend fun execute(toolName: String, args: JSONObject): ToolExecution = when (toolName) {
        "list_email_accounts" -> ToolExecution(emailClient.accountsJson(settings.emailServers()))
        "list_email_folders" -> ToolExecution(emailClient.listFolders(resolveEmailAccount(args)))
        "list_emails" -> ToolExecution(
            emailClient.listMessages(
                account = resolveEmailAccount(args),
                folderName = args.optString("folder").ifBlank { "INBOX" },
                unreadOnly = args.optBoolean("unread_only", false),
                limit = args.optInt("limit", 30),
            ),
        )
        "read_email" -> ToolExecution(
            emailClient.readMessage(
                resolveEmailAccount(args),
                args.optString("folder").ifBlank { "INBOX" },
                args.getLong("uid"),
            ),
        )
        "set_email_flags" -> ToolExecution(
            emailClient.setFlags(
                resolveEmailAccount(args),
                args.optString("folder").ifBlank { "INBOX" },
                args.getLong("uid"),
                args.booleanOrNull("seen"),
                args.booleanOrNull("flagged"),
            ),
        )
        "download_email_attachment" -> ToolExecution(
            emailClient.downloadAttachment(
                resolveEmailAccount(args),
                args.optString("folder").ifBlank { "INBOX" },
                args.getLong("uid"),
                args.getInt("attachment_id"),
            ),
        )
        "record_email_attachment_scan" -> ToolExecution(
            emailClient.recordAttachmentScan(args.getString("attachment_token"), args.getBoolean("safe")),
        )
        "save_email_draft" -> ToolExecution(emailClient.saveDraft(resolveEmailAccount(args), emailComposeRequest(args)))
        "send_email" -> ToolExecution(emailClient.send(resolveEmailAccount(args), emailComposeRequest(args)))
        "list_ssh_servers" -> ToolExecution(sshExecutor.availableServers())
        "ssh_exec" -> {
            val server = settings.resolveSshServer(args.getString("server_id"))
                ?: error("SSH server is missing or disabled: ${args.optString("server_id")}. Call list_ssh_servers and use a returned id.")
            val timeoutSeconds = args.optInt("timeout_seconds", server.timeoutSeconds).coerceIn(5, 600)
            val result = sshExecutor.execute(
                server = server,
                command = args.toolCommandArgument(),
                cwd = args.optString("cwd"),
                inputLines = args.optJSONArray("input_lines")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) add(array.optString(index))
                    }
                }.orEmpty(),
                timeoutSeconds = timeoutSeconds,
            )
            if (result.ok) ToolExecution(result.message) else error(result.message)
        }
        "list_webdav_servers" -> ToolExecution(webDavClient.serversJson(settings.webDavServers().filter { it.enabled }))
        "webdav_list" -> {
            val server = settings.resolveWebDavServer(args.getString("server_id"))
                ?: error("WebDAV server is missing or disabled: ${args.optString("server_id")}. Call list_webdav_servers and use a returned id.")
            val files = webDavClient.list(
                server = server,
                path = args.optString("path").ifBlank { server.initialPath.ifBlank { "/" } },
                depth = args.optInt("depth", 1).coerceIn(0, 2),
            )
            ToolExecution(webDavFilesJson(server, files).put("path", args.optString("path").ifBlank { server.initialPath.ifBlank { "/" } }).toString())
        }
        "webdav_search" -> {
            val server = settings.resolveWebDavServer(args.getString("server_id"))
                ?: error("WebDAV server is missing or disabled: ${args.optString("server_id")}. Call list_webdav_servers and use a returned id.")
            val files = webDavClient.search(
                server = server,
                query = args.getString("query"),
                basePath = args.optString("path").ifBlank { server.initialPath },
                limit = args.optInt("limit", 80).coerceIn(1, 200),
            )
            ToolExecution(webDavFilesJson(server, files).toString())
        }
        "webdav_download_to_workspace" -> {
            val server = settings.resolveWebDavServer(args.getString("server_id"))
                ?: error("WebDAV server is missing or disabled: ${args.optString("server_id")}. Call list_webdav_servers and use a returned id.")
            val bytes = webDavClient.download(server, args.getString("remote_path"))
            val message = nativeFileManager.writeBytes(args.getString("local_path"), bytes).getOrThrow()
            ToolExecution("$message\nDownloaded ${bytes.size} bytes from WebDAV.")
        }
        "webdav_upload_from_workspace" -> {
            val server = settings.resolveWebDavServer(args.getString("server_id"))
                ?: error("WebDAV server is missing or disabled: ${args.optString("server_id")}. Call list_webdav_servers and use a returned id.")
            val bytes = nativeFileManager.readBytes(args.getString("local_path")).getOrThrow()
            webDavClient.upload(server, args.getString("remote_path"), bytes)
            ToolExecution("Uploaded to WebDAV: ${server.name}:${args.getString("remote_path")}; ${bytes.size} bytes.")
        }
        "list_file_transfer_servers" -> ToolExecution(fileTransferClient.serversJson(settings.fileTransferServers().filter { it.enabled }))
        "file_transfer_list" -> {
            val server = settings.resolveFileTransferServer(args.getString("server_id"))
                ?: error("File-transfer server is missing or disabled: ${args.optString("server_id")}. Call list_file_transfer_servers and use a returned id.")
            val path = args.optString("path").ifBlank { server.initialPath.ifBlank { "/" } }
            val files = fileTransferClient.list(server, path)
            ToolExecution(fileTransferFilesJson(server, files).put("path", path).toString())
        }
        "file_transfer_search" -> {
            val server = settings.resolveFileTransferServer(args.getString("server_id"))
                ?: error("File-transfer server is missing or disabled: ${args.optString("server_id")}. Call list_file_transfer_servers and use a returned id.")
            val files = fileTransferClient.search(
                server = server,
                query = args.getString("query"),
                basePath = args.optString("path").ifBlank { server.initialPath.ifBlank { "/" } },
                limit = args.optInt("limit", 80).coerceIn(1, 200),
            )
            ToolExecution(fileTransferFilesJson(server, files).toString())
        }
        "file_transfer_download_to_workspace" -> {
            val server = settings.resolveFileTransferServer(args.getString("server_id"))
                ?: error("File-transfer server is missing or disabled: ${args.optString("server_id")}. Call list_file_transfer_servers and use a returned id.")
            val bytes = fileTransferClient.download(server, args.getString("remote_path"))
            val message = nativeFileManager.writeBytes(args.getString("local_path"), bytes).getOrThrow()
            ToolExecution("$message\nDownloaded ${bytes.size} bytes from ${server.protocol.uppercase(Locale.US)}.")
        }
        "file_transfer_upload_from_workspace" -> {
            val server = settings.resolveFileTransferServer(args.getString("server_id"))
                ?: error("File-transfer server is missing or disabled: ${args.optString("server_id")}. Call list_file_transfer_servers and use a returned id.")
            val bytes = nativeFileManager.readBytes(args.getString("local_path")).getOrThrow()
            fileTransferClient.upload(server, args.getString("remote_path"), bytes)
            ToolExecution("Uploaded to ${server.protocol.uppercase(Locale.US)}: ${server.name}:${args.getString("remote_path")}; ${bytes.size} bytes.")
        }
        "export_backup" -> {
            val options = parseBackupOptions(args)
            val destination = args.optString("destination", "local").lowercase(Locale.US)
            if (destination == "webdav") {
                val server = settings.resolveWebDavServer(args.getString("server_id"))
                    ?: error("WebDAV server is missing or disabled: ${args.optString("server_id")}. Call list_webdav_servers and use a returned id.")
                val remotePath = args.optString("remote_path").ifBlank { DEFAULT_WEBDAV_BACKUP_PATH }
                val bytes = backupManager.exportZip(options)
                webDavClient.upload(server, remotePath, bytes)
                ToolExecution(
                    "Exported and uploaded the backup to WebDAV: ${server.name}:$remotePath; ${bytes.size} bytes.\n" +
                        "When remote_path is omitted, the stable latest-backup path is overwritten so a later import does not need a timestamped name.",
                )
            } else {
                ToolExecution(backupManager.exportToDownloads(options))
            }
        }
        "import_backup" -> {
            val source = args.optString("source", "local").lowercase(Locale.US)
            val result = if (source == "webdav") {
                val server = settings.resolveWebDavServer(args.getString("server_id"))
                    ?: error("WebDAV server is missing or disabled: ${args.optString("server_id")}. Call list_webdav_servers and use a returned id.")
                val remotePath = resolveWebDavBackupPath(server, args.optString("remote_path"))
                val bytes = webDavClient.download(server, remotePath)
                backupManager.importZip(bytes, "supplement")
            } else if (source == "download" || source == "global") {
                val path = args.optString("global_path").ifBlank { args.optString("local_path") }
                val bytes = globalFileManager.readBytes(path).getOrThrow()
                backupManager.importZip(bytes, "supplement")
            } else {
                val bytes = nativeFileManager.readBytes(args.getString("local_path")).getOrThrow()
                backupManager.importZip(bytes, "supplement")
            }
            onConfigChanged()
            ToolExecution("Imported the backup in supplement mode: $result")
        }
        else -> error("Unsupported remote tool: $toolName")
    }

    fun resolveWebDavBackupPath(server: WebDavServerConfig, requestedPath: String): String {
        val explicit = requestedPath.trim()
        if (explicit.isNotBlank()) return explicit
        val files = runCatching { webDavClient.list(server, "/LyraCode", depth = 1) }.getOrDefault(emptyList())
        val latest = files
            .filter { !it.directory && it.path.endsWith(".zip", ignoreCase = true) }
            .filter {
                val name = it.path.substringAfterLast('/').lowercase(Locale.US)
                "backup" in name || "lyra" in name
            }
        latest.firstOrNull { it.path.equals(DEFAULT_WEBDAV_BACKUP_PATH, ignoreCase = true) }?.let { return it.path }
        return latest.maxWithOrNull(
            compareBy<com.yukisoffd.lyracode.webdav.WebDavFile> { parseWebDavModifiedMillis(it.modified) }
                .thenBy { it.path },
        )?.path ?: DEFAULT_WEBDAV_BACKUP_PATH
    }
    
    fun parseWebDavModifiedMillis(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }
    
    fun webDavFilesJson(server: WebDavServerConfig, files: List<com.yukisoffd.lyracode.webdav.WebDavFile>): JSONObject = JSONObject()
        .put("schema", "lyra_webdav_files_v1")
        .put("server_id", server.id)
        .put("server_name", server.name)
        .put("files", JSONArray().also { array ->
            files.forEach { file ->
                array.put(
                    JSONObject()
                        .put("path", file.path)
                        .put("directory", file.directory)
                        .put("size", file.size)
                        .put("modified", file.modified),
                )
            }
        })
    
    fun fileTransferFilesJson(server: FileTransferServerConfig, files: List<com.yukisoffd.lyracode.filetransfer.FileTransferFile>): JSONObject = JSONObject()
        .put("schema", "lyra_file_transfer_files_v1")
        .put("server_id", server.id)
        .put("server_name", server.name)
        .put("protocol", server.protocol)
        .put("files", JSONArray().also { array ->
            files.forEach { file ->
                array.put(
                    JSONObject()
                        .put("path", file.path)
                        .put("directory", file.directory)
                        .put("size", file.size)
                        .put("modified", file.modified),
                )
            }
        })
    
    fun resolveEmailAccount(args: JSONObject): EmailServerConfig =
        settings.resolveEmailServer(args.getString("account_id"))
            ?: error("Email account is missing or disabled: ${args.optString("account_id")}. Call list_email_accounts and use a returned id.")
    
    fun emailComposeRequest(args: JSONObject): EmailComposeRequest {
        fun strings(name: String): List<String> = args.optJSONArray(name)?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()
        val attachmentPaths = strings("attachments")
        val attachments = attachmentPaths.map { path ->
            val bytes = nativeFileManager.readBytes(path, EmailClient.MAX_ATTACHMENT_BYTES.toLong() + 1L).getOrThrow()
            require(bytes.size <= EmailClient.MAX_ATTACHMENT_BYTES) {
                "Attachment exceeds 20 MB: $path. Use a cloud link or file-transfer service instead."
            }
            OutgoingAttachment(path.substringAfterLast('/').substringAfterLast('\\'), bytes)
        }
        require(attachments.sumOf { it.bytes.size.toLong() } <= EmailClient.MAX_ATTACHMENT_BYTES) {
            "Combined attachments exceed 20 MB. Use a cloud link or file-transfer service instead."
        }
        return EmailComposeRequest(
            to = strings("to"),
            cc = strings("cc"),
            bcc = strings("bcc"),
            subject = args.getString("subject"),
            textBody = args.optString("text_body"),
            htmlBody = args.optString("html_body"),
            attachments = attachments,
            replyFolder = args.optString("reply_folder"),
            replyUid = args.optLong("reply_uid", 0L),
            allowReplyToAnswered = args.optBoolean("allow_reply_to_answered", false),
        )
    }
    
    fun parseBackupOptions(args: JSONObject): BackupOptions = BackupOptions(
        includeProfile = args.optBoolean("include_profile", true),
        includeConversations = args.optBoolean("include_conversations", true),
        includeModelProfiles = args.optBoolean("include_model_profiles", true),
        includeMcp = args.optBoolean("include_mcp", true),
        includeSsh = args.optBoolean("include_ssh", true),
        includeEmail = args.optBoolean("include_email", true),
        includePrompts = args.optBoolean("include_prompts", true),
        includeMemories = args.optBoolean("include_memories", true),
        includeSkills = args.optBoolean("include_skills", true),
        includeWebDav = args.optBoolean("include_webdav", true),
        includeFileTransfer = args.optBoolean("include_file_transfer", true),
        includeSecrets = args.optBoolean("include_secrets", false),
    )

    private companion object {
        const val DEFAULT_WEBDAV_BACKUP_PATH = "/LyraCode/lyra_backup_latest.zip"
    }
}

