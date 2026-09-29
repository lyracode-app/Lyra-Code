package com.yukisoffd.lyracode.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import java.io.BufferedReader

internal const val MODEL_RESPONSE_READ_TIMEOUT_SECONDS = 120L

/** Cancellation must close the socket even while execute() or a body read is blocked. */
internal suspend inline fun <T> Call.useModelResponse(block: (Response) -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val cancellation = CoroutineScope(context).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            this@useModelResponse.cancel()
        }
    }
    try {
        return execute().use { response ->
            context.ensureActive()
            block(response)
        }
    } catch (error: Exception) {
        context.ensureActive()
        throw error
    } finally {
        cancellation.cancel()
    }
}

/** A terminal SSE event ends the response without waiting for the peer to close TCP. */
internal suspend inline fun BufferedReader.consumeModelStream(onLine: (String) -> Boolean) {
    use {
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = readLine() ?: break
            if (!onLine(line)) break
        }
    }
}
