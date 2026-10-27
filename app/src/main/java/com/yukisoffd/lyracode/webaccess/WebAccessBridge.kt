package com.yukisoffd.lyracode.webaccess

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.yukisoffd.lyracode.ChatController
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.SendOutcome
import com.yukisoffd.lyracode.agentToolCatalog
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.chat.pending.PendingInputSnapshot
import com.yukisoffd.lyracode.localizedContext
import com.yukisoffd.lyracode.data.*
import com.yukisoffd.lyracode.debian.ProotLinuxManager
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.mcp.McpJsonConfig
import com.yukisoffd.lyracode.workspace.ProotWorkspace
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import com.yukisoffd.lyracode.workspace.grantedWorkspaceDirectory
import com.yukisoffd.lyracode.workspace.SharedStorageWorkspace
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

internal class WebAccessBridge(
    private val context: Context,
    private val settings: AppSettings,
    private val controller: ChatController,
    private val workspace: WorkspaceManager,
    private val uploads: UploadedFileManager,
    private val mcp: McpClientManager,
) {
    var theme: JSONObject by mutableStateOf(JSONObject())
    val media = WebAccessMedia(context, workspace)
    private val instanceId = UUID.randomUUID().toString()
    private var snapshotVersion = 0L
    fun configurationSignature(): Int = listOf(controller.settingsRevision.intValue,controller.sessionRevision.intValue,
        controller.activeConversationId.value,controller.conversations.toList(),controller.archivedConversations.toList(),
        controller.projects.toList(),controller.archivedProjects.toList(),controller.profiles.toList(),controller.activeProfileId.value,
        controller.activeModel.value,settings.languageMode,settings.reasoningDepth,controller.isAutoApprovalEnabledForActiveSession(),theme).hashCode()
    fun currentMessages() = controller.messages.value

    /** Only changed records are serialized for token updates; history and settings travel on configuration changes. */
    fun liveSnapshot(previous: List<ChatRecord>): JSONObject {
        val messages=controller.messages.value
        val before=previous.associateBy { it.id }
        media.update(messages,controller.pendingUploads.toList())
        val usage=controller.contextWindowUsage.value
        val queue=controller.pendingInputSnapshotFor(controller.activeConversationId.value)
        val result=JSONObject().put("partial",true).put("instanceId",instanceId).put("version",++snapshotVersion)
            .put("conversationId",controller.activeConversationId.value).put("projectId",controller.activeProjectId()).put("sessionRevision",controller.sessionRevision.intValue)
            .put("running",controller.isActiveConversationBusy()).put("generating",controller.isActiveConversationGenerating())
            .put("loading",controller.messagesLoading.value).put("compressing",controller.isActiveHistoryCompressionRunning()).put("status",controller.status.value)
            .put("canContinue",controller.canContinueActive()).put("media",media.catalog())
            .put("composerDelivery",composerDeliveryJson(queue))
            .put("messages",array(messages.filter { before[it.id]!=it }, ::messageJson))
            .put("uploads",array(controller.pendingUploads) { f -> JSONObject().put("uri",f.uri).put("name",f.name).put("size",f.size).put("kind",f.mediaKind) })
            .put("context",JSONObject().put("tokens",usage.estimatedTokens).put("messages",usage.contextMessageCount).put("turns",usage.turnsSinceCompression)
                .put("compressed",usage.hasCompressedHistory).put("updating",usage.updating))
            .put("approval",controller.pendingToolApproval.value?.let { p -> JSONObject().put("id",p.id).put("tool",p.request.toolName)
                .put("summary",p.request.summary).put("risk",p.request.risk).put("arguments",p.request.arguments) } ?: JSONObject.NULL)
            .put("question",controller.pendingUserQuestion.value?.let { p -> JSONObject().put("id",p.id).put("title",p.request.title)
                .put("question",p.request.question).put("options",JSONArray(p.request.options)) } ?: JSONObject.NULL)
        if(messages.map { it.id }!=previous.map { it.id })result.put("messageOrder",JSONArray(messages.map { it.id }))
        return result
    }
    private fun <T> array(items: Iterable<T>, transform: (T) -> JSONObject): JSONArray = JSONArray().also { a -> items.forEach { a.put(transform(it)) } }
    private fun composerDeliveryJson(queue: PendingInputSnapshot): JSONObject = JSONObject()
        .put("enabled", settings.composerDeliveryEnabled)
        .put("queued", queue.queuedCount)
        .put("parked", queue.parkedCount)
    private fun messageJson(message: ChatRecord): JSONObject = JSONObject()
        .put("id", message.id).put("role", message.role).put("content", message.content)
        .put("thinking", message.thinking).put("toolName", message.toolName).put("toolInput", message.toolInput).put("model", message.model)
        .put("tokensPerSecond", message.tokensPerSecond.takeIf { it.isFinite() && it > 0.0 } ?: 0.0)
        .put("deepSeekCacheHitRate", message.deepSeekCacheHitRate?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: JSONObject.NULL)

    /** Resolve system mode against the application configuration, not an Activity locale override. */
    fun locale(): JSONObject {
        val resources = context.applicationContext.localizedContext(settings.languageMode).resources
        val labels = JSONObject()
        val ids = listOf(R.string.reasoning_auto, R.string.reasoning_low, R.string.reasoning_medium,
            R.string.reasoning_high, R.string.reasoning_xhigh, R.string.reasoning_max)
        AppSettings.reasoningDepthValues.zip(ids).forEach { (value, id) -> labels.put(value, resources.getString(id)) }
        // Unsupported system languages use the same English resource fallback as the phone.
        val language = when (labels.getString("auto")) { "自动" -> "zh-CN"; "自動" -> "zh-TW"; else -> "en" }
        return JSONObject().put("language", language).put("reasoningLabels", labels)
            .put("reasoningTitle", resources.getString(R.string.label_reasoning_depth))
            .put("reasoningHint", resources.getString(R.string.reasoning_depth_hint))
    }

    /** Called on Main so streaming records and Compose state form one coherent snapshot. */
    fun snapshot(): JSONObject {
        val compression = controller.autoCompressionConfig()
        val usage = controller.contextWindowUsage.value
        val queue = controller.pendingInputSnapshotFor(controller.activeConversationId.value)
        val disabled = settings.disabledTools()
        controller.settingsRevision.intValue // Also observe changes to preference-backed settings.
        media.update(controller.messages.value, controller.pendingUploads.toList())
        fun conversation(c: Conversation) = JSONObject().put("id", c.id).put("title", c.title).put("projectId", c.projectId)
            .put("status", c.status).put("pinned", c.pinnedAt > 0).put("archived", c.archivedAt > 0).put("updatedAt", c.updatedAt)
        return JSONObject()
            .put("instanceId", instanceId).put("version", ++snapshotVersion)
            .put("theme", theme)
            .put("media", media.catalog())
            .put("motionEnabled", android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
            .put("streamingAnimationMode", settings.streamingAnimationMode)
            .put("canContinue", controller.canContinueActive())
            .put("composerDelivery", composerDeliveryJson(queue))
            .put("locale", locale())
            .put("sessionRevision", controller.sessionRevision.intValue)
            .put("conversationId", controller.activeConversationId.value).put("projectId", controller.activeProjectId())
            .put("title", controller.activeConversation()?.title ?: context.getString(com.yukisoffd.lyracode.R.string.title_new_chat))
            .put("running", controller.isActiveConversationBusy()).put("loading", controller.messagesLoading.value)
            .put("generating",controller.isActiveConversationGenerating())
            .put("compressing", controller.isActiveHistoryCompressionRunning()).put("status", controller.status.value)
            .put("profileId", controller.activeProfileId.value).put("model", controller.activeModel.value)
            .put("workspace", JSONObject().put("uri", workspace.activeWorkspaceUri()).put("name", workspace.displayName()).put("path", workspace.displayPath()))
            .put("conversations", array(controller.conversations + controller.archivedConversations, ::conversation))
            .put("projects", array(controller.projects + controller.archivedProjects) { p -> JSONObject().put("id", p.id).put("name", p.name)
                .put("workspaceUri", p.workspaceUri).put("pinned", p.pinnedAt > 0).put("archived", p.archivedAt > 0) })
            .put("messages", array(controller.messages.value, ::messageJson))
            .put("uploads", array(controller.pendingUploads.withIndex().toList()) { (i, f) -> JSONObject().put("index", i).put("uri", f.uri).put("name", f.name).put("size", f.size).put("kind", f.mediaKind) })
            .put("profiles", array(controller.profiles) { p -> JSONObject().put("id", p.id).put("name", p.name)
                .put("models", JSONArray((p.enabledModels + p.selectedModel).filter { it.isNotBlank() }.distinct())) })
            .put("autoApproval", controller.isAutoApprovalEnabledForActiveSession())
            .put("reasoning", settings.reasoningDepth).put("reasoningOptions", JSONArray(AppSettings.reasoningDepthValues))
            .put("promptId", settings.selectedSystemPromptId).put("purePrompt", settings.purePromptMode)
            .put("prompts", array(settings.systemPromptPresets()) { p -> JSONObject().put("id", p.id).put("name", p.name)
                .put("prompt", p.prompt).put("exampleConversation", p.exampleConversation).put("builtIn", p.builtIn) })
            .put("subAgentsEnabled", settings.subAgentOrchestrationEnabled)
            .put("subAgents", array(settings.subAgents()) { a -> JSONObject().put("id", a.id).put("name", a.name).put("profileId", a.profileId)
                .put("model", a.model).put("description", a.description).put("enabled", a.enabled) })
            .put("tools", array(agentToolCatalog().map { Triple(it.name, it.title, it.description) } + settings.enabledMcpTools().map { (s, t) -> Triple(settings.mcpToolFunctionName(s, t), "${s.name} / ${t.name}", t.description) }) { (name, title, description) ->
                JSONObject().put("name", name).put("title", title).put("description", description)
                    .put("locked", name == "manage_app_config").put("enabled", name == "manage_app_config" || name !in disabled) })
            .put("mcpServers", array(settings.mcpServers()) { s -> JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
                .put("enabled", s.enabled).put("rawJson", s.rawJson).put("timeoutSeconds", s.timeoutSeconds)
                .put("tools", array(s.tools) { t -> JSONObject().put("name", t.name).put("description", t.description).put("inputSchema", t.inputSchema) }) })
            .put("context", JSONObject().put("tokens", usage.estimatedTokens).put("messages", usage.contextMessageCount)
                .put("turns", usage.turnsSinceCompression).put("compressed", usage.hasCompressedHistory).put("updating", usage.updating))
            .put("compression", JSONObject().put("mode", compression.first).put("turns", compression.second).put("tokens", compression.third)
                .put("chunks", settings.historyCompressionChunkCount).put("minChunks", AppSettings.MIN_HISTORY_COMPRESSION_CHUNKS).put("maxChunks", AppSettings.MAX_HISTORY_COMPRESSION_CHUNKS)
                .put("profileId", settings.historyCompressionProfileId).put("model", settings.historyCompressionModel))
            .put("approval", controller.pendingToolApproval.value?.let { p -> JSONObject().put("id", p.id).put("conversationId", p.request.conversationId)
                .put("tool", p.request.toolName).put("summary", p.request.summary).put("arguments", p.request.arguments).put("risk", p.request.risk) } ?: JSONObject.NULL)
            .put("question", controller.pendingUserQuestion.value?.let { p -> JSONObject().put("id", p.id).put("conversationId", p.request.conversationId)
                .put("title", p.request.title).put("question", p.request.question).put("options", JSONArray(p.request.options)) } ?: JSONObject.NULL)
    }

    private fun requireSession(args: JSONObject) {
        if (args.has("instanceId") && args.getString("instanceId") != instanceId) throw WebHttpError(409,"手机端已切换会话，请刷新后重试")
        if (!args.has("sessionRevision") || args.getInt("sessionRevision") != controller.sessionRevision.intValue ||
            !args.has("conversationId") || !args.has("projectId") || args.getLong("conversationId") != controller.activeConversationId.value ||
            args.getLong("projectId") != controller.activeProjectId()) throw WebHttpError(409, "手机端已切换会话，请刷新后重试")
    }

    /** A run-owned refusal is a conflict, not a malformed request. */
    private fun requireNotBusy(message: String) {
        if (controller.isActiveConversationBusy()) throw WebHttpError(409, message)
    }

    suspend fun action(args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val action = args.getString("action")
        if (action in setOf("send", "stop", "continue", "workspace", "autoApproval", "compression", "compress", "removeUpload", "model", "refreshContext", "branch", "editMessage")) requireSession(args)
        when (action) {
            "send" -> {
                val text = args.getString("text")
                require(text.isNotBlank() || controller.pendingUploads.isNotEmpty()) { "请输入消息或上传文件" }
                // The web composer has no per-message queue controls, so a mid-run send
                // always queues. Steering stays a queue-row action on the phone.
                when (controller.send(text)) {
                    SendOutcome.SENT -> return@withContext JSONObject().put("ok", true).put("delivery", "sent")
                    SendOutcome.QUEUED -> {
                        val queue = controller.pendingInputSnapshotFor()
                        return@withContext JSONObject().put("ok", true).put("delivery", "queued")
                            .put("position", queue.queuedCount)
                    }
                    SendOutcome.BLOCKED -> throw WebHttpError(409, context.getString(R.string.status_message_refused))
                }
            }
            "stop" -> controller.stopActive()
            "continue" -> {
                requireNotBusy("会话正在运行，请重试")
                controller.continueActive()
            }
            "new" -> controller.newConversation()
            "select" -> { require(controller.usageStore().conversation(args.getLong("id")) != null) { "会话不存在" }; controller.selectConversation(args.getLong("id")) }
            "newProjectChat" -> controller.startProjectConversation(args.getLong("id"))
            "projectCreate" -> controller.createProject(authorizedWorkspace(args.getString("uri")))
            "workspace" -> controller.persistWorkspaceForActiveSession(authorizedWorkspace(args.getString("uri")))
            "browseWorkspace" -> {
                return@withContext withContext(Dispatchers.IO) { browseWorkspace(args.optString("uri")) }
            }
            "autoApproval" -> controller.setAutoApprovalForActiveSession(args.getBoolean("enabled"))
            "model" -> {
                val p = controller.profiles.firstOrNull { it.id == args.getString("profileId") } ?: error("服务商不存在")
                val model = args.getString("model").trim(); require(model.isNotEmpty() && model.length <= 256)
                controller.selectProfile(p.id); controller.selectModel(model)
            }
            "reasoning" -> { require(args.getString("value") in AppSettings.reasoningDepthValues); controller.selectReasoningDepth(args.getString("value")) }
            "approval" -> {
                val pending = controller.pendingToolApproval.value ?: throw WebHttpError(409, "审批已处理")
                if (pending.id != args.getLong("id")) throw WebHttpError(409, "审批请求已更新")
                controller.answerToolApproval(args.getBoolean("approved"), args.optBoolean("remember"), args.optString("feedback"))
            }
            "question" -> {
                val p = controller.pendingUserQuestion.value ?: throw WebHttpError(409, "问题已处理")
                if (p.id != args.getLong("id")) throw WebHttpError(409, "问题已更新")
                controller.markUserQuestionInteraction(p.id)
                val options = args.optJSONArray("options") ?: JSONArray()
                controller.answerUserQuestion((0 until options.length()).map { options.getString(it) }.also { require(it.all(p.request.options::contains)) }, args.optString("text"))
            }
            "removeUpload" -> { val uri = args.getString("uri"); val index = controller.pendingUploads.indexOfFirst { it.uri == uri }; require(index >= 0); controller.removePendingUpload(index) }
            "promptSelect" -> { require(settings.systemPromptPresets().any { it.id == args.getString("id") }); controller.selectSystemPrompt(args.getString("id")) }
            "purePrompt" -> settings.purePromptMode = args.getBoolean("enabled")
            "promptSave" -> {
                val id = args.optString("id").ifBlank { UUID.randomUUID().toString() }
                require(id != AppSettings.NATIVE_SYSTEM_PROMPT_ID) { "原生提示词不能编辑" }
                require(args.getString("name").isNotBlank())
                settings.saveSystemPromptConfig(SystemPromptPreset(id, args.getString("name"), args.getString("prompt"), args.optString("exampleConversation"), false))
            }
            "promptDelete" -> { require(settings.systemPromptPresets().any { it.id == args.getString("id") && !it.builtIn }); settings.deleteSystemPromptConfig(args.getString("id")) }
            "subAgentsEnabled" -> settings.subAgentOrchestrationEnabled = args.getBoolean("enabled")
            "subAgentSave" -> {
                val id = args.optString("id").ifBlank { UUID.randomUUID().toString() }
                require(controller.profiles.any { it.id == args.getString("profileId") }); require(args.getString("name").isNotBlank() && args.getString("model").isNotBlank())
                val agent = SubAgentConfig(id, args.getString("name"), args.getString("profileId"), args.getString("model"), args.optString("description"), args.getBoolean("enabled"))
                settings.saveSubAgents(settings.subAgents().filterNot { it.id == id } + agent)
            }
            "subAgentDelete" -> settings.saveSubAgents(settings.subAgents().filterNot { it.id == args.getString("id") })
            "tool" -> {
                val name = args.getString("name")
                require(name != "manage_app_config" || args.getBoolean("enabled")) { "This tool is always enabled" }
                require(agentToolCatalog().any { it.name == name } || settings.enabledMcpTools().any { (s, t) -> settings.mcpToolFunctionName(s, t) == name })
                settings.setToolEnabled(name, args.getBoolean("enabled"))
            }
            "mcpSave" -> {
                val id = args.optString("id").ifBlank { UUID.randomUUID().toString() }
                val config = McpJsonConfig(args.getString("rawJson")); config.validateEndpoint()
                val old = settings.mcpServers().firstOrNull { it.id == id }
                val server = McpServerConfig(id, args.getString("name"), config.url, config.authKey, config.transport,
                    args.getInt("timeoutSeconds").coerceIn(5, 600), args.getBoolean("enabled"), config.root.toString(), emptyList())
                settings.upsertMcpServer(config.project(server).copy(tools = if (old?.rawJson == server.rawJson) old.tools else emptyList())); mcp.invalidate(id)
            }
            "mcpDelete" -> { settings.deleteMcpServer(args.getString("id")); mcp.invalidate(args.getString("id")) }
            "mcpEnabled" -> { settings.setMcpServerEnabled(args.getString("id"), args.getBoolean("enabled")); mcp.invalidate(args.getString("id")) }
            "mcpRefresh" -> {
                val server = settings.mcpServers().first { it.id == args.getString("id") }
                withContext(Dispatchers.IO) { mcp.testAndRefreshTools(server).getOrThrow() }
            }
            "refreshContext" -> controller.refreshContextWindowUsage()
            "compression" -> {
                val mode = args.getString("mode"); require(mode in ConversationStore.AUTO_COMPRESSION_MODES)
                val chunks = args.getInt("chunks"); require(chunks in AppSettings.MIN_HISTORY_COMPRESSION_CHUNKS..AppSettings.MAX_HISTORY_COMPRESSION_CHUNKS)
                val profileId = args.optString("profileId"); require(profileId.isBlank() || controller.profiles.any { it.id == profileId })
                settings.historyCompressionProfileId = profileId; settings.historyCompressionModel = args.optString("model")
                settings.historyCompressionChunkCount = chunks
                controller.setAutoCompressionForActiveSession(mode, args.getInt("turns"), args.getLong("tokens"))
            }
            "compress" -> {
                requireNotBusy("会话正在运行，请重试")
                require(controller.activeConversationId.value > 0) { "当前会话不能压缩" }
                controller.compressActiveHistory(args.optString("instruction")) { result -> result.onFailure { controller.status.value = it.message.orEmpty() } }
            }
            "rename" -> { require(args.getString("title").isNotBlank()); controller.renameConversation(args.getLong("id"), args.getString("title")) }
            "pin" -> controller.setConversationPinned(args.getLong("id"), args.getBoolean("enabled"))
            "archive" -> controller.archiveConversation(args.getLong("id"))
            "restore" -> controller.restoreArchivedConversation(args.getLong("id"))
            "projectRename" -> { require(args.getString("name").isNotBlank()); controller.renameProject(args.getLong("id"), args.getString("name")) }
            "projectPin" -> controller.setProjectPinned(args.getLong("id"), args.getBoolean("enabled"))
            "projectArchive" -> controller.archiveProject(args.getLong("id"))
            "projectRestore" -> controller.restoreArchivedProject(args.getLong("id"))
            "branch" -> controller.createConversationBranch(args.getLong("id"))
            "editMessage" -> {
                requireNotBusy("会话正在运行，请重试")
                controller.editAndRegenerateUserMessage(args.getLong("id"), args.getString("text"))
            }
            else -> throw WebHttpError(400, "Unknown action")
        }
        controller.settingsRevision.intValue++
        JSONObject().put("ok", true)
    }

    suspend fun upload(headers: Map<String, String>, bytes: ByteArray): JSONObject {
        val expected = JSONObject().put("conversationId", headers["x-conversation-id"]?.toLongOrNull()).put("projectId", headers["x-project-id"]?.toLongOrNull())
            .put("sessionRevision", headers["x-session-revision"]?.toIntOrNull())
        // A queued follow-up needs its attachment captured while the run is active.
        withContext(Dispatchers.Main) {
            requireSession(expected)
            if (!settings.composerDeliveryEnabled) requireNotBusy("请等待当前回复结束")
        }
        val name = URLDecoder.decode(headers["x-file-name"].orEmpty(), "UTF-8")
        val file = withContext(Dispatchers.IO) { uploads.saveWebUpload(name, headers["content-type"].orEmpty(), bytes).getOrThrow() }
        try {
            withContext(Dispatchers.Main) {
                requireSession(expected)
                if (!settings.composerDeliveryEnabled) requireNotBusy("会话正在运行，请重试")
                controller.pendingUploads += file
            }
        } catch (error: Exception) { uploads.discardWebUpload(file); throw error }
        return JSONObject().put("ok", true).put("name", file.name)
    }

    private fun authorizedWorkspace(value: String): Uri {
        val uri = Uri.parse(value)
        if (uri.scheme == ProotWorkspace.SCHEME) { ProotWorkspace.directory(context, uri); return uri }
        SharedStorageWorkspace.directory(context, uri)
        return uri
    }

    private fun browseWorkspace(value: String): JSONObject {
        if (value.isBlank()) {
            val entries = SharedStorageWorkspace.roots(context).map { root ->
                JSONObject().put("uri", Uri.fromFile(root)).put("name", root.path).put("browsable", true)
            }.toMutableList()
            ProotLinuxManager.getInstance(context).state.value.instances.filter { it.enabled && it.rootfsDir.isDirectory }.forEach { i ->
                entries += JSONObject().put("uri", ProotWorkspace.uri(i.id, "/").toString()).put("name", "PRoot · ${i.name}").put("browsable", true)
            }
            return JSONObject().put("entries", JSONArray(entries)).put("uri", "").put("needsStoragePermission", !SharedStorageWorkspace.permitted())
        }
        val uri = authorizedWorkspace(value)
        val entries = if (uri.scheme == ProotWorkspace.SCHEME) ProotWorkspace.directory(context, uri).listFiles().orEmpty()
            .filter { it.isDirectory }.sortedBy { it.name.lowercase() }.mapNotNull { file ->
                val next = ProotWorkspace.uri(uri.authority.orEmpty(), uri.path.orEmpty().trimEnd('/') + "/" + file.name)
                runCatching { ProotWorkspace.directory(context, next); JSONObject().put("uri", next.toString()).put("name", file.name).put("browsable", true) }.getOrNull()
            } else {
                val children = SharedStorageWorkspace.directory(context, uri).listFiles() ?: error("目录不可访问")
                children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.mapNotNull { directory ->
                    runCatching { SharedStorageWorkspace.directory(context, Uri.fromFile(directory));
                        JSONObject().put("uri", Uri.fromFile(directory)).put("name", directory.name).put("browsable", true) }.getOrNull()
                }
            }
        return JSONObject().put("entries", JSONArray(entries)).put("uri", value)
    }
}
