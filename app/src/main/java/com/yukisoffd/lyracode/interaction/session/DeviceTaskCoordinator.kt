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
    private var contextHistory: List<DeviceChatMessage> = emptyList()
    @Volatile private var activeAgent: com.yukisoffd.lyracode.ai.OpenAiAgent? = null

    @Synchronized
    fun submit(context: Context, rawInput: String) {
        val current = ManualControlController.state.value
        if (!current.isActive() || job?.isCompleted == false || current.chat.running) return
        if (contextSession != current.sessionId) {
            contextSession = current.sessionId
            contextHistory = emptyList()
        }
        val input = rawInput.trim().take(2000)
        if (input.isBlank()) return
        val app = context.applicationContext
        val settings = AppSettings(app)
        val target = current.targetPackage
        val profile = settings.selectedProfile()
        val failure = when {
            target.isNullOrBlank() || target.startsWith(app.packageName) -> "请先手动打开目标 App，等待页面识别后再发送。"
            !isAgentPackageAllowed(target) -> "此 App 不在设备 Agent 的支持范围内。"
            profile.apiKey.isBlank() -> "请先在 Lyra 设置中配置模型 API Key。"
            com.yukisoffd.lyracode.ai.isMediaGenerationModel(profile.selectedModel) -> "请选择支持工具调用的对话模型。"
            else -> null
        }
        if (failure != null) {
            ManualControlController.updateChat(current.chat.copy(status = failure))
            return
        }
        target ?: return
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
                // Reuse the existing schema in SQLite memory. Screen tool results never reach backups/history.
                store = ConversationStore(app, inMemory = true)
                val id = store.createConversation(profile.id, profile.selectedModel, title = "设备任务 · ${input.take(24)}")
                contextHistory.takeLast(24).forEach { previous ->
                    store.addMessage(id, previous.role, previous.text, profileId = profile.id, model = profile.selectedModel)
                }
                val provider = DeviceInteractionToolProvider(id, target, expiry,
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
                val agent = DeviceAgentFactory.create(app, settings, store)
                agent.scopedTools = provider
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
                synchronized(this@DeviceTaskCoordinator) {
                    if (token == generation) contextHistory = store.messages(id)
                        .filter { it.role in setOf("user", "assistant") && it.content.isNotBlank() }
                        .takeLast(24).map { DeviceChatMessage(it.id, it.role, it.content.take(8000)) }
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
        contextHistory = ManualControlController.state.value.chat.messages
            .filter { it.role in setOf("user", "assistant") }
        val chat = ManualControlController.state.value.chat
        ManualControlController.clearSelection()
        ManualControlController.updateChat(chat.copy(running = false, status = "已暂停；发送新任务后继续"), null)
    }

    @Synchronized
    fun clearContext() {
        pause()
        contextHistory = emptyList()
        ManualControlController.setApproval(null)
        ManualControlController.updateChat(DeviceChatState(status = "上下文已清空，可开始新任务"), null)
    }

    @Synchronized
    private fun update(token: Long, transform: (DeviceChatState) -> DeviceChatState) {
        if (token != generation) return
        val state = ManualControlController.state.value
        if (state.activeUntilEpochMillis != taskExpiry || state.sessionId != taskSession) return
        ManualControlController.updateChat(transform(state.chat))
    }

    private fun checkAvailability(context: Context) {
        check(DeviceInteractionAvailability.isSupported() && AppSettings(context).deviceInteractionExperimentalEnabled &&
            AccessibilityConnection.connected.value && OverlayPermission.isGranted(context)) { "设备交互权限不可用。" }
        check(context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "锁屏时不能执行设备任务。" }
    }

    // Conservative Alpha exclusion in addition to the existing manual debugger policy.
    internal fun isAgentPackageAllowed(packageName: String): Boolean =
        com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy.isPackageAllowed(packageName) &&
            listOf("password", "authenticator", "keychain")
                .none { packageName.contains(it, ignoreCase = true) }
}
