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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Why a scoped device task released its conversation. */
internal enum class ConversationIdleOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Final outcome from the body's explicit outcome flag and the job's completion cause.
 * A body that swallowed an exception still reports failure, so only a true normal
 * completion may deliver a queued follow-up. Cancellation parks like failure.
 */
internal fun deviceTaskIdleOutcome(
    bodyOutcome: ConversationIdleOutcome,
    completionCause: Throwable?,
): ConversationIdleOutcome = when {
    completionCause == null -> bodyOutcome
    completionCause is CancellationException -> ConversationIdleOutcome.CANCELLED
    else -> ConversationIdleOutcome.FAILED
}

/**
 * A task superseded while a newer task is active on the same conversation must stay
 * silent: the newer task releases that queue when it finishes.
 */
internal fun isSupersededIdleNotification(
    token: Long,
    generation: Long,
    newerTaskActiveOnConversation: Boolean,
): Boolean = token != generation && newerTaskActiveOnConversation

/**
 * Listeners notified after a scoped device task releases its conversation.
 *
 * The coordinator knows nothing about queues or agent runs; the chat controller uses
 * this seam to drain or park a follow-up that was queued while the device task owned
 * the conversation. A failing listener must not break job completion.
 *
 * Listeners run synchronously on the completing task's thread (a Default dispatcher
 * worker), never on the main thread. A listener must not touch UI or block; the chat
 * controller switches to Main and rechecks ownership there.
 */
internal class ConversationIdleCallbacks {
    private val listeners = LinkedHashSet<(Long, ConversationIdleOutcome) -> Unit>()

