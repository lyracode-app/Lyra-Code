package com.yukisoffd.lyracode.ai

import android.util.Log
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.MediaGenerationKind
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.termux.TermuxExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal const val VISION_UNDERSTANDING_TOOL_NAME = "analyze_image"
internal const val OCR_TOOL_NAME = "extract_image_text"

internal class AgentToolSchemaFactory(
    private val settings: AppSettings,
    private val termuxExecutor: TermuxExecutor,
    private val systemCommandExecutor: SystemCommandExecutor,
    private val prootAvailable: () -> Boolean,
) {
    fun toolDefinitions(
        allowSubAgents: Boolean = false,
        allowedToolNames: Set<String>? = null,
    ): JSONArray {
        val definitions = JSONArray()
        .put(function("list_directory", "List files and subdirectories in the workspace. Use a workspace-relative path; use \".\" or an empty string for the root.", "path" to "string"))
        .put(function("read_file", "Read a workspace text file up to 1 MB. Use a workspace-relative path, never a Termux-private path.", "path" to "string"))
        .put(
            functionWithOptional(
                "read_file_lines",
                "Read a line range from a workspace text file up to 16 MB and return real line numbers. Use this for targeted inspection before edit_file.",
                required = listOf("path" to "string"),
                optional = listOf("start_line" to "integer", "line_count" to "integer"),
            ),
        )
        .put(
            functionWithOptional(
                "write_file",
                "Create or fully replace a workspace text file. Prefer edit_file for existing files. Supply exactly one of content or content_lines; content_lines must be a JSON array of strings, not a serialized array.",
                required = listOf("path" to "string"),
                optional = listOf("content" to "string", "content_lines" to "array:string", "ensure_trailing_newline" to "boolean"),
                propertyDescriptions = FILE_WRITE_PROPERTY_DESCRIPTIONS,
                disallowAdditionalProperties = true,
            ),
        )
        .put(
            functionWithOptional(
                "edit_file",
                "Precisely edit an existing workspace text file up to 16 MB. Read the relevant context first. Choose one mode: replace unique old_content/old_content_lines with new_content/new_content_lines, or replace the inclusive 1-based start_line..end_line range. Text mode defaults to exactly one match and rejects mismatches.",
                required = listOf("path" to "string"),
                optional = listOf(
                    "old_content" to "string",
                    "old_content_lines" to "array:string",
                    "new_content" to "string",
                    "new_content_lines" to "array:string",
                    "start_line" to "integer",
                    "end_line" to "integer",
                    "expected_replacements" to "integer",
                    "ensure_trailing_newline" to "boolean",
                ),
                propertyDescriptions = FILE_EDIT_PROPERTY_DESCRIPTIONS,
                disallowAdditionalProperties = true,
            ),
        )
        .put(
            functionWithOptional(
                "append_file",
                "Append text to a workspace file. Supply exactly one of content or content_lines; content_lines must be a JSON string array with one element per line.",
                required = listOf("path" to "string"),
                optional = listOf("content" to "string", "content_lines" to "array:string", "ensure_trailing_newline" to "boolean"),
                propertyDescriptions = FILE_WRITE_PROPERTY_DESCRIPTIONS,
                disallowAdditionalProperties = true,
            ),
        )
        .put(function("create_folder", "Create a directory in the workspace. path must be workspace-relative.", "path" to "string"))
        .put(function("delete_file_or_folder", "Delete a workspace file or empty directory. path must be workspace-relative.", "path" to "string"))
        .put(function("rename_move", "Rename or move a file or directory within the workspace.", "from" to "string", "to" to "string"))
        .put(function("global_list_directory", "List Android shared-storage files and directories outside the workspace. path may be Download, Downloads, a shared-storage-relative path, or a path under /storage/emulated/0. Android/data, Android/obb, and /data are blocked.", "path" to "string"))
        .put(function("global_read_file", "Read an Android shared-storage text file up to 1 MB, including files outside the workspace.", "path" to "string"))
        .put(
            functionWithOptional(
                "global_read_file_lines",
                "Read a line range from an Android shared-storage text file up to 16 MB and return real line numbers. Use this before global_edit_file.",
                required = listOf("path" to "string"),
                optional = listOf("start_line" to "integer", "line_count" to "integer"),
            ),
        )
        .put(
            functionWithOptional(
                "global_write_file",
                "Create or fully replace an Android shared-storage text file after user approval. Prefer global_edit_file for existing files. Supply exactly one of content or content_lines; content_lines must be a JSON string array.",
                required = listOf("path" to "string"),
                optional = listOf("content" to "string", "content_lines" to "array:string", "ensure_trailing_newline" to "boolean"),
                propertyDescriptions = GLOBAL_FILE_WRITE_PROPERTY_DESCRIPTIONS,
                disallowAdditionalProperties = true,
            ),
        )
        .put(
            functionWithOptional(
                "global_edit_file",
                "Precisely edit an existing Android shared-storage text file up to 16 MB after user approval. Use unique old/new content or an inclusive 1-based line range. The write is rejected when the match count differs from expected_replacements.",
                required = listOf("path" to "string"),
                optional = listOf(
                    "old_content" to "string",
                    "old_content_lines" to "array:string",
                    "new_content" to "string",
                    "new_content_lines" to "array:string",
                    "start_line" to "integer",
                    "end_line" to "integer",
                    "expected_replacements" to "integer",
                    "ensure_trailing_newline" to "boolean",
                ),
                propertyDescriptions = GLOBAL_FILE_EDIT_PROPERTY_DESCRIPTIONS,
                disallowAdditionalProperties = true,
            ),
        )
        .put(
            functionWithOptional(
                "global_append_file",
                "Append text to an Android shared-storage file after user approval. Supply exactly one of content or content_lines; content_lines must be a JSON string array.",
                required = listOf("path" to "string"),
                optional = listOf("content" to "string", "content_lines" to "array:string", "ensure_trailing_newline" to "boolean"),
                propertyDescriptions = GLOBAL_FILE_WRITE_PROPERTY_DESCRIPTIONS,
                disallowAdditionalProperties = true,
            ),
        )
        .put(function("global_create_folder", "Create an Android shared-storage directory after user approval.", "path" to "string"))
        .put(function("global_delete_file_or_folder", "Delete an Android shared-storage file or directory after user approval.", "path" to "string"))
        .put(function("global_rename_move", "Move or rename an Android shared-storage file or directory after user approval.", "from" to "string", "to" to "string"))
        .put(
            functionWithOptional(
                "download_file",
                "Download an HTTP/HTTPS file with Lyra Code's native client after user approval; prefer this over curl/wget. For destination=workspace, path is workspace-relative; without a selected workspace it falls back to Download/LyraCode/<path>. For destination=global, path is in Android shared storage. headers entries use \"Name: Value\"; sha256 verifies integrity.",
                required = listOf("url" to "string", "path" to "string"),
                optional = listOf(
                    "destination" to "string",
                    "headers" to "array:string",
                    "sha256" to "string",
                    "timeout_seconds" to "integer",
                ),
            ),
        )
        .put(
            functionWithOptional(
                "manage_scheduled_tasks",
                "Manage Lyra Code background scheduled tasks. action=list is read-only; create/update/delete/enable/disable require approval. schedule_type is once, daily, weekly, or monthly. once uses run_at; daily uses hour/minute; weekly also uses day_of_week (1=Monday, 7=Sunday); monthly also uses day_of_month. A task may select its own profile_id and model and does not appear in normal chat history.",
                required = listOf("action" to "string"),
                optional = listOf(
                    "task_id" to "string",
                    "title" to "string",
                    "prompt" to "string",
                    "schedule_type" to "string",
                    "run_at" to "string",
                    "hour" to "integer",
                    "minute" to "integer",
                    "day_of_week" to "integer",
                    "day_of_month" to "integer",
                    "profile_id" to "string",
                    "model" to "string",
                    "enabled" to "boolean",
                ),
            ),
        )
        .put(function("get_mini_server_status", "Get the Lyra Code mini server state, workspace root, bind address, local URL, and LAN URLs."))
        .put(
            functionWithOptional(
                "read_mini_server_logs",
                "Read recent Lyra Code mini-server logs for connections, assets, 404s, authentication failures, and browser JavaScript errors. Use this to debug static, Vue, Vite, VitePress, HTML, CSS, or JS sites. level is debug/info/warn/error; limit is at most 500.",
                required = emptyList(),
                optional = listOf("limit" to "integer", "level" to "string"),
            ),
        )
        .put(
            functionWithOptional(
                "manage_mini_server",
                "Start, stop, restart, reset, inspect, or update Lyra Code's HTTP/HTTPS static server rooted at the current workspace. action=status/update/start/stop/restart/reset. host=127.0.0.1 is device-only; 0.0.0.0 exposes it to the LAN or port mapping. username/password enable Basic auth; an empty password disables auth. HTTPS accepts a base64 keystore or PEM certificate chain/private key. force_https redirects HTTP to HTTPS.",
                required = listOf("action" to "string"),
                optional = listOf(
                    "protocol" to "string",
                    "host" to "string",
                    "port" to "integer",
                    "username" to "string",
                    "password" to "string",
                    "custom_domains" to "array:string",
                    "force_https" to "boolean",
                    "tls_key_store_base64" to "string",
                    "tls_key_store_password" to "string",
                    "tls_certificate_chain" to "string",
                    "tls_private_key" to "string",
                    "spa_fallback" to "boolean",
                    "directory_listing" to "boolean",
                    "mdns_enabled" to "boolean",
                    "mdns_name" to "string",
                ),
            ),
        )
        .put(
            functionWithOptional(
                "search_conversation_history",
                "Search normal conversation history by keyword and time range. Returns conversation IDs, titles, timestamps, message counts, and short previews, never hidden reasoning or tool calls. start_time/end_time accept epoch timestamps, yyyy-MM-dd, yyyy-MM-dd HH:mm, or ISO-8601.",
                required = emptyList(),
                optional = listOf("query" to "string", "start_time" to "string", "end_time" to "string", "limit" to "integer"),
            ),
        )
        .put(
            functionWithOptional(
                "read_conversation_history",
                "Read user messages and visible assistant replies from one or more normal conversations. Hidden reasoning, tool calls, and tool results are excluded. Call search_conversation_history first to obtain IDs.",
                required = emptyList(),
                optional = listOf("conversation_id" to "string", "conversation_ids" to "array:string", "max_messages" to "integer"),
            ),
        )
        .put(
            functionWithOptional(
                "read_memories",
                "Read cross-conversation user memories and their IDs. Enabled memories are already injected into the prompt; call this only to look up IDs, verify, filter, update, disable, or delete entries. Disabled entries are excluded by default.",
                required = emptyList(),
                optional = listOf("query" to "string", "include_disabled" to "boolean"),
            ),
        )
        .put(
            functionWithOptional(
                "save_memory",
                "Save one durable cross-conversation memory that the user explicitly stated and that will remain useful, such as a preference, work style, or communication habit. Never save secrets, inferred sensitive traits, temporary tasks, or one-off context. category is preference, work_style, communication, personal, or other.",
                required = listOf("content" to "string"),
                optional = listOf("category" to "string"),
            ),
        )
        .put(
            functionWithOptional(
                "update_memory",
                "Update an existing memory. Call read_memories first to obtain its id, then provide at least one of content, category, or enabled.",
                required = listOf("id" to "string"),
                optional = listOf("content" to "string", "category" to "string", "enabled" to "boolean"),
            ),
        )
        .put(function("delete_memory", "Delete a memory that no longer applies or that the user asked to forget. Call read_memories first to obtain its id.", "id" to "string"))
        .put(function("search_files", "Search the workspace by file name, extension, or path fragment. Always use this before shell-based file discovery. query is a name or keyword; path is \".\" or a relative subdirectory.", "query" to "string", "path" to "string"))
        .put(function("global_search_files", "Search /storage/emulated/0 by file name or path fragment. Use only after search_files returns SEARCH_EMPTY and the target may be outside the workspace. Returns absolute shared-storage paths that can be passed to global_* file tools.", "query" to "string"))
        .put(function("get_file_info", "Get metadata for a workspace file or directory.", "path" to "string"))
        .put(function("list_skill_files", "List files in an enabled Skill package. First determine relevance from LYRA_ACTIVE_SKILLS_V1, then inspect the package.", "skill_id" to "string"))
        .put(function("read_skill_file", "Read a text file from a Skill package. Start with SKILL.md and read only files relevant to the current task.", "skill_id" to "string", "path" to "string"))
        if (termuxExecutor.hasRunCommandPermission()) {
            definitions.put(
                functionWithOptional(
                    "run_command",
                    "Run a shell command in Termux and return exit_code, stdout, and stderr. High-risk commands may be blocked. Prefer download_file over curl/wget. Do not start interactive processes. For a persistent service or watcher, set background=true: Lyra closes inherited input/output, saves launcher output to the returned output_file, and returns without waiting for the process. A standalone shell & is auto-detected for compatibility. A successful launch does not prove the service is healthy, so inspect its process or log with a separate call. For multiline or indentation-sensitive commands, use command_lines; Lyra Code joins its string elements with newlines. Foreground timeout defaults to 60 seconds and has a maximum of 600; a background launch waits at most 15 seconds for acknowledgement.",
                    required = emptyList(),
                    optional = listOf("command" to "string", "command_lines" to "array:string", "workDir" to "string", "timeout_seconds" to "integer", "background" to "boolean"),
                ),
            )
        }
        if (prootAvailable()) definitions.put(prootCommandToolDefinition())
        definitions
        .put(function("web_search", "Search the web in the embedded WebView and return candidate titles, URLs, and snippets. User-blocked sites are filtered. Use for current or web-specific information, then verify candidates with read_web_page.", "query" to "string", "limit" to "integer"))
        .put(function("read_web_page", "Open an HTTP/HTTPS page in the embedded WebView and extract its body. User-blocked domains are rejected. Read trustworthy candidates and base factual claims on page content, not search snippets.", "url" to "string"))
        .put(function("mark_web_sources", "Declare the web pages actually used in the answer. Call only when the answer relies on web content. sources is an array of objects with title, url, and used_for. Then cite those pages with nearby Markdown links.", "sources" to "array:object"))
        .put(
            functionWithOptional(
                "manage_app_config",
                "Manage MCP, SSH, email (IMAP/SMTP), WebDAV, FTP/FTPS/SFTP, Skill, and Agent-tool configuration when the user asks to add, update, enable, disable, or delete it. If the target is ambiguous, call target=all action=list and inspect disabled_summary. Ask for missing account credentials; never invent them. Agent tools can only be enabled or disabled, and manage_app_config itself cannot be disabled.",
                required = listOf("target" to "string", "action" to "string"),
                optional = listOf(
                    "id" to "string",
                    "name" to "string",
                    "description" to "string",
                    "url" to "string",
                    "raw_json" to "string",
                    "auth_key" to "string",
                    "transport" to "string",
                    "timeout_seconds" to "integer",
                    "host" to "string",
                    "port" to "integer",
                    "username" to "string",
                    "email_address" to "string",
                    "password" to "string",
                    "private_key" to "string",
                    "passphrase" to "string",
                    "auth_type" to "string",
                    "protocol" to "string",
                    "imap_host" to "string",
                    "imap_port" to "integer",
                    "imap_security" to "string",
                    "smtp_host" to "string",
                    "smtp_port" to "integer",
                    "smtp_security" to "string",
                    "use_private_key" to "boolean",
                    "encoding" to "string",
                    "passive_mode" to "boolean",
                    "explicit_ftps" to "boolean",
                    "sync_permissions" to "boolean",
                    "zip_url" to "string",
                    "tool_name" to "string",
                    "user_agent" to "string",
                    "initial_path" to "string",
                    "path" to "string",
                    "note" to "string",
                    "trust_all_certificates" to "boolean",
                    "multi_thread" to "boolean",
                    "hide_address" to "boolean",
                    "enabled" to "boolean",
                ),
            ),
        )
        .put(function("get_current_time", "Get the device's current local time, time zone, and epoch timestamp. Use when relative dates or time ranges matter."))
        .put(function("get_current_location", "Get the device's last known system location for location-aware answers or searches. Returns permission/status details when unavailable."))
        .put(function("get_device_hardware_info", "Collect Android OS, CPU, memory, storage, ABI, display, network, Bluetooth, and battery diagnostics. Use for device troubleshooting or hardware plausibility checks; do not present it as definitive authenticity proof."))
        .put(
            functionWithOptional(
                "list_installed_apps",
                "List installed apps with label, package, version, APK size, user/system classification, and signing-certificate SHA-256. scope is all, user, or system; use offset/limit for pagination.",
                required = emptyList(),
                optional = listOf("scope" to "string", "query" to "string", "offset" to "integer", "limit" to "integer"),
            ),
        )
        if (systemCommandExecutor.shouldShowShellTool()) {
            definitions.put(
                functionWithOptional(
                    "execute_shell_command",
                    "Run an Android shell command through Shizuku after user approval and return exit_code, stdout, and stderr. Supports pm, cmd, dumpsys, and shell-readable protected paths. Inspect state with read-only commands before changes. Do not run persistent or interactive programs.",
                    required = emptyList(),
                    optional = listOf("command" to "string", "command_lines" to "array:string", "timeout_seconds" to "integer"),
                ),
            )
        }
        if (systemCommandExecutor.shouldShowRootTool()) {
            definitions.put(
                functionWithOptional(
                    "execute_root_command",
                    "Run a command as root with the user's configured su command after approval and return exit_code, stdout, and stderr. Inspect exact targets and a recovery plan before changing /data, system files, or system packages. It falls back to Shizuku only when Shell fallback is enabled and authorized.",
                    required = emptyList(),
                    optional = listOf("command" to "string", "command_lines" to "array:string", "timeout_seconds" to "integer"),
                ),
            )
        }
        definitions
        .put(function("list_email_accounts", "List enabled user-configured IMAP/SMTP accounts without revealing passwords. Call this before other email tools and use a returned id as account_id."))
        .put(function("list_email_folders", "List IMAP folders and identify likely drafts folders without opening message content.", "account_id" to "string"))
        .put(
            functionWithOptional(
                "list_emails",
                "List bounded email metadata and exact IMAP flags. This opens the folder read-only and never downloads bodies or attachments.",
                required = listOf("account_id" to "string"),
                optional = listOf("folder" to "string", "unread_only" to "boolean", "limit" to "integer"),
            ),
        )
        .put(function("read_email", "Read one bounded email body by IMAP UID without marking it read. HTML is converted to plain text; inline media and attachment bytes are omitted.", "account_id" to "string", "folder" to "string", "uid" to "integer"))
        .put(
            functionWithOptional(
                "set_email_flags",
                "Change the seen/unseen or flagged state of one email after user approval. Draft, answered, and deleted state are reported but cannot be forged with this tool.",
                required = listOf("account_id" to "string", "folder" to "string", "uid" to "integer"),
                optional = listOf("seen" to "boolean", "flagged" to "boolean"),
            ),
        )
        .put(function("download_email_attachment", "Download one attachment into Lyra Code's isolated temporary quarantine after user approval. The result is never readable by AI; ask the user to scan it before opening.", "account_id" to "string", "folder" to "string", "uid" to "integer", "attachment_id" to "integer"))
        .put(function("record_email_attachment_scan", "Record the user's explicit antivirus scan result for a quarantined attachment. This does not make its contents readable by AI.", "attachment_token" to "string", "safe" to "boolean"))
        .put(
            functionWithOptional(
                "save_email_draft",
                "Build a MIME email and append it to the provider's discovered IMAP drafts folder after approval, so the user can review and send manually. Supports text, HTML, standards-compliant reply headers, and workspace attachments up to 20 MB total.",
                required = listOf("account_id" to "string", "to" to "array:string", "subject" to "string"),
                optional = listOf("cc" to "array:string", "bcc" to "array:string", "text_body" to "string", "html_body" to "string", "attachments" to "array:string", "reply_folder" to "string", "reply_uid" to "integer", "allow_reply_to_answered" to "boolean"),
            ),
        )
        .put(
            functionWithOptional(
                "send_email",
                "Send a MIME email through SMTP. Every invocation requires explicit user confirmation. Supports text/HTML, attachments up to 20 MB total, and threaded replies using Message-ID/In-Reply-To/References. Duplicate or uncertain deliveries are blocked from automatic retry.",
                required = listOf("account_id" to "string", "to" to "array:string", "subject" to "string"),
                optional = listOf("cc" to "array:string", "bcc" to "array:string", "text_body" to "string", "html_body" to "string", "attachments" to "array:string", "reply_folder" to "string", "reply_uid" to "integer", "allow_reply_to_answered" to "boolean"),
            ),
        )
        .put(function("list_ssh_servers", "List enabled user-configured SSH servers. Call this before ssh_exec and use a returned id as server_id."))
        .put(
            functionWithOptional(
                "ssh_exec",
                "Run a command on a configured remote SSH server after approval and return exit_code/stdout/stderr. server_id must come from list_ssh_servers. Before installs or service/config changes, inspect OS, CPU/GPU, memory, disk, and permissions. Before reading logs, inspect size and line count, then read only a small range. Avoid interactive programs such as vim, top, or nested ssh; input_lines supports simple stdin responses.",
                required = listOf("server_id" to "string"),
                optional = listOf("command" to "string", "command_lines" to "array:string", "cwd" to "string", "input_lines" to "array:string", "timeout_seconds" to "integer"),
            ),
        )
        .put(function("list_webdav_servers", "List enabled user-configured WebDAV servers. Call this first and use a returned id as server_id."))
        .put(
            functionWithOptional(
                "webdav_list",
                "Use PROPFIND to list a WebDAV directory with paths, directory flags, sizes, and modification times. Prefer this when the name is unknown, search is empty, or directory structure must be confirmed. Reads metadata only.",
                required = listOf("server_id" to "string"),
                optional = listOf("path" to "string", "depth" to "integer"),
            ),
        )
        .put(
            functionWithOptional(
                "webdav_search",
                "Search a WebDAV server by file name or path fragment and return metadata without downloading. If the name is unknown or you need a directory listing, call webdav_list instead of searching for \".\".",
                required = listOf("server_id" to "string", "query" to "string"),
                optional = listOf("path" to "string", "limit" to "integer"),
            ),
        )
        .put(function("webdav_download_to_workspace", "Download a WebDAV file into the workspace after user approval. local_path must be workspace-relative.", "server_id" to "string", "remote_path" to "string", "local_path" to "string"))
        .put(function("webdav_upload_from_workspace", "Upload a workspace file to WebDAV after user approval. local_path must be workspace-relative.", "server_id" to "string", "local_path" to "string", "remote_path" to "string"))
        .put(function("list_file_transfer_servers", "List enabled user-configured FTP/FTPS/SFTP servers. Call this first and use a returned id as server_id."))
        .put(
            functionWithOptional(
                "file_transfer_list",
                "List an FTP/FTPS/SFTP directory with paths, directory flags, sizes, and modification times. Prefer this when a name is unknown, search is empty, or structure must be confirmed. Reads metadata only.",
                required = listOf("server_id" to "string"),
                optional = listOf("path" to "string"),
            ),
        )
        .put(
            functionWithOptional(
                "file_transfer_search",
                "Search an FTP/FTPS/SFTP server by file name or path fragment and return metadata without downloading.",
                required = listOf("server_id" to "string", "query" to "string"),
                optional = listOf("path" to "string", "limit" to "integer"),
            ),
        )
        .put(function("file_transfer_download_to_workspace", "Download an FTP/FTPS/SFTP file into the workspace after user approval. local_path must be workspace-relative.", "server_id" to "string", "remote_path" to "string", "local_path" to "string"))
        .put(function("file_transfer_upload_from_workspace", "Upload a workspace file to FTP/FTPS/SFTP after user approval. local_path must be workspace-relative.", "server_id" to "string", "local_path" to "string", "remote_path" to "string"))
        .put(
            functionWithOptional(
                "export_backup",
                "Export a Lyra Code backup to Download/LyraCode or WebDAV after approval. destination is local or webdav. Warn the user when include_secrets=true. Without remote_path, WebDAV overwrites /LyraCode/lyra_backup_latest.zip for predictable later import.",
                required = listOf("destination" to "string"),
                optional = listOf(
                    "server_id" to "string",
                    "remote_path" to "string",
                    "include_profile" to "boolean",
                    "include_conversations" to "boolean",
                    "include_model_profiles" to "boolean",
                    "include_mcp" to "boolean",
                    "include_ssh" to "boolean",
                    "include_email" to "boolean",
                    "include_prompts" to "boolean",
                    "include_memories" to "boolean",
                    "include_skills" to "boolean",
                    "include_webdav" to "boolean",
                    "include_file_transfer" to "boolean",
                    "include_secrets" to "boolean",
                ),
            ),
        )
        .put(
            functionWithOptional(
                "import_backup",
                "Import a Lyra Code backup zip from the workspace, Android shared storage, or WebDAV after approval. Agent imports always use non-destructive supplement mode with deduplication. source is local, download, global, or webdav. download/global use global_path or local_path. With an empty WebDAV remote_path, Lyra Code tries /LyraCode/lyra_backup_latest.zip, then the newest matching backup.",
                required = listOf("source" to "string"),
                optional = listOf("server_id" to "string", "remote_path" to "string", "local_path" to "string", "global_path" to "string"),
            ),
        )
        if (allowSubAgents && settings.subAgentOrchestrationEnabled && settings.enabledSubAgents().isNotEmpty()) {
            definitions.put(subAgentFunction())
        }
        MediaGenerationKind.entries.forEach { kind ->
            if (settings.mediaGenerationModelOrNull(kind) != null) {
                definitions.put(mediaGenerationFunction(kind))
            }
        }
        if (settings.isVisionSupplementRoutingEnabled()) {
            if (settings.visionUnderstandingModelOrNull() != null || settings.visionUnderstandingMcpToolOrNull() != null) {
                definitions.put(visionUnderstandingFunction())
            }
            if (settings.ocrModelOrNull() != null) {
                definitions.put(ocrFunction())
            }
        }
        definitions
        .put(
            functionWithOptional(
                "ask_user",
                "Pause and ask the user one focused follow-up question when a complex task has material ambiguity, depends on a preference, or encounters an unexpected situation that changes the correct next step. title must be a concise heading. question contains the full prompt. options is an optional list shown as multi-select choices; it may be omitted for open questions. The UI always provides an additional free-text field and requires a second confirmation before submission. After 10 minutes without any interaction the question is withdrawn and the tool returns timed_out so you can continue with your best judgment.",
                required = listOf("title" to "string", "question" to "string"),
                optional = listOf("options" to "array:string"),
                propertyDescriptions = mapOf(
                    "title" to "A concise heading that tells the user what decision or information is needed.",
                    "question" to "One clear, self-contained question.",
                    "options" to "Optional suggested answers. The user may select multiple options and can always add a different or supplementary free-text answer.",
                ),
                disallowAdditionalProperties = true,
            ),
        )
        .put(function("set_todo_list", "Set the current task's TODO list before multistep work, file changes, or commands. items is an array of objects with id, text, status, and note.", "items" to "array:object"))
        .put(function("update_todo_item", "Update one TODO item. status is pending, running, completed, or blocked.", "id" to "string", "status" to "string", "note" to "string"))
        val selectedVisionMcp = settings.visionUnderstandingMcpToolOrNull()
        settings.enabledMcpTools()
            .filterNot { (server, tool) ->
                settings.isVisionSupplementRoutingEnabled() && selectedVisionMcp?.let { selected ->
                    selected.first.id == server.id && selected.second.name == tool.name
                } == true
            }
            .forEach { (server, tool) ->
            runCatching { mcpFunction(server, tool) }
                .onSuccess { definitions.put(it) }
                .onFailure {
                    Log.w(AGENT_TAG, "skip_invalid_mcp_schema server=${server.name} tool=${tool.name} error=${it.message}", it)
                }
        }
        val disabled = settings.disabledTools()
        val visible = JSONArray().apply {
            for (index in 0 until definitions.length()) {
                val item = definitions.getJSONObject(index)
                val name = item.optJSONObject("function")?.optString("name").orEmpty()
                if ((name == "manage_app_config" || name !in disabled) && (allowedToolNames == null || name in allowedToolNames)) {
                    put(item)
                }
            }
        }
        return canonicalToolDefinitions(visible)
    }

    fun anthropicTools(
        allowSubAgents: Boolean = false,
        allowedToolNames: Set<String>? = null,
    ): JSONArray {
        val tools = toolDefinitions(allowSubAgents, allowedToolNames)
        return JSONArray().also { output ->
            for (index in 0 until tools.length()) {
                val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                output.put(
                    JSONObject()
                        .put("name", function.optString("name"))
                        .put("description", function.optString("description"))
                        .put("input_schema", function.optJSONObject("parameters") ?: JSONObject().put("type", "object")),
                )
            }
        }
    }

    fun geminiFunctionDeclarations(
        allowSubAgents: Boolean = false,
        allowedToolNames: Set<String>? = null,
    ): JSONArray {
        val tools = toolDefinitions(allowSubAgents, allowedToolNames)
        return JSONArray().also { output ->
            for (index in 0 until tools.length()) {
                val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                output.put(
                    JSONObject()
                        .put("name", function.optString("name"))
                        .put("description", function.optString("description"))
                        .put("parameters", toGeminiSchema(function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))),
                )
            }
        }
    }

    private fun function(name: String, description: String, vararg properties: Pair<String, String>): JSONObject {
        return functionWithOptional(name, description, required = properties.toList(), optional = emptyList())
    }

    private fun subAgentFunction(): JSONObject {
        val taskProperties = JSONObject()
            .put("task", JSONObject().put("type", "string").put("description", "One independent, bounded subtask."))
            .put("capability_hint", JSONObject().put("type", "string").put("description", "Optional specialization hint used for automatic agent selection."))
            .put("expected_output", JSONObject().put("type", "string").put("description", "Evidence and result shape the parent needs for verification."))
            .put("sub_agent_id", JSONObject().put("type", "string").put("description", "Optional exact enabled sub-agent id. Omit for automatic selection."))
            .put("read_only", JSONObject().put("type", "boolean").put("description", "True when the task must not mutate workspace state."))
            .put(
                "write_paths",
                JSONObject()
                    .put("type", "array")
                    .put("description", "Every exact workspace-relative file or directory this task may mutate. Empty for read-only tasks. Paths cannot overlap another task.")
                    .put("items", JSONObject().put("type", "string")),
            )
        val taskSchema = JSONObject()
            .put("type", "object")
            .put("properties", taskProperties)
            .put("required", JSONArray().put("task").put("read_only").put("write_paths"))
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "run_sub_agents")
                    .put(
                        "description",
                        "Delegate independent subtasks only for complex work that benefits from separate research, review, validation, alternatives, or specialization. Mutating tasks must declare non-overlapping exact workspace paths. Sub-agents receive restricted tools, cannot delegate again, and return results for parent verification.",
                    )
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "tasks",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("minItems", 1)
                                        .put("maxItems", 6)
                                        .put("items", taskSchema),
                                ),
                            )
                            .put("required", JSONArray().put("tasks"))
                            .put("additionalProperties", false),
                    ),
            )
    }

    private fun mediaGenerationFunction(kind: MediaGenerationKind): JSONObject {
        val optional = buildList {
            add("reference_media_message_ids" to "array:string")
            add("use_latest_user_attachments" to "boolean")
            add("negative_prompt" to "string")
            when (kind) {
                MediaGenerationKind.IMAGE -> add("aspect_ratio" to "string")
                MediaGenerationKind.VIDEO -> {
                    add("aspect_ratio" to "string")
                    add("duration_seconds" to "integer")
                }
                MediaGenerationKind.MUSIC -> {
                    add("duration_seconds" to "integer")
                    add("lyrics" to "string")
                    add("instrumental" to "boolean")
                }
                MediaGenerationKind.AUDIO -> {
                    add("duration_seconds" to "integer")
                    add("voice" to "string")
                }
            }
        }
        val description = when (kind) {
            MediaGenerationKind.IMAGE -> "Generate still images only with the separately configured image model. Use this for image requests, reference sketches, illustrations, or visual drafts; never use video, music, or audio tools for a still image. Optimize the user's prompt before calling."
            MediaGenerationKind.VIDEO -> "Generate moving video only with the separately configured video model. Never route a video request to the image, music, or audio model. Optimize the prompt and specify motion, shot, and timing."
            MediaGenerationKind.MUSIC -> "Generate songs or instrumental music only with the separately configured music model. Use generate_audio instead for speech, sound effects, or other non-musical audio."
            MediaGenerationKind.AUDIO -> "Generate non-music audio only with the separately configured audio model, including speech, narration, voices, ambience, and sound effects. Use generate_music for songs or instrumental music."
        }
        return functionWithOptional(
            name = mediaGenerationToolName(kind),
            description = "$description Generated media is displayed to the user by Lyra. The tool result contains status metadata only and never returns media bytes to you. To reuse generated media as a reference, pass its media_message_id in reference_media_message_ids.",
            required = listOf("prompt" to "string"),
            optional = optional,
            propertyDescriptions = mapOf(
                "prompt" to "The final, self-contained prompt for the dedicated media model. Do not include Lyra protocol text or tool instructions.",
                "reference_media_message_ids" to "Optional media_message_id values from earlier successful media tool results. Lyra resolves the files internally without exposing their bytes to the main LLM.",
                "use_latest_user_attachments" to "When true, include compatible media attachments from the latest user message as private model references. Defaults to true.",
                "negative_prompt" to "Optional undesired content or qualities.",
                "aspect_ratio" to "Optional target aspect ratio such as 1:1, 16:9, or 9:16.",
                "duration_seconds" to "Optional requested duration in seconds.",
                "lyrics" to "Optional lyrics for music generation.",
                "instrumental" to "True to request music without vocals.",
                "voice" to "Optional voice, speaker, or delivery description for speech audio.",
            ),
            disallowAdditionalProperties = true,
        )
    }

    private fun visionUnderstandingFunction(): JSONObject = functionWithOptional(
        name = VISION_UNDERSTANDING_TOOL_NAME,
        description = "Inspect image attachments that Lyra withheld from your prompt because visual supplement routing is enabled. Call this whenever visual appearance, layout, objects, charts, screenshots, or non-text image details are needed. The configured visual model or MCP tool returns a faithful relay report; reason about the user's request only after reading that report.",
        required = listOf("instruction" to "string"),
        optional = listOf("message_id" to "string"),
        propertyDescriptions = mapOf(
            "instruction" to "A narrow description of what visual details the relay should inspect. Do not ask the relay to answer the user's underlying question.",
            "message_id" to "Optional user message id shown in the withheld-image placeholder. Omit it to inspect images from the latest user message that contains them.",
        ),
        disallowAdditionalProperties = true,
    )

    private fun ocrFunction(): JSONObject = functionWithOptional(
        name = OCR_TOOL_NAME,
        description = "Extract visible text from image attachments that Lyra withheld from your prompt because visual supplement routing is enabled. Use this for screenshots, documents, signs, tables, labels, or any task requiring exact image text. The result is OCR evidence, not a final answer.",
        required = emptyList(),
        optional = listOf("message_id" to "string", "language_hint" to "string"),
        propertyDescriptions = mapOf(
            "message_id" to "Optional user message id shown in the withheld-image placeholder. Omit it to OCR images from the latest user message that contains them.",
            "language_hint" to "Optional expected language or script, such as zh-CN, en, Japanese, or mixed.",
        ),
        disallowAdditionalProperties = true,
    )

    private fun mcpFunction(server: McpServerConfig, tool: McpToolDefinition): JSONObject {
        val parameters = runCatching { JSONObject(tool.inputSchema.ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        val sanitized = sanitizeMcpSchema(parameters) as? JSONObject ?: JSONObject()
        if (sanitized.optString("type").isBlank()) {
            sanitized.put("type", "object")
        }
        if (!sanitized.has("properties")) {
            sanitized.put("properties", JSONObject())
        }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", settings.mcpToolFunctionName(server, tool))
                    .put(
                        "description",
                        "MCP:${server.name} / ${tool.name}. ${tool.description}".take(1024),
                    )
                    .put("parameters", sanitized),
            )
    }

    private fun sanitizeMcpSchema(value: Any?): Any {
        return when (value) {
            is JSONObject -> sanitizeMcpSchemaObject(value)
            is JSONArray -> JSONArray().also { array ->
                for (index in 0 until value.length()) {
                    sanitizeMcpSchema(value.opt(index)).let { sanitized ->
                        if (!isNullSchema(sanitized)) array.put(sanitized)
                    }
                }
            }
            is Boolean -> JSONObject()
            JSONObject.NULL, null -> JSONObject()
            else -> value
        }
    }

    private fun sanitizeMcpSchemaObject(source: JSONObject): JSONObject {
        val output = JSONObject()
        source.keys().forEach { key ->
            when (key) {
                "type" -> normalizeJsonSchemaType(source.opt(key))?.let { output.put("type", it) }
                "properties" -> {
                    val props = source.optJSONObject(key) ?: JSONObject()
                    val sanitizedProps = JSONObject()
                    props.keys().forEach { propName ->
                        sanitizedProps.put(propName, sanitizeMcpSchema(props.opt(propName)))
                    }
                    output.put("properties", sanitizedProps)
                }
                "items", "additionalProperties" -> output.put(key, sanitizeMcpSchema(source.opt(key)))
                "anyOf", "oneOf", "allOf" -> {
                    val sourceArray = source.optJSONArray(key)
                    val sanitizedArray = JSONArray()
                    if (sourceArray != null) {
                        for (index in 0 until sourceArray.length()) {
                            val item = sanitizeMcpSchema(sourceArray.opt(index))
                            if (!isNullSchema(item)) sanitizedArray.put(item)
                        }
                    }
                    if (sanitizedArray.length() == 1) {
                        val only = sanitizedArray.optJSONObject(0)
                        if (only != null) {
                            only.keys().forEach { innerKey -> output.put(innerKey, only.opt(innerKey)) }
                        } else {
                            output.put(key, sanitizedArray)
                        }
                    } else if (sanitizedArray.length() > 1) {
                        output.put(key, sanitizedArray)
                    }
                }
                "required" -> {
                    val required = source.optJSONArray(key) ?: JSONArray()
                    val sanitizedRequired = JSONArray()
                    for (index in 0 until required.length()) {
                        required.optString(index).takeIf { it.isNotBlank() }?.let { sanitizedRequired.put(it) }
                    }
                    output.put("required", sanitizedRequired)
                }
                "enum" -> output.put(key, source.optJSONArray(key) ?: JSONArray())
                "description", "title", "default", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "pattern" -> {
                    output.put(key, source.opt(key))
                }
                else -> {
                    val raw = source.opt(key)
                    if (raw is JSONObject || raw is JSONArray) output.put(key, sanitizeMcpSchema(raw))
                }
            }
        }
        return output
    }

    private fun normalizeJsonSchemaType(raw: Any?): Any? {
        fun normalizeOne(type: String): String? {
            return when (type.trim().lowercase()) {
                "bool", "boolean" -> "boolean"
                "str", "string", "text" -> "string"
                "int", "integer" -> "integer"
                "float", "double", "number" -> "number"
                "dict", "map", "object" -> "object"
                "list", "array" -> "array"
                "null", "none", "nil" -> null
                else -> if (type in JSON_SCHEMA_TYPES) type else "string"
            }
        }
        return when (raw) {
            is String -> normalizeOne(raw)
            is JSONArray -> {
                val array = JSONArray()
                for (index in 0 until raw.length()) {
                    raw.optString(index).takeIf { it.isNotBlank() }?.let { normalizeOne(it) }?.let { array.put(it) }
                }
                when (array.length()) {
                    0 -> null
                    1 -> array.optString(0)
                    else -> array
                }
            }
            else -> null
        }
    }

    private fun isNullSchema(value: Any?): Boolean {
        return value is JSONObject && value.optString("type").equals("null", ignoreCase = true)
    }

    private fun functionWithOptional(
        name: String,
        description: String,
        required: List<Pair<String, String>>,
        optional: List<Pair<String, String>>,
        propertyDescriptions: Map<String, String> = emptyMap(),
        disallowAdditionalProperties: Boolean = false,
    ): JSONObject {
        val props = JSONObject()
        val requiredArray = JSONArray()
        (required + optional).forEach { (key, type) ->
            val schema = when (type) {
                "array:string" -> JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                "array:object" -> JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "object"))
                else -> JSONObject().put("type", type)
            }
            propertyDescriptions[key]?.let { schema.put("description", it) }
            props.put(key, schema)
        }
        required.forEach { (key, _) -> requiredArray.put(key) }
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", requiredArray)
        if (disallowAdditionalProperties) parameters.put("additionalProperties", false)
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
    }


    private companion object {
        const val AGENT_TAG = "LyraAgent"
        val JSON_SCHEMA_TYPES = setOf("string", "number", "integer", "boolean", "object", "array")
        const val CONTENT_TEXT_DESCRIPTION =
            "Complete text. Mutually exclusive with content_lines; prefer this for short content. An empty string creates an empty file."
        const val CONTENT_LINES_DESCRIPTION =
            "A real JSON array of strings, one element per line without newline characters. Correct: [\"line 1\",\"line 2\",\"\"]; wrong: \"\\\"line 1\\\", \\\"line 2\\\"\"."
        const val TRAILING_NEWLINE_DESCRIPTION =
            "When true, add one trailing newline to the final text. Defaults to false."
        const val OLD_CONTENT_DESCRIPTION =
            "Exact source text to replace. Mutually exclusive with old_content_lines."
        const val OLD_CONTENT_LINES_DESCRIPTION =
            "Source lines to replace as a real JSON string array, not a serialized string. Mutually exclusive with old_content."
        const val NEW_CONTENT_DESCRIPTION =
            "Replacement text; an empty string deletes the matched text. Mutually exclusive with new_content_lines."
        const val NEW_CONTENT_LINES_DESCRIPTION =
            "Replacement lines as a real JSON string array; an empty array deletes the matched text. Mutually exclusive with new_content."
        val FILE_WRITE_PROPERTY_DESCRIPTIONS = mapOf(
            "path" to "Workspace-relative path, for example src/main.kt. Android absolute paths are invalid.",
            "content" to CONTENT_TEXT_DESCRIPTION,
            "content_lines" to CONTENT_LINES_DESCRIPTION,
            "ensure_trailing_newline" to TRAILING_NEWLINE_DESCRIPTION,
        )
        val GLOBAL_FILE_WRITE_PROPERTY_DESCRIPTIONS = FILE_WRITE_PROPERTY_DESCRIPTIONS +
            ("path" to "Android shared-storage path, for example Download/file.txt or /storage/emulated/0/Download/file.txt.")
        val FILE_EDIT_PROPERTY_DESCRIPTIONS = FILE_WRITE_PROPERTY_DESCRIPTIONS + mapOf(
            "old_content" to OLD_CONTENT_DESCRIPTION,
            "old_content_lines" to OLD_CONTENT_LINES_DESCRIPTION,
            "new_content" to NEW_CONTENT_DESCRIPTION,
            "new_content_lines" to NEW_CONTENT_LINES_DESCRIPTION,
            "start_line" to "1-based first line; required in line-range mode.",
            "end_line" to "1-based inclusive last line; defaults to start_line.",
            "expected_replacements" to "Required match count in text mode. Defaults to 1; a mismatch rejects the write.",
        )
        val GLOBAL_FILE_EDIT_PROPERTY_DESCRIPTIONS = FILE_EDIT_PROPERTY_DESCRIPTIONS +
            ("path" to "Android shared-storage path, for example Download/file.txt or /storage/emulated/0/Download/file.txt.")
    }}

internal fun toGeminiSchema(source: JSONObject): JSONObject {
    val output = JSONObject()
    val type = source.optString("type").ifBlank { "object" }
    output.put("type", type.uppercase(Locale.US))
    (source.opt("description") as? String)?.takeUnless { it.equals("null", ignoreCase = true) }
        ?.let { output.put("description", it) }
    source.optJSONArray("required")?.let { output.put("required", it) }
    source.optJSONArray("enum")?.let { output.put("enum", it) }
    source.optJSONObject("properties")?.let { props ->
        val outProps = JSONObject()
        props.keys().forEach { name ->
            (props.optJSONObject(name) ?: JSONObject().put("type", "string")).let { outProps.put(name, toGeminiSchema(it)) }
        }
        output.put("properties", outProps)
    }
    source.optJSONObject("items")?.let { output.put("items", toGeminiSchema(it)) }
    return output
}
