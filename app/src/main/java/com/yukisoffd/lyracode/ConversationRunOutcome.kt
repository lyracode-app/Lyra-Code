package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.data.ConversationStore
import kotlinx.coroutines.CancellationException

internal sealed interface ConversationRunOutcome {
    data object Completed : ConversationRunOutcome
    data object Cancelled : ConversationRunOutcome
    data class Failed(val error: Throwable?) : ConversationRunOutcome
}

/**
 * Classifies how a conversation run ended. A cancelled run never looks like a
 * failure, and a run that returned normally still counts as failed when the
 * agent swallowed its error and left the conversation interrupted in the store.
 */
internal fun classifyRunOutcome(error: Throwable?, conversationStatus: String?): ConversationRunOutcome {
    if (error is CancellationException) return ConversationRunOutcome.Cancelled
    if (error != null) return ConversationRunOutcome.Failed(error)
    if (conversationStatus == ConversationStore.STATUS_INTERRUPTED) return ConversationRunOutcome.Failed(null)
    return ConversationRunOutcome.Completed
}
