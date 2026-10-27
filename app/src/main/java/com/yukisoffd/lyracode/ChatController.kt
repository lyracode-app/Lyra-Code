package com.yukisoffd.lyracode

import android.net.Uri
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
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
import com.yukisoffd.lyracode.ai.SteerSource
import com.yukisoffd.lyracode.ai.ToolApprovalDecision
import com.yukisoffd.lyracode.ai.ToolApprovalRequest
import com.yukisoffd.lyracode.ai.TodoItem
import com.yukisoffd.lyracode.ai.UserQuestionAnswer
import com.yukisoffd.lyracode.ai.UserQuestionRequest
import com.yukisoffd.lyracode.ai.isMediaGenerationModel
import com.yukisoffd.lyracode.ai.toRecord
import com.yukisoffd.lyracode.chat.pending.PendingAttachment
import com.yukisoffd.lyracode.chat.pending.PendingInputAttachmentReaper
import com.yukisoffd.lyracode.chat.pending.PendingInputCapture
import com.yukisoffd.lyracode.chat.pending.PendingInputFileStore
import com.yukisoffd.lyracode.chat.pending.PendingInputItem
import com.yukisoffd.lyracode.chat.pending.PendingInputRestorer
import com.yukisoffd.lyracode.chat.pending.PendingInputSnapshot
import com.yukisoffd.lyracode.chat.pending.PendingInputState
import com.yukisoffd.lyracode.chat.pending.PendingInputStore
import com.yukisoffd.lyracode.chat.pending.PendingSteer
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.withChatGptModels
import com.yukisoffd.lyracode.data.ChatProject
import com.yukisoffd.lyracode.data.Conversation
import com.yukisoffd.lyracode.interaction.session.ConversationIdleOutcome
import com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator
import com.yukisoffd.lyracode.interaction.session.dispatchConversationIdle
import kotlinx.coroutines.flow.StateFlow
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class PendingToolApproval(
    val id: Long,
    val request: ToolApprovalRequest,
)

