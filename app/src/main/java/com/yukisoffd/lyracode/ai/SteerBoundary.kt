package com.yukisoffd.lyracode.ai

/**
 * Source of already-composed steer texts for an active agent run.
 *
 * [drain] is synchronous and conversation-scoped: it atomically removes and returns every queued
 * steer text in order. The common case returns an empty list and must have no side effects.
 */
internal fun interface SteerSource {
    fun drain(conversationId: Long): List<String>
}

internal object NoOpSteerSource : SteerSource {
    override fun drain(conversationId: Long): List<String> = emptyList()
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
