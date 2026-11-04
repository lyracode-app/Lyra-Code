package com.yukisoffd.lyracode.ai

import org.json.JSONObject

/**
 * The single source for the sub-agent task limit and the sub-agent concurrency limit.
 *
 * The tool schema's `maxItems` and the runtime check in `runSubAgents` both read
 * [com.yukisoffd.lyracode.data.AppSettings.subAgentMaxTasks], so the model can never ask for
 * more tasks than the runtime accepts. A batch over the limit is refused with an error, never
 * truncated, because a silently dropped task is work the parent believes it delegated.
 */
internal object SubAgentLimits {
    /** The limit this app shipped with before it became configurable. */
    const val DEFAULT_MAX_TASKS = 6

    /** One child at a time keeps the behaviour of earlier releases. */
    const val DEFAULT_MAX_CONCURRENCY = 1

    /**
     * Upper bound for the task limit. It exists to keep one batch (and its progress line) a
     * bounded size; it is not a performance claim.
     */
    const val HARD_MAX_TASKS = 12

    const val KEY_MAX_TASKS = "subAgentMaxTasks"
    const val KEY_MAX_CONCURRENCY = "subAgentMaxConcurrency"

    fun normalizeTaskLimit(value: Int): Int = value.coerceIn(1, HARD_MAX_TASKS)

    fun normalizeConcurrency(value: Int, taskLimit: Int): Int =
        value.coerceIn(1, normalizeTaskLimit(taskLimit))

    /**
     * The concurrency one batch actually uses: the configured value, capped to the number of
     * tasks in the batch. This is the single seam between the stored setting and the runner.
     */
    fun effectiveConcurrency(configuredConcurrency: Int, taskCount: Int): Int =
        configuredConcurrency.coerceIn(1, taskCount.coerceIn(1, HARD_MAX_TASKS))

    sealed interface BatchSize {
        data class Accepted(val tasks: Int) : BatchSize
        data class Rejected(val requested: Int, val limit: Int) : BatchSize {
            /** The tool error for an oversized call. Nothing has run yet, so the model can split it. */
            fun error(): String =
                "ERROR: TOO_MANY_SUB_AGENT_TASKS\n" +
                    "This batch has $requested tasks but the configured limit is $limit. " +
                    "No task ran. Split the work across several run_sub_agents calls."
        }
    }

    /** Decides a batch size before any task starts. Over the limit is a refusal, not a cut. */
    fun evaluateBatchSize(requested: Int, taskLimit: Int): BatchSize {
        val limit = normalizeTaskLimit(taskLimit)
        return if (requested <= limit) BatchSize.Accepted(requested) else BatchSize.Rejected(requested, limit)
    }

    fun requireAllTasksParsed(requested: Int, parsed: Int) {
        require(requested == parsed) {
            "INVALID_SUB_AGENT_TASKS: Every tasks entry must contain a non-blank task; no task ran."
        }
    }

    /** Writes the two limits into a settings export. */
    fun writeExport(root: JSONObject, maxTasks: Int, maxConcurrency: Int) {
        root.put(KEY_MAX_TASKS, normalizeTaskLimit(maxTasks))
        root.put(KEY_MAX_CONCURRENCY, normalizeConcurrency(maxConcurrency, maxTasks))
    }

    data class Imported(val maxTasks: Int, val maxConcurrency: Int)

    /**
     * Reads the two limits from a settings import, clamping them to the legal range and to each
     * other. Returns null when the export predates these keys, so an old backup changes nothing.
     */
    fun readImport(root: JSONObject): Imported? {
        if (!root.has(KEY_MAX_TASKS) && !root.has(KEY_MAX_CONCURRENCY)) return null
        val maxTasks = normalizeTaskLimit(root.optInt(KEY_MAX_TASKS, DEFAULT_MAX_TASKS))
        val maxConcurrency = normalizeConcurrency(root.optInt(KEY_MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY), maxTasks)
        return Imported(maxTasks, maxConcurrency)
    }
}
