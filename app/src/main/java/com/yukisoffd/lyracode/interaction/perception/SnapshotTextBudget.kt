package com.yukisoffd.lyracode.interaction.perception

internal class SnapshotTextBudget(
    private val maxTotalChars: Int,
    private val maxCharsPerValue: Int,
) {
    var truncated: Boolean = false
        private set

    private var consumedChars: Int = 0

    fun take(raw: CharSequence?): String? {
        val normalized = raw
            ?.toString()
            ?.replace(WHITESPACE, " ")
            ?.trim()
            .orEmpty()
        if (normalized.isEmpty()) return null

        val remaining = (maxTotalChars - consumedChars).coerceAtLeast(0)
        val limit = minOf(maxCharsPerValue, remaining)
        if (limit == 0) {
            truncated = true
            return null
        }
        val result = normalized.take(limit)
        consumedChars += result.length
        if (result.length < normalized.length) truncated = true
        return result
    }

    fun redacted(): String = REDACTED_VALUE

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val REDACTED_VALUE = "[REDACTED]"
    }
}
