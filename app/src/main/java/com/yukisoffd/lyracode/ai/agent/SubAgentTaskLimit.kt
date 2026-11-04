package com.yukisoffd.lyracode.ai

/** The per-call sub-agent task limit, shared by the tool schema and the runtime. */
internal object SubAgentTaskLimit {
    const val MAX_TASKS = 6

    /**
     * Refuses a call with more tasks than the limit, before any task runs. A task dropped
     * without notice is work the parent believes it delegated.
     */
    fun overLimitError(taskCount: Int): String? {
        if (taskCount <= MAX_TASKS) return null
        return "ERROR: TOO_MANY_SUB_AGENT_TASKS\n" +
            "tasks has $taskCount subtasks; at most $MAX_TASKS are allowed per call. No task ran. " +
            "Split the work across several run_sub_agents calls."
    }
}
