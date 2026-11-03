package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ToolApprovalDecision
import com.yukisoffd.lyracode.ai.ToolApprovalRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * The single tool approval dialog slot shared by every conversation.
 *
 * [request] suspends until the shown request is answered through [answer]. A request for a
 * conversation in [autoApprovedConversations] is approved at once, except `send_email`, which
 * always asks. [onPendingChanged] receives the request to show, or null when the slot clears.
 *
 * Not thread-safe: call every member from one thread (the main thread in [ChatController]).
 */
internal class ToolApprovalSlot(
    private val autoApprovedConversations: MutableSet<Long>,
    private val onPendingChanged: (PendingToolApproval?) -> Unit,
) {
    private var approvalId = 0L
    private val approvalWaiters = mutableMapOf<Long, CompletableDeferred<ToolApprovalDecision>>()

    /** The request the dialog shows now, or null. */
    var current: PendingToolApproval? = null
        private set

    suspend fun request(request: ToolApprovalRequest): ToolApprovalDecision {
        if (autoApproves(request)) return ToolApprovalDecision.Approved
        val id = ++approvalId
        val waiter = CompletableDeferred<ToolApprovalDecision>()
        approvalWaiters[id] = waiter
        show(PendingToolApproval(id, request))
        return waiter.await()
    }

    /**
     * Gives [decision] to the caller that waits on [pending] and clears the slot if it shows
     * [pending]. An approval with `rememberForConversation` approves the rest of that conversation.
     */
    fun answer(pending: PendingToolApproval, decision: ToolApprovalDecision) {
        approvalWaiters.remove(pending.id)?.complete(decision)
        if (decision.approved && decision.rememberForConversation && canRemember(pending.request.toolName)) {
            autoApprovedConversations += pending.request.conversationId
        }
        if (current?.id == pending.id) show(null)
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
