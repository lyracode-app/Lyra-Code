package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ToolApprovalDecision
import com.yukisoffd.lyracode.ai.ToolApprovalRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single tool approval dialog slot shared by every conversation.
 *
 * Requests take turns: while one request is shown, later requests wait in [request], in
 * order, and each is shown only after the one before it is answered or its caller is
 * cancelled. [request] suspends until its own request is answered through [answer]. A request
 * for a conversation in [autoApprovedConversations] is approved at once, except `send_email`,
 * which always asks; a waiting request checks this again when its turn comes.
 * [onPendingChanged] receives the request to show, or null when the slot clears.
 *
 * Not thread-safe: call every member from one thread (the main thread in [ChatController]).
 */
internal class ToolApprovalSlot(
    private val autoApprovedConversations: MutableSet<Long>,
    private val onPendingChanged: (PendingToolApproval?) -> Unit,
) {
    private var approvalId = 0L
    private val approvalWaiters = mutableMapOf<Long, CompletableDeferred<ToolApprovalDecision>>()
    private val turn = Mutex()

    /** The request the dialog shows now, or null. */
    var current: PendingToolApproval? = null
        private set

    suspend fun request(request: ToolApprovalRequest): ToolApprovalDecision {
        if (autoApproves(request)) return ToolApprovalDecision.Approved
        return turn.withLock {
            // An answer given while this request waited can approve its conversation.
            if (autoApproves(request)) return@withLock ToolApprovalDecision.Approved
            val id = ++approvalId
            val waiter = CompletableDeferred<ToolApprovalDecision>()
            approvalWaiters[id] = waiter
            show(PendingToolApproval(id, request))
            try {
                waiter.await()
            } finally {
                approvalWaiters.remove(id)
                if (current?.id == id) show(null)
            }
        }
    }

    /**
     * Gives [decision] to the request with [id] and clears the slot, if the slot still shows that
     * request; otherwise does nothing and returns false. The id guards against a stale answer: a
     * fast second tap on a dialog must not answer the next request, which the user has not seen.
     * An approval with `rememberForConversation` approves the rest of that conversation.
     */
    fun answer(id: Long, decision: ToolApprovalDecision): Boolean {
        val pending = current?.takeIf { it.id == id } ?: return false
        // Remember first, so the next waiting request sees the approval when its turn comes.
        if (decision.approved && decision.rememberForConversation && canRemember(pending.request.toolName)) {
            autoApprovedConversations += pending.request.conversationId
        }
        approvalWaiters.remove(pending.id)?.complete(decision)
        show(null)
        return true
    }

    private fun autoApproves(request: ToolApprovalRequest): Boolean =
        canRemember(request.toolName) && request.conversationId in autoApprovedConversations

    /** `send_email` always asks, so a conversation-wide approval never covers it. */
    private fun canRemember(toolName: String): Boolean = toolName != "send_email"

    private fun show(pending: PendingToolApproval?) {
        current = pending
        onPendingChanged(pending)
    }
}
