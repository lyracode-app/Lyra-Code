package com.yukisoffd.lyracode

/**
 * The action the composer's send control should take for the current draft.
 *
 * Kept free of Android and Compose types so the decision can be unit tested.
 */
internal enum class ComposerSendAction { SEND, QUEUE, BLOCKED }

/**
 * Mirrors the composer's existing "can send" rule: text must be non-blank, or at
 * least one attachment or workspace file must be selected. Forced skills alone
 * do not make a draft sendable, so they are deliberately absent from the inputs.
 *
 * Ticket #7 must replace the duplicated inline rule at ChatScreen.kt:270 with
 * this helper when it wires the composer.
 */
internal fun composerHasSendableContent(
    text: String,
    attachmentCount: Int,
    workspaceFileCount: Int,
): Boolean = text.isNotBlank() || attachmentCount > 0 || workspaceFileCount > 0

/**
 * Whether a parked queue row may offer its manual "send now" action: only when
 * no run is active. Pure so ticket #7 can share the idleness gate.
 */
internal fun composerCanSendParked(runActive: Boolean): Boolean = !runActive

/** The per-message action a queue row exposes. */
internal enum class ComposerQueueRowAction { NONE, SEND_NOW, STEER }

/**
 * While a run is active, a queue row's action steers that message into the run instead of
 * waiting for the queue drain. Steering carries text only, so an item with attachments keeps
 * its place in the queue and exposes no steer action. An idle conversation can only send a
 * parked item manually; a queued item is already owned by the drain.
 */
internal fun composerQueueRowAction(
    state: ComposerQueueItemState,
    runActive: Boolean,
    steerAccepted: Boolean,
    attachmentCount: Int,
): ComposerQueueRowAction = when {
    runActive && steerAccepted && attachmentCount == 0 -> ComposerQueueRowAction.STEER
    runActive -> ComposerQueueRowAction.NONE
    state == ComposerQueueItemState.PARKED -> ComposerQueueRowAction.SEND_NOW
    else -> ComposerQueueRowAction.NONE
}

/**
 * Decides what the composer send action does.
 *
 * Precedence: no content is always blocked; an idle composer sends; a mid-run
 * send with the feature off is blocked; otherwise a mid-run send queues.
 * Steering is a per-queued-message action in the queue dock, not a composer mode.
 */
internal fun composerSendAction(
    hasSendableContent: Boolean,
    runActive: Boolean,
    featureEnabled: Boolean,
): ComposerSendAction = when {
    !hasSendableContent -> ComposerSendAction.BLOCKED
    !runActive -> ComposerSendAction.SEND
    !featureEnabled -> ComposerSendAction.BLOCKED
    else -> ComposerSendAction.QUEUE
}
