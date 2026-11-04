package com.yukisoffd.lyracode.ai

/** Formats one batch summary and the child's unformatted status detail. */
internal class SubAgentProgressFormatter(
    private val sequential: (Int, Int, String) -> String,
    private val sequentialDetail: (Int, Int, String, String) -> String,
    private val concurrent: (Int, Int, Int) -> String,
    private val concurrentDetail: (Int, Int, Int, String) -> String,
) {
    fun format(
        maxConcurrency: Int,
        completed: Int,
        total: Int,
        running: Int,
        agentName: String,
        detail: String? = null,
    ): String {
        return if (maxConcurrency > 1) {
            if (detail.isNullOrBlank()) concurrent(completed, total, running)
            else concurrentDetail(completed, total, running, detail)
        } else {
            if (detail.isNullOrBlank()) sequential(completed, total, agentName)
            else sequentialDetail(completed, total, agentName, detail)
        }
    }
}
