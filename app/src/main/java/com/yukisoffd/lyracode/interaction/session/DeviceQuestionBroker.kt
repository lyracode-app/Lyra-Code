package com.yukisoffd.lyracode.interaction.session

import com.yukisoffd.lyracode.ai.UserQuestionRequest
import com.yukisoffd.lyracode.ai.UserQuestionAnswer
import kotlinx.coroutines.CompletableDeferred

internal object DeviceQuestionBroker {
    private var pending: CompletableDeferred<String>? = null
    private var session = 0L
    suspend fun ask(request: UserQuestionRequest): UserQuestionAnswer {
        val reply = CompletableDeferred<String>()
        synchronized(this) { check(pending == null); pending = reply; session = ManualControlController.state.value.sessionId }
        val proposal = DeviceApproval(java.util.UUID.randomUUID().toString(), request.title,
            request.question + request.options.joinToString(separator = "\n", prefix = if (request.options.isEmpty()) "" else "\n") + "\n请在输入框中回复。",
            null, false, textInput = true)
        ManualControlController.setApproval(proposal)
        try { return UserQuestionAnswer(UserQuestionAnswer.STATUS_ANSWERED, freeText = reply.await()) }
        finally {
            synchronized(this) { if (pending === reply) pending = null }
            if (ManualControlController.state.value.approval?.id == proposal.id) ManualControlController.setApproval(null)
        }
    }
    @Synchronized fun answer(text: String): Boolean {
        val reply = pending ?: return false
        val state = ManualControlController.state.value
        if (state.sessionId != session || !state.chat.running) return false
        return reply.complete(text)
    }
    @Synchronized fun cancel() { pending?.cancel(); pending = null }
}
