package com.yukisoffd.lyracode.interaction.session

import com.yukisoffd.lyracode.QueueDrain

/** A completed device task drains one queued item; failure or cancellation parks. */
internal fun conversationIdleDrain(outcome: ConversationIdleOutcome): QueueDrain =
    if (outcome == ConversationIdleOutcome.COMPLETED) QueueDrain.DELIVER_NEXT else QueueDrain.PARK_ALL

/** Applies the idle decision through the caller's checked delivery and parking paths. */
internal fun dispatchConversationIdle(
    outcome: ConversationIdleOutcome,
    deliverNext: () -> Unit,
    parkAll: () -> Unit,
) {
    when (conversationIdleDrain(outcome)) {
        QueueDrain.DELIVER_NEXT -> deliverNext()
        QueueDrain.PARK_ALL -> parkAll()
    }
}
