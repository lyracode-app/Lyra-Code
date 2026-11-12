package com.yukisoffd.lyracode.chat.pending

/**
 * Startup sweep for persisted queues. Runs off the main thread.
 *
 * - A valid document for an existing conversation is loaded parked; [onRestored] runs on the
 *   calling thread so the caller can check attachment files before publishing UI flags.
 * - A valid document for a deleted conversation is swept through [PendingInputStore.delete];
 *   [onSwept] receives its attachment candidates for the IO reference check.
 * - Corrupt or unknown-version documents are quarantined, never deleted. Their raw text keeps
 *   attachment references alive for [PendingInputFileStore.isAttachmentReferencedElsewhere].
 * - A transient read failure leaves the document untouched.
 *
 * A failing [conversationExists] is treated as existing so a database error cannot delete queues.
 */
internal class PendingInputRestorer(
    private val fileStore: PendingInputFileStore,
    private val store: PendingInputStore,
    private val conversationExists: (Long) -> Boolean,
    private val onRestored: (Long) -> Unit = {},
    private val onSwept: (Long, List<PendingAttachment>) -> Unit = { _, _ -> },
) {
    fun restoreAll() {
        fileStore.allConversationIds().sorted().forEach { conversationId ->
            when (val document = fileStore.readQueueDocument(conversationId)) {
                PendingQueueDocument.Missing -> Unit
                is PendingQueueDocument.Unreadable -> Unit
                is PendingQueueDocument.Present -> {
                    if (decodePendingInputQueue(conversationId, document.raw).isEmpty()) {
                        // Quarantine before any conversation-existence decision: a corrupt
                        // document must never be deleted merely because its conversation is gone.
                        fileStore.quarantineUnreadableQueue(conversationId, document.raw)
                    } else {
                        val exists = runCatching { conversationExists(conversationId) }.getOrDefault(true)
                        if (exists) {
                            store.load(conversationId)
                            onRestored(conversationId)
                        } else {
                            onSwept(conversationId, store.delete(conversationId))
                        }
                    }
                }
            }
        }
    }
}
