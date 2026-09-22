package com.yukisoffd.lyracode.interaction.session

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.interaction.DeviceInteractionAvailability
import com.yukisoffd.lyracode.interaction.agent.DeviceAgentFactory
import com.yukisoffd.lyracode.interaction.agent.DeviceInteractionToolProvider
import com.yukisoffd.lyracode.interaction.overlay.OverlayPermission
import com.yukisoffd.lyracode.interaction.service.AccessibilityConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One explicitly requested foreground task at a time; process death never resumes actions. */
internal object DeviceTaskCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var generation = 0L
    private var taskExpiry = 0L
    private var taskSession = 0L
    private var contextSession = 0L
    private var conversationId: Long? = null
    private var floatingProfileId: String? = null
    private var floatingModel: String? = null
    private var floatingWorkspace: String? = null

    @Synchronized fun configure(context: Context, payload: String) {
        val state = ManualControlController.state.value
        if (!DeviceInteractionAvailability.isSupported() || !state.isActive() || state.chat.running) return
        if (contextSession != state.sessionId) {
            contextSession = state.sessionId
            conversationId = null
            floatingProfileId = null; floatingModel = null; floatingWorkspace = null
        }
        val settings = AppSettings(context)
        val value = runCatching { org.json.JSONObject(payload) }.getOrNull() ?: return
        if (value.has("profile")) {
            val profile = settings.profiles().firstOrNull { it.id == value.optString("profile") } ?: return
            val model = value.optString("model")
            if (model !in (profile.enabledModels + profile.selectedModel)) return
            floatingProfileId = profile.id; floatingModel = model
            conversationId?.let { id -> ConversationStore(context).use { it.setConversationMeta(id, profileId = profile.id, model = model) } }
        }
        if (value.has("workspace")) {
            val uri = value.optString("workspace")
            val parsed = android.net.Uri.parse(uri)
            val privateWorkspace = parsed.scheme == com.yukisoffd.lyracode.workspace.ProotWorkspace.SCHEME
            if (privateWorkspace) {
                if (runCatching { com.yukisoffd.lyracode.workspace.ProotWorkspace.directory(context, parsed) }.isFailure) return
            } else if (uri.isNotBlank() && context.contentResolver.persistedUriPermissions.none { it.uri.toString() == uri && it.isReadPermission }) return
            floatingWorkspace = uri
            conversationId?.let { id -> ConversationStore(context).use { it.setConversationMeta(id, workspaceUri = uri) } }
        }
        if (value.has("pure")) settings.purePromptMode = value.optBoolean("pure")
        val profile = settings.profiles().firstOrNull { it.id == floatingProfileId } ?: settings.selectedProfile()
        val options = org.json.JSONObject().apply {
            put("models", org.json.JSONArray().apply {
                settings.profiles().forEach { p ->
                    (p.enabledModels + p.selectedModel).filter(String::isNotBlank).distinct().forEach { m ->
                        put(org.json.JSONObject().put("profile", p.id).put("model", m).put("label", "${p.name} · $m"))
                    }
                }
            })
        }
        val uri = floatingWorkspace ?: settings.workspaceUri
        val workspaceName = uri?.takeIf { it.isNotBlank() }?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(it))?.name }
        ManualControlController.updateChat(state.chat.copy(modelLabel = floatingModel ?: profile.selectedModel,
            providerLabel = "${profile.name} · ${floatingModel ?: profile.selectedModel}",
            workspaceLabel = workspaceName.orEmpty(), configurationOptions = options.toString(), pureMode = settings.purePromptMode))
    }
    @Volatile private var activeAgent: com.yukisoffd.lyracode.ai.OpenAiAgent? = null

    @Synchronized
    fun submit(context: Context, rawInput: String) {
        if (DeviceQuestionBroker.answer(rawInput.trim())) return
        val current = ManualControlController.state.value
        if (!current.isActive() || job?.isCompleted == false || current.chat.running) return
        if (contextSession != current.sessionId) {
            contextSession = current.sessionId
            conversationId = null
            floatingProfileId = null; floatingModel = null; floatingWorkspace = null
        }
        val input = rawInput.trim().take(2000)
        if (input.isBlank()) return
        val app = context.applicationContext
        val settings = AppSettings(app)
        val target = current.targetPackage
        val selected = settings.profiles().firstOrNull { it.id == floatingProfileId } ?: settings.selectedProfile()
        val profile = selected.copy(selectedModel = floatingModel?.takeIf { selected.id == floatingProfileId && it in (selected.enabledModels + selected.selectedModel) } ?: selected.selectedModel)
        val token = ++generation
        val expiry = current.activeUntilEpochMillis
        val sessionId = current.sessionId
        taskExpiry = expiry
        taskSession = sessionId
        val userMessage = DeviceChatMessage(-System.nanoTime(), "user", input)
        ManualControlController.clearSelection()
        ManualControlController.updateChat(current.chat.copy(
            messages = (current.chat.messages + userMessage).takeLast(16), running = true,
            status = "思考中", providerLabel = "${profile.name} · ${profile.selectedModel}",
            modelLabel = profile.selectedModel, pureMode = settings.purePromptMode,
        ), target)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            var store: ConversationStore? = null
            var monitor: Job? = null
            try {
                checkAvailability(app)
                val taskJob = coroutineContext[Job]!!
                monitor = scope.launch {
                    while (isActive) {
                        delay(150)
                        val state = ManualControlController.state.value
                        if (!state.isActive() || state.activeUntilEpochMillis != expiry ||
                            state.sessionId != sessionId ||
                            runCatching { checkAvailability(app) }.isFailure) {
                            taskJob.cancel(CancellationException("设备任务因权限、锁屏、会话结束而中断。"))
                            activeAgent?.cancelScopedRequests()
                            break
                        }
                    }
                }
                // The same persisted conversation is reused until the user clears context.
                store = ConversationStore(app)
                val id = synchronized(this@DeviceTaskCoordinator) {
                    conversationId?.takeIf { store.conversation(it) != null }
                        ?: store.createConversation(profile.id, profile.selectedModel, title = "悬浮对话 · ${input.take(24)}", workspaceUri = floatingWorkspace ?: settings.workspaceUri.orEmpty()).also { conversationId = it }
                }
                val provider = DeviceInteractionToolProvider(id, target.orEmpty(), expiry,
                    ExecutionBudget(SystemClock::elapsedRealtime),
                    checkAvailability = { checkAvailability(app) },
                    onStatus = { status -> update(token) { it.copy(status = status.take(240)) } },
                    onTool = { eventId, name, result -> update(token) { chat ->
                        val message = DeviceChatMessage(eventId, "tool", result, toolName = name, createdAt = chat.messages.firstOrNull { it.id == eventId }?.createdAt ?: System.currentTimeMillis())
                        chat.copy(messages = if (chat.messages.any { it.id == eventId })
                            chat.messages.map { if (it.id == eventId) message else it }
                            else (chat.messages + message).takeLast(16))
                    } },
                    onFinish = { summary -> update(token) {
                        it.copy(messages = (it.messages + DeviceChatMessage(-System.nanoTime(), "assistant", summary)).takeLast(16))
                    } },
                )
                val agent = DeviceAgentFactory.create(app, settings, store, floatingWorkspace ?: settings.workspaceUri)
                agent.scopedTools = provider
                agent.userQuestionHandler = DeviceQuestionBroker::ask
                agent.approvalHandler = { request ->
                    val approved = DeviceApprovalBroker.request(request.summary,
                        "${request.risk}\n${request.arguments}".take(16000),
                        twice = request.toolName.contains("delete") || request.toolName.contains("uninstall") ||
                            Regex("""(?i)\b(rm|rmdir|uninstall|delete)\b""").containsMatchIn(request.arguments))
                    com.yukisoffd.lyracode.ai.ToolApprovalDecision(approved)
                }
                provider.nativeDefinitions = { agent.floatingToolDefinitions() }
                provider.nativeExecute = { name, args -> agent.executeFloatingTool(id, name, args) }
                provider.deviceAvailable = { DeviceInteractionAvailability.isSupported() && settings.deviceInteractionExperimentalEnabled && AccessibilityConnection.connected.value }
                provider.extended = com.yukisoffd.lyracode.interaction.agent.DeviceExtendedTools(app, settings, agent, id) { provider.ensureSession() }
                activeAgent = agent
                run {
                    agent.chat(id, input, profile, profile.selectedModel, propagateErrors = true) { delta ->
                        update(token) { chat ->
                            val messages = if (delta.content.isBlank() && delta.thinking.isBlank()) chat.messages else {
                                val messageId = token * 1_000_000L + delta.messageId
                                val message = DeviceChatMessage(messageId, "assistant", delta.content.takeLast(4000), thinking = delta.thinking.takeLast(4000), createdAt = chat.messages.firstOrNull { it.id == messageId }?.createdAt ?: System.currentTimeMillis())
                                if (chat.messages.any { it.id == messageId }) chat.messages.map { if (it.id == messageId) message else it }
                                else (chat.messages + message).takeLast(16)
                            }
                            chat.copy(messages = messages, status = if (ManualControlController.state.value.selection != null)
                                chat.status else delta.status.take(240))
                        }
                    }
                }
                update(token) { it.copy(running = false, status = "已完成") }
            } catch (error: CancellationException) {
                update(token) { it.copy(running = false, status = "已中断，可重新发起任务") }
            } catch (error: Exception) {
                update(token) { it.copy(running = false, status = "任务停止：${error.message.orEmpty().take(180)}") }
            } finally {
                monitor?.cancel()
                synchronized(this@DeviceTaskCoordinator) {
                    if (token == generation && ManualControlController.state.value.sessionId == sessionId) {
                        ManualControlController.clearSelection()
                        val chat = ManualControlController.state.value.chat
                        ManualControlController.updateChat(chat, null)
                    }
                }
                synchronized(this@DeviceTaskCoordinator) { if (token == generation) activeAgent = null }
                store?.close()
            }
        }
        job = next
        next.start()
    }

    @Synchronized
    fun pause() {
        ++generation
        job?.cancel()
        activeAgent?.cancelScopedRequests()
        DeviceApprovalBroker.cancel()
        DeviceQuestionBroker.cancel()
        val chat = ManualControlController.state.value.chat
        ManualControlController.clearSelection()
        ManualControlController.updateChat(chat.copy(running = false, status = "已暂停；发送新任务后继续"), null)
    }

    @Synchronized
    fun clearContext() {
        pause()
        conversationId = null
        ManualControlController.setApproval(null)
        ManualControlController.updateChat(ManualControlController.state.value.chat.copy(messages = emptyList(), running = false, status = ""), null)
    }

    @Synchronized
    private fun update(token: Long, transform: (DeviceChatState) -> DeviceChatState) {
        if (token != generation) return
        val state = ManualControlController.state.value
        if (state.activeUntilEpochMillis != taskExpiry || state.sessionId != taskSession) return
        ManualControlController.updateChat(transform(state.chat))
    }

    private fun checkAvailability(context: Context) {
        check(DeviceInteractionAvailability.isSupported()) { context.getString(com.yukisoffd.lyracode.R.string.device_interaction_status_unsupported) }
        check(OverlayPermission.isGranted(context)) { "悬浮窗权限不可用。" }
        check(context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "锁屏时不能执行设备任务。" }
    }

    // Conservative Alpha exclusion in addition to the existing manual debugger policy.
    internal fun isAgentPackageAllowed(packageName: String): Boolean =
        com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy.isPackageAllowed(packageName) &&
            listOf("password", "authenticator", "keychain")
                .none { packageName.contains(it, ignoreCase = true) }
}
