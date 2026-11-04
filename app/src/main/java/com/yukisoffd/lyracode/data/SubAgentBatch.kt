package com.yukisoffd.lyracode.data

/** One task of a `run_sub_agents` batch, as persisted before any child starts. */
data class SubAgentTaskSeed(
    val index: Int,
    val taskText: String,
    val writePaths: List<String>,
    val readOnly: Boolean,
    val expectedOutput: String,
    val capabilityHint: String,
    val preferredAgent: String = "",
)

/**
 * One persisted row of an open batch. The row is written before the child starts and updated
 * as the child progresses, so a process death leaves a record of every task's state. Every
 * field of the requested task is stored so a resume can prove the model asked for the same
 * work, not a task that merely shares its text.
 */
data class SubAgentTaskRecord(
    val index: Int,
    val taskText: String,
    val writePaths: List<String>,
    val readOnly: Boolean,
    val expectedOutput: String,
    val capabilityHint: String,
    val childConversationId: Long,
    val status: String,
    /** The batch this row belongs to. The recovery copy of a path is named after it. */
    val batchId: String = "",
    val preferredAgent: String = "",
)

/**
 * One write path whose task started and may have been interrupted, with the batch that owns it
 * and the workspace its path is relative to.
 *
 * The workspace travels with the path because a controller start has no conversation selected:
 * resolving every path against the active workspace would copy the wrong files, or none.
 */
data class InterruptedWritePath(
    val path: String,
    val batchId: String,
    val workspaceUri: String,
)

object SubAgentTaskStatus {
    /** The row exists but no child conversation was created yet. */
    const val PENDING = "pending"

    /** A child conversation exists and the child may be running. */
    const val RUNNING = "running"

    /** The child returned and its result is in its conversation. */
    const val COMPLETED = "completed"

    /** The child stopped without producing a result, or its setup failed. */
    const val FAILED = "failed"

    /** The child was cancelled or the app died while it ran. */
    const val INTERRUPTED = "interrupted"
}

/** The newest batch that never delivered a result to its parent conversation. */
data class OpenSubAgentBatch(
    val batchId: String,
    /** The parent's last user message when the batch began; a resume must match it. */
    val userMessageId: Long,
    val tasks: List<SubAgentTaskRecord>,
)

/**
 * Schema of the durable batch table. Kept as constants so a unit test can prove the migration
 * still carries every column the resume logic reads. The SQLite execution itself is covered on
 * a device only: the repo has no Robolectric, and Android's SQLite is unavailable on the JVM.
 */
internal object SubAgentTaskSchema {
    const val DATABASE_VERSION = 13

    const val CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS sub_agent_tasks (
            parent_conversation_id INTEGER NOT NULL,
            batch_id TEXT NOT NULL,
            task_index INTEGER NOT NULL,
            child_conversation_id INTEGER NOT NULL DEFAULT 0,
            task_text TEXT NOT NULL,
            write_paths TEXT NOT NULL DEFAULT '[]',
            read_only INTEGER NOT NULL DEFAULT 0,
            expected_output TEXT NOT NULL DEFAULT '',
            capability_hint TEXT NOT NULL DEFAULT '',
            preferred_agent TEXT NOT NULL DEFAULT '',
            user_message_id INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL,
            delivered INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (parent_conversation_id, batch_id, task_index)
        )
        """

    const val CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_sub_agent_tasks_open ON sub_agent_tasks(parent_conversation_id, delivered)"
}
