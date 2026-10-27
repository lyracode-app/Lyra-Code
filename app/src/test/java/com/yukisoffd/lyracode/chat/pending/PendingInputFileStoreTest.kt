package com.yukisoffd.lyracode.chat.pending

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingInputFileStoreTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("pending-input-file-store").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun writesReadsAndDeletesOneDocumentPerConversation() {
        val store = store()

        store.writeQueue(7L, "seven")
        store.writeQueue(8L, "eight")

        assertEquals("seven", store.readQueue(7L))
        assertEquals("eight", store.readQueue(8L))
        assertEquals(setOf(7L, 8L), store.allConversationIds())

        store.writeQueue(7L, "updated")
        assertEquals("updated", store.readQueue(7L))

        store.writeQueue(7L, null)
        assertNull(store.readQueue(7L))
        assertEquals(setOf(8L), store.allConversationIds())
        assertFalse(File(queueDirectory(), "7.json.tmp").exists())
    }

    @Test
    fun allConversationIdsIgnoresTemporaryAndForeignFiles() {
        val store = store()
        store.writeQueue(4L, "four")
        queueDirectory().mkdirs()
        File(queueDirectory(), "not-a-number.json").writeText("junk")
        File(queueDirectory(), "9.json.tmp").writeText("junk")
        File(queueDirectory(), "notes.txt").writeText("junk")

        assertEquals(setOf(4L), store.allConversationIds())
    }

    @Test
    fun restoredItemsComeBackParkedAndNeverAutoDeliver() {
        val fileStore = store()
        val firstRun = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        val item = firstRun.enqueue(
            4L,
            PendingInputCapture(rawText = "queued before kill", composedInput = "queued before kill"),
        )

        val relaunched = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        val restored = relaunched.load(4L)

        assertEquals(listOf(item.id), restored.queue.map { it.id })
        assertEquals(1, restored.parkedCount)
        assertEquals(0, restored.queuedCount)
        assertNull(relaunched.deliverNextQueued(4L))
        assertEquals(item.id, relaunched.takeForDelivery(4L, item.id)?.id)
    }

    @Test
    fun enqueueRemoveDeliverParkArchiveAndDeleteWriteThroughOneDocument() {
        val fileStore = store()
        val store = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)

        val first = store.enqueue(5L, PendingInputCapture(rawText = "first", composedInput = "first"))
        assertEquals(listOf(first.id), decodePendingInputQueue(5L, fileStore.readQueue(5L)).map { it.id })

        store.remove(5L, first.id)
        assertNull(fileStore.readQueue(5L))

        store.enqueue(5L, PendingInputCapture(rawText = "second", composedInput = "second"))
        val third = store.enqueue(5L, PendingInputCapture(rawText = "third", composedInput = "third"))
        store.deliverNextQueued(5L)
        assertEquals(listOf(third.id), decodePendingInputQueue(5L, fileStore.readQueue(5L)).map { it.id })

        store.parkAllQueued(5L)
        assertEquals(listOf(third.id), decodePendingInputQueue(5L, fileStore.readQueue(5L)).map { it.id })

        store.archive(5L)
        assertEquals(listOf(third.id), decodePendingInputQueue(5L, fileStore.readQueue(5L)).map { it.id })

        store.delete(5L)
        assertNull(fileStore.readQueue(5L))
        assertFalse(5L in fileStore.allConversationIds())
    }

    @Test
    fun corruptAndUnknownVersionDocumentsRestoreEmptyWithoutThrowing() {
        val fileStore = store()
        queueDirectory().mkdirs()
        val document = File(queueDirectory(), "4.json")

        document.writeText("not json at all")
        val corrupt = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        assertTrue(corrupt.load(4L).queue.isEmpty())

        document.writeText(
            JSONObject()
                .put("schema", "lyra_pending_input_v999")
                .put("conversationId", 4L)
                .put("items", org.json.JSONArray())
                .toString(),
        )
        val unknownVersion = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        assertTrue(unknownVersion.load(4L).queue.isEmpty())

        document.writeText("{ truncated")
        val truncated = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        assertTrue(truncated.load(4L).queue.isEmpty())
    }

    @Test
    fun attachmentExistsChecksAppPrivateFilesAndTrustsContentUris() {
        val store = store()
        val uploads = File(filesDir, ATTACHMENT_DIRECTORY).apply { mkdirs() }
        val present = File(uploads, "present.jpg").apply { writeText("payload") }

        assertTrue(store.attachmentExists(attachment(present.absolutePath)))
        assertTrue(store.attachmentExists(attachment("file://${present.absolutePath}")))
        assertFalse(store.attachmentExists(attachment(File(uploads, "gone.jpg").absolutePath)))
        assertTrue(store.attachmentExists(attachment("content://media/external/images/1")))
        assertTrue(store.attachmentExists(attachment("custom://opaque")))
        assertFalse(store.attachmentExists(attachment("")))
    }

    @Test
    fun deleteAttachmentOnlyTouchesFilesInsideChatUploads() {
        val store = store()
        val uploads = File(filesDir, ATTACHMENT_DIRECTORY).apply { mkdirs() }
        val private = File(uploads, "private.jpg").apply { writeText("payload") }
        val outside = File(filesDir, "outside.jpg").apply { writeText("payload") }

        assertFalse(store.deleteAttachment(attachment("content://media/external/images/1")))
        assertFalse(store.deleteAttachment(attachment(outside.absolutePath)))
        assertTrue(outside.exists())

        assertTrue(store.deleteAttachment(attachment(private.absolutePath)))
        assertFalse(private.exists())
    }

    @Test
    fun referenceCheckerExcludesMutatedConversationButScansOtherPersistedQueues() {
        val store = store()
        val ownUri = "/uploads/own.jpg"
        val otherUri = "/uploads/other.jpg"
        store.writeQueue(1L, encodedQueue(1L, ownUri))
        store.writeQueue(2L, encodedQueue(2L, otherUri))

        // The mutated conversation's own document must not mask an orphan.
        assertFalse(store.isAttachmentReferencedElsewhere(1L, attachment(ownUri)))
        // Another persisted queue still references its attachment.
        assertTrue(store.isAttachmentReferencedElsewhere(3L, attachment(ownUri)))
        assertTrue(store.isAttachmentReferencedElsewhere(1L, attachment(otherUri)))
        assertFalse(store.isAttachmentReferencedElsewhere(1L, attachment("/uploads/absent.jpg")))
    }

    @Test
    fun deletedConversationQueuesDoNotMaskRealOrphans() {
        val uri = "/uploads/shared.jpg"
        val store = PendingInputFileStore(
            filesDir = filesDir,
            conversationExists = { it != 3L },
        )
        store.writeQueue(3L, encodedQueue(3L, uri))

        assertFalse(store.isAttachmentReferencedElsewhere(1L, attachment(uri)))
    }

    @Test
    fun deliveredUserMessagesPreventOrphanReport() {
        val uri = "/uploads/delivered.jpg"
        val store = PendingInputFileStore(
            filesDir = filesDir,
            deliveredAttachmentUris = { setOf(uri) },
        )

        assertTrue(store.isAttachmentReferencedElsewhere(1L, attachment(uri)))
        assertFalse(store.isAttachmentReferencedElsewhere(1L, attachment("/uploads/orphan.jpg")))
    }

    @Test
    fun readsDistinguishMissingFromUnreadableDocuments() {
        val store = store()

        assertEquals(PendingQueueDocument.Missing, store.readQueueDocument(1L))
        val unreadable = File(queueDirectory(), "2.json").apply { mkdirs() }

        assertTrue(unreadable.isDirectory)
        assertTrue(store.readQueueDocument(2L) is PendingQueueDocument.Unreadable)
        assertThrows(IOException::class.java) { store.readQueue(2L) }
    }

    @Test
    fun failedWriteLeavesThePreviousDocumentIntactAndThrows() {
        val store = store()
        store.writeQueue(5L, "previous")
        // Make the temporary path unusable so the write fails before the rename.
        File(queueDirectory(), "5.json.tmp").mkdirs()

        assertThrows(IOException::class.java) { store.writeQueue(5L, "replacement") }

        assertEquals("previous", store.readQueue(5L))
    }

    @Test
    fun quarantineIsConditionalAndKeepsAttachmentReferencesAlive() {
        val store = store()
        queueDirectory().mkdirs()
        val uri = "/uploads/keep.jpg"
        val corrupt = "not json but still mentions $uri"
        val document = File(queueDirectory(), "7.json").apply { writeText(corrupt) }

        // A concurrent replacement must not be quarantined by a stale decision.
        document.writeText("replacement")
        assertFalse(store.quarantineUnreadableQueue(7L, corrupt))
        assertEquals("replacement", store.readQueue(7L))

        document.writeText(corrupt)
        assertTrue(store.quarantineUnreadableQueue(7L, corrupt))
        assertNull(store.readQueue(7L))
        assertTrue(quarantineFiles().single().readText().contains(uri))
        assertTrue(store.isAttachmentReferencedElsewhere(1L, attachment(uri)))
        assertFalse(store.isAttachmentReferencedElsewhere(1L, attachment("/uploads/gone.jpg")))
    }

    @Test
    fun quarantinedDocumentsAreNotQueueIds() {
        val store = store()
        queueDirectory().mkdirs()
        File(queueDirectory(), "8.json").writeText("corrupt")
        assertTrue(store.quarantineUnreadableQueue(8L, "corrupt"))

        assertFalse(8L in store.allConversationIds())
        assertTrue(quarantineFiles().single().name.startsWith("8.json.corrupt-"))
    }

    private fun quarantineFiles(): List<File> =
        queueDirectory().listFiles().orEmpty().filter { it.name.contains(".json.corrupt-") }

    private fun store(
        conversationExists: (Long) -> Boolean = { true },
        deliveredAttachmentUris: () -> Set<String> = { emptySet() },
    ) = PendingInputFileStore(
        filesDir = filesDir,
        conversationExists = conversationExists,
        deliveredAttachmentUris = deliveredAttachmentUris,
    )

    private fun queueDirectory(): File = File(filesDir, "queued_followups")

    private fun encodedQueue(conversationId: Long, uri: String): String = encodePendingInputQueue(
        conversationId,
        listOf(
            PendingInputItem(
                id = "item-$conversationId",
                conversationId = conversationId,
                capture = PendingInputCapture(
                    rawText = "queued",
                    composedInput = "queued",
                    attachments = listOf(attachment(uri)),
                ),
                createdAt = 1L,
                state = PendingInputState.PARKED,
            ),
        ),
    )

    private fun attachment(uri: String): PendingAttachment = PendingAttachment(
        name = uri.substringAfterLast('/'),
        kind = "image",
        mimeType = "image/jpeg",
        uri = uri,
        size = 1,
    )

    private companion object {
        const val ATTACHMENT_DIRECTORY = "chat_uploads"
    }
}
