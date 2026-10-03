package com.yukisoffd.lyracode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class UserQuestionIdleTimeout(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 10L * 60L * 1000L,
    private val onTimeout: (Long) -> Unit,
) {
    private var job: Job? = null
    private var paused = false

    fun reset(id: Long) {
        if (paused) return
        job?.cancel()
        job = scope.launch {
            delay(timeoutMillis)
            job = null
            onTimeout(id)
        }
    }

    fun pause() {
        paused = true
        job?.cancel()
        job = null
    }

    fun resume(id: Long) {
        paused = false
        reset(id)
    }

    fun cancel() {
        job?.cancel()
        job = null
        paused = false
    }
}
