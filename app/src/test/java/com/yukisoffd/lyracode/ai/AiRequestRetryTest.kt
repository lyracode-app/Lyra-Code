package com.yukisoffd.lyracode.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AiRequestRetryTest {
    @Test
    fun template500FailsImmediatelyWithServerMessage() = runBlocking {
        val detail = "Error: Jinja Exception: System message must be at the beginning."
        val body = org.json.JSONObject().put("error", org.json.JSONObject().put("message", detail)).toString()
        var attempts = 0
        val result = runCatching {
            executeModelRequestWithRetry(retryDelayMillis = 0L) {
                attempts++
                throw modelRequestHttpException(500, body, "Request failed")
            }
        }
        assertEquals(1, attempts)
        assertEquals("Request failed HTTP 500: $detail", result.exceptionOrNull()?.message)
        assertFalse(isRetryableModelFailure(result.exceptionOrNull()!!))
    }

    @Test
    fun preservesTransientErrorsAndReadableNonJsonResponses() {
        assertTrue(isRetryableModelFailure(modelRequestHttpException(500, "temporarily unavailable", "Failed")))
        assertTrue(isRetryableModelFailure(modelRequestHttpException(503, "overloaded", "Failed")))
        assertTrue(isRetryableModelFailure(modelRequestHttpException(429, "rate limit", "Failed")))
        assertFalse(isRetryableModelFailure(modelRequestHttpException(400, "bad request", "Failed")))
        assertEquals("plain text", modelHttpErrorDetail("plain text"))
        assertEquals("bad key", modelHttpErrorDetail("{\"error\":\"bad key\"}"))
        assertEquals("invalid", modelHttpErrorDetail("{\"message\":\"invalid\"}"))
        assertEquals(2_000, modelHttpErrorDetail("x".repeat(3_000)).length)
    }

    @Test
    fun classifiesTransientHttpAndNetworkFailures() {
        assertTrue(isRetryableModelHttpStatus(402))
        assertTrue(isRetryableModelHttpStatus(429))
        assertTrue(isRetryableModelHttpStatus(502))
        assertFalse(isRetryableModelHttpStatus(400))
        assertFalse(isRetryableModelHttpStatus(401))
        assertTrue(isRetryableModelFailure(SocketTimeoutException("timeout")))
        assertFalse(isRetryableModelFailure(SSLHandshakeException("bad certificate")))
    }

    @Test
    fun retriesFiveTimesThenThrowsTheLastError() = runBlocking {
        var requestCount = 0
        val retries = mutableListOf<Int>()

        val result = runCatching {
            executeModelRequestWithRetry(
                retryDelayMillis = 0L,
                onRetry = { retryNumber, maxRetries, _ ->
                    assertEquals(MODEL_REQUEST_MAX_RETRIES, maxRetries)
                    retries += retryNumber
                },
            ) {
                requestCount++
                throw RetryableModelHttpException(502, "failure $requestCount")
            }
        }

        assertTrue(result.isFailure)
        assertEquals(MODEL_REQUEST_MAX_RETRIES + 1, requestCount)
        assertEquals((1..MODEL_REQUEST_MAX_RETRIES).toList(), retries)
        assertEquals("failure 6", result.exceptionOrNull()?.message)
        assertTrue(result.exceptionOrNull() is ModelRequestRetriesExhaustedException)
    }

    @Test
    fun returnsSuccessfulRetryResult() = runBlocking {
        var requestCount = 0
        val result = executeModelRequestWithRetry(retryDelayMillis = 0L) {
            requestCount++
            if (requestCount < 3) throw RetryableModelHttpException(429, "busy")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, requestCount)
    }

    @Test
    fun restartedStreamNeverRetractsPreviouslyDisplayedText() {
        assertEquals("partial answer", mergeRetriedStreamText("partial answer", "partial"))
        assertEquals("partial answer continued", mergeRetriedStreamText("partial answer", "partial answer continued"))
        assertEquals(
            "first paragraph continues here",
            mergeRetriedStreamText("first paragraph continues", "paragraph continues here"),
        )
        assertEquals("kept output\n\nnew beginning", mergeRetriedStreamText("kept output", "new beginning"))
    }
}