data class PendingUserQuestion(
    val id: Long,
    val request: UserQuestionRequest,
    val isMinimized: Boolean = false,
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

/** Result of a user-initiated send. The caller clears its draft only for SENT and QUEUED. */
internal enum class SendOutcome { SENT, QUEUED, BLOCKED }

/** Result of a user-initiated steer. The caller clears its draft only for STEERED. */
internal enum class SteerOutcome { STEERED, REFUSED }

/**
 * Why the steer channel can or cannot accept a message right now.
 *
 * [AWAITING_APPROVAL], [AWAITING_QUESTION] and [SUB_AGENT_RUNNING] accept the steer, but the
 * agent can only drain it after the blocking wait resolves, so the UI must not claim
 * immediate delivery. Every other non-[AVAILABLE] value is a refusal.
 */
internal enum class SteerAvailability {
    AVAILABLE,
    FEATURE_DISABLED,
    NO_ACTIVE_GENERATING_RUN,
    COMPRESSING,
    SCOPED_DEVICE,
    MEDIA_GENERATION,
    SUB_AGENT_TARGET,
    AWAITING_APPROVAL,
    AWAITING_QUESTION,
    SUB_AGENT_RUNNING,
}

/**
 * Gate for the transient steer channel.
 *
 * Precedence: feature-off, then the states where no steer can ever be drained (scoped
 * device-control run, compression, media-generation models, sub-agent target), then a run
 * that is not generating, then the waits that merely delay delivery, then available.
 *
 * [generating] must come from the GENERATING operation only. A conversation that is
 * COMPRESSING inside a generating job is still refused: the agent is not at a steer
 * boundary, and the compression owns the provider call.
 *
 * Media-generation models consume prompt text as generation material and have no agent
 * loop, so there is no safe boundary to drain a steer.
 */
internal fun steerAvailability(
    featureEnabled: Boolean,
    generating: Boolean,
    compressing: Boolean = false,
    scopedDeviceRunning: Boolean = false,
    mediaGeneration: Boolean = false,
    subAgentTarget: Boolean = false,
    pendingApprovalForConversation: Boolean = false,
    pendingQuestionForConversation: Boolean = false,
    subAgentRunning: Boolean = false,
): SteerAvailability = when {
    !featureEnabled -> SteerAvailability.FEATURE_DISABLED
    scopedDeviceRunning -> SteerAvailability.SCOPED_DEVICE
    compressing -> SteerAvailability.COMPRESSING
    mediaGeneration -> SteerAvailability.MEDIA_GENERATION
    subAgentTarget -> SteerAvailability.SUB_AGENT_TARGET
    !generating -> SteerAvailability.NO_ACTIVE_GENERATING_RUN
    pendingApprovalForConversation -> SteerAvailability.AWAITING_APPROVAL
    pendingQuestionForConversation -> SteerAvailability.AWAITING_QUESTION
    subAgentRunning -> SteerAvailability.SUB_AGENT_RUNNING
    else -> SteerAvailability.AVAILABLE
}

/** Whether the steer channel accepts text in this state, even when delivery waits. */
internal fun SteerAvailability.acceptsSteer(): Boolean = when (this) {
    SteerAvailability.AVAILABLE,
    SteerAvailability.AWAITING_APPROVAL,
    SteerAvailability.AWAITING_QUESTION,
    SteerAvailability.SUB_AGENT_RUNNING,
    -> true
    SteerAvailability.FEATURE_DISABLED,
    SteerAvailability.NO_ACTIVE_GENERATING_RUN,
    SteerAvailability.COMPRESSING,
    SteerAvailability.SCOPED_DEVICE,
    SteerAvailability.MEDIA_GENERATION,
    SteerAvailability.SUB_AGENT_TARGET,
    -> false
}

/**
 * A pending approval or question only blocks its own conversation; another conversation's
 * global prompt slot must not affect this one.
 */
internal fun isPendingInteractionForConversation(
    pendingConversationId: Long?,
    conversationId: Long,
): Boolean = pendingConversationId != null && pendingConversationId > 0L && pendingConversationId == conversationId

/** Reason-specific disabled explanation for every refusal state. */
internal fun SteerAvailability.unavailableExplanationRes(): Int = when (this) {
    SteerAvailability.FEATURE_DISABLED -> R.string.composer_steer_unavailable_feature_disabled
    SteerAvailability.NO_ACTIVE_GENERATING_RUN -> R.string.composer_steer_unavailable_no_run
    SteerAvailability.COMPRESSING -> R.string.composer_steer_unavailable_compressing
    SteerAvailability.SCOPED_DEVICE -> R.string.composer_steer_unavailable_scoped_device
    SteerAvailability.MEDIA_GENERATION -> R.string.composer_steer_unavailable_media_generation
    SteerAvailability.SUB_AGENT_TARGET -> R.string.composer_steer_unavailable_sub_agent_target
    SteerAvailability.AVAILABLE,
    SteerAvailability.AWAITING_APPROVAL,
    SteerAvailability.AWAITING_QUESTION,
    SteerAvailability.SUB_AGENT_RUNNING,
    -> R.string.composer_steer_unavailable_default
}

/** Note shown while a steer is accepted but delivery waits for a blocking interaction. */
internal fun SteerAvailability.waitNoteRes(): Int? = when (this) {
    SteerAvailability.AWAITING_APPROVAL -> R.string.composer_steer_wait_approval
    SteerAvailability.AWAITING_QUESTION -> R.string.composer_steer_wait_question
    SteerAvailability.SUB_AGENT_RUNNING -> R.string.composer_steer_wait_sub_agent
    else -> null
}

/**
 * True while any owner is running the conversation: a controller job (generating or
 * compressing) or a scoped device-control task. Sends queue while this is true.
 */
internal fun conversationBusy(
    conversationId: Long,
    operationActive: Boolean,
    scopedDeviceRunning: (Long) -> Boolean,
): Boolean = operationActive || scopedDeviceRunning(conversationId)

/**
 * Auto-compression temporarily swaps the generating job's operation marker to COMPRESSING.
 * When compression returns, the same job is generating again, so the marker must be restored:
 * otherwise steering stays blocked with a compression explanation after compression ended.
 * A superseded job must not overwrite a newer owner's marker.
 */
internal fun shouldRestoreGeneratingMarker(
    operationIsCompressing: Boolean,
    owningJobIsCurrent: Boolean,
): Boolean = operationIsCompressing && owningJobIsCurrent

/**
 * Maps the store's atomic steer drain to the agent's text-only source. A drained steer is
 * removed from the channel before it is returned, so each steering text reaches the agent at
 * most once; blank captures are skipped because the agent persists plain user turns.
 */
internal fun steerSourceFrom(drain: (Long) -> List<PendingSteer>): SteerSource = SteerSource { conversationId ->
    drain(conversationId)
        .map { steer -> steer.capture.composedInput }
        .filter { text -> text.isNotBlank() }
}

/** What the post-run queue handler must do with the conversation's queue items. */
internal enum class QueueDrain { DELIVER_NEXT, PARK_ALL }

internal const val pendingInputExcerptMaxLength = 60
private const val LOG_TAG = "ChatController"

/**
 * Normal completion drains exactly one queued item. Stop, cancellation, failure,
 * a failed compression, or the feature being off parks every queued item.
 */
internal fun queueDrainFor(
    outcome: ConversationRunOutcome,
    compressionFailed: Boolean,
    featureEnabled: Boolean,
): QueueDrain = if (featureEnabled && outcome == ConversationRunOutcome.Completed && !compressionFailed) {
    QueueDrain.DELIVER_NEXT
} else {
    QueueDrain.PARK_ALL
}

/** Late steers join the queue in the same mode the queue drain chose. */
internal fun lateSteerQueueState(drain: QueueDrain): PendingInputState = when (drain) {
    QueueDrain.DELIVER_NEXT -> PendingInputState.QUEUED
    QueueDrain.PARK_ALL -> PendingInputState.PARKED
}

/**
 * Moves every undrained steer into the persistent queue so late text is not lost.
 * Steering carries no attachments, so the captured request is enqueued unchanged.
 */
internal fun carryLateSteers(
    store: PendingInputStore,
    conversationId: Long,
    state: PendingInputState,
): List<PendingInputItem> = store.drainSteers(conversationId).map { steer ->
    store.enqueue(conversationId, steer.capture, state)
}

internal fun CapturedChatRequest.toPendingInputCapture(): PendingInputCapture = PendingInputCapture(
    rawText = rawText,
    composedInput = composedInput,
    attachments = attachments.map { attachment ->
        PendingAttachment(
            name = attachment.name,
            kind = attachment.mediaKind,
            mimeType = attachment.mimeType,
            uri = attachment.uri,
            size = attachment.size,
        )
    },
    forcedSkillIds = forcedSkillIds.toList(),
)

internal fun PendingInputCapture.toCapturedChatRequest(): CapturedChatRequest = CapturedChatRequest(
    rawText = rawText,
    composedInput = composedInput,
    forcedSkillIds = forcedSkillIds.toList(),
    attachments = attachments.map { attachment ->
        CapturedChatAttachment(
            name = attachment.name,
            size = attachment.size,
            mimeType = attachment.mimeType,
            mediaKind = attachment.kind,
            uri = attachment.uri,
        )
    },
)

/** Chip text: the typed text, else the attachment or workspace file that made the item sendable. */
internal fun pendingInputExcerpt(
    capture: PendingInputCapture,
    maxLength: Int = pendingInputExcerptMaxLength,
): String {
    val raw = capture.rawText.trim().replace(WHITESPACE_REGEX, " ")
    if (raw.isNotBlank()) return raw.take(maxLength)
    val attachmentName = capture.attachments.firstOrNull()?.name.orEmpty().trim()
    if (attachmentName.isNotBlank()) return attachmentName.take(maxLength)
    return workspaceFileName(capture.composedInput).take(maxLength)
}

internal fun composerQueueRowUis(
    snapshot: PendingInputSnapshot?,
): List<ComposerQueueRowUi> =
    snapshot?.let { current ->
        current.queue.map { item ->
            ComposerQueueRowUi(
                id = item.id,
                excerpt = pendingInputExcerpt(item.capture),
                attachmentCount = item.capture.attachments.size,
                missingAttachmentCount = item.capture.attachments.count { it.uri.trim() in current.missingAttachmentUris },
                state = when (item.state) {
                    PendingInputState.QUEUED -> ComposerQueueItemState.QUEUED
                    PendingInputState.PARKED -> ComposerQueueItemState.PARKED
                },
            )
        }
    }.orEmpty()

/**
 * URIs in the queue whose backing files are absent. Called when a queue is loaded on IO; the
 * result is stored in [PendingInputSnapshot.missingAttachmentUris] for composition to read.
 */
internal fun missingAttachmentUris(
    snapshot: PendingInputSnapshot,
    attachmentExists: (PendingAttachment) -> Boolean,
): Set<String> = snapshot.queue
    .asSequence()
    .flatMap { it.capture.attachments.asSequence() }
    .filter { it.uri.trim().isNotEmpty() }
    .filterNot(attachmentExists)
    .map { it.uri.trim() }
    .toSet()

private val WHITESPACE_REGEX = Regex("\\s+")
private val WORKSPACE_REFERENCE_REGEX = Regex("<lyra_workspace_refs_v1>([\\s\\S]*?)</lyra_workspace_refs_v1>")

private fun workspaceFileName(composedInput: String): String =
    WORKSPACE_REFERENCE_REGEX.find(composedInput)?.let { match ->
        runCatching {
            JSONObject(match.groupValues[1]).optJSONArray("files")?.optJSONObject(0)?.optString("name")
        }.getOrNull()
    }.orEmpty().trim()

class ChatController(
    private val appContext: Context,
    private val settings: AppSettings,
    private val conversationStore: ConversationStore,
    private val uploadedFileManager: UploadedFileManager,
    private val workspaceManager: WorkspaceManager,
    private val agent: OpenAiAgent,
    /** Read-only view of the scoped device-control run for one conversation. */
    private val scopedDeviceRunning: (Long) -> Boolean = DeviceTaskCoordinator::isRunningConversation,
) {
    private enum class ConversationOperation {
        GENERATING,
        COMPRESSING,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /**
     * A scoped device task has no controller run outcome, so the coordinator reports when it
     * releases its conversation. The callback arrives on the coordinator's worker thread:
     * switch to Main, then recheck that no newer run has taken the conversation before
     * touching its queue. A completed task drains one item; failure or cancellation parks.
     */
    private val deviceConversationIdleListener: (Long, ConversationIdleOutcome) -> Unit = { conversationId, outcome ->
        scope.launch {
            // A newer run may have started between the coordinator's notification and this
            // main-thread coroutine; it owns the queue and releases it when it finishes.
            if (isConversationBusy(conversationId)) return@launch
            // Use the in-memory snapshot: an idle conversation with no loaded queue must not
            // trigger a disk read on the main thread.
            val queued = pendingInput.snapshots.value[conversationId]?.queuedCount ?: 0
            if (queued <= 0) return@launch
            dispatchConversationIdle(
                outcome = outcome,
                deliverNext = { deliverNextQueued(conversationId) },
                parkAll = { parkQueued(conversationId) },
            )
        }
    }
    /**
     * Queue file IO and attachment reference checks run on one dedicated thread. It is not a
     * child of [scope], so close can flush queued writes before cancelling UI work.
     */
    private val pendingInputExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyra-pending-input").apply { isDaemon = true }
    }
    private val pendingInputScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Long, Job>()
    private val jobOutcomes = mutableMapOf<Long, (ConversationRunOutcome) -> Unit>()
    private val conversationRunGate = ConversationRunGate()
    private val conversationOperations = mutableStateMapOf<Long, ConversationOperation>()
    /**
     * One versioned document per conversation. Restored items are always parked; nothing here
     * can start a run. The checker excludes the conversation being mutated from the persisted
     * queue scan and also checks delivered user messages.
     */
    private val pendingInputFileStore = PendingInputFileStore(
        filesDir = appContext.filesDir,
        conversationExists = { id -> conversationStore.conversation(id) != null },
        deliveredAttachmentUris = { conversationStore.userMessageAttachmentUris() },
    )
    private val pendingInput = PendingInputStore(
        readQueue = pendingInputFileStore::readQueue,
        writeQueue = pendingInputFileStore::writeQueue,
        persistenceExecutor = pendingInputExecutor,
        onPersistenceFailure = { error -> Log.w(LOG_TAG, "Pending input persistence failed", error) },
    )
    private val pendingInputReaper = PendingInputAttachmentReaper(pendingInputFileStore, pendingInputExecutor)
    private val pendingInputRestorer = PendingInputRestorer(
        fileStore = pendingInputFileStore,
        store = pendingInput,
        conversationExists = { id -> conversationStore.conversation(id) != null },
        onRestored = { id -> markMissingAttachments(id) },
        onSwept = { id, candidates -> pendingInputReaper.reap(id, candidates) },
    )
    /** Read-only queue snapshots for the UI; mutate them only through controller actions. */
    internal val pendingInputSnapshots: StateFlow<Map<Long, PendingInputSnapshot>> = pendingInput.snapshots
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
    val sessionRevision = mutableIntStateOf(0)
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
    private val userQuestionIdleTimeout = UserQuestionIdleTimeout(scope) { id ->
        val pending = pendingUserQuestion.value
        if (pending?.id == id && !pending.isMinimized) {
            userQuestionWaiters.remove(id)?.complete(
                UserQuestionAnswer(status = UserQuestionAnswer.STATUS_TIMED_OUT),
            )
            pendingUserQuestion.value = null
            status.value = appContext.getString(R.string.status_user_question_timed_out)
        }
    }
    private val editorMutationWaiters = mutableMapOf<Long, CompletableDeferred<AgentFileEditResult>>()
    private val autoApprovedConversations = mutableSetOf<Long>()
    private var transientWorkspaceUri = ""
    private var transientProjectId = 0L
    private var transientAutoApprovalEnabled = false
    private var transientAutoCompressionMode = ConversationStore.AUTO_COMPRESSION_OFF
    private var transientAutoCompressionTurnThreshold = ConversationStore.DEFAULT_AUTO_COMPRESSION_TURNS
    private var transientAutoCompressionTokenThreshold = ConversationStore.DEFAULT_AUTO_COMPRESSION_TOKENS
    private val todoByConversation = mutableMapOf<Long, MutableList<TodoItem>>()
    /** Recomposes the composer guards when a sub-agent call starts or ends. */
    private val subAgentRunRevision = mutableIntStateOf(0)

    init {
        agent.approvalHandler = ::requestToolApproval
        agent.userQuestionHandler = ::requestUserQuestion
        agent.todoSetHandler = ::setTodos
        agent.todoUpdateHandler = ::updateTodo
        agent.configChangedHandler = ::handleConfigChanged
        agent.fileEditHandler = ::handleAgentFileEdit
        agent.fileMutationHandler = ::handleAgentFileMutation
        agent.fileActivityHandler = ::handleAgentFileActivity
        agent.subAgentRunStateChanged = { scope.launch { subAgentRunRevision.intValue++ } }
        DeviceTaskCoordinator.addConversationIdleListener(deviceConversationIdleListener)
        // Steers are transient: the store drains them atomically at an agent round boundary.
        agent.steerSource = steerSourceFrom(pendingInput::drainSteers)
        reloadProfiles()
        markAbandonedRunsInterrupted()
        reloadConversations()
        showTransientNewConversation()
        pendingInputScope.launch { pendingInputRestorer.restoreAll() }
    }

    fun close() {
        DeviceTaskCoordinator.removeConversationIdleListener(deviceConversationIdleListener)
        // The executor is independent of [scope]. Flush submits a barrier behind every queued
        // write and waits for it, so no pending document work is lost to cancellation.
        runCatching { runBlocking(NonCancellable) { pendingInput.flush() } }
            .onFailure { error -> Log.w(LOG_TAG, "Pending input flush failed", error) }
        pendingInputScope.cancel()
        pendingInputExecutor.shutdown()
        runCatching { pendingInputExecutor.awaitTermination(5, TimeUnit.SECONDS) }
        scope.cancel()
    }

    /**
     * Deletes the persisted queue and reports attachments for the IO reference check. Runs on
     * the queue executor so an unloaded conversation never reads its document on the main thread;
     * close's flush barrier is ordered behind this task.
     */
    private fun deletePendingInput(conversationId: Long) {
        runCatching {
            pendingInputExecutor.execute {
                runCatching { pendingInputReaper.reap(conversationId, pendingInput.delete(conversationId)) }
            }
        }
    }

    /** Lazily restores a conversation's queue without blocking the main thread. */
    private fun ensurePendingInputLoaded(conversationId: Long) {
        if (conversationId <= 0L || pendingInput.snapshots.value.containsKey(conversationId)) return
        pendingInputScope.launch { loadPendingInput(conversationId) }
    }

    /** Load and attachment checks are file IO; call only from [pendingInputScope]. */
    private fun loadPendingInput(conversationId: Long) {
        pendingInput.load(conversationId)
        markMissingAttachments(conversationId)
    }

    /**
     * Computes missing backing files once per load on IO. Recomposition reads the resulting set
     * from [PendingInputSnapshot]; it never touches the filesystem.
     */
    private fun markMissingAttachments(conversationId: Long) {
        val snapshot = pendingInput.snapshots.value[conversationId] ?: return
        val missing = missingAttachmentUris(snapshot, pendingInputFileStore::attachmentExists)
        pendingInput.updateMissingAttachments(conversationId, missing)
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
        sessionRevision.intValue++
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
        sessionRevision.intValue++
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
        ensurePendingInputLoaded(id)
    }

    fun deleteConversation(id: Long) {
        cancelConversationJob(id)
        // Delete the messages first: attachments that only that conversation referenced then
        // become orphans and can be removed with the queue document.
        conversationStore.deleteConversation(id)
        deletePendingInput(id)
        autoApprovedConversations.remove(id)
        todoByConversation.remove(id)
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
        conversationStore.conversationsForProject(id, archived = null).forEach { pendingInput.archive(it.id) }
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
            cancelConversationJob(conversationId)
            autoApprovedConversations.remove(conversationId)
            todoByConversation.remove(conversationId)
        }
        val wasActive = activeProjectId() == id
        conversationStore.deleteProject(id)
        conversationIds.forEach(::deletePendingInput)
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
        if (isConversationBusy(conversationId)) {
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
        var compressionSucceeded = false
        launchConversationJob(
            conversationId,
            ConversationOperation.COMPRESSING,
            onOutcome = { outcome ->
                handleConversationRunOutcome(
                    conversationId,
                    outcome,
                    compressionFailed = !compressionSucceeded,
                )
            },
        ) {
            conversationStore.setConversationMeta(conversationId, status = ConversationStore.STATUS_RUNNING)
            reloadConversations()
            status.value = appContext.getString(R.string.status_compressing_history)
            val result = runCatching {
                val summary = agent.compressConversationHistory(
                    conversationId,
                    profile,
                    model,
                    customInstruction,
                    normalizedChunkCount,
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                conversationStore.setCompressedContext(conversationId, summary, throughMessageId)
            }
            compressionSucceeded = result.isSuccess
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
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
            .onSuccess { summary ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                conversationStore.setCompressedContext(conversationId, summary, throughMessageId)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                status.value = error.message.orEmpty().ifBlank { appContext.getString(R.string.error_history_compression_failed) }
            }
            .exceptionOrNull()
    }

    /**
     * Runs [maybeAutoCompress] and restores the generating marker afterwards, on success and
     * on failure. Only the job that still owns the conversation may restore it.
     */
    private suspend fun autoCompressDuringRun(conversationId: Long): Throwable? {
        val owningJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        return try {
            maybeAutoCompress(conversationId)
        } finally {
            if (shouldRestoreGeneratingMarker(
                    operationIsCompressing = conversationOperations[conversationId] == ConversationOperation.COMPRESSING,
                    owningJobIsCurrent = owningJob != null && jobs[conversationId] === owningJob,
                )
            ) {
                conversationOperations[conversationId] = ConversationOperation.GENERATING
            }
        }
    }

    fun setConversationPinned(id: Long, pinned: Boolean) {
        conversationStore.setPinned(id, pinned)
        reloadConversations()
    }

    fun archiveConversation(id: Long) {
        pendingInput.archive(id)
        conversationStore.setArchived(id, true)
        reloadConversations()
    }

    fun restoreArchivedConversation(id: Long) {
        conversationStore.setArchived(id, false)
        reloadConversations()
    }

    fun permanentlyDeleteArchivedConversation(id: Long) {
        if (conversationStore.conversation(id)?.archivedAt?.let { it > 0L } != true) return
        cancelConversationJob(id)
        conversationStore.deleteConversation(id)
        deletePendingInput(id)
        autoApprovedConversations.remove(id)
        todoByConversation.remove(id)
        reloadConversations()
    }

    fun deleteConversations(ids: Collection<Long>) {
        ids.forEach { id ->
            cancelConversationJob(id)
            conversationStore.deleteConversation(id)
            deletePendingInput(id)
            autoApprovedConversations.remove(id)
            todoByConversation.remove(id)
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

    /**
     * Handles one user-initiated send. Queueing captures the composed request once and
     * never touches the draft; callers clear their own editor state on SENT or QUEUED.
     */
    internal fun send(
        text: String,
        forcedSkillIds: List<String> = emptyList(),
        workspaceFiles: List<WorkspaceFileReference> = emptyList(),
    ): SendOutcome {
        val uploads = pendingUploads.toList()
        if (!composerHasSendableContent(text, uploads.size, workspaceFiles.size)) return SendOutcome.BLOCKED
        val activeId = activeConversationId.value
        // A scoped device-control run owns the conversation too, even though it is not a
        // controller job: queue instead of starting a concurrent run on the same history.
        val runActive = isConversationBusy(activeId)
        if (runActive && !settings.composerDeliveryEnabled) {
            status.value = appContext.getString(R.string.status_message_refused)
            return SendOutcome.BLOCKED
        }
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: createPersistedConversation()
        conversationStore.conversation(conversationId)?.let { workspaceManager.setActiveWorkspaceUri(it.workspaceUri) }
        val request = captureChatRequest(
            rawText = text,
            uploads = uploads,
            workspaceFiles = workspaceFiles,
            forcedSkillIds = forcedSkillIds,
            editorContextPath = editorContextPath,
        )
        if (runActive) {
            pendingInput.enqueue(conversationId, request.toPendingInputCapture())
            pendingUploads.clear()
            uploadingStatus.value = ""
            status.value = appContext.getString(
                R.string.status_message_queued,
                pendingInput.snapshot(conversationId).queuedCount,
            )
            return SendOutcome.QUEUED
        }
        val profile = currentProfile()
        val model = activeModel.value.ifBlank { profile.selectedModel }
        deliverConversationRequest(
            conversationId = conversationId,
            request = request,
            profile = profile,
            model = model,
            clearComposerUploadsOnPersisted = true,
        )
        return SendOutcome.SENT
    }

    /**
     * Moves one queued or parked message into the active run's steer channel. The item leaves
     * the queue only after every guard passes, so a refusal never loses it. Steering carries
     * text only; a captured item with attachments keeps its place in the queue instead of
     * silently dropping them.
     */
    internal fun steerQueued(itemId: String): SteerOutcome {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return SteerOutcome.REFUSED
        val availability = steerAvailabilityFor(conversationId)
        if (!availability.acceptsSteer()) {
            status.value = appContext.getString(availability.unavailableExplanationRes())
            return SteerOutcome.REFUSED
        }
        val item = pendingInput.snapshot(conversationId).queue.firstOrNull { it.id == itemId }
            ?: return SteerOutcome.REFUSED
        if (item.capture.attachments.isNotEmpty()) {
            status.value = appContext.getString(R.string.status_message_steer_attachments)
            return SteerOutcome.REFUSED
        }
        if (pendingInput.takeForSteering(conversationId, itemId) == null) return SteerOutcome.REFUSED
        status.value = appContext.getString(
            availability.waitNoteRes() ?: R.string.status_message_steer_queued,
        )
        return SteerOutcome.STEERED
    }

    /**
     * Policy inputs for one conversation. The pending approval and question slots are
     * global in the controller, so they are filtered to the target conversation here.
     */
    private fun steerAvailabilityFor(conversationId: Long): SteerAvailability {
        val conversation = conversationStore.conversation(conversationId)
        val operation = conversationOperations[conversationId]
        return steerAvailability(
            featureEnabled = settings.composerDeliveryEnabled,
            generating = operation == ConversationOperation.GENERATING,
            compressing = operation == ConversationOperation.COMPRESSING,
            scopedDeviceRunning = conversationId > 0L && scopedDeviceRunning(conversationId),
            mediaGeneration = isMediaGenerationModel(conversation?.model.orEmpty()),
            subAgentTarget = conversation?.mode == ConversationStore.MODE_SUBAGENT,
            pendingApprovalForConversation = isPendingInteractionForConversation(
                pendingToolApproval.value?.request?.conversationId,
                conversationId,
            ),
            pendingQuestionForConversation = isPendingInteractionForConversation(
                pendingUserQuestion.value?.request?.conversationId,
                conversationId,
            ),
            subAgentRunning = conversationId > 0L && agent.isSubAgentRunning(conversationId),
        )
    }

    /**
     * Current availability for the visible conversation. Reads [subAgentRunRevision] so
     * Compose recomposes when a sub-agent call starts or ends.
     */
    internal fun activeSteerAvailability(): SteerAvailability {
        subAgentRunRevision.intValue
        return steerAvailabilityFor(activeConversationId.value)
    }

    /** Localized refusal explanation for a non-accepting availability value. */
    internal fun steerUnavailableExplanation(availability: SteerAvailability): String =
        appContext.getString(availability.unavailableExplanationRes())

    /** Cancels a queued item that has not been delivered. */
    fun removeQueued(itemId: String) {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        pendingInput.remove(conversationId, itemId)?.let { removal ->
            pendingInputReaper.reap(conversationId, removal.orphanedAttachments)
        }
    }

    /** Synchronous UI view of the loaded queue; IO restores publish through [pendingInputSnapshots]. */
    internal fun pendingInputSnapshotFor(conversationId: Long = activeConversationId.value): PendingInputSnapshot =
        pendingInput.snapshots.value[conversationId] ?: PendingInputSnapshot(conversationId)

    /** Manual delivery of a parked item; only when the conversation is idle. */
    fun sendQueuedNow(itemId: String) {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        val runActive = isConversationBusy(conversationId)
        if (!composerCanSendParked(runActive)) {
            status.value = appContext.getString(R.string.status_message_send_now_busy)
            return
        }
        val conversation = conversationForQueuedDelivery(conversationId) ?: return
        val profile = profileForQueuedDelivery(conversation) ?: return
        // The item is removed only after every guard passed, so no guard can drop it.
        val item = pendingInput.takeForDelivery(conversationId, itemId) ?: return
        deliverQueuedItem(item, conversation, profile)
    }

    /** Feature-off transition: park everything already waiting so nothing auto-sends. */
    fun parkAllQueuedForDisabledFeature() {
        // Loaded queues carry the only QUEUED state; persisted-only queues are already parked.
        // Park loaded ones synchronously for the UI, then restore every persisted queue on IO so
        // feature-off covers conversations that were never opened in this process.
        val loadedQueuedIds = pendingInput.snapshots.value
            .filterValues { it.queuedCount > 0 }
            .keys
            .toList()
        loadedQueuedIds.forEach(pendingInput::parkAllQueued)
        pendingInputScope.launch {
            val activeId = activeConversationId.value
            (pendingInputFileStore.allConversationIds() + activeId).forEach { conversationId ->
                if (conversationId > 0L &&
                    conversationId !in loadedQueuedIds &&
                    pendingInput.snapshots.value[conversationId] == null &&
                    conversationStore.conversation(conversationId) != null
                ) {
                    pendingInput.load(conversationId)
                    pendingInput.parkAllQueued(conversationId)
                }
            }
        }
    }

    private fun deliverConversationRequest(
        conversationId: Long,
        request: CapturedChatRequest,
        profile: ApiProfile,
        model: String,
        clearComposerUploadsOnPersisted: Boolean,
        restoreWorkspaceTo: String? = null,
    ) {
        var compressionFailed = false
        deliverCapturedRequest(
            conversationId = conversationId,
            request = request,
            profile = profile,
            model = model,
            onStatus = { text ->
                if (activeConversationId.value == conversationId) status.value = text
            },
            onUpdate = { update ->
                if (activeConversationId.value == conversationId) {
                    applyChatUpdate(update)
                    status.value = update.status
                }
            },
            // Viewer policy is live, not snapshotted: stream updates and the
            // final status never apply to a conversation the user has switched
            // away from. The old unconditional end-of-run status writes leaked
            // into whichever conversation was visible; this is a deliberate
            // deviation. The conversation list itself is global, so
            // reloadConversations stays unconditional.
            onViewerRefresh = {
                val targetVisible = activeConversationId.value == conversationId
                if (targetVisible) reloadMessages()
                reloadConversations()
                if (targetVisible) refreshContextWindowUsage()
            },
            onRunFinished = { failed ->
                compressionFailed = failed
                // A hidden run must not overwrite the visible conversation's
                // finished status; markConversationFinished is viewer-scoped.
                if (!failed && activeConversationId.value == conversationId) {
                    markConversationFinished(conversationId)
                }
            },
            onPersisted = {
                if (clearComposerUploadsOnPersisted) {
                    pendingUploads.clear()
                    uploadingStatus.value = ""
                }
            },
            onOutcome = { outcome ->
                if (restoreWorkspaceTo != null) {
                    restoreWorkspaceAfterQueuedDelivery(conversationId, restoreWorkspaceTo)
                }
                handleConversationRunOutcome(conversationId, outcome, compressionFailed)
            },
        )
    }

    /**
     * Runs after the completed job has left the registry and the run gate is free.
     * Only one queued item is delivered per completed run; every other outcome parks.
     * Late steers move into the queue before the drain so a completed run can deliver
     * them as its next run (older queue items stay ahead). Stop, cancellation and
     * failure park them instead of dropping text the UI already acknowledged.
     */
    private fun handleConversationRunOutcome(
        conversationId: Long,
        outcome: ConversationRunOutcome,
        compressionFailed: Boolean,
    ) {
        val drain = queueDrainFor(outcome, compressionFailed, settings.composerDeliveryEnabled)
        val carried = carryLateSteers(
            store = pendingInput,
            conversationId = conversationId,
            state = lateSteerQueueState(drain),
        )
        if (outcome == ConversationRunOutcome.Completed &&
            carried.isNotEmpty() &&
            activeConversationId.value == conversationId &&
            conversationStore.conversation(conversationId) != null
        ) {
            // A following delivered/running/parked status may replace this one; when
            // the item cannot be delivered, this status explains where the text went.
            status.value = appContext.getString(R.string.status_message_steer_carried)
        }
        when (drain) {
            QueueDrain.DELIVER_NEXT -> deliverNextQueued(conversationId)
            QueueDrain.PARK_ALL -> parkQueued(conversationId)
        }
    }

    private fun parkQueued(conversationId: Long) {
        val queued = pendingInput.snapshot(conversationId).queuedCount
        if (queued <= 0) return
        pendingInput.parkAllQueued(conversationId)
        if (activeConversationId.value == conversationId) {
            status.value = appContext.getString(R.string.status_message_parked, queued)
        }
    }

    private fun deliverNextQueued(conversationId: Long) {
        if (!settings.composerDeliveryEnabled) {
            parkQueued(conversationId)
            return
        }
        val runActive = isConversationBusy(conversationId)
        if (runActive) return
        val conversation = conversationForQueuedDelivery(conversationId) ?: return
        val profile = profileForQueuedDelivery(conversation) ?: return
        val item = pendingInput.deliverNextQueued(conversationId) ?: return
        deliverQueuedItem(item, conversation, profile)
    }

    /**
     * Validates the delivery target before the item leaves the queue. A deleted
     * conversation discards its queue; an archived one keeps its items parked.
     */
    private fun conversationForQueuedDelivery(conversationId: Long): Conversation? {
        val conversation = conversationStore.conversation(conversationId) ?: run {
            deletePendingInput(conversationId)
            return null
        }
        if (conversation.archivedAt > 0L || isProjectArchived(conversation.projectId)) {
            pendingInput.archive(conversationId)
            return null
        }
        return conversation
    }

    /** Never substitute the visible profile: the item must run under its own. */
    private fun profileForQueuedDelivery(conversation: Conversation): ApiProfile? {
        val profile = profiles.firstOrNull { it.id == conversation.profileId }
        if (profile == null) {
            pendingInput.parkAllQueued(conversation.id)
            if (activeConversationId.value == conversation.id) {
                status.value = appContext.getString(R.string.status_message_profile_unavailable)
            }
        }
        return profile
    }

    private fun deliverQueuedItem(
        item: PendingInputItem,
        conversation: Conversation,
        profile: ApiProfile,
    ) {
        val model = conversation.model.ifBlank { profile.selectedModel }
        // Delivery may target a hidden conversation. WorkspaceManager is one global
        // mutable workspace, so a visible conversation can re-point it while this
        // run is active and the run's tools can then use the wrong files. This is
        // accepted pre-existing overlap: per-conversation runs already share one
        // workspace. Scoped device-control runs are also invisible here (ticket
        // #10). The prior workspace is restored after the run only if nothing has
        // re-pointed it in the meantime.
        val previousWorkspace = workspaceManager.activeWorkspaceUri()
        workspaceManager.setActiveWorkspaceUri(conversation.workspaceUri)
        if (activeConversationId.value == item.conversationId) {
            // The delivered run's first status_running overwrites this immediately;
            // chip removal from the strip is the durable delivery signal.
            status.value = appContext.getString(R.string.status_message_delivered)
        }
        deliverConversationRequest(
            conversationId = item.conversationId,
            request = item.capture.toCapturedChatRequest(),
            profile = profile,
            model = model,
            clearComposerUploadsOnPersisted = false,
            restoreWorkspaceTo = previousWorkspace,
        )
    }

    private fun restoreWorkspaceAfterQueuedDelivery(conversationId: Long, previousWorkspace: String) {
        val deliveredWorkspace = conversationStore.conversation(conversationId)?.workspaceUri.orEmpty()
        if (workspaceManager.activeWorkspaceUri() == deliveredWorkspace) {
            workspaceManager.setActiveWorkspaceUri(previousWorkspace)
        }
    }

    private fun isProjectArchived(projectId: Long): Boolean =
        projectId > 0L && conversationStore.project(projectId)?.archivedAt?.let { it > 0L } == true


    /**
     * Delivers a captured request to one explicit conversation. Viewer-scoped
     * UI work is delegated to the caller, so a delivery does not depend on or
     * write into whichever conversation happens to be visible.
     */
    internal fun deliverCapturedRequest(
        conversationId: Long,
        request: CapturedChatRequest,
        profile: ApiProfile,
        model: String,
        onStatus: (String) -> Unit = {},
        onUpdate: (ChatUpdate) -> Unit = {},
        onViewerRefresh: () -> Unit = {},
        onRunFinished: (compressionFailed: Boolean) -> Unit = {},
        onPersisted: () -> Unit = {},
        onOutcome: (ConversationRunOutcome) -> Unit = {},
    ) {
        val isFirstUserMessage = conversationStore.messages(conversationId).none { it.role == "user" }
        val titleBeforeSend = conversationStore.conversation(conversationId)?.title
        val defaultTitle = appContext.getString(R.string.default_conversation_title)
        val provisionalTitle = if (titleBeforeSend in setOf(
                defaultTitle,
                appContext.getString(R.string.title_new_chat),
            )) {
            fallbackConversationTitle(request.composedInput, defaultTitle)
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
        conversationStore.addMessage(conversationId, "user", request.composedInput, profileId = profile.id, model = model)
        onViewerRefresh()
        onPersisted()
        if (isFirstUserMessage) {
            val topicProfile = settings.topicSummaryProfile()
            val topicModel = settings.topicSummaryModel.ifBlank { topicProfile.selectedModel }
            val topicInput = request.rawText.trim().ifBlank { fallbackConversationTitle(request.composedInput, defaultTitle) }
            scope.launch {
                runCatching { agent.summarizeConversationTopic(topicProfile, topicModel, topicInput) }
                    .onSuccess { title ->
                        val current = conversationStore.conversation(conversationId)
                        if (current != null && current.title == topicTitleGuard) {
                            conversationStore.setConversationMeta(conversationId, title = title)
                            reloadConversations()
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        android.util.Log.w(
                            "LyraTopicSummary",
                            "Title generation failed: conversation=$conversationId profile=${topicProfile.id} model=$topicModel",
                            error,
                        )
                    }
            }
        }
        launchConversationJob(conversationId, ConversationOperation.GENERATING, onOutcome) {
            onStatus(appContext.getString(R.string.status_running))
            agent.chat(conversationId, request.composedInput, profile, model, userMessagePersisted = true, forcedSkillIds = request.forcedSkillIds) {
                withContext(Dispatchers.Main) {
                    onUpdate(it)
                }
            }
            val compressionError = autoCompressDuringRun(conversationId)
            onViewerRefresh()
            onRunFinished(compressionError != null)
        }
    }

    fun stopActive() {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        // A scoped device task owns the conversation outside the controller's job registry;
        // stop that task instead of only touching controller state.
        if (scopedDeviceRunning(conversationId)) {
            DeviceTaskCoordinator.pause()
            status.value = appContext.getString(R.string.status_interrupted)
            return
        }
        jobs[conversationId]?.cancel()
        conversationOperations.remove(conversationId)
        // Park synchronously: a new send can supersede the cancelled job before its
        // outcome callback runs, and a superseded job deliberately skips that callback.
        pendingInput.parkAllQueued(conversationId)
        // The stopped agent will not drain again, and the skipped outcome callback
        // cannot preserve accepted steers, so park them from this stop path explicitly.
        carryLateSteers(pendingInput, conversationId, PendingInputState.PARKED)
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
            userQuestionIdleTimeout.cancel()
        }
        reloadConversations()
        reloadMessages()
        status.value = appContext.getString(R.string.status_interrupted)
    }

    internal fun stopChatGptAccountRequests(clientId: String) {
        val profileIds = settings.profiles().filter { it.chatGptAccountId == clientId }.map { it.id }.toSet()
        jobs.keys.toList().forEach { id ->
            if (conversationStore.conversation(id)?.profileId in profileIds) {
                jobs[id]?.cancel()
                // Same supersession race as stopActive: park before the job unwinds.
                pendingInput.parkAllQueued(id)
                carryLateSteers(pendingInput, id, PendingInputState.PARKED)
                conversationStore.setConversationMeta(id, status = ConversationStore.STATUS_INTERRUPTED)
            }
        }
        reloadMessages()
        reloadConversations()
    }

    fun continueActive() {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        if (isConversationBusy(conversationId)) {
            status.value = appContext.getString(R.string.status_message_run_busy)
            return
        }
        if (conversationStore.conversation(conversationId)?.status != ConversationStore.STATUS_INTERRUPTED) return
        conversationStore.conversation(conversationId)?.let { workspaceManager.setActiveWorkspaceUri(it.workspaceUri) }
        val profile = currentProfile()
        val model = activeModel.value.ifBlank { profile.selectedModel }
        var compressionFailed = false
        launchConversationJob(
            conversationId,
            ConversationOperation.GENERATING,
            onOutcome = { outcome -> handleConversationRunOutcome(conversationId, outcome, compressionFailed) },
        ) {
            conversationStore.setConversationMeta(
                conversationId,
                status = ConversationStore.STATUS_RUNNING,
                profileId = profile.id,
                model = model,
            )
            reloadConversations()
            status.value = appContext.getString(R.string.status_continue)
            agent.continueConversation(conversationId, profile, model) {
                withContext(Dispatchers.Main) {
                    if (activeConversationId.value == conversationId) {
                        applyChatUpdate(it)
                        status.value = it.status
                    }
                }
            }
            val compressionError = autoCompressDuringRun(conversationId)
            compressionFailed = compressionError != null
            reloadMessages()
            reloadConversations()
            refreshContextWindowUsage()
            if (compressionError == null) markConversationFinished(conversationId)
        }
    }

    fun editAndRegenerateUserMessage(messageId: Long, newContent: String) {
        val conversationId = activeConversationId.value.takeIf { it > 0 } ?: return
        if (isConversationBusy(conversationId)) {
            status.value = appContext.getString(R.string.status_message_run_busy)
            return
        }
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
        var compressionFailed = false
        launchConversationJob(
            conversationId,
            ConversationOperation.GENERATING,
            onOutcome = { outcome -> handleConversationRunOutcome(conversationId, outcome, compressionFailed) },
        ) {
            status.value = appContext.getString(R.string.status_regenerate)
            agent.continueConversation(conversationId, profile, model) {
                withContext(Dispatchers.Main) {
                    if (activeConversationId.value == conversationId) {
                        applyChatUpdate(it)
                        status.value = it.status
                    }
                }
            }
            val compressionError = autoCompressDuringRun(conversationId)
            compressionFailed = compressionError != null
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

    fun fetchModels(onDone: (Result<List<String>>) -> Unit) {
        val profile = currentProfile()
        scope.launch {
            status.value = appContext.getString(R.string.status_fetching_models)
            val result = withContext(Dispatchers.IO) { agent.fetchModels(profile) }
            result.onSuccess { models ->
                val updated = profiles.map {
                    when {
                        it.id != profile.id -> it
                        it.isChatGpt -> it.withChatGptModels(models, settings.chatGptAccount.cachedModelNames(it.chatGptAccountId))
                        else -> it.copy(savedModels = (it.savedModels + models).distinct())
                    }
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
        onOutcome: (ConversationRunOutcome) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        lateinit var launchedJob: Job
        launchedJob = scope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                conversationRunGate.run(conversationId) { block() }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                // A cancelled job may finish after a new operation has already
                // started for this conversation. Only clear our own state. A
                // superseded run also skips its outcome callback here because
                // the registry entry now belongs to the newer job. Deletion
                // paths report Cancelled through cancelConversationJob instead.
                if (jobs[conversationId] === launchedJob) {
                    jobs.remove(conversationId)
                    conversationOperations.remove(conversationId)
                    val outcomeCallback = jobOutcomes.remove(conversationId)
                    val status = runCatching { conversationStore.conversation(conversationId)?.status }.getOrNull()
                    val outcome = classifyRunOutcome(failure, status)
                    runCatching { outcomeCallback?.invoke(outcome) }
                }
            }
        }
        jobs[conversationId] = launchedJob
        jobOutcomes[conversationId] = onOutcome
        conversationOperations[conversationId] = operation
        launchedJob.start()
        return launchedJob
    }

    private fun cancelConversationJob(conversationId: Long) {
        val job = jobs.remove(conversationId) ?: return
        val outcomeCallback = jobOutcomes.remove(conversationId)
        conversationOperations.remove(conversationId)
        job.cancel()
        // The job's own finally now sees a foreign registry entry and skips its
        // outcome, so report the deletion cancellation from here instead. The
        // callback observes the registry and operation state already cleared.
        runCatching { outcomeCallback?.invoke(ConversationRunOutcome.Cancelled) }
    }

    fun isActiveConversationRunning(): Boolean = conversationOperations.containsKey(activeConversationId.value)

    /** Unified busy predicate shared by controller actions, the screen, and the web bridge. */
    private fun isConversationBusy(conversationId: Long): Boolean = conversationBusy(
        conversationId = conversationId,
        operationActive = conversationOperations.containsKey(conversationId),
        scopedDeviceRunning = scopedDeviceRunning,
    )

    /** True while any owner runs the visible conversation, including a scoped device task. */
    fun isActiveConversationBusy(): Boolean = isConversationBusy(activeConversationId.value)

    fun canContinueActive(): Boolean = !isActiveConversationBusy() &&
        activeConversation()?.status == ConversationStore.STATUS_INTERRUPTED

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
        val pending = pendingUserQuestion.value
        if (pending?.id == id && !pending.isMinimized) userQuestionIdleTimeout.reset(id)
    }

    fun minimizeUserQuestion(id: Long) {
        val pending = pendingUserQuestion.value ?: return
        if (pending.id != id || pending.isMinimized) return
        pendingUserQuestion.value = pending.copy(isMinimized = true)
        userQuestionIdleTimeout.pause()
    }

    fun restoreUserQuestion(id: Long) {
        val pending = pendingUserQuestion.value ?: return
        if (pending.id != id || !pending.isMinimized) return
        pendingUserQuestion.value = pending.copy(isMinimized = false)
        userQuestionIdleTimeout.resume(id)
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
        userQuestionIdleTimeout.cancel()
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
            // A drained steer just inserted a user turn; its typed flag bypasses the throttle
            // so the new bubble cannot be hidden behind a streaming token update.
            if (update.forceReload) {
                reloadMessages()
            } else {
                reloadMessagesThrottled()
            }
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
                userQuestionIdleTimeout.reset(id)
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
                    userQuestionIdleTimeout.cancel()
                }
            }
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
