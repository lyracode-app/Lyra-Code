package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.OpenSubAgentBatch
import com.yukisoffd.lyracode.data.SubAgentTaskRecord
import com.yukisoffd.lyracode.workspace.TextBackupStore
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Decides what a `run_sub_agents` call does when its parent conversation has an undelivered
 * batch: reuse finished children, run the tasks that never started, and report — never blindly
 * re-run — a write task whose child may have changed files before the app died.
 *
 * Pure by design: the caller supplies the stored rows, the tasks the model just asked for,
 * which child conversations produced a result, and which `.bak` copies exist. Everything here
 * is unit-testable without Android.
 */
internal object SubAgentResume {
    data class RequestedTask(
        val index: Int,
        val text: String,
        val readOnly: Boolean,
        val writePaths: List<String> = emptyList(),
        val expectedOutput: String = "",
        val capabilityHint: String = "",
        val preferredAgent: String = "",
    )

    sealed interface Action {
        /** Re-use the stored result in this child conversation. */
        data class Reuse(val childConversationId: Long) : Action

        /** Run the task now. */
        data object Run : Action

        /** Never re-run: the child may have written files. Report the paths and any `.bak`. */
        data class InterruptedWrite(
            val writePaths: List<String>,
            val backupPaths: List<String>,
        ) : Action
    }

    data class Plan(val batchId: String, val actions: Map<Int, Action>) {
        val reusedIndices: List<Int> = actions.filterValues { it is Action.Reuse }.keys.sorted()
        val runIndices: List<Int> = actions.filterValues { it is Action.Run }.keys.sorted()
        val interruptedIndices: List<Int> = actions.filterValues { it is Action.InterruptedWrite }.keys.sorted()
    }

    /**
     * True only when the child conversation returned normally: it is idle and its last message
     * is a non-blank assistant answer.
     *
     * [com.yukisoffd.lyracode.ai.agent.OpenAiAgent.runSubAgentTask] writes RUNNING before
     * `runLoop` starts, and `runLoop` writes idle only after the final message, so a process
     * death leaves the child running or interrupted. A child killed before its first token,
     * mid-stream or inside `write_file` therefore never looks finished, and its partial text
     * is never handed back as a result. A child whose conversation no longer exists did not
     * finish either.
     */
    fun childFinished(
        conversationStatus: String?,
        lastMessageRole: String?,
        lastMessageContent: String?,
    ): Boolean = conversationStatus == ConversationStore.STATUS_IDLE &&
        lastMessageRole == "assistant" &&
        !lastMessageContent.isNullOrBlank()

    /**
     * Returns null when the stored batch is not the batch the model just asked for: a different
     * task count, a different user message, or any task field that differs at an index. A null
     * plan starts a fresh batch.
     *
     * [storedUserMessageId] is the parent's last user message when the batch began and
     * [currentUserMessageId] is the parent's last user message now. A new message changes the
     * id, so an old batch can never answer a later turn; editing or regenerating deletes the
     * turn's messages and the store closes the batch outright.
     *
     * [reusableChildIds] are child conversations that [childFinished] proved finished even
     * though their row never recorded completion, which is exactly the "finished child whose
     * result never reached the parent" case. A write task whose child is not in that set is
     * never re-run: the interrupted write beats reuse. [backupPaths] are the workspace-relative
     * `.bak` paths that exist right now.
     */
    fun plan(
        batchId: String,
        storedUserMessageId: Long,
        currentUserMessageId: Long,
        stored: List<SubAgentTaskRecord>,
        requested: List<RequestedTask>,
        reusableChildIds: Set<Long> = emptySet(),
        backupPaths: Set<String> = emptySet(),
    ): Plan? {
        if (storedUserMessageId != currentUserMessageId) return null
        val ordered = stored.sortedBy { it.index }
        if (ordered.size != requested.size) return null
        if (requested.map { it.index } != ordered.indices.toList()) return null
        if (ordered.indices.any { !sameTask(ordered[it], requested[it]) }) return null
        val actions = requested.associate { task ->
            val row = ordered[task.index]
            task.index to when {
                row.childConversationId > 0 && row.childConversationId in reusableChildIds ->
                    Action.Reuse(row.childConversationId)
                row.childConversationId == 0L -> Action.Run
                task.readOnly -> Action.Run
                else -> Action.InterruptedWrite(
                    writePaths = row.writePaths,
                    backupPaths = backupPathsFor(row.writePaths, backupPaths, batchId),
                )
            }
        }
        return Plan(batchId, actions)
    }

    /**
     * Decides whether a `run_sub_agents` call resumes the open [batch]. [plan] is the task plan
     * built for that batch against the requested tasks, or null when the turn or tasks differ.
     *
     * A normal call ([expectedReplayBatchId] null) resumes when the turn and tasks match,
     * whatever its call id: a model that repeats the same tasks in the same turn under a new
     * call id gets the finished children back instead of re-running them. A Continue replay
     * ([expectedReplayBatchId] non-null) resumes only through the batch's own saved call, in
     * that batch, turn and task list; a batch with no saved call id never matches a replay.
     */
    fun selectResume(
        batch: OpenSubAgentBatch,
        currentUserMessageId: Long,
        sourceToolCallId: String,
        expectedReplayBatchId: String?,
        plan: Plan?,
    ): Plan? {
        if (plan == null || plan.batchId != batch.batchId || batch.userMessageId != currentUserMessageId) return null
        if (expectedReplayBatchId == null) return plan
        val ownCall = sourceToolCallId.isNotBlank() && batch.toolCallId == sourceToolCallId
        return plan.takeIf { ownCall && batch.batchId == expectedReplayBatchId }
    }

