package com.yukisoffd.lyracode.chat.pending

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

internal enum class PendingInputState {
    QUEUED,
    PARKED,
}

/**
 * Display metadata only. Attachment content is never captured or persisted here. Backing-file
 * existence is computed by the wiring when a queue is loaded, not by this pure store.
 */
internal data class PendingAttachment(
    val name: String,
    val kind: String,
    val mimeType: String,
    val uri: String,
    val size: Int = 0,
)

/** Passive capture of an already-composed user turn; composition belongs to the send path. */
internal data class PendingInputCapture(
    val rawText: String,
    val composedInput: String,
    val attachments: List<PendingAttachment> = emptyList(),
    val forcedSkillIds: List<String> = emptyList(),
)

internal data class PendingInputItem(
    val id: String,
    val conversationId: Long,
    val capture: PendingInputCapture,
    val createdAt: Long,
    val state: PendingInputState,
)

internal data class PendingSteer(
    val id: String,
    val conversationId: Long,
    val capture: PendingInputCapture,
    val createdAt: Long,
)

/**
 * [missingAttachmentUris] is derived state, not persisted. The wiring fills it when it loads a
 * queue on an IO thread; recomposition only reads the set.
 */
internal data class PendingInputSnapshot(
    val conversationId: Long,
    val queue: List<PendingInputItem> = emptyList(),
    val steers: List<PendingSteer> = emptyList(),
    val missingAttachmentUris: Set<String> = emptySet(),
) {
    val queuedCount: Int get() = queue.count { it.state == PendingInputState.QUEUED }
    val parkedCount: Int get() = queue.count { it.state == PendingInputState.PARKED }

    /** Attachments waiting in the queue channel; transient steers are not counted. */
    val attachmentCount: Int get() = queue.sumOf { it.capture.attachments.size }
}

/**
 * [orphanedAttachments] are candidates that no retained in-memory item references. The caller
 * must still run the external reference check (other persisted queues, quarantined documents,
 * delivered messages) before deleting them. That check belongs on an IO executor.
 */
internal data class PendingInputRemoval(
    val item: PendingInputItem,
    val orphanedAttachments: List<PendingAttachment>,
)

/**
 * Owns queue and steer channels per conversation. Queue changes publish the new snapshot
 * synchronously and pass the encoded document to the injected [persistenceExecutor]; in-memory
 * UI state never waits for disk. Steers are transient and never persisted.
 *
 * A read failure is sticky for the life of this store instance: later mutations still publish,
 * but are not written, so a transient read failure followed by [flush] cannot replace a valid
 * document with an empty one.
 *
 * With no [persistenceExecutor], writes run inline. Tests use that form; production supplies a
 * single-thread executor so writes stay ordered and off the main thread.
 */
