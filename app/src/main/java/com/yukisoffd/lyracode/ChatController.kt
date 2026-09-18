package com.yukisoffd.lyracode

import android.net.Uri
import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.ChatUpdate
import com.yukisoffd.lyracode.ai.AgentFileMutation
import com.yukisoffd.lyracode.ai.AgentFileActivity
import com.yukisoffd.lyracode.ai.AgentFileEditResult
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.ModelReachabilityResult
import com.yukisoffd.lyracode.ai.ProviderReachabilityReport
import com.yukisoffd.lyracode.ai.ProviderReachabilityResult
import com.yukisoffd.lyracode.ai.RUNTIME_CONTEXT_ROLE
import com.yukisoffd.lyracode.ai.ToolApprovalDecision
import com.yukisoffd.lyracode.ai.ToolApprovalRequest
import com.yukisoffd.lyracode.ai.TodoItem
import com.yukisoffd.lyracode.ai.UserQuestionAnswer
import com.yukisoffd.lyracode.ai.UserQuestionRequest
import com.yukisoffd.lyracode.ai.toRecord
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ChatProject
import com.yukisoffd.lyracode.data.Conversation
import android.content.Context
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.workspace.UploadedFile
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import com.yukisoffd.lyracode.workspace.WorkspaceFileReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

data class PendingToolApproval(
    val id: Long,
    val request: ToolApprovalRequest,
)

data class PendingUserQuestion(
    val id: Long,
    val request: UserQuestionRequest,
)

data class EditorFileMutation(
    val id: Long,
    val path: String,
    val content: String,
    val beforeContent: String?,
    val committed: Boolean,
)

data class EditorFileActivity(
    val id: Long,
    val path: String,
    val operation: String,
    val content: String?,
)

data class ContextWindowUsage(
    val estimatedTokens: Long = 0L,
    val contextMessageCount: Int = 0,
    val turnsSinceCompression: Int = 0,
    val hasCompressedHistory: Boolean = false,
    val updating: Boolean = false,
)

