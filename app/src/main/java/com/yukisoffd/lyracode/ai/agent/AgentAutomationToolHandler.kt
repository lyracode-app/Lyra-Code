package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.MiniServerConfig
import com.yukisoffd.lyracode.server.MiniServerManager
import com.yukisoffd.lyracode.tasks.ScheduledTask
import com.yukisoffd.lyracode.tasks.ScheduledTaskManager
import com.yukisoffd.lyracode.tasks.ScheduledTaskType
import org.json.JSONObject
import java.util.Locale

internal class AgentAutomationToolHandler(
    private val settings: AppSettings,
    private val scheduledTaskManager: ScheduledTaskManager,
    private val miniServerManager: MiniServerManager,
    private val parseTime: (String) -> Long,
) {
    fun manageScheduledTasks(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase(Locale.US)
        if (action == "list") {
            return JSONObject()
                .put("schema", "lyra_scheduled_tasks_v1")
                .put("tasks", scheduledTaskManager.describe())
                .toString()
        }
        val taskId = args.optString("task_id")
        if (action == "delete") {
                require(taskId.isNotBlank()) { "task_id is required." }
            scheduledTaskManager.delete(taskId)
            return JSONObject().put("ok", true).put("action", action).put("task_id", taskId).toString()
        }
        if (action == "enable" || action == "disable") {
                require(taskId.isNotBlank()) { "task_id is required." }
            val task = scheduledTaskManager.setEnabled(taskId, action == "enable")
                    ?: error("Scheduled task does not exist: $taskId. Call action=list and use a returned task_id.")
            return scheduledTaskResult(action, task)
        }
        require(action == "create" || action == "update") { "action must be list, create, update, enable, disable, or delete." }
        val existing = taskId.takeIf { it.isNotBlank() }?.let(scheduledTaskManager::task)
        if (action == "update") require(existing != null) { "Scheduled task does not exist: $taskId. Call action=list and use a returned task_id." }
        val profile = settings.profiles().firstOrNull { it.id == args.optString("profile_id") }
            ?: existing?.profileId?.let { id -> settings.profiles().firstOrNull { it.id == id } }
            ?: settings.selectedProfile()
        val type = args.optString("schedule_type", existing?.type?.name.orEmpty())
            .uppercase(Locale.US)
            .let { runCatching { ScheduledTaskType.valueOf(it) }.getOrDefault(existing?.type ?: ScheduledTaskType.ONCE) }
        val runAt = args.optString("run_at").takeIf { it.isNotBlank() }?.let(parseTime)
            ?: existing?.runAtMillis
            ?: 0L
        if (type == ScheduledTaskType.ONCE) require(runAt > System.currentTimeMillis()) {
            "run_at for a one-time task must be in the future and use yyyy-MM-dd HH:mm or ISO-8601."
        }
        val task = ScheduledTask(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            title = args.optString("title").ifBlank { existing?.title ?: "定时任务" },
            prompt = args.optString("prompt").ifBlank { existing?.prompt.orEmpty() },
            type = type,
            hour = if (args.has("hour")) args.optInt("hour") else existing?.hour ?: 9,
            minute = if (args.has("minute")) args.optInt("minute") else existing?.minute ?: 0,
            runAtMillis = runAt,
            dayOfWeek = if (args.has("day_of_week")) args.optInt("day_of_week") else existing?.dayOfWeek ?: 1,
            dayOfMonth = if (args.has("day_of_month")) args.optInt("day_of_month") else existing?.dayOfMonth ?: 1,
            profileId = profile.id,
            model = args.optString("model").ifBlank { existing?.model ?: profile.selectedModel },
            enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing?.enabled ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastRunAt = existing?.lastRunAt ?: 0L,
            finishedAt = existing?.finishedAt ?: 0L,
            status = existing?.status ?: com.yukisoffd.lyracode.tasks.ScheduledTaskStatus.IDLE,
            result = existing?.result.orEmpty(),
            error = existing?.error.orEmpty(),
        )
        require(task.prompt.isNotBlank()) { "prompt is required." }
        return scheduledTaskResult(action, scheduledTaskManager.save(task))
    }
    
    fun scheduledTaskResult(action: String, task: ScheduledTask): String = JSONObject()
        .put("ok", true)
        .put("action", action)
        .put("task_id", task.id)
        .put("title", task.title)
        .put("schedule_type", task.type.name.lowercase(Locale.US))
        .put("enabled", task.enabled)
        .put("next_run_at", task.nextRunAt)
        .put("profile_id", task.profileId)
        .put("model", task.model)
        .toString()
    
    fun manageMiniServer(args: JSONObject): String {
        val action = args.optString("action", "status").lowercase(Locale.US)
        val current = settings.miniServerConfig()
        val config = current.copy(
            protocol = args.optString("protocol").ifBlank { current.protocol }.lowercase(Locale.US).let {
                if (it == AppSettings.MINI_SERVER_PROTOCOL_HTTPS) AppSettings.MINI_SERVER_PROTOCOL_HTTPS else AppSettings.MINI_SERVER_PROTOCOL_HTTP
            },
            host = args.optString("host").ifBlank { current.host },
            port = if (args.has("port")) args.optInt("port", current.port).coerceIn(1, 65535) else current.port,
            username = args.optString("username").ifBlank { current.username },
            password = if (args.has("password")) args.optString("password") else current.password,
            customDomains = miniServerDomains(args, current.customDomains),
            forceHttps = if (args.has("force_https")) args.optBoolean("force_https") else current.forceHttps,
            tlsKeyStoreBase64 = if (args.has("tls_key_store_base64")) args.optString("tls_key_store_base64") else current.tlsKeyStoreBase64,
            tlsKeyStorePassword = if (args.has("tls_key_store_password")) args.optString("tls_key_store_password") else current.tlsKeyStorePassword,
            tlsCertificateChain = if (args.has("tls_certificate_chain")) args.optString("tls_certificate_chain") else current.tlsCertificateChain,
            tlsPrivateKey = if (args.has("tls_private_key")) args.optString("tls_private_key") else current.tlsPrivateKey,
            spaFallback = if (args.has("spa_fallback")) args.optBoolean("spa_fallback") else current.spaFallback,
            directoryListing = if (args.has("directory_listing")) args.optBoolean("directory_listing") else current.directoryListing,
            mdnsEnabled = if (args.has("mdns_enabled")) args.optBoolean("mdns_enabled") else current.mdnsEnabled,
            mdnsName = args.optString("mdns_name").ifBlank { current.mdnsName },
        )
        val status = when (action) {
            "status" -> miniServerManager.status()
            "update" -> {
                settings.saveMiniServerConfig(config)
                miniServerManager.status()
            }
            "start" -> miniServerManager.start(config.copy(enabled = true))
            "stop" -> miniServerManager.stop()
            "restart" -> miniServerManager.restart(config.copy(enabled = true))
            "reset" -> {
                if (miniServerManager.status().running) miniServerManager.stop()
                settings.saveMiniServerConfig(
                    MiniServerConfig(
                        protocol = AppSettings.MINI_SERVER_PROTOCOL_HTTP,
                        host = AppSettings.DEFAULT_MINI_SERVER_HOST,
                        port = AppSettings.DEFAULT_MINI_SERVER_PORT,
                        username = AppSettings.DEFAULT_MINI_SERVER_USERNAME,
                        password = "",
                        customDomains = emptyList(),
                        forceHttps = false,
                        tlsKeyStoreBase64 = "",
                        tlsKeyStorePassword = "",
                        tlsCertificateChain = "",
                        tlsPrivateKey = "",
                        spaFallback = true,
                        directoryListing = false,
                        mdnsEnabled = false,
                        mdnsName = AppSettings.DEFAULT_MINI_SERVER_MDNS_NAME,
                        enabled = false,
                    ),
                )
                miniServerManager.status()
            }
            else -> error("Unknown mini-server action: $action. Use status, update, start, stop, restart, or reset.")
        }
        return miniServerManager.statusJson()
            .put("action", action)
            .put("running", status.running)
            .put("security_note", miniServerSecurityNote(config))
            .toString()
    }
    
    fun readMiniServerLogs(args: JSONObject): String {
        val limit = args.optInt("limit", 120).coerceIn(1, 500)
        val level = args.optString("level").lowercase(Locale.US).takeIf { it in setOf("debug", "info", "warn", "error") }.orEmpty()
        return miniServerManager.logsJson(limit, level).toString()
    }
    
    fun miniServerDomains(args: JSONObject, current: List<String>): List<String> {
        val array = args.optJSONArray("custom_domains")
        if (array != null) {
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct()
        }
        return args.optString("custom_domains")
            .takeIf { it.isNotBlank() }
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            ?: current
    }
    
    fun miniServerSecurityNote(config: MiniServerConfig): String {
        return buildString {
            append("The mini server uses the current workspace as its static site root.")
            if (config.protocol == AppSettings.MINI_SERVER_PROTOCOL_HTTPS) {
                append(" HTTPS uses the built-in self-signed certificate, which browsers may distrust. Use trusted TLS through a reverse proxy or tunnel for public sharing.")
            }
            if (config.forceHttps) {
                append(" Forced HTTPS is enabled; HTTP requests are redirected.")
            }
            if (config.customDomains.isNotEmpty()) {
                append(" Configured domains: ${config.customDomains.joinToString(", ")}.")
            }
            if (config.host == "0.0.0.0" || config.host == "::") {
                append(" The bind address exposes the server to the LAN and potentially the public internet through port mapping or tunneling.")
            }
            if (config.password.isBlank()) {
                    append(" No access password is configured; use only on a trusted network.")
            }
            if (config.protocol == AppSettings.MINI_SERVER_PROTOCOL_HTTP) {
                append(" Plain HTTP can expose paths, content, and credentials.")
            }
        }
    }
}

