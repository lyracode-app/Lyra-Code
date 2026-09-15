package com.yukisoffd.lyracode.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal const val MODEL_REQUEST_MAX_RETRIES = 5
internal const val MODEL_REQUEST_RETRY_DELAY_MS = 5_000L

internal class RetryableModelHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

internal class ModelRequestRetriesExhaustedException(
    val retryCount: Int,
    cause: Throwable,
) : IOException(cause.message, cause)

internal fun isRetryableModelHttpStatus(statusCode: Int): Boolean =
    statusCode in 500..599 || statusCode in setOf(402, 408, 409, 425, 429)

internal fun modelHttpErrorDetail(body: String): String {
    val root = runCatching { JSONObject(body) }.getOrNull()
    val detail = root?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        ?: (root?.opt("error") as? String)?.takeIf { it.isNotBlank() }
        ?: root?.optString("message")?.takeIf { it.isNotBlank() }
        ?: body
    return detail.trim().take(2_000)
}

internal fun modelRequestHttpException(statusCode: Int, body: String, prefix: String): Exception {
    val message = "$prefix HTTP $statusCode: ${modelHttpErrorDetail(body)}"
    // Some compatible servers report deterministic template/input errors as 500.
    val templateError = listOf(
        "jinja exception", "system message must be at the beginning",
        "roles must alternate", "only supports user and assistant roles",
    ).any { body.contains(it, ignoreCase = true) }
    return if (isRetryableModelHttpStatus(statusCode) && !templateError) {
        RetryableModelHttpException(statusCode, message)
    } else {
        IllegalStateException(message)
    }
}

internal fun isRetryableModelFailure(error: Throwable): Boolean {
    val causes = generateSequence(error as Throwable?) { it.cause }.toList()
    if (causes.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException }) return false
    return causes.any { it is RetryableModelHttpException || it is IOException }
}

internal suspend fun <T> executeModelRequestWithRetry(
    maxRetries: Int = MODEL_REQUEST_MAX_RETRIES,
    retryDelayMillis: Long = MODEL_REQUEST_RETRY_DELAY_MS,
    onRetry: suspend (retryNumber: Int, maxRetries: Int, error: Throwable) -> Unit = { _, _, _ -> },
    request: suspend () -> T,
): T {
    require(maxRetries >= 0)
    require(retryDelayMillis >= 0L)
    var retries = 0
    while (true) {
        try {
            return request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isRetryableModelFailure(error)) throw error
            if (retries >= maxRetries) throw ModelRequestRetriesExhaustedException(maxRetries, error)
            retries++
            onRetry(retries, maxRetries, error)
            delay(retryDelayMillis)
        }
    }
}

internal fun mergeRetriedStreamText(previous: String, restarted: String): String {
    if (previous.isEmpty()) return restarted
    if (restarted.isEmpty()) return previous
    if (restarted.startsWith(previous)) return restarted
    if (previous.startsWith(restarted)) return previous
    val overlapLimit = minOf(previous.length, restarted.length)
    for (length in overlapLimit downTo MIN_RETRY_STREAM_OVERLAP) {
        if (previous.regionMatches(previous.length - length, restarted, 0, length)) {
            return previous + restarted.substring(length)
        }
    }
    return previous.trimEnd() + "\n\n" + restarted.trimStart()
}

private const val MIN_RETRY_STREAM_OVERLAP = 12
