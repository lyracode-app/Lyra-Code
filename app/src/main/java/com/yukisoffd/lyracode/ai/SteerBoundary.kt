package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.chat.pending.PendingInputCapture

/**
 * Delivers captured requests to an active run at a safe boundary.
 * Each item is withdrawn from the waiting list after the synchronous insert succeeds.
 */
internal fun interface SteerSource {
    fun drain(conversationId: Long, insert: (PendingInputCapture) -> Unit)
}

internal object NoOpSteerSource : SteerSource {
    override fun drain(conversationId: Long, insert: (PendingInputCapture) -> Unit) = Unit
}

internal enum class RunBoundary { Continue, Return }

/** Scoped (foreground/sub-agent) runs own their conversation, so steering is refused inside them. */
internal fun steeringAvailable(hasScopedTools: Boolean): Boolean = !hasScopedTools

/**
 * Decides whether the agent loop runs another round after a model turn.
 *
 * Today the only reasons to continue are pending tool calls and pending steer text. With no steer
 * queued this preserves the previous behavior exactly: a turn with no tool calls returns.
 */
internal fun runBoundaryAfterTurn(hasToolCalls: Boolean, hasPendingSteer: Boolean): RunBoundary =
    if (hasToolCalls || hasPendingSteer) RunBoundary.Continue else RunBoundary.Return