    @Synchronized
    fun add(listener: (Long, ConversationIdleOutcome) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun remove(listener: (Long, ConversationIdleOutcome) -> Unit) {
        listeners -= listener
    }

    fun notify(conversationId: Long, outcome: ConversationIdleOutcome) {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { listener -> runCatching { listener(conversationId, outcome) } }
    }
}

/** One explicitly requested foreground task at a time; process death never resumes actions. */
internal object DeviceTaskCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idleCallbacks = ConversationIdleCallbacks()
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
            messages = current.chat.messages + userMessage, running = true,
            status = "思考中", providerLabel = "${profile.name} · ${profile.selectedModel}",
            modelLabel = profile.selectedModel, pureMode = settings.purePromptMode,
        ), target)
        // The chat controller may have queued a follow-up while this task owned the
        // conversation and needs to know when it becomes free again. Start from the
        // conversation this task will reuse, if any, so an early failure still parks its
        // queue. A session change cleared the id; the body replaces this value once it
        // resolves or creates the conversation. Atomics make both values safely visible
        // to the completion hook.
        val taskConversationId = AtomicLong(conversationId ?: 0L)
        val bodyOutcome = AtomicReference(ConversationIdleOutcome.CANCELLED)
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
                taskConversationId.set(id)
                val provider = DeviceInteractionToolProvider(id, target.orEmpty(), expiry,
                    ExecutionBudget(SystemClock::elapsedRealtime),
                    checkAvailability = { checkAvailability(app) },
                    onStatus = { status -> update(token) { it.copy(status = status.take(240)) } },
                    onTool = { eventId, name, result -> update(token) { chat ->
                        val message = DeviceChatMessage(eventId, "tool", result, toolName = name, createdAt = chat.messages.firstOrNull { it.id == eventId }?.createdAt ?: System.currentTimeMillis())
                        chat.copy(messages = if (chat.messages.any { it.id == eventId })
                            chat.messages.map { if (it.id == eventId) message else it }
                            else chat.messages + message)
                    } },
                    onFinish = { summary -> update(token) {
                        it.copy(messages = it.messages + DeviceChatMessage(-System.nanoTime(), "assistant", summary))
                    } },
                )
                val agent = DeviceAgentFactory.create(app, settings, store, floatingWorkspace ?: settings.workspaceUri)
                agent.scopedTools = provider
                agent.userQuestionHandler = { request ->
                    DeviceQuestionBroker.ask(request, app.getString(com.yukisoffd.lyracode.R.string.ask_user_recommended))
                }
                agent.approvalHandler = { request ->
                    val approved = com.yukisoffd.lyracode.interaction.DeviceFullAccess.isEnabled(app) || DeviceApprovalBroker.request(request.summary,
                        "${request.risk}\n${request.arguments}".take(16000),
                        twice = request.toolName.contains("delete") || request.toolName.contains("uninstall") ||
                            Regex("""(?i)\b(rm|rmdir|uninstall|delete)\b""").containsMatchIn(request.arguments))
                    com.yukisoffd.lyracode.ai.ToolApprovalDecision(approved)
                }
                provider.nativeDefinitions = { agent.floatingToolDefinitions() }
                provider.nativeExecute = { name, args -> agent.executeFloatingTool(id, name, args) }
                provider.fullAccessEnabled = { com.yukisoffd.lyracode.interaction.DeviceFullAccess.isEnabled(app) }
                provider.deviceAvailable = { DeviceInteractionAvailability.isSupported() && settings.deviceInteractionExperimentalEnabled && AccessibilityConnection.connected.value }
                provider.extended = com.yukisoffd.lyracode.interaction.agent.DeviceExtendedTools(app, settings, agent, id) { provider.ensureSession() }
                provider.capabilityProbe = { provider.extended!!.capabilities() }
                activeAgent = agent
                run {
                    agent.chat(id, input, profile, profile.selectedModel, propagateErrors = true) { delta ->
                        update(token) { chat ->
                            val messages = if (delta.content.isBlank() && delta.thinking.isBlank()) chat.messages else {
                                val messageId = token * 1_000_000L + delta.messageId
                                val message = DeviceChatMessage(messageId, "assistant", delta.content, thinking = delta.thinking, createdAt = chat.messages.firstOrNull { it.id == messageId }?.createdAt ?: System.currentTimeMillis())
                                if (chat.messages.any { it.id == messageId }) chat.messages.map { if (it.id == messageId) message else it }
                                else chat.messages + message
                            }
                            chat.copy(messages = messages, status = if (ManualControlController.state.value.selection != null)
                                chat.status else delta.status.take(240))
                        }
                    }
                }
                update(token) { it.copy(running = false, status = "已完成") }
                bodyOutcome.set(ConversationIdleOutcome.COMPLETED)
            } catch (error: CancellationException) {
                // The body swallows cancellation to update the overlay; it must still
                // report cancellation so a queued follow-up is parked, never delivered.
                bodyOutcome.set(ConversationIdleOutcome.CANCELLED)
                update(token) { it.copy(running = false, status = "已中断，可重新发起任务") }
            } catch (error: Exception) {
                // Swallowing the exception keeps the process alive, but the release must
                // not look like a normal completion.
                bodyOutcome.set(ConversationIdleOutcome.FAILED)
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
        // Completion (not the job body's finally) is the point where isRunningConversation
        // has already turned false, so a listener can safely release a queued follow-up.
        next.invokeOnCompletion { cause ->
            val id = taskConversationId.get().takeIf { it > 0L } ?: return@invokeOnCompletion
            // A newer task may own this conversation before this one finishes; it will
            // release the queue itself, so a stale completion must stay silent.
            val superseded = synchronized(this@DeviceTaskCoordinator) {
                isSupersededIdleNotification(
                    token = token,
                    generation = generation,
                    newerTaskActiveOnConversation = job !== next && job?.isActive == true && conversationId == id,
                )
            }
            if (!superseded) {
                idleCallbacks.notify(id, deviceTaskIdleOutcome(bodyOutcome.get(), cause))
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

    /**
     * Read-only query for other subsystems (the chat composer's steer guards). A scoped
     * device task owns its persisted conversation and is invisible to ChatController's job
     * registry, so callers need this to avoid starting a concurrent run or accepting a
     * steer the device agent will never drain.
     */
    @Synchronized
    fun isRunningConversation(id: Long): Boolean = job?.isActive == true && conversationId == id

    /**
     * Registers a listener for the end of a scoped device task on its conversation.
     * The listener runs on the completing task's thread (not Main) and must not touch
     * UI or block.
     */
    fun addConversationIdleListener(listener: (Long, ConversationIdleOutcome) -> Unit) {
        idleCallbacks.add(listener)
    }

    /** Removes a listener registered with [addConversationIdleListener]. */
    fun removeConversationIdleListener(listener: (Long, ConversationIdleOutcome) -> Unit) {
        idleCallbacks.remove(listener)
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
        check(com.yukisoffd.lyracode.interaction.DeviceFullAccess.isEnabled(context) ||
            (context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)) { "普通模式下锁屏时不能执行设备任务。" }
    }

    // Standard mode excludes protected packages; the authenticated grant lifts this app policy.
    internal fun isAgentPackageAllowed(packageName: String, fullAccess: Boolean = false): Boolean =
        com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy.isPackageAllowed(packageName, fullAccess) &&
            (fullAccess || listOf("password", "authenticator", "keychain")
                .none { packageName.contains(it, ignoreCase = true) }
            )
}
