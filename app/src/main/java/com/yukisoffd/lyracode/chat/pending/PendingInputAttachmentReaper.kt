package com.yukisoffd.lyracode.chat.pending

import java.util.concurrent.Executor

/**
 * Deletes attachments that no outside holder references. Runs on the injected executor because
 * the check scans delivered messages and every persisted queue document. Any checker failure
 * keeps every candidate file; deletion is never the fallback for an error.
 */
internal class PendingInputAttachmentReaper(
    private val fileStore: PendingInputFileStore,
    private val executor: Executor,
) {
    fun reap(conversationId: Long, candidates: List<PendingAttachment>) {
        if (candidates.isEmpty()) return
        runCatching {
            executor.execute {
                runCatching {
                    candidates.distinctBy { it.uri }
                        .filterNot { fileStore.isAttachmentReferencedElsewhere(conversationId, it) }
                        .forEach { fileStore.deleteAttachment(it) }
                }
            }
        }
    }
}