class ChatController(
    private val appContext: Context,
    private val settings: AppSettings,
    private val conversationStore: ConversationStore,
    private val uploadedFileManager: UploadedFileManager,
    private val workspaceManager: WorkspaceManager,
    private val agent: OpenAiAgent,
) {
    private enum class ConversationOperation {
        GENERATING,
        COMPRESSING,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<Long, Job>()
    private val conversationOperations = mutableStateMapOf<Long, ConversationOperation>()
    private var editorContextPath = ""
    private var editorMutationId = 0L
    private var editorActivityId = 0L

    val conversations = mutableStateListOf<Conversation>()
    val archivedConversations = mutableStateListOf<Conversation>()
    val projects = mutableStateListOf<ChatProject>()
    val archivedProjects = mutableStateListOf<ChatProject>()
    private val _messages = mutableStateOf<List<ChatRecord>>(emptyList())
    val messages: State<List<ChatRecord>> = _messages
    val profiles = mutableStateListOf<ApiProfile>()
    val activeConversationId = mutableStateOf(0L)
    val activeProfileId = mutableStateOf("")
    val activeModel = mutableStateOf("")
    val status = mutableStateOf("")
    val uploadingStatus = mutableStateOf("")
    val pendingUploads = mutableStateListOf<UploadedFile>()
    val pendingToolApproval = mutableStateOf<PendingToolApproval?>(null)
    val pendingUserQuestion = mutableStateOf<PendingUserQuestion?>(null)
    val editorFileMutations = mutableStateListOf<EditorFileMutation>()
    val editorFileActivity = mutableStateOf<EditorFileActivity?>(null)
    val editorFileFollowRequests = mutableStateListOf<EditorFileActivity>()
    val editorFileChangeRevision = mutableIntStateOf(0)
    val todoItems = mutableStateListOf<TodoItem>()
    val settingsRevision = mutableIntStateOf(0)
    val contextWindowUsage = mutableStateOf(ContextWindowUsage())
    val messagesLoading = mutableStateOf(false)
    private var messageLoadJob: Job? = null
    private val messageLoadMutex = Mutex()
    private var usageLoadJob: Job? = null
    private val usageLoadMutex = Mutex()
    private val updatesDuringLoad = mutableMapOf<Long, ChatUpdate>()
    private var lastMessageReloadAt = 0L
    private var approvalId = 0L
    private val approvalWaiters = mutableMapOf<Long, CompletableDeferred<ToolApprovalDecision>>()
    private var userQuestionId = 0L
    private val userQuestionWaiters = mutableMapOf<Long, CompletableDeferred<UserQuestionAnswer>>()
    private var userQuestionTimeoutJob: Job? = null
    private val editorMutationWaiters = mutableMapOf<Long, CompletableDeferred<AgentFileEditResult>>()
    private val autoApprovedConversations = mutableSetOf<Long>()
    private var transientWorkspaceUri = ""
    private var transientProjectId = 0L
    private var transientAutoApprovalEnabled = false
    private var transientAutoCompressionMode = ConversationStore.AUTO_COMPRESSION_OFF
    private var transientAutoCompressionTurnThreshold = ConversationStore.DEFAULT_AUTO_COMPRESSION_TURNS
    private var transientAutoCompressionTokenThreshold = ConversationStore.DEFAULT_AUTO_COMPRESSION_TOKENS
    private val todoByConversation = mutableMapOf<Long, MutableList<TodoItem>>()

    init {
        agent.approvalHandler = ::requestToolApproval
        agent.userQuestionHandler = ::requestUserQuestion
        agent.todoSetHandler = ::setTodos
        agent.todoUpdateHandler = ::updateTodo
        agent.configChangedHandler = ::handleConfigChanged
        agent.fileEditHandler = ::handleAgentFileEdit
        agent.fileMutationHandler = ::handleAgentFileMutation
        agent.fileActivityHandler = ::handleAgentFileActivity
        reloadProfiles()
        markAbandonedRunsInterrupted()
        reloadConversations()
        showTransientNewConversation()
    }

    fun close() {
        scope.cancel()
    }

    fun usageStore(): ConversationStore = conversationStore
    fun inputDraftKey(): String = if (activeProjectId() > 0L) {
        "project:${activeProjectId()}:${activeConversationId.value}"
    } else {
        "normal:${activeConversationId.value}"
    }

    fun loadInputDraft(): String = settings.chatInputDraft(inputDraftKey())

    fun saveInputDraft(text: String) {
        settings.setChatInputDraft(inputDraftKey(), text)
    }

    fun clearInputDraft() {
        settings.setChatInputDraft(inputDraftKey(), "")
    }

    fun reloadProfiles() {
        profiles.clear()
        profiles.addAll(settings.profiles())
        val selected = settings.selectedProfile()
        activeProfileId.value = selected.id
        activeModel.value = selected.selectedModel
    }

    fun saveProfiles(updated: List<ApiProfile>, selectedId: String = activeProfileId.value) {
        settings.saveProfiles(updated, selectedId)
        reloadProfiles()
    }

    private suspend fun handleConfigChanged() {
        withContext(Dispatchers.Main) {
            settingsRevision.intValue++
            reloadProfiles()
            reloadConversations()
        }
    }

    fun selectProfile(profileId: String) {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        settings.selectedApiProfileId = profile.id
        activeProfileId.value = profile.id
        activeModel.value = profile.selectedModel
        activeConversationId.value.takeIf { it > 0 }?.let {
            conversationStore.setConversationMeta(it, profileId = profile.id, model = profile.selectedModel)
            reloadConversations()
        }
    }

    fun selectModel(model: String) {
        activeModel.value = model
        val updated = profiles.map {
            if (it.id == activeProfileId.value) it.copy(
                selectedModel = model,
                savedModels = (it.savedModels + model).filter { item -> item.isNotBlank() }.distinct(),
                enabledModels = (it.enabledModels + model).filter { item -> item.isNotBlank() }.distinct(),
            ) else it
        }
        saveProfiles(updated, activeProfileId.value)
        activeConversationId.value.takeIf { it > 0 }?.let {
            conversationStore.setConversationMeta(it, profileId = activeProfileId.value, model = model)
            reloadConversations()
        }
    }

    fun selectSystemPrompt(promptId: String) {
        settings.selectedSystemPromptId = promptId
        settingsRevision.intValue++
    }

    fun selectReasoningDepth(depth: String) {
        settings.reasoningDepth = depth
        settingsRevision.intValue++
    }

    fun newConversation() {
        showTransientNewConversation()
    }

    private fun createPersistedConversation(): Long {
        val profile = currentProfile()
        val id = conversationStore.createConversation(
            profileId = profile.id,
            model = activeModel.value.ifBlank { profile.selectedModel },
            title = appContext.getString(R.string.title_new_chat),
            workspaceUri = transientWorkspaceUri,
            projectId = transientProjectId,
        )
        conversationStore.setAutoCompression(
            id,
            transientAutoCompressionMode,
            transientAutoCompressionTurnThreshold,
            transientAutoCompressionTokenThreshold,
        )
        todoByConversation[id] = mutableListOf()
        if (transientAutoApprovalEnabled) autoApprovedConversations += id
        reloadConversations()
        selectConversation(id)
        return id
    }

    private fun showTransientNewConversation(projectId: Long = 0L) {
        val project = projectId.takeIf { it > 0L }?.let(conversationStore::project)
        messageLoadJob?.cancel()
        messagesLoading.value = false
        usageLoadJob?.cancel()
        updatesDuringLoad.clear()
        activeConversationId.value = 0L
        _messages.value = emptyList()
        todoItems.clear()
        pendingUploads.clear()
        uploadingStatus.value = ""
        status.value = ""
        transientProjectId = project?.id ?: 0L
        transientWorkspaceUri = project?.workspaceUri.orEmpty()
        transientAutoApprovalEnabled = false
        transientAutoCompressionMode = ConversationStore.AUTO_COMPRESSION_OFF
        transientAutoCompressionTurnThreshold = ConversationStore.DEFAULT_AUTO_COMPRESSION_TURNS
        transientAutoCompressionTokenThreshold = ConversationStore.DEFAULT_AUTO_COMPRESSION_TOKENS
        contextWindowUsage.value = ContextWindowUsage()
        workspaceManager.setActiveWorkspaceUri(transientWorkspaceUri)
        settingsRevision.intValue++
    }

    fun requestNewConversation(): Boolean {
        if (activeConversationId.value <= 0L || isCurrentConversationBlank()) {
            return false
        }
        showTransientNewConversation(activeProjectId())
        return true
    }

    fun startProjectConversation(projectId: Long): Boolean {
        val project = conversationStore.project(projectId)?.takeIf { it.archivedAt <= 0L } ?: return false
        if (activeConversationId.value <= 0L && transientProjectId == project.id) return false
        showTransientNewConversation(project.id)
        return true
    }

    fun createProject(uri: Uri): ChatProject? {
        val workspaceUri = workspaceManager.persistWorkspace(uri)
        val projectName = workspaceManager.displayName()
            .takeUnless { it == "未选择工作目录" }
            .orEmpty()
            .ifBlank { appContext.getString(R.string.default_project_name) }
        val projectId = conversationStore.createProject(projectName, workspaceUri)
        if (projectId <= 0L) return null
        reloadConversations()
        showTransientNewConversation(projectId)
        return conversationStore.project(projectId)
    }

    fun selectConversation(id: Long) {
        _messages.value = emptyList()
        updatesDuringLoad.clear()
        contextWindowUsage.value = ContextWindowUsage()
        activeConversationId.value = id
        val conversation = conversationStore.conversation(id)
        if (conversation != null) {
            transientProjectId = conversation.projectId
            transientWorkspaceUri = conversation.workspaceUri
            activeProfileId.value = conversation.profileId.ifBlank { activeProfileId.value }
            activeModel.value = conversation.model.ifBlank { activeModel.value }
            workspaceManager.setActiveWorkspaceUri(conversation.workspaceUri)
        }
        reloadMessages()
        reloadTodos()
        refreshContextWindowUsage()
    }

    fun deleteConversation(id: Long) {
        jobs.remove(id)?.cancel()
        conversationOperations.remove(id)
        autoApprovedConversations.remove(id)
        todoByConversation.remove(id)
        conversationStore.deleteConversation(id)
        reloadConversations()
        val next = conversations.firstOrNull()?.id
        if (next == null) {
            showTransientNewConversation()
        } else {
            selectConversation(next)
        }
    }

    fun renameConversation(id: Long, title: String) {
        conversationStore.setConversationMeta(id, title = title)
        reloadConversations()
    }

    fun createConversationBranch(messageId: Long) {
        val sourceId = activeConversationId.value.takeIf { it > 0L } ?: return
        if (jobs[sourceId]?.isActive == true) return
        val source = conversationStore.conversation(sourceId) ?: return
        val title = appContext.getString(R.string.conversation_branch_title, source.title)
        val branchId = conversationStore.createConversationBranch(sourceId, messageId, title)
        if (branchId <= 0L) return

        todoByConversation[branchId] = todoByConversation[sourceId]
            .orEmpty()
            .map { it.copy() }
            .toMutableList()
        if (sourceId in autoApprovedConversations) autoApprovedConversations += branchId
        reloadConversations()
        selectConversation(branchId)
        status.value = appContext.getString(R.string.status_branch_created)
    }

    fun persistWorkspaceForActiveSession(uri: Uri): String {
        val workspaceUri = workspaceManager.persistWorkspace(uri)
        val conversationId = activeConversationId.value
        val projectId = activeProjectId()
        if (conversationId > 0L) {
            conversationStore.setConversationMeta(conversationId, workspaceUri = workspaceUri)
        } else {
            transientWorkspaceUri = workspaceUri
        }
        if (projectId > 0L) conversationStore.updateProjectWorkspace(projectId, workspaceUri)
        reloadConversations()
        settingsRevision.intValue++
        return workspaceManager.displayName()
    }

    fun activeProjectId(): Long {
        val conversationProjectId = activeConversationId.value
            .takeIf { it > 0L }
            ?.let(conversationStore::conversation)
            ?.projectId
            ?: 0L
        return conversationProjectId.takeIf { it > 0L } ?: transientProjectId
    }

    fun renameProject(id: Long, name: String) {
        conversationStore.renameProject(id, name)
        reloadConversations()
    }

    fun setProjectPinned(id: Long, pinned: Boolean) {
        conversationStore.setProjectPinned(id, pinned)
        reloadConversations()
    }

    fun archiveProject(id: Long) {
        conversationStore.setProjectArchived(id, true)
        if (activeProjectId() == id) showTransientNewConversation()
        reloadConversations()
    }

    fun restoreArchivedProject(id: Long) {
        conversationStore.setProjectArchived(id, false)
        reloadConversations()
    }

    fun deleteProject(id: Long) {
        val conversationIds = conversationStore.conversationsForProject(id, archived = null).map { it.id }
        conversationIds.forEach { conversationId ->
            jobs.remove(conversationId)?.cancel()
            autoApprovedConversations.remove(conversationId)
            todoByConversation.remove(conversationId)
        }
        val wasActive = activeProjectId() == id
        conversationStore.deleteProject(id)
        if (wasActive) showTransientNewConversation()
        reloadConversations()
    }

    fun workspaceDisplayName(): String = workspaceManager.displayName()

    fun workspaceDisplayPath(): String? = workspaceManager.displayPath()

    fun setEditorContextPath(path: String?) {
        val next = path.orEmpty()
        if (next.isBlank()) {
            editorMutationWaiters.values.forEach { waiter ->
                waiter.complete(AgentFileEditResult.NotHandled)
            }
            editorMutationWaiters.clear()
            editorFileMutations.clear()
            editorFileActivity.value = null
            editorFileFollowRequests.clear()
        }
        editorContextPath = next
    }

    fun consumeEditorFileMutation(
        id: Long,
        result: AgentFileEditResult? = null,
    ) {
        editorFileMutations.removeAll { it.id == id }
        editorMutationWaiters.remove(id)?.let { waiter ->
            waiter.complete(result ?: AgentFileEditResult.NotHandled)
        }
    }

    fun consumeEditorFileFollowRequest(id: Long) {
        editorFileFollowRequests.removeAll { it.id == id }
    }

    private suspend fun handleAgentFileEdit(mutation: AgentFileMutation): AgentFileEditResult {
        val pending = withContext(Dispatchers.Main) {
            if (editorContextPath.isBlank()) return@withContext null
            val mutationPath = resolveMutationPath(mutation) ?: return@withContext null
            val id = ++editorMutationId
            val waiter = CompletableDeferred<AgentFileEditResult>()
            editorMutationWaiters[id] = waiter
            editorFileMutations += EditorFileMutation(
                id = id,
                path = mutationPath.absolutePath,
                content = mutation.content,
                beforeContent = mutation.beforeContent,
                committed = false,
            )
            id to waiter
        } ?: return AgentFileEditResult.NotHandled

        val result = withTimeoutOrNull(30_000L) { pending.second.await() }
        if (result != null) return result
        return withContext(Dispatchers.Main) {
            editorFileMutations.removeAll { it.id == pending.first }
            editorMutationWaiters.remove(pending.first)
            AgentFileEditResult.failed("等待文件编辑器应用 AI 修改超时，已取消磁盘写入。")
        }
    }

    private suspend fun handleAgentFileMutation(mutation: AgentFileMutation) = withContext(Dispatchers.Main) {
        editorFileChangeRevision.intValue++
        if (editorContextPath.isBlank()) return@withContext
        val mutationPath = resolveMutationPath(mutation) ?: return@withContext
        editorFileMutations += EditorFileMutation(
            id = ++editorMutationId,
            path = mutationPath.absolutePath,
            content = mutation.content,
            beforeContent = mutation.beforeContent,
            committed = mutation.editorApplied,
        )
    }

    private suspend fun handleAgentFileActivity(activity: AgentFileActivity?) = withContext(Dispatchers.Main) {
        if (activity == null) {
            editorFileActivity.value = null
            return@withContext
        }
        if (editorContextPath.isBlank()) return@withContext
        val path = resolveMutationPath(
            AgentFileMutation(activity.path, content = "", globalStorage = activity.globalStorage),
        ) ?: return@withContext
        val editorActivity = EditorFileActivity(
            id = ++editorActivityId,
            path = path.absolutePath,
            operation = activity.operation,
            content = activity.content,
        )
        editorFileActivity.value = editorActivity
        editorFileFollowRequests += editorActivity
    }

    private fun resolveMutationPath(mutation: AgentFileMutation): File? {
        val clean = mutation.path.trim().replace('\\', '/')
        if (mutation.globalStorage) {
            val relative = clean
                .removePrefix("/sdcard/")
                .removePrefix("sdcard/")
                .removePrefix("/storage/emulated/0/")
                .removePrefix("storage/emulated/0/")
                .trimStart('/')
            if (
                relative == "Android/data" ||
                relative.startsWith("Android/data/") ||
                relative == "Android/obb" ||
                relative.startsWith("Android/obb/")
            ) {
                return null
            }
            val root = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull() ?: return null
            val candidate = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
            return candidate.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
        }
        val workspaceRoot = workspaceManager.termuxRootPath() ?: return null
        val root = runCatching { File(workspaceRoot).canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching {
            File(root, clean.removePrefix("./").trimStart('/')).canonicalFile
        }.getOrNull() ?: return null
        return candidate.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    fun hasWorkspace(): Boolean = workspaceManager.rootUri() != null

    fun searchWorkspaceFiles(query: String): List<WorkspaceFileReference> = workspaceManager.searchFiles(query)

    fun isAutoApprovalEnabledForActiveSession(): Boolean {
        val conversationId = activeConversationId.value
        return if (conversationId > 0L) conversationId in autoApprovedConversations else transientAutoApprovalEnabled
    }

    fun setAutoApprovalForActiveSession(enabled: Boolean) {
        val conversationId = activeConversationId.value
        if (conversationId > 0L) {
            if (enabled) autoApprovedConversations += conversationId else autoApprovedConversations -= conversationId
        } else {
            transientAutoApprovalEnabled = enabled
        }
        settingsRevision.intValue++
    }

    fun autoCompressionConfig(): Triple<String, Int, Long> {
        val conversationId = activeConversationId.value
        val conversation = conversationId.takeIf { it > 0L }?.let(conversationStore::conversation)
        return if (conversation != null) {
            Triple(conversation.autoCompressionMode, conversation.autoCompressionTurnThreshold, conversation.autoCompressionTokenThreshold)
        } else {
            Triple(transientAutoCompressionMode, transientAutoCompressionTurnThreshold, transientAutoCompressionTokenThreshold)
        }
    }

    fun setAutoCompressionForActiveSession(mode: String, turnThreshold: Int, tokenThreshold: Long) {
        val normalizedMode = mode.takeIf { it in ConversationStore.AUTO_COMPRESSION_MODES }
            ?: ConversationStore.AUTO_COMPRESSION_OFF
        val normalizedTurns = turnThreshold.coerceIn(1, 10_000)
        val normalizedTokens = tokenThreshold.coerceIn(1_024L, 16_777_216L)
        val conversationId = activeConversationId.value
        if (conversationId > 0L) {
            conversationStore.setAutoCompression(conversationId, normalizedMode, normalizedTurns, normalizedTokens)
            reloadConversations()
        } else {
            transientAutoCompressionMode = normalizedMode
            transientAutoCompressionTurnThreshold = normalizedTurns
            transientAutoCompressionTokenThreshold = normalizedTokens
        }
        settingsRevision.intValue++
    }

    fun refreshContextWindowUsage() {
        usageLoadJob?.cancel()
        val conversationId = activeConversationId.value
        if (conversationId <= 0L) {
            contextWindowUsage.value = ContextWindowUsage()
            return
        }
        val previous = contextWindowUsage.value
        contextWindowUsage.value = previous.copy(updating = true)
        usageLoadJob = scope.launch {
            try {
                // Prioritize visible history and avoid two full-history reads at opening.
                messageLoadJob?.join()
                val usage = withContext(Dispatchers.IO) {
                    usageLoadMutex.withLock {
                        ensureActive()
                        calculateContextWindowUsage(conversationId)
                    }
                }
                if (activeConversationId.value == conversationId) contextWindowUsage.value = usage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (activeConversationId.value == conversationId) {
                    contextWindowUsage.value = previous.copy(updating = false)
                }
            }
        }
    }

    private fun calculateContextWindowUsage(conversationId: Long): ContextWindowUsage {
        val conversation = conversationStore.conversation(conversationId) ?: return ContextWindowUsage()
        val counts = conversationStore.contextMessageCounts(conversationId, conversation.compressedThroughMessageId)
        return ContextWindowUsage(
            estimatedTokens = agent.estimatedConversationContextTokens(conversationId),
            contextMessageCount = counts.first + if (conversation.compressedContext.isNotBlank()) 1 else 0,
            turnsSinceCompression = counts.second,
            hasCompressedHistory = conversation.compressedContext.isNotBlank(),
        )
    }

    fun compressActiveHistory(
        customInstruction: String,
        chunkCount: Int = settings.historyCompressionChunkCount,
        onDone: (Result<Unit>) -> Unit = {},
    ) {
        val conversationId = activeConversationId.value.takeIf { it > 0L } ?: run {
            onDone(Result.failure(IllegalStateException(appContext.getString(R.string.error_no_history_to_compress))))
            return
        }
        if (jobs[conversationId]?.isActive == true) {
            onDone(
                Result.failure(
                    IllegalStateException(appContext.getString(R.string.error_history_compression_conversation_busy)),
                ),
            )
            return
        }
        val throughMessageId = conversationStore.messages(conversationId).lastOrNull()?.id ?: run {
            onDone(Result.failure(IllegalStateException(appContext.getString(R.string.error_no_history_to_compress))))
            return
        }
        val (profile, model) = historyCompressionTarget(conversationId)
        val normalizedChunkCount = chunkCount.coerceIn(
            AppSettings.MIN_HISTORY_COMPRESSION_CHUNKS,
            AppSettings.MAX_HISTORY_COMPRESSION_CHUNKS,
        )
        settings.historyCompressionChunkCount = normalizedChunkCount
        conversationStore.setConversationMeta(conversationId, status = ConversationStore.STATUS_RUNNING)
        reloadConversations()
        launchConversationJob(conversationId, ConversationOperation.COMPRESSING) {
            status.value = appContext.getString(R.string.status_compressing_history)
            val result = runCatching {
                val summary = agent.compressConversationHistory(
                    conversationId,
                    profile,
                    model,
                    customInstruction,
                    normalizedChunkCount,
                )
                conversationStore.setCompressedContext(conversationId, summary, throughMessageId)
            }
            conversationStore.setConversationMeta(conversationId, status = ConversationStore.STATUS_IDLE)
            reloadMessages()
            reloadConversations()
            refreshContextWindowUsage()
            status.value = result.fold(
                onSuccess = { appContext.getString(R.string.status_history_compressed) },
                onFailure = { it.message.orEmpty().ifBlank { appContext.getString(R.string.error_history_compression_failed) } },
            )
            onDone(result)
        }
    }

    private fun historyCompressionTarget(conversationId: Long): Pair<ApiProfile, String> {
        val configuredProfile = settings.historyCompressionProfileOrNull()
        if (configuredProfile != null && settings.historyCompressionModel.isNotBlank()) {
            return configuredProfile to settings.historyCompressionModel
        }
        val conversation = conversationStore.conversation(conversationId)
        val profile = profiles.firstOrNull { it.id == conversation?.profileId } ?: currentProfile()
        return profile to conversation?.model.orEmpty().ifBlank { activeModel.value.ifBlank { profile.selectedModel } }
    }

    private suspend fun maybeAutoCompress(conversationId: Long): Throwable? {
        val conversation = conversationStore.conversation(conversationId) ?: return null
        if (conversation.status == ConversationStore.STATUS_INTERRUPTED) return null
        if (conversation.autoCompressionMode == ConversationStore.AUTO_COMPRESSION_OFF) return null
        val usage = withContext(Dispatchers.IO) { calculateContextWindowUsage(conversationId) }
        val shouldCompress = when (conversation.autoCompressionMode) {
            ConversationStore.AUTO_COMPRESSION_TURNS -> usage.turnsSinceCompression >= conversation.autoCompressionTurnThreshold
            ConversationStore.AUTO_COMPRESSION_TOKENS -> usage.estimatedTokens >= conversation.autoCompressionTokenThreshold
            else -> false
        }
        if (!shouldCompress) return null
        val throughMessageId = conversationStore.messages(conversationId).lastOrNull()?.id ?: return null
        val (profile, model) = historyCompressionTarget(conversationId)
        conversationOperations[conversationId] = ConversationOperation.COMPRESSING
        status.value = appContext.getString(R.string.status_auto_compressing_history)
        return runCatching {
            agent.compressConversationHistory(
                conversationId,
                profile,
                model,
                "",
                settings.historyCompressionChunkCount,
            )
        }
            .onSuccess { summary -> conversationStore.setCompressedContext(conversationId, summary, throughMessageId) }
            .onFailure { error ->
                status.value = error.message.orEmpty().ifBlank { appContext.getString(R.string.error_history_compression_failed) }
            }
            .exceptionOrNull()
    }

    fun setConversationPinned(id: Long, pinned: Boolean) {
        conversationStore.setPinned(id, pinned)
        reloadConversations()
    }

    fun archiveConversation(id: Long) {
        conversationStore.setArchived(id, true)
        reloadConversations()
    }

    fun restoreArchivedConversation(id: Long) {
        conversationStore.setArchived(id, false)
        reloadConversations()
    }

    fun permanentlyDeleteArchivedConversation(id: Long) {
        if (conversationStore.conversation(id)?.archivedAt?.let { it > 0L } != true) return
        jobs.remove(id)?.cancel()
        autoApprovedConversations.remove(id)
        todoByConversation.remove(id)
        conversationStore.deleteConversation(id)
        reloadConversations()
    }

    fun deleteConversations(ids: Collection<Long>) {
        ids.forEach { id ->
            jobs.remove(id)?.cancel()
            autoApprovedConversations.remove(id)
            todoByConversation.remove(id)
            conversationStore.deleteConversation(id)
        }
        reloadConversations()
        if (activeConversationId.value in ids) {
            val next = conversations.firstOrNull()?.id
            if (next == null) {
                showTransientNewConversation()
            } else {
                selectConversation(next)
            }
        }
    }

    fun setConversationsPinned(ids: Collection<Long>, pinned: Boolean) {
        ids.forEach { conversationStore.setPinned(it, pinned) }
        reloadConversations()
    }

    fun send(text: String, forcedSkillIds: List<String> = emptyList(), workspaceFiles: List<WorkspaceFileReference> = emptyList()) {
        val uploads = pendingUploads.toList()
        if (text.isBlank() && uploads.isEmpty() && workspaceFiles.isEmpty()) return
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: createPersistedConversation()
        conversationStore.conversation(conversationId)?.let { workspaceManager.setActiveWorkspaceUri(it.workspaceUri) }
        if (jobs[conversationId]?.isActive == true) return
        val isFirstUserMessage = conversationStore.messages(conversationId).none { it.role == "user" }
        val profile = currentProfile()
        val model = activeModel.value.ifBlank { profile.selectedModel }
        val userInput = composeUserInput(text, uploads, workspaceFiles)
        val titleBeforeSend = activeConversation()?.title
        val provisionalTitle = if (titleBeforeSend == appContext.getString(R.string.default_conversation_title)) {
            fallbackConversationTitle(userInput)
        } else {
            null
        }
        val topicTitleGuard = provisionalTitle ?: titleBeforeSend
        conversationStore.setConversationMeta(
            conversationId,
            title = provisionalTitle,
            status = ConversationStore.STATUS_RUNNING,
            profileId = profile.id,
            model = model,
        )
        conversationStore.addMessage(conversationId, "user", userInput, profileId = profile.id, model = model)
        reloadMessages()
        reloadConversations()
        pendingUploads.clear()
        uploadingStatus.value = ""
        if (isFirstUserMessage) {
            val topicProfile = settings.topicSummaryProfile()
            val topicModel = settings.topicSummaryModel.ifBlank { topicProfile.selectedModel }
            val topicInput = text.trim().ifBlank { fallbackConversationTitle(userInput) }
            scope.launch {
                runCatching { agent.summarizeConversationTopic(topicProfile, topicModel, topicInput) }
                    .onSuccess { title ->
                        val current = conversationStore.conversation(conversationId)
                        if (current != null && current.title == topicTitleGuard) {
                            conversationStore.setConversationMeta(conversationId, title = title)
                            reloadConversations()
                        }
                    }
            }
        }
        launchConversationJob(conversationId, ConversationOperation.GENERATING) {
            status.value = appContext.getString(R.string.status_running)
            agent.chat(conversationId, userInput, profile, model, userMessagePersisted = true, forcedSkillIds = forcedSkillIds) {
                withContext(Dispatchers.Main) {
                    if (activeConversationId.value == conversationId) {
                        applyChatUpdate(it)
                        status.value = it.status
                    }
                }
            }
            val compressionError = maybeAutoCompress(conversationId)
            reloadMessages()
            reloadConversations()
            refreshContextWindowUsage()
            if (compressionError == null) markConversationFinished(conversationId)
        }
    }

    fun stopActive() {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        jobs.remove(conversationId)?.cancel()
        conversationOperations.remove(conversationId)
        conversationStore.setConversationMeta(conversationId, status = ConversationStore.STATUS_INTERRUPTED)
        pendingToolApproval.value?.takeIf { it.request.conversationId == conversationId }?.let { pending ->
            approvalWaiters.remove(pending.id)?.complete(
                ToolApprovalDecision(approved = false, feedback = appContext.getString(R.string.label_user_interrupted)),
            )
            pendingToolApproval.value = null
        }
        pendingUserQuestion.value?.takeIf { it.request.conversationId == conversationId }?.let { pending ->
            userQuestionWaiters.remove(pending.id)?.complete(
                UserQuestionAnswer(status = UserQuestionAnswer.STATUS_INTERRUPTED),
            )
            pendingUserQuestion.value = null
            userQuestionTimeoutJob?.cancel()
            userQuestionTimeoutJob = null
        }
        reloadConversations()
        reloadMessages()
        status.value = appContext.getString(R.string.status_interrupted)
    }

    fun continueActive() {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        if (jobs[conversationId]?.isActive == true) return
        conversationStore.conversation(conversationId)?.let { workspaceManager.setActiveWorkspaceUri(it.workspaceUri) }
        val profile = currentProfile()
        val model = activeModel.value.ifBlank { profile.selectedModel }
        // Publish the running state before starting the request so Compose
        // removes the interrupted action in the same update that starts the
        // streaming layout. The agent repeats this write defensively on IO.
        conversationStore.setConversationMeta(
            conversationId,
            status = ConversationStore.STATUS_RUNNING,
            profileId = profile.id,
            model = model,
        )
        reloadConversations()
        launchConversationJob(conversationId, ConversationOperation.GENERATING) {
            status.value = appContext.getString(R.string.status_continue)
            agent.continueConversation(conversationId, profile, model) {
                withContext(Dispatchers.Main) {
                    if (activeConversationId.value == conversationId) {
                        applyChatUpdate(it)
                        status.value = it.status
                    }
                }
            }
            val compressionError = maybeAutoCompress(conversationId)
            reloadMessages()
            reloadConversations()
            refreshContextWindowUsage()
            if (compressionError == null) markConversationFinished(conversationId)
        }
    }

    fun editAndRegenerateUserMessage(messageId: Long, newContent: String) {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        if (jobs[conversationId]?.isActive == true) return
        val message = conversationStore.message(messageId) ?: return
        if (message.conversationId != conversationId || message.role != "user") return
        conversationStore.conversation(conversationId)?.let { workspaceManager.setActiveWorkspaceUri(it.workspaceUri) }
        val content = newContent.trim().ifBlank { message.content }
        conversationStore.updateMessage(messageId, content = content, thinking = "")
        conversationStore.deleteMessagesAfter(conversationId, messageId)
        reloadMessages()
        reloadConversations()
        val profile = currentProfile()
        val model = activeModel.value.ifBlank { profile.selectedModel }
        launchConversationJob(conversationId, ConversationOperation.GENERATING) {
            status.value = appContext.getString(R.string.status_regenerate)
            agent.continueConversation(conversationId, profile, model) {
                withContext(Dispatchers.Main) {
                    if (activeConversationId.value == conversationId) {
                        applyChatUpdate(it)
                        status.value = it.status
                    }
                }
            }
            val compressionError = maybeAutoCompress(conversationId)
            reloadMessages()
            reloadConversations()
            refreshContextWindowUsage()
            if (compressionError == null) markConversationFinished(conversationId)
        }
    }

    private fun markConversationFinished(conversationId: Long) {
        if (conversationStore.conversation(conversationId)?.status == ConversationStore.STATUS_INTERRUPTED) return
        status.value = appContext.getString(R.string.status_done)
        scope.launch {
            delay(2400L)
            if (activeConversationId.value == conversationId && jobs[conversationId]?.isActive != true && status.value == appContext.getString(R.string.status_done)) {
                status.value = ""
            }
        }
    }

    fun attachUploadedFile(uri: Uri) {
        scope.launch {
            uploadingStatus.value = appContext.getString(R.string.status_reading_upload)
            val result = withContext(Dispatchers.IO) { uploadedFileManager.readText(uri) }
            result.fold(
                onSuccess = { file ->
                    pendingUploads += file
                    uploadingStatus.value = appContext.getString(R.string.status_uploaded, file.name)
                },
                onFailure = { uploadingStatus.value = it.message.orEmpty() },
            )
        }
    }

    fun attachCapturedImage(bitmap: Bitmap) {
        scope.launch {
            uploadingStatus.value = appContext.getString(R.string.status_processing_photo)
            val result = withContext(Dispatchers.IO) { uploadedFileManager.saveCapturedImage(bitmap) }
            result.fold(
                onSuccess = { file ->
                    pendingUploads += file
                    uploadingStatus.value = appContext.getString(R.string.status_uploaded, file.name)
                },
                onFailure = { uploadingStatus.value = it.message.orEmpty() },
            )
        }
    }

    fun removePendingUpload(index: Int) {
        pendingUploads.getOrNull(index) ?: return
        pendingUploads.removeAt(index)
        uploadingStatus.value = if (pendingUploads.isEmpty()) "" else appContext.getString(R.string.label_pending_attachments, pendingUploads.size)
    }

    private fun composeUserInput(text: String, uploads: List<UploadedFile>, workspaceFiles: List<WorkspaceFileReference> = emptyList()): String {
        return buildString {
            val cleanText = text.trim()
            if (cleanText.isNotBlank()) append(cleanText)
            workspaceFiles.distinctBy { it.relativePath }.take(24).takeIf { it.isNotEmpty() }?.let { files ->
                if (isNotBlank()) append("\n\n")
                append(workspaceReferenceMarker(files))
            }
            editorContextPath.takeIf { it.isNotBlank() }?.let { path ->
                if (isNotBlank()) append("\n\n")
                append(editorContextMarker(path))
            }
            uploads.forEach { file ->
                if (isNotBlank()) append("\n\n")
                append(uploadedAttachmentMarker(file))
            }
        }
    }


    private fun workspaceReferenceMarker(files: List<WorkspaceFileReference>): String {
        val payload = JSONObject()
            .put("instruction", "Prioritize these workspace files explicitly selected by the user. Every path is workspace-relative.")
            .put("files", org.json.JSONArray().also { array ->
                files.forEach { file ->
                    array.put(JSONObject().put("name", file.name).put("path", file.relativePath).put("size", file.size))
                }
            })
        return "$WORKSPACE_REFERENCE_MARKER_START$payload$WORKSPACE_REFERENCE_MARKER_END"
    }

    private fun editorContextMarker(path: String): String {
        val payload = JSONObject()
            .put("path", path)
            .put(
                "instruction",
                "This is the Android shared-storage file currently open in the user's editor. Read it with global_read_file/global_read_file_lines as needed; prefer global_edit_file for precise changes and preserve the user-approval flow. Workspace tools may be used for related files when a workspace is selected.",
            )
        return "$EDITOR_CONTEXT_MARKER_START$payload$EDITOR_CONTEXT_MARKER_END"
    }

    private fun uploadedAttachmentMarker(file: UploadedFile): String {
        val payload = JSONObject()
            .put("name", file.name)
            .put("kind", file.mediaKind)
            .put("mime_type", file.mimeType)
            .put("size", file.size)
            .put("uri", file.uri)
        if (file.mediaKind == "text") {
            payload.put("text", file.content)
        }
        return "$ATTACHMENT_MARKER_START$payload$ATTACHMENT_MARKER_END"
    }

    private companion object {
        const val USER_QUESTION_IDLE_TIMEOUT_MS = 10L * 60L * 1000L
        const val ATTACHMENT_MARKER_START = "<lyra_attachment_v1>"
        const val ATTACHMENT_MARKER_END = "</lyra_attachment_v1>"
        const val WORKSPACE_REFERENCE_MARKER_START = "<lyra_workspace_refs_v1>"
        const val WORKSPACE_REFERENCE_MARKER_END = "</lyra_workspace_refs_v1>"
        const val EDITOR_CONTEXT_MARKER_START = "<lyra_editor_context_v1>"
        const val EDITOR_CONTEXT_MARKER_END = "</lyra_editor_context_v1>"
    }
    private fun fallbackConversationTitle(userInput: String): String {
        val markerRegex = Regex("<lyra_attachment_v1>([\\s\\S]*?)</lyra_attachment_v1>")
        val workspaceRegex = Regex("<lyra_workspace_refs_v1>([\\s\\S]*?)</lyra_workspace_refs_v1>")
        val editorRegex = Regex("<lyra_editor_context_v1>([\\s\\S]*?)</lyra_editor_context_v1>")
        val workspaceTitle = workspaceRegex.find(userInput)?.let { match ->
            runCatching { JSONObject(match.groupValues[1]).optJSONArray("files")?.optJSONObject(0)?.optString("name") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { "@$it" }
        }
        val attachmentTitle = markerRegex.find(userInput)?.let { match ->
            runCatching { JSONObject(match.groupValues[1]).optString("name") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { "上传附件：$it" }
        }
        return editorRegex.replace(workspaceRegex.replace(markerRegex.replace(userInput, ""), ""), "")
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(36)
            .ifBlank { workspaceTitle ?: attachmentTitle ?: appContext.getString(R.string.default_conversation_title) }
    }

    fun fetchModels(onDone: (Result<List<String>>) -> Unit) {
        val profile = currentProfile()
        scope.launch {
            status.value = appContext.getString(R.string.status_fetching_models)
            val result = withContext(Dispatchers.IO) { agent.fetchModels(profile) }
            result.onSuccess { models ->
                val updated = profiles.map {
                    if (it.id == profile.id) it.copy(
                        savedModels = (it.savedModels + models).distinct(),
                    ) else it
                }
                saveProfiles(updated, profile.id)
            }
            status.value = ""
            onDone(result)
        }
    }


    fun checkReachabilityForProfile(profile: ApiProfile, models: List<String>, onDone: (Result<ProviderReachabilityReport>) -> Unit) {
        scope.launch {
            status.value = appContext.getString(R.string.status_checking_reachability)
            val result = withContext(Dispatchers.IO) {
                runCatching { agent.checkReachability(profile, models) }
            }
            status.value = ""
            onDone(result)
        }
    }

    fun checkReachabilityForProfileIncremental(
        profile: ApiProfile,
        models: List<String>,
        onProviderResult: (ProviderReachabilityResult) -> Unit,
        onModelChecking: (String) -> Unit = {},
        onModelResult: (ModelReachabilityResult) -> Unit,
        onDone: (Result<Unit>) -> Unit,
    ) {
        scope.launch {
            status.value = appContext.getString(R.string.status_checking_reachability)
            val result = runCatching {
                val targets = models
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(profile.selectedModel) }
                    .distinct()
                val provider = withContext(Dispatchers.IO) { agent.checkProviderReachability(profile) }
                onProviderResult(provider)
                targets.forEach { model ->
                    onModelChecking(model)
                    val modelResult = withContext(Dispatchers.IO) { agent.checkModelReachability(profile, model) }
                    onModelResult(modelResult)
                }
            }
            status.value = ""
            onDone(result)
        }
    }

    fun fetchModelsForProfile(profile: ApiProfile, onDone: (Result<List<String>>) -> Unit) {
        scope.launch {
            status.value = appContext.getString(R.string.status_fetching_models)
            val result = withContext(Dispatchers.IO) { agent.fetchModels(profile) }
            status.value = ""
            onDone(result)
        }
    }

    fun reloadConversations() {
        conversations.clear()
        conversations.addAll(conversationStore.conversations(ConversationStore.MODE_NORMAL))
        archivedConversations.clear()
        archivedConversations.addAll(conversationStore.conversations(ConversationStore.MODE_NORMAL, archived = true))
        projects.clear()
        projects.addAll(conversationStore.projects())
        archivedProjects.clear()
        archivedProjects.addAll(conversationStore.projects(archived = true))
        val active = activeConversationId.value
        if (active > 0 && conversations.none { it.id == active }) {
            val next = conversations.firstOrNull()?.id
            if (next == null) {
                messageLoadJob?.cancel()
                usageLoadJob?.cancel()
                messagesLoading.value = false
                updatesDuringLoad.clear()
                activeConversationId.value = 0L
                _messages.value = emptyList()
                workspaceManager.setActiveWorkspaceUri("")
            } else {
                selectConversation(next)
            }
        }
    }

    fun reloadMessages() {
        messageLoadJob?.cancel()
        val id = activeConversationId.value
        lastMessageReloadAt = System.currentTimeMillis()
        if (id <= 0L) {
            _messages.value = emptyList()
            messagesLoading.value = false
            return
        }
        messagesLoading.value = true
        messageLoadJob = scope.launch {
            try {
                val records = withContext(Dispatchers.IO) {
                    // SQLite/JSON work is blocking: cancellation alone does not stop it.
                    // Serialize reads so rapid switching cannot retain several full histories.
                    messageLoadMutex.withLock {
                        ensureActive()
                        val stored = conversationStore.messages(id)
                        ensureActive()
                        enrichToolRecords(
                            stored.asSequence()
                                .filterNot { it.role == RUNTIME_CONTEXT_ROLE }
                                .map { it.toRecord() }
                                .toList(),
                        )
                    }
                }
                if (activeConversationId.value == id) {
                    _messages.value = records.map { record ->
                        updatesDuringLoad[record.id]?.let { record.withUpdate(it) } ?: record
                    }
                    updatesDuringLoad.clear()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeConversationId.value == id) status.value = error.message.orEmpty()
            } finally {
                if (isActive && activeConversationId.value == id) messagesLoading.value = false
            }
        }
    }

    private fun ChatRecord.withUpdate(update: ChatUpdate): ChatRecord = copy(
        content = update.content,
        thinking = update.thinking,
        tokensPerSecond = update.tokensPerSecond.takeIf { it > 0.0 } ?: tokensPerSecond,
        deepSeekCacheHitRate = update.deepSeekCacheHitRate ?: deepSeekCacheHitRate,
    )

    private fun enrichToolRecords(records: List<ChatRecord>): List<ChatRecord> {
        val calls = mutableMapOf<String, Pair<String, String>>()
        return records.map { record ->
            if (record.role == "assistant") {
                runCatching { JSONObject(record.rawJson.orEmpty()) }.getOrNull()
                    ?.optJSONArray("tool_calls")
                    ?.let { array ->
                        for (index in 0 until array.length()) {
                            val call = array.optJSONObject(index) ?: continue
                            val id = call.optString("id")
                            val function = call.optJSONObject("function") ?: continue
                            if (id.isNotBlank()) {
                                calls[id] = function.optString("name") to prettyToolJson(function.optString("arguments"))
                            }
                        }
                    }
                record
            } else if (record.role == "tool") {
                val details = calls[record.toolCallId]
                record.copy(toolName = details?.first.orEmpty(), toolInput = details?.second.orEmpty())
            } else {
                record
            }
        }
    }

    private fun prettyToolJson(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "{}"
        return runCatching { JSONObject(trimmed).toString(2) }
            .recoverCatching { org.json.JSONArray(trimmed).toString(2) }
            .getOrDefault(trimmed)
    }
    fun reloadTodos() {
        todoItems.clear()
        todoItems.addAll(todoByConversation[activeConversationId.value].orEmpty())
    }

    private fun launchConversationJob(
        conversationId: Long,
        operation: ConversationOperation,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        lateinit var launchedJob: Job
        launchedJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                // A cancelled job may finish after a new operation has already
                // started for this conversation. Only clear our own state.
                if (jobs[conversationId] === launchedJob) {
                    jobs.remove(conversationId)
                    conversationOperations.remove(conversationId)
                }
            }
        }
        jobs[conversationId] = launchedJob
        conversationOperations[conversationId] = operation
        launchedJob.start()
        return launchedJob
    }

    fun isActiveConversationRunning(): Boolean = conversationOperations.containsKey(activeConversationId.value)

    fun isActiveConversationGenerating(): Boolean =
        conversationOperations[activeConversationId.value] == ConversationOperation.GENERATING

    fun isActiveHistoryCompressionRunning(): Boolean =
        conversationOperations[activeConversationId.value] == ConversationOperation.COMPRESSING

    fun activeConversation(): Conversation? = conversationStore.conversation(activeConversationId.value)

    private fun isCurrentConversationBlank(): Boolean {
        val id = activeConversationId.value.takeIf { it > 0 } ?: return true
        return !conversationHasUserMessage(id)
    }

    private fun conversationHasUserMessage(id: Long): Boolean {
        return conversationStore.messages(id).any { it.role == "user" }
    }

    fun answerToolApproval(approved: Boolean, rememberForConversation: Boolean, feedback: String) {
        val pending = pendingToolApproval.value ?: return
        approvalWaiters.remove(pending.id)?.complete(
            ToolApprovalDecision(
                approved = approved,
                rememberForConversation = rememberForConversation,
                feedback = feedback.trim(),
            ),
        )
        if (approved && rememberForConversation && pending.request.toolName != "send_email") {
            autoApprovedConversations += pending.request.conversationId
        }
        pendingToolApproval.value = null
        status.value = if (approved) appContext.getString(R.string.status_approved_tool) else appContext.getString(R.string.status_rejected_tool)
    }

    fun markUserQuestionInteraction(id: Long) {
        if (pendingUserQuestion.value?.id == id) resetUserQuestionTimeout(id)
    }

    fun answerUserQuestion(selectedOptions: List<String>, freeText: String) {
        val pending = pendingUserQuestion.value ?: return
        val selected = pending.request.options.filter { it in selectedOptions }.distinct()
        val detail = freeText.trim()
        if (selected.isEmpty() && detail.isBlank()) return
        userQuestionWaiters.remove(pending.id)?.complete(
            UserQuestionAnswer(
                status = UserQuestionAnswer.STATUS_ANSWERED,
                selectedOptions = selected,
                freeText = detail,
            ),
        )
        pendingUserQuestion.value = null
        userQuestionTimeoutJob?.cancel()
        userQuestionTimeoutJob = null
        status.value = appContext.getString(R.string.status_user_answer_submitted)
    }

    private fun currentProfile(): ApiProfile {
        return profiles.firstOrNull { it.id == activeProfileId.value } ?: profiles.first()
    }

    private fun reloadMessagesThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastMessageReloadAt < 180L) return
        reloadMessages()
    }

    private fun applyChatUpdate(update: ChatUpdate) {
        if (update.messageId <= 0L) {
            reloadMessagesThrottled()
            return
        }
        if (messageLoadJob?.isActive == true) updatesDuringLoad[update.messageId] = update
        val current = _messages.value
        val index = current.indexOfFirst { it.id == update.messageId }
        if (index < 0) {
            // Do not restart a slow history read on every streaming token.
            if (messageLoadJob?.isActive != true) reloadMessages()
            return
        }
        val updated = current[index].copy(
            content = update.content,
            thinking = update.thinking,
            tokensPerSecond = update.tokensPerSecond.takeIf { value -> value > 0.0 } ?: current[index].tokensPerSecond,
            deepSeekCacheHitRate = update.deepSeekCacheHitRate ?: current[index].deepSeekCacheHitRate,
        )
        _messages.value = current.toMutableList().also { it[index] = updated }
        lastMessageReloadAt = System.currentTimeMillis()
        if (update.status.startsWith(appContext.getString(R.string.ui_tool_complete))) {
            reloadConversations()
            reloadMessages()
        }
    }

    private suspend fun requestToolApproval(request: ToolApprovalRequest): ToolApprovalDecision {
        if (request.toolName != "send_email" && request.conversationId in autoApprovedConversations) {
            return ToolApprovalDecision.Approved
        }
        return withContext(Dispatchers.Main) {
            val id = ++approvalId
            val waiter = CompletableDeferred<ToolApprovalDecision>()
            approvalWaiters[id] = waiter
            pendingToolApproval.value = PendingToolApproval(id, request)
            status.value = appContext.getString(R.string.status_waiting_confirm, request.toolName)
            waiter
        }.await()
    }

    private suspend fun requestUserQuestion(request: UserQuestionRequest): UserQuestionAnswer {
        val registration = withContext(Dispatchers.Main) {
            if (pendingUserQuestion.value != null) {
                null
            } else {
                val id = ++userQuestionId
                val waiter = CompletableDeferred<UserQuestionAnswer>()
                userQuestionWaiters[id] = waiter
                pendingUserQuestion.value = PendingUserQuestion(id, request)
                status.value = appContext.getString(R.string.status_waiting_user_answer)
                resetUserQuestionTimeout(id)
                id to waiter
            }
        } ?: return UserQuestionAnswer(status = UserQuestionAnswer.STATUS_UNAVAILABLE)
        return try {
            registration.second.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                userQuestionWaiters.remove(registration.first)
                if (pendingUserQuestion.value?.id == registration.first) {
                    pendingUserQuestion.value = null
                    userQuestionTimeoutJob?.cancel()
                    userQuestionTimeoutJob = null
                }
            }
        }
    }

    private fun resetUserQuestionTimeout(id: Long) {
        userQuestionTimeoutJob?.cancel()
        userQuestionTimeoutJob = scope.launch {
            delay(USER_QUESTION_IDLE_TIMEOUT_MS)
            if (pendingUserQuestion.value?.id != id) return@launch
            userQuestionWaiters.remove(id)?.complete(
                UserQuestionAnswer(status = UserQuestionAnswer.STATUS_TIMED_OUT),
            )
            pendingUserQuestion.value = null
            userQuestionTimeoutJob = null
            status.value = appContext.getString(R.string.status_user_question_timed_out)
        }
    }

    private suspend fun setTodos(conversationId: Long, items: List<TodoItem>): String = withContext(Dispatchers.Main) {
        val normalized = items.ifEmpty { listOf(TodoItem("1", appContext.getString(R.string.todo_default_task), "pending")) }
            .mapIndexed { index, item ->
                item.copy(
                    id = item.id.ifBlank { (index + 1).toString() },
                    status = item.status.ifBlank { "pending" },
                )
            }
            .toMutableList()
        todoByConversation[conversationId] = normalized
        if (activeConversationId.value == conversationId) reloadTodos()
        appContext.getString(R.string.todo_list_set, normalized.size)
    }

    private suspend fun updateTodo(conversationId: Long, id: String, status: String, note: String): String = withContext(Dispatchers.Main) {
        val list = todoByConversation.getOrPut(conversationId) { mutableListOf() }
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(
                status = status.ifBlank { list[index].status },
                note = note.ifBlank { list[index].note },
            )
        } else {
            list += TodoItem(id.ifBlank { (list.size + 1).toString() }, note.ifBlank { appContext.getString(R.string.todo_item_default_name) }, status.ifBlank { appContext.getString(R.string.todo_status_completed) })
        }
        if (activeConversationId.value == conversationId) reloadTodos()
        appContext.getString(R.string.todo_marked_as, id.ifBlank { list.last().id }, status.ifBlank { appContext.getString(R.string.todo_status_completed) })
    }

    private fun markAbandonedRunsInterrupted() {
        conversationStore.conversations(archived = null)
            .filter { it.status == ConversationStore.STATUS_RUNNING }
            .forEach { conversationStore.setConversationMeta(it.id, status = ConversationStore.STATUS_INTERRUPTED) }
    }
}
