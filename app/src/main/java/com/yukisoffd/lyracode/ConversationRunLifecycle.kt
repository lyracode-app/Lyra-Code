package com.yukisoffd.lyracode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ConversationRunGate {
    private val locks = mutableMapOf<Long, Mutex>()

    suspend fun <T> run(conversationId: Long, block: suspend () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(conversationId) { Mutex() } }
        // Hold ownership through cancellation cleanup; a cancelled waiter cannot
        // allow another run to overtake the operation still shutting down.
        return lock.withLock { block() }
    }
}

internal fun showContinueConversation(isInterrupted: Boolean, isRunning: Boolean, hasMessages: Boolean): Boolean =
    isInterrupted && !isRunning && hasMessages

/**
 * Creates the conversation job and starts it at once, unless the controller is closed or the
 * job was already cancelled (a launch on a cancelled scope, or a cancel from a completion
 * callback).
 *
 * The job is created [CoroutineStart.LAZY] so that [onLaunched] can register it, and the
 * completion handler can be installed, before it can run: a run that fails on its first
 * dispatch must still report through the registry entry and pair its foreground run. For the
 * same reason the start decision tests [Job.isCancelled]. A lazy job reports
 * `isActive == false` until [Job.start], so testing [Job.isActive] starts no run at all.
 *
 * Registration comes first: a job created on a cancelled scope is already completed, and its
 * completion handler fires while it is installed.
 */
internal fun CoroutineScope.launchConversationRun(
    conversationId: Long,
    gate: ConversationRunGate,
    closed: Boolean,
    onLaunched: (Job) -> Unit,
    onCompleted: (Job, Throwable?) -> Unit,
    onStarted: () -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = launch(start = CoroutineStart.LAZY) { gate.run(conversationId) { block() } }
    onLaunched(job)
    job.invokeOnCompletion { cause -> onCompleted(job, cause) }
    if (!job.isCancelled && !closed) {
        onStarted()
        job.start()
    }
    return job
}
