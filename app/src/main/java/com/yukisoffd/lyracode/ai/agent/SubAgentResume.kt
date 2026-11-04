package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.SubAgentTaskRecord
import com.yukisoffd.lyracode.workspace.TextBackupStore
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
