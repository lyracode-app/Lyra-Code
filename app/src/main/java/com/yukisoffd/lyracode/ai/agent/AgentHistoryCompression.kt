package com.yukisoffd.lyracode.ai

internal val HISTORY_COMPRESSION_SCHEMA_V2 = """
    LYRA_STRUCTURED_CONTEXT_V2
    current_goal:
    - ...
    confirmed_facts:
    - ...
    constraints_and_preferences:
    - ...
    decisions_and_rationale:
    - ...
    completed_tasks:
    - ...
    pending_tasks:
    - ...
    important_artifacts:
    - files, paths, code symbols, commands, IDs, URLs, configuration values, and outputs
    errors_and_attempts:
    - error, attempted remedy, and result
    attention_items:
    - risks, caveats, assumptions, conflicts, and details that must not be lost
    next_actions:
    - ...
    open_questions:
    - ...
""".trimIndent()

internal fun splitCompressionTranscript(transcript: String, requestedChunkCount: Int): List<String> {
    if (transcript.isEmpty()) return emptyList()
    val codePointCount = transcript.codePointCount(0, transcript.length)
    val chunkCount = requestedChunkCount.coerceAtLeast(1).coerceAtMost(codePointCount)
    if (chunkCount == 1) return listOf(transcript)
    val chunks = ArrayList<String>(chunkCount)
    var start = 0
    repeat(chunkCount) { index ->
        val chunksLeft = chunkCount - index
        if (chunksLeft == 1) {
            chunks += transcript.substring(start)
            return@repeat
        }
        val remainingLength = transcript.length - start
        val idealEnd = start + (remainingLength + chunksLeft - 1) / chunksLeft
        val maxEnd = transcript.length - (chunksLeft - 1)
        val searchRadius = minOf(384, maxOf(24, (idealEnd - start) / 8))
        val forwardEnd = transcript.indexOf('\n', idealEnd)
            .takeIf { it >= 0 && it + 1 <= maxEnd && it - idealEnd <= searchRadius }
            ?.plus(1)
        val backwardEnd = transcript.lastIndexOf('\n', idealEnd - 1)
            .takeIf { it >= start && idealEnd - (it + 1) <= searchRadius }
            ?.plus(1)
        var end = listOfNotNull(forwardEnd, backwardEnd)
            .minByOrNull { kotlin.math.abs(it - idealEnd) }
            ?: idealEnd
        if (end < transcript.length && end > start &&
            Character.isHighSurrogate(transcript[end - 1]) && Character.isLowSurrogate(transcript[end])
        ) {
            end = if (end + 1 <= maxEnd) end + 1 else end - 1
        }
        end = end.coerceIn(start + 1, maxEnd)
        chunks += transcript.substring(start, end)
        start = end
    }
    return chunks
}