internal class PendingInputStore(
    private val readQueue: (Long) -> String?,
    private val writeQueue: (Long, String?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val persistenceExecutor: Executor? = null,
    private val onPersistenceFailure: (Throwable) -> Unit = {},
) {
    private val mutableSnapshots = MutableStateFlow<Map<Long, PendingInputSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<Long, PendingInputSnapshot>> = mutableSnapshots.asStateFlow()
    private val failedReads = ConcurrentHashMap.newKeySet<Long>()

    /** Idempotent restore. Persisted items come back parked; a second call keeps live state. */
    @Synchronized
    fun load(conversationId: Long): PendingInputSnapshot = ensureLoaded(conversationId)

    /** Current state, restoring from storage on first access. */
    @Synchronized
    fun snapshot(conversationId: Long): PendingInputSnapshot = ensureLoaded(conversationId)

    @Synchronized
    fun enqueue(
        conversationId: Long,
        capture: PendingInputCapture,
        state: PendingInputState = PendingInputState.QUEUED,
    ): PendingInputItem {
        val item = PendingInputItem(
            id = newId(),
            conversationId = conversationId,
            capture = capture,
            createdAt = now(),
            state = state,
        )
        val current = ensureLoaded(conversationId)
        persistAndPublish(conversationId, current.copy(queue = current.queue + item))
        return item
    }

    @Synchronized
    fun enqueueSteer(conversationId: Long, capture: PendingInputCapture): PendingSteer {
        val steer = PendingSteer(
            id = newId(),
            conversationId = conversationId,
            capture = capture,
            createdAt = now(),
        )
        val current = ensureLoaded(conversationId)
        // Steers are transient and never encoded.
        publish(conversationId, current.copy(steers = current.steers + steer))
        return steer
    }

    /**
     * Removes one queue item and reports attachment files that no retained in-memory item
     * references. External holders are checked by the caller on IO.
     */
    @Synchronized
    fun remove(conversationId: Long, itemId: String): PendingInputRemoval? {
        val current = ensureLoaded(conversationId)
        val item = current.queue.firstOrNull { it.id == itemId } ?: return null
        val remaining = current.copy(queue = current.queue.filterNot { it.id == itemId })
        val referenced = referencedAttachmentUris(remaining) +
            referencedAttachmentUris(excluding = conversationId)
        val orphaned = item.capture.attachments
            .asSequence()
            .filter { it.uri.isNotBlank() && it.uri !in referenced }
            .distinctBy { it.uri }
            .toList()
        persistAndPublish(conversationId, remaining)
        return PendingInputRemoval(item, orphaned)
    }

    /**
     * Manual send, including of a parked item. Delivery adopts the attachments, so nothing is
     * reported as orphaned. Never selects an item implicitly.
     */
    @Synchronized
    fun takeForDelivery(conversationId: Long, itemId: String): PendingInputItem? {
        val current = ensureLoaded(conversationId)
        val item = current.queue.firstOrNull { it.id == itemId } ?: return null
        persistAndPublish(conversationId, current.copy(queue = current.queue.filterNot { it.id == itemId }))
        return item
    }

    /**
     * Converts one queue item into a transient steer in one step, so no window exists where
     * the item is neither queued nor steerable. The queue document is rewritten without the
     * item; the steer itself is never persisted.
     */
    @Synchronized
    fun takeForSteering(conversationId: Long, itemId: String): PendingSteer? {
        val current = ensureLoaded(conversationId)
        val item = current.queue.firstOrNull { it.id == itemId } ?: return null
        val steer = PendingSteer(
            id = newId(),
            conversationId = conversationId,
            capture = item.capture,
            createdAt = now(),
        )
        persistAndPublish(
            conversationId,
            current.copy(
                queue = current.queue.filterNot { it.id == itemId },
                steers = current.steers + steer,
            ),
        )
        return steer
    }

    /** Normal completion path: exactly one queued item, in FIFO order. Parked items never qualify. */
    @Synchronized
    fun deliverNextQueued(conversationId: Long): PendingInputItem? {
        val current = ensureLoaded(conversationId)
        val item = current.queue.firstOrNull { it.state == PendingInputState.QUEUED } ?: return null
        persistAndPublish(conversationId, current.copy(queue = current.queue.filterNot { it.id == item.id }))
        return item
    }

    /** Stop, cancellation, and failure park every queued item. Parked state is not encoded. */
    @Synchronized
    fun parkAllQueued(conversationId: Long) {
        val current = ensureLoaded(conversationId)
        if (current.queue.none { it.state == PendingInputState.QUEUED }) return
        persistAndPublish(
            conversationId,
            current.copy(
                queue = current.queue.map { item ->
                    if (item.state == PendingInputState.QUEUED) item.copy(state = PendingInputState.PARKED) else item
                },
            ),
        )
    }

    /** Archiving keeps the items and parks them; it does not authorize automatic delivery. */
    @Synchronized
    fun archive(conversationId: Long) {
        parkAllQueued(conversationId)
    }

    /**
     * Discards the conversation's queue and steers, deletes its persisted document, and reports
     * attachments that no retained in-memory item still references. External holders are checked
     * by the caller on IO.
     */
    @Synchronized
    fun delete(conversationId: Long): List<PendingAttachment> {
        val current = ensureLoaded(conversationId)
        val referenced = referencedAttachmentUris(excluding = conversationId)
        val orphaned = (current.queue.flatMap { it.capture.attachments } +
            current.steers.flatMap { it.capture.attachments })
            .asSequence()
            .filter { it.uri.isNotBlank() && it.uri !in referenced }
            .distinctBy { it.uri }
            .toList()
        writeThrough(conversationId, null)
        mutableSnapshots.value = mutableSnapshots.value - conversationId
        return orphaned
    }

    /** Removes and returns the conversation's steers in arrival order. */
    @Synchronized
    fun drainSteers(conversationId: Long): List<PendingSteer> {
        val current = ensureLoaded(conversationId)
        if (current.steers.isEmpty()) return emptyList()
        publish(conversationId, current.copy(steers = emptyList()))
        return current.steers
    }

    @Synchronized
    fun discardSteers(conversationId: Long) {
        val current = ensureLoaded(conversationId)
        if (current.steers.isEmpty()) return
        publish(conversationId, current.copy(steers = emptyList()))
    }

    /**
     * Replaces the derived missing-attachment set without touching the document. Called on IO
     * after a load or restore has checked backing files.
     */
    @Synchronized
    fun updateMissingAttachments(conversationId: Long, missingUris: Set<String>) {
        val current = mutableSnapshots.value[conversationId] ?: return
        if (current.missingAttachmentUris == missingUris) return
        publish(conversationId, current.copy(missingAttachmentUris = missingUris))
    }

    private fun ensureLoaded(conversationId: Long): PendingInputSnapshot =
        mutableSnapshots.value[conversationId] ?: restore(conversationId)

    private fun restore(conversationId: Long): PendingInputSnapshot {
        val read = runCatching { readQueue(conversationId) }
        if (read.isFailure) failedReads += conversationId
        val snapshot = PendingInputSnapshot(
            conversationId = conversationId,
            queue = decodePendingInputQueue(conversationId, read.getOrNull()),
        )
        publish(conversationId, snapshot)
        return snapshot
    }

    private fun persistAndPublish(conversationId: Long, snapshot: PendingInputSnapshot) {
        publish(conversationId, snapshot)
        writeThrough(conversationId, encodeQueue(conversationId, snapshot))
    }

    private fun writeThrough(conversationId: Long, encoded: String?) {
        if (conversationId in failedReads) return
        val task = { writeQueue(conversationId, encoded) }
        val executor = persistenceExecutor
        if (executor == null) {
            task()
        } else {
            runCatching {
                executor.execute {
                    runCatching(task).onFailure(onPersistenceFailure)
                }
            }.onFailure(onPersistenceFailure)
        }
    }

    private fun encodeQueue(conversationId: Long, snapshot: PendingInputSnapshot): String? =
        snapshot.queue.takeIf { it.isNotEmpty() }?.let { encodePendingInputQueue(conversationId, it) }

    private fun publish(conversationId: Long, snapshot: PendingInputSnapshot) {
        mutableSnapshots.value = mutableSnapshots.value + (conversationId to snapshot)
    }

    private fun referencedAttachmentUris(snapshot: PendingInputSnapshot): Set<String> = buildSet {
        snapshot.queue.forEach { item -> item.capture.attachments.mapTo(this) { it.uri } }
        snapshot.steers.forEach { steer -> steer.capture.attachments.mapTo(this) { it.uri } }
    }

    private fun referencedAttachmentUris(excluding: Long): Set<String> = buildSet {
        mutableSnapshots.value.forEach { (conversationId, snapshot) ->
            if (conversationId != excluding) addAll(referencedAttachmentUris(snapshot))
        }
    }

    /**
     * Re-persists every loaded conversation after earlier scheduled writes. When a scheduler is
     * present, this submits a barrier and waits for it, so callers (close/flush) know that every
     * write queued before the call has completed. Conversations whose initial read failed are
     * skipped so a transient failure cannot overwrite a valid document.
     */
    fun flush() {
        val writeAll = {
            var firstFailure: Throwable? = null
            mutableSnapshots.value
                .filterKeys { it !in failedReads }
                .forEach { (conversationId, snapshot) ->
                    runCatching { writeQueue(conversationId, encodeQueue(conversationId, snapshot)) }
                        .onFailure { error -> if (firstFailure == null) firstFailure = error }
                }
            firstFailure?.let { throw it }
        }
        val executor = persistenceExecutor
        when (executor) {
            null -> writeAll()
            is ExecutorService -> executor.submit(Runnable { writeAll() }).get()
            else -> {
                val done = CountDownLatch(1)
                executor.execute {
                    try {
                        writeAll()
                    } finally {
                        done.countDown()
                    }
                }
                done.await()
            }
        }
    }
}
