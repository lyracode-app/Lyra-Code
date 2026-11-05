package com.yukisoffd.lyracode.data

import android.content.Context
import com.yukisoffd.lyracode.canonicalModelName
import com.yukisoffd.lyracode.R
import java.util.Calendar
import java.util.Locale

enum class UsageStatsPeriod(val labelResId: Int) {
    DAY(R.string.stat_period_day),
    WEEK(R.string.stat_period_week),
    MONTH(R.string.stat_period_month),
    YEAR(R.string.stat_period_year),
    TOTAL(R.string.stat_period_total),
}

data class UsageStatsSummary(
    val period: UsageStatsPeriod,
    val startAt: Long,
    val endAt: Long,
    val conversationCount: Int,
    val userInputTokens: Long,
    val aiOutputTokens: Long,
    val userMessageCount: Int,
    val assistantMessageCount: Int,
    val toolMessageCount: Int,
    val modelRequestCount: Int,
    val modelUsage: List<ModelUsageStat>,
)

data class ModelUsageStat(
    val modelName: String,
    val requestCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

internal data class ModelUsageSample(
    val modelName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val requestCount: Int = 1,
)

internal fun aggregateModelUsage(samples: List<ModelUsageSample>): List<ModelUsageStat> {
    data class MutableModelUsage(
        var displayName: String,
        var requestCount: Int = 0,
        var inputTokens: Long = 0L,
        var outputTokens: Long = 0L,
    )

    val grouped = linkedMapOf<String, MutableModelUsage>()
    samples.forEach { sample ->
        val displayName = canonicalModelName(sample.modelName)
        val normalizedName = displayName.lowercase(Locale.ROOT)
        val aggregate = grouped.getOrPut(normalizedName) {
            MutableModelUsage(displayName = displayName)
        }
        if (aggregate.displayName.isBlank() && displayName.isNotBlank()) {
            aggregate.displayName = displayName
        }
        aggregate.requestCount += sample.requestCount
        aggregate.inputTokens += sample.inputTokens.coerceAtLeast(0L)
        aggregate.outputTokens += sample.outputTokens.coerceAtLeast(0L)
    }
    return grouped.values
        .map {
            ModelUsageStat(
                modelName = it.displayName,
                requestCount = it.requestCount,
                inputTokens = it.inputTokens,
                outputTokens = it.outputTokens,
            )
        }
        .sortedWith(
            compareByDescending<ModelUsageStat> { it.totalTokens }
                .thenByDescending { it.requestCount }
                .thenBy { it.modelName.lowercase(Locale.ROOT) },
        )
}

class UsageStatisticsRepository(
    context: Context,
    private val conversationStore: ConversationStore,
) {
    private val appContext = context.applicationContext
    private val tokenizer by lazy { DeepSeekV4Tokenizer.get(appContext) }

    fun calculate(
        period: UsageStatsPeriod,
        anchorAt: Long = System.currentTimeMillis(),
        checkCancelled: () -> Unit = {},
    ): UsageStatsSummary {
        val range = periodRange(period, anchorAt)
        conversationStore.prepareLegacyUsage(range.first, range.second, { tokenizer }, checkCancelled)
        checkCancelled()
        val counts = conversationStore.usageEventCounts(range.first, range.second)
        val samples = conversationStore.usageTokenSamples(range.first, range.second)
        checkCancelled()
        return UsageStatsSummary(
            period = period,
            startAt = range.first,
            endAt = range.second,
            conversationCount = counts.conversations,
            userInputTokens = samples.sumOf { it.inputTokens },
            aiOutputTokens = samples.sumOf { it.outputTokens },
            userMessageCount = counts.users,
            assistantMessageCount = counts.assistants,
            toolMessageCount = counts.tools,
            modelRequestCount = samples.sumOf { it.requestCount },
            modelUsage = aggregateModelUsage(samples),
        )
    }
    private fun periodRange(period: UsageStatsPeriod, anchorAt: Long): Pair<Long, Long> {
        if (period == UsageStatsPeriod.TOTAL) return Long.MIN_VALUE to Long.MAX_VALUE
        val start = Calendar.getInstance().apply {
            timeInMillis = anchorAt
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (period) {
            UsageStatsPeriod.DAY -> Unit
            UsageStatsPeriod.WEEK -> start.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            UsageStatsPeriod.MONTH -> start.set(Calendar.DAY_OF_MONTH, 1)
            UsageStatsPeriod.YEAR -> start.set(Calendar.DAY_OF_YEAR, 1)
            UsageStatsPeriod.TOTAL -> Unit
        }
        val end = start.clone() as Calendar
        when (period) {
            UsageStatsPeriod.DAY -> end.add(Calendar.DAY_OF_YEAR, 1)
            UsageStatsPeriod.WEEK -> end.add(Calendar.WEEK_OF_YEAR, 1)
            UsageStatsPeriod.MONTH -> end.add(Calendar.MONTH, 1)
            UsageStatsPeriod.YEAR -> end.add(Calendar.YEAR, 1)
            UsageStatsPeriod.TOTAL -> Unit
        }
        return start.timeInMillis to end.timeInMillis
    }

}