    /** When an open batch was interrupted and how long ago, for the resumed tool result. */
    data class ResumeTiming(val interruptedAtIso: String, val secondsAgo: Long, val agoText: String)

    /**
     * The last durable activity of [batch]: the newest `updated_at` of its rows. The caller reads
     * the batch before the resume re-points its call id or marks rows running, because both
     * writes move `updated_at` to now. Null when the time is unknown: the batch has no rows, or
     * its newest `updated_at` is missing or not positive.
     */
    fun interruptedAtMillis(batch: OpenSubAgentBatch): Long? =
        batch.tasks.maxOfOrNull { it.updatedAt }?.takeIf { it > 0L }

    /**
     * Times the interruption at [interruptedAtMillis] against the clock reading [nowMillis], or
     * null when the interruption time is unknown (null or not positive).
     */
    fun resumeTiming(interruptedAtMillis: Long?, nowMillis: Long): ResumeTiming? {
        if (interruptedAtMillis == null || interruptedAtMillis <= 0L) return null
        val secondsAgo = ((nowMillis - interruptedAtMillis) / 1000).coerceAtLeast(0)
        val iso = Instant.ofEpochMilli(interruptedAtMillis).truncatedTo(ChronoUnit.SECONDS).toString()
        return ResumeTiming(iso, secondsAgo, durationText(secondsAgo))
    }

    /** A whole-unit English duration: seconds under a minute, then minutes, hours or days. */
    fun durationText(seconds: Long): String {
        fun unit(count: Long, name: String) = if (count == 1L) "1 $name" else "$count ${name}s"
        return when {
            seconds < 60 -> unit(seconds, "second")
            seconds < 3_600 -> unit(seconds / 60, "minute")
            seconds < 86_400 -> unit(seconds / 3_600, "hour")
            else -> unit(seconds / 86_400, "day")
        }
    }

    /** The resumed result's timing fields; empty when the interruption time is unknown. */
    fun resumeTimingFields(timing: ResumeTiming?): Map<String, Any> =
        if (timing == null) {
            emptyMap()
        } else {
            mapOf("interrupted_at" to timing.interruptedAtIso, "interrupted_seconds_ago" to timing.secondsAgo)
        }

    /**
     * The model-facing timing sentences that every resume note carries. An unknown interruption
     * time is said to be unknown, never given as a date; the staleness warning stays either way.
     */
    fun resumeTimingNote(timing: ResumeTiming?): String =
        if (timing == null) {
            "The interruption time is unknown; reused task results may be stale; re-check the write paths " +
                "and any outside state before relying on them."
        } else {
            "The batch was interrupted at ${timing.interruptedAtIso}, about ${timing.agoText} ago. Reused task " +
                "results date from before the interruption and may be stale; re-check the write paths and any " +
                "outside state before relying on them."
        }

    /**
     * The call id to save on [batch] when a normal call resumes it under a different id, or
     * null when the saved id stays. The caller saves it before any child runs, so a kill during
     * the resumed run leaves a batch that a Continue replay of the new call can find.
     */
    fun repointToolCallId(batch: OpenSubAgentBatch, sourceToolCallId: String, expectedReplayBatchId: String?): String? =
        sourceToolCallId.takeIf { expectedReplayBatchId == null && it.isNotBlank() && it != batch.toolCallId }

    /**
     * The copies that exist for a write path in [batchId]: the batch's never-replaced copy an
     * interrupted write keeps first, then the ordinary `.bak`, which every later write replaces.
     */
    internal fun backupPathsFor(writePaths: List<String>, existing: Set<String>, batchId: String): List<String> =
        writePaths.flatMap { path ->
            listOf(TextBackupStore.interruptedBackupPath(path, batchId), TextBackupStore.backupPath(path))
        }.filter { it in existing }

    /**
     * Every field the child's prompt is built from: the same text with a different read-only
     * flag, write paths, expected output or capability hint is a different task.
     */
    private fun sameTask(row: SubAgentTaskRecord, request: RequestedTask): Boolean =
        row.taskText.trim() == request.text.trim() &&
            row.readOnly == request.readOnly &&
            row.writePaths.map(::normalizePath).sorted() == request.writePaths.map(::normalizePath).sorted() &&
            row.expectedOutput.trim() == request.expectedOutput.trim() &&
            row.capabilityHint.trim() == request.capabilityHint.trim() &&
            row.preferredAgent.trim().lowercase(Locale.US) == request.preferredAgent.trim().lowercase(Locale.US)

    private fun normalizePath(path: String): String = path.trim().trimStart('/')
}
