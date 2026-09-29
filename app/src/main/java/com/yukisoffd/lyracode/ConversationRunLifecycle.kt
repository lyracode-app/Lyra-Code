package com.yukisoffd.lyracode

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
