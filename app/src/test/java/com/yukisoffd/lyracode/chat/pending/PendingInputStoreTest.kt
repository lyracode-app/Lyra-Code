package com.yukisoffd.lyracode.chat.pending

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInputStoreTest {
    private var nextId = 0

    @Test
    fun enqueueCapturesInputAndAppendsInOrder() {
        val storage = FakeStorage()
        var time = 100L
        val store = store(storage, now = { time }, newId = { "item-${++nextId}" })

        val first = store.enqueue(7L, capture(rawText = "first", composedInput = "one"))
        time = 200L
        val second = store.enqueue(
            7L,
            capture(
                rawText = "second",
                composedInput = "two",
                attachments = listOf(attachment("a.png")),
                forcedSkillIds = listOf("skill-a"),
            ),
        )

        val snapshot = store.snapshot(7L)
        assertEquals(listOf(first, second), snapshot.queue)
        assertEquals(2, snapshot.queuedCount)
        assertEquals(0, snapshot.parkedCount)
        assertEquals(1, snapshot.attachmentCount)
        assertTrue(snapshot.steers.isEmpty())
        assertEquals(100L, first.createdAt)
        assertEquals(200L, second.createdAt)
        assertEquals(PendingInputState.QUEUED, first.state)
        assertEquals(PendingInputState.QUEUED, second.state)
        assertEquals("second", second.capture.rawText)
        assertEquals("two", second.capture.composedInput)
        assertEquals(listOf("skill-a"), second.capture.forcedSkillIds)
        assertEquals(1, second.capture.attachments.single().size)
        assertEquals(snapshot, store.snapshots.value[7L])
        assertNotNull(storage.documents[7L])
    }

    @Test
    fun conversationsHaveIsolatedQueuesAndSteers() {
        val storage = FakeStorage()
        val store = store(storage)

        val one = store.enqueue(1L, capture(rawText = "one"))
        val two = store.enqueue(2L, capture(rawText = "two"))
        store.enqueueSteer(1L, capture(rawText = "steer one"))

        assertEquals(listOf(one), store.snapshot(1L).queue)
        assertEquals(listOf(two), store.snapshot(2L).queue)
        assertEquals(1, store.snapshot(1L).steers.size)
        assertTrue(store.snapshot(2L).steers.isEmpty())

        store.remove(1L, one.id)
        assertEquals(listOf(two), store.snapshot(2L).queue)
        assertTrue(store.snapshot(1L).queue.isEmpty())
        assertNotNull(storage.documents[2L])
        assertNull(storage.documents[1L])
    }

    @Test
    fun removeReportsOnlyAttachmentsThatNoOtherItemOrSteerUses() {
        val storage = FakeStorage()
        val store = store(storage)
        val shared = attachment("shared.txt", uri = "/uploads/shared.txt")
        val unique = attachment("unique.txt", uri = "/uploads/unique.txt")
        val steered = attachment("steered.txt", uri = "/uploads/steered.txt")

        val first = store.enqueue(1L, capture(attachments = listOf(shared, unique, steered)))
        val second = store.enqueue(1L, capture(attachments = listOf(shared)))
        store.enqueueSteer(1L, capture(attachments = listOf(steered)))

        val removal = store.remove(1L, first.id)
        assertNotNull(removal)
        assertEquals(first, removal!!.item)
        assertEquals(listOf(unique), removal.orphanedAttachments)
        assertEquals(listOf(second), store.snapshot(1L).queue)
        assertNull(store.remove(1L, first.id))
        assertNull(store.remove(99L, second.id))
    }

    @Test
    fun duplicateAttachmentReferencesOnARemovedItemAreReportedOnce() {
        val storage = FakeStorage()
        val store = store(storage)
        val first = store.enqueue(
            1L,
            capture(
                attachments = listOf(
                    attachment("same.txt", uri = "/uploads/same.txt"),
                    attachment("same-again.txt", uri = "/uploads/same.txt"),
                ),
            ),
        )

        assertEquals(listOf(attachment("same.txt", uri = "/uploads/same.txt")), store.remove(1L, first.id)!!.orphanedAttachments)
    }

    @Test
    fun normalCompletionDeliversOneQueuedItemAtATime() {
        val storage = FakeStorage()
        val store = store(storage)
        val first = store.enqueue(1L, capture(rawText = "first"))
        val second = store.enqueue(1L, capture(rawText = "second"))

        assertEquals(first, store.deliverNextQueued(1L))
        assertEquals(listOf(second), store.snapshot(1L).queue)
        assertEquals(second, store.deliverNextQueued(1L))
        assertTrue(store.snapshot(1L).queue.isEmpty())
        assertNull(store.deliverNextQueued(1L))
        assertNull(storage.documents[1L])
    }

    @Test
    fun parkedItemsAreNeverAutoDeliveredButCanBeTakenManually() {
        val storage = FakeStorage()
        val store = store(storage)
        val parked = store.enqueue(1L, capture(rawText = "parked"), PendingInputState.PARKED)
        val queued = store.enqueue(1L, capture(rawText = "queued"))

        assertEquals(queued, store.deliverNextQueued(1L))
        assertNull(store.deliverNextQueued(1L))
        assertEquals(listOf(parked), store.snapshot(1L).queue)
        assertEquals(0, store.snapshot(1L).queuedCount)
        assertEquals(1, store.snapshot(1L).parkedCount)

        assertEquals(parked, store.takeForDelivery(1L, parked.id))
        assertTrue(store.snapshot(1L).queue.isEmpty())
        assertNull(store.takeForDelivery(1L, "missing"))
        assertNull(store.takeForDelivery(2L, parked.id))
    }

    @Test
    fun convertingAQueueItemToSteerRemovesItFromThePersistedQueue() {
        val storage = FakeStorage()
        val store = store(storage, now = { 50L })
        val steered = store.enqueue(1L, capture(rawText = "steer me"))
        val parked = store.enqueue(1L, capture(rawText = "parked"), PendingInputState.PARKED)
        val kept = store.enqueue(1L, capture(rawText = "keep me"))

        val steer = store.takeForSteering(1L, steered.id)
        assertEquals(steered.capture, steer?.capture)
        assertEquals(50L, steer?.createdAt)
        assertEquals(listOf(parked, kept), store.snapshot(1L).queue)
        assertEquals(listOf(steer), store.snapshot(1L).steers)

        val persisted = storage.documents[1L].orEmpty()
        assertFalse(persisted.contains("steer me"))
        assertTrue(persisted.contains("keep me"))

        assertEquals(parked.capture, store.takeForSteering(1L, parked.id)?.capture)
        assertEquals(listOf(kept), store.snapshot(1L).queue)
        assertEquals(2, store.snapshot(1L).steers.size)
        assertNull(store.takeForSteering(1L, "missing"))
        assertNull(store.takeForSteering(2L, kept.id))
    }

    @Test
    fun stopParksEveryQueuedItemForOnlyThatConversation() {
        val storage = FakeStorage()
        val store = store(storage)
        val first = store.enqueue(1L, capture(rawText = "first"))
        val second = store.enqueue(1L, capture(rawText = "second"))
        val other = store.enqueue(2L, capture(rawText = "other"))

        store.parkAllQueued(1L)

        val parked = store.snapshot(1L)
        assertEquals(listOf(first.id, second.id), parked.queue.map { it.id })
        assertTrue(parked.queue.all { it.state == PendingInputState.PARKED })
        assertEquals(0, parked.queuedCount)
        assertEquals(2, parked.parkedCount)
        assertNull(store.deliverNextQueued(1L))
        assertEquals(PendingInputState.QUEUED, store.snapshot(2L).queue.single().state)
        assertEquals(other, store.deliverNextQueued(2L))

        store.parkAllQueued(1L)
        assertTrue(store.snapshot(1L).queue.all { it.state == PendingInputState.PARKED })
        store.parkAllQueued(99L)
        assertTrue(store.snapshot(99L).queue.isEmpty())
    }

    @Test
    fun restoredItemsAreParkedAndNeverAutoDelivered() {
        val storage = FakeStorage()
        val firstStore = store(storage, now = { 10L })
        val first = firstStore.enqueue(4L, capture(rawText = "first"))
        val second = firstStore.enqueue(4L, capture(rawText = "second"))

        val restoredStore = store(storage, now = { 20L })
        val snapshot = restoredStore.load(4L)

        assertEquals(2, snapshot.parkedCount)
        assertEquals(0, snapshot.queuedCount)
        assertEquals(listOf(first.id, second.id), snapshot.queue.map { it.id })
        assertTrue(snapshot.queue.all { it.state == PendingInputState.PARKED })
        assertNull(restoredStore.deliverNextQueued(4L))
        assertEquals(first.id, restoredStore.takeForDelivery(4L, first.id)?.id)
    }

    @Test
    fun attachmentOnlyCaptureRestoresAsParked() {
        val storage = FakeStorage()
        val markerOnly = "<lyra_attachment_v1>{}</lyra_attachment_v1>"
        val firstStore = store(storage)
        val item = firstStore.enqueue(4L, capture(rawText = "", composedInput = markerOnly))

        val restored = store(storage).load(4L)

        assertEquals(listOf(item.id), restored.queue.map { it.id })
        assertEquals(PendingInputState.PARKED, restored.queue.single().state)
        assertEquals("", restored.queue.single().capture.rawText)
        assertEquals(markerOnly, restored.queue.single().capture.composedInput)
    }

    @Test
    fun enqueueBeforeLoadPreservesPersistedItems() {
        val storage = FakeStorage()
        val firstStore = store(storage, now = { 1L })
        val persisted = firstStore.enqueue(3L, capture(rawText = "persisted"))

        val secondStore = store(storage, now = { 2L })
        val added = secondStore.enqueue(3L, capture(rawText = "added"))

        val snapshot = secondStore.snapshot(3L)
        assertEquals(listOf(persisted.id, added.id), snapshot.queue.map { it.id })
        assertEquals(PendingInputState.PARKED, snapshot.queue.first().state)
        assertEquals(PendingInputState.QUEUED, snapshot.queue.last().state)
    }

    @Test
    fun deleteDiscardsItemsAndReportsAttachmentsNotUsedElsewhere() {
        val storage = FakeStorage()
        val store = store(storage)
        val unique = attachment("unique.txt", uri = "/uploads/unique.txt")
        val shared = attachment("shared.txt", uri = "/uploads/shared.txt")
        val steered = attachment("steered.txt", uri = "/uploads/steered.txt")

        store.enqueue(1L, capture(attachments = listOf(unique, shared)))
        store.enqueueSteer(1L, capture(attachments = listOf(steered)))
        store.enqueue(2L, capture(attachments = listOf(shared)))

        val orphaned = store.delete(1L)

        assertEquals(listOf(unique, steered), orphaned)
        assertNotNull(storage.documents[2L])
        assertNull(storage.documents[1L])
        assertFalse(store.snapshots.value.containsKey(1L))
        assertTrue(store.snapshot(1L).queue.isEmpty())
        assertTrue(store.snapshot(1L).steers.isEmpty())
        assertEquals(1, store.snapshot(2L).queue.size)
    }

    @Test
    fun archiveParksQueuedItemsWithoutDiscardingThem() {
        val storage = FakeStorage()
        val store = store(storage)
        val first = store.enqueue(1L, capture(rawText = "first"))
        val second = store.enqueue(1L, capture(rawText = "second"), PendingInputState.PARKED)

        store.archive(1L)

        val snapshot = store.snapshot(1L)
        assertEquals(listOf(first.id, second.id), snapshot.queue.map { it.id })
        assertTrue(snapshot.queue.all { it.state == PendingInputState.PARKED })
        assertNull(store.deliverNextQueued(1L))
        assertEquals(2, decodePendingInputQueue(1L, storage.documents[1L]).size)
    }

    @Test
    fun steersAreConversationScopedTransientAndDrainedInOrder() {
        val storage = FakeStorage()
        val store = store(storage)
        val first = store.enqueueSteer(1L, capture(rawText = "one"))
        val second = store.enqueueSteer(1L, capture(rawText = "two"))
        val other = store.enqueueSteer(2L, capture(rawText = "other"))

        assertTrue(storage.documents.isEmpty())
        assertEquals(listOf(first, second), store.snapshot(1L).steers)
        assertEquals(listOf(first, second), store.drainSteers(1L))
        assertTrue(store.snapshot(1L).steers.isEmpty())
        assertEquals(listOf(other), store.snapshot(2L).steers)

        store.discardSteers(2L)
        assertTrue(store.snapshot(2L).steers.isEmpty())
        assertTrue(store.drainSteers(2L).isEmpty())
        assertTrue(storage.documents.isEmpty())
    }

    @Test
    fun queuePersistenceNeverContainsSteers() {
        val storage = FakeStorage()
        val store = store(storage)
        store.enqueue(1L, capture(rawText = "queued"))
        store.enqueueSteer(1L, capture(rawText = "steer"))

        val document = JSONObject(storage.documents.getValue(1L))
        assertEquals(1, document.getJSONArray("items").length())
        assertFalse(document.has("steers"))
    }

    @Test
    fun parkingRewritesTheStoredQueueAndRemainsParkedAfterRestore() {
        val storage = FakeStorage()
        val store = store(storage)
        val item = store.enqueue(1L, capture(rawText = "queued"))
        // A publish-only park would leave the document absent after this point.
        storage.documents.clear()

        store.parkAllQueued(1L)

        assertEquals(1, decodePendingInputQueue(1L, storage.documents.getValue(1L)).size)
        val restored = store(storage).load(1L)
        assertEquals(listOf(item.id), restored.queue.map { it.id })
        assertEquals(PendingInputState.PARKED, restored.queue.single().state)
    }

    @Test
    fun queueWritesWaitForTheInjectedExecutor() {
        val storage = FakeStorage()
        val scheduled = ArrayDeque<Runnable>()
        val store = PendingInputStore(
            readQueue = storage::read,
            writeQueue = storage::write,
            now = { 1L },
            newId = { "item-${++nextId}" },
            persistenceExecutor = { task -> scheduled.addLast(task) },
        )

        store.enqueue(1L, capture(rawText = "queued"))

        assertEquals(1, store.snapshot(1L).queue.size)
        assertTrue(storage.documents.isEmpty())
        assertEquals(1, scheduled.size)

        scheduled.removeFirst().run()
        assertEquals(1, decodePendingInputQueue(1L, storage.documents.getValue(1L)).size)
    }

    @Test
    fun transientReadFailureIsNeverOverwrittenByLaterWritesOrFlush() {
        val storage = FakeStorage()
        val persisted = encodePendingInputQueue(1L, listOf(PendingInputItem("persisted", 1L, capture(), 1L, PendingInputState.PARKED)))
        storage.documents[1L] = persisted
        var reads = 0
        val store = PendingInputStore(
            readQueue = { id ->
                reads++
                if (reads == 1) throw java.io.IOException("transient") else storage.read(id)
            },
            writeQueue = storage::write,
            now = { 1L },
            newId = { "item-${++nextId}" },
        )

        assertTrue(store.load(1L).queue.isEmpty())
        store.enqueue(1L, capture(rawText = "after failed read"))
        store.flush()

        assertEquals(persisted, storage.documents[1L])
        assertEquals(listOf("after failed read"), store.snapshot(1L).queue.map { it.capture.rawText })
    }

    @Test
    fun missingAttachmentUrisAreDerivedStateAndDoNotChangeTheDocument() {
        val storage = FakeStorage()
        val store = store(storage)
        store.enqueue(1L, capture(rawText = "queued"))
        val persisted = storage.documents.getValue(1L)

        store.updateMissingAttachments(1L, setOf("/uploads/gone.png"))
        assertEquals(setOf("/uploads/gone.png"), store.snapshot(1L).missingAttachmentUris)
        assertEquals(persisted, storage.documents.getValue(1L))

        store.updateMissingAttachments(99L, setOf("/uploads/unknown.png"))
        assertFalse(store.snapshots.value.containsKey(99L))
    }

    @Test
    fun failedDirectWriteKeepsTheSnapshotAndFlushRetries() {
        val storage = FakeStorage()
        var failWrites = true
        val store = PendingInputStore(
            readQueue = storage::read,
            writeQueue = { conversationId, raw ->
                if (failWrites) throw java.io.IOException("disk full")
                storage.write(conversationId, raw)
            },
            now = { 1L },
            newId = { "item-${++nextId}" },
        )

        assertThrows(java.io.IOException::class.java) {
            store.enqueue(1L, capture(rawText = "survives"))
        }
        assertEquals(listOf("survives"), store.snapshot(1L).queue.map { it.capture.rawText })

        failWrites = false
        store.flush()
        assertEquals(listOf("survives"), decodePendingInputQueue(1L, storage.documents.getValue(1L)).map { it.capture.rawText })
    }

    @Test
    fun flushRewritesEveryLoadedConversation() {
        val storage = FakeStorage()
        val store = store(storage)
        val first = store.enqueue(1L, capture(rawText = "one"))
        val second = store.enqueue(2L, capture(rawText = "two"))
        storage.documents.clear()

        store.flush()

        assertEquals(listOf(first.id), decodePendingInputQueue(1L, storage.documents.getValue(1L)).map { it.id })
        assertEquals(listOf(second.id), decodePendingInputQueue(2L, storage.documents.getValue(2L)).map { it.id })
    }

    private fun store(
        storage: FakeStorage,
        now: () -> Long = { 1_000L },
        newId: () -> String = { "id-${++nextId}" },
    ) = PendingInputStore(
        readQueue = storage::read,
        writeQueue = storage::write,
        now = now,
        newId = newId,
    )

    private fun capture(
        rawText: String = "raw",
        composedInput: String = "composed",
        attachments: List<PendingAttachment> = emptyList(),
        forcedSkillIds: List<String> = emptyList(),
    ) = PendingInputCapture(rawText, composedInput, attachments, forcedSkillIds)

    private fun attachment(
        name: String,
        uri: String = "/uploads/$name",
        kind: String = "text",
        mimeType: String = "text/plain",
        size: Int = 1,
    ) = PendingAttachment(name, kind, mimeType, uri, size)

    private class FakeStorage {
        val documents = mutableMapOf<Long, String>()

        fun read(conversationId: Long): String? = documents[conversationId]

        fun write(conversationId: Long, raw: String?) {
            if (raw == null) documents.remove(conversationId) else documents[conversationId] = raw
        }
    }
}
