package com.yukisoffd.lyracode.ai



data class ChatRecord(
    val id: Long = 0L,
    val role: String,
    val content: String,
    val thinking: String = "",
    val profileId: String = "",
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val tokensPerSecond: Double = 0.0,
    val deepSeekCacheHitRate: Double? = null,
    val toolCallId: String? = null,
    val rawJson: String? = null,
    val toolName: String = "",
    val toolInput: String = "",
)

data class ChatUpdate(
    val content: String,
    val thinking: String,
    val status: String,
    val messageId: Long = 0L,
    val tokensPerSecond: Double = 0.0,
    val deepSeekCacheHitRate: Double? = null,
    /** Asks the controller to reload history immediately, bypassing the throttle. */
    val forceReload: Boolean = false,
)


data class ModelReachabilityResult(
    val model: String,
    val available: Boolean,
    val latencyMs: Long,
    val message: String,
    val statusCode: Int? = null,
)

data class ProviderReachabilityResult(
    val available: Boolean,
    val latencyMs: Long,
    val message: String,
    val statusCode: Int? = null,
)

data class ProviderReachabilityReport(
    val providerAvailable: Boolean,
    val providerLatencyMs: Long,
    val providerMessage: String,
    val modelResults: List<ModelReachabilityResult>,
)
