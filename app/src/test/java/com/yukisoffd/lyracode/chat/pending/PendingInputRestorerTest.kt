package com.yukisoffd.lyracode.chat.pending

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingInputRestorerTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("pending-input-restorer").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun validDocumentForExistingConversationLoadsParked() {
        val fileStore = fileStore()
        val store = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        writeQueue(fileStore, conversationId = 1L, uri = "/uploads/one.jpg")
        var restored = -1L

        restorer(fileStore, store, exists = { true }, onRestored = { restored = it }).restoreAll()

        assertEquals(1L, restored)
        val snapshot = store.snapshot(1L)
        assertEquals(1, snapshot.parkedCount)
        assertEquals(0, snapshot.queuedCount)
        assertNull(store.deliverNextQueued(1L))
    }

    @Test
    fun validDocumentForDeletedConversationIsSweptAndReportsAttachments() {
        val fileStore = fileStore()
        val store = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        val uri = "/uploads/deleted.jpg"
        writeQueue(fileStore, conversationId = 2L, uri = uri)
        val swept = mutableMapOf<Long, List<PendingAttachment>>()

        restorer(fileStore, store, exists = { false }, onSwept = { id, candidates -> swept[id] = candidates }).restoreAll()

        assertEquals(setOf(2L), swept.keys)
        assertEquals(listOf(uri), swept.getValue(2L).map { it.uri })
        assertNull(fileStore.readQueue(2L))
        assertTrue(store.snapshots.value.isEmpty())
    }

    @Test
    fun corruptAndUnknownVersionDocumentsAreQuarantinedForEveryConversationState() {
        val fileStore = fileStore()
        val store = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        queueDirectory().mkdirs()
        File(queueDirectory(), "3.json").writeText("not json at all")
        File(queueDirectory(), "4.json").writeText(unknownVersionDocument(4L))
        File(queueDirectory(), "5.json").writeText("corrupt with /uploads/keep.jpg")
        var restored = 0
        val swept = mutableSetOf<Long>()

        restorer(
            fileStore,
            store,
            exists = { it != 5L },
            onRestored = { restored++ },
            onSwept = { id, _ -> swept += id },
        ).restoreAll()

        assertEquals(0, restored)
        assertTrue(swept.isEmpty())
        assertTrue(store.snapshots.value.isEmpty())
        assertNull(fileStore.readQueue(3L))
        assertNull(fileStore.readQueue(4L))
        assertNull(fileStore.readQueue(5L))
        assertEquals(3, quarantineFiles().size)
    }

    @Test
    fun conversationLookupFailureKeepsAndLoadsTheQueue() {
        val fileStore = fileStore()
        val store = PendingInputStore(fileStore::readQueue, fileStore::writeQueue)
        writeQueue(fileStore, conversationId = 6L, uri = "/uploads/six.jpg")
        var restored = -1L

        restorer(
            fileStore,
            store,
            exists = { error("database closed") },
            onRestored = { restored = it },
        ).restoreAll()

        assertEquals(6L, restored)
        assertEquals(1, store.snapshot(6L).queue.size)
        assertTrue(quarantineFiles().isEmpty())
    }

    private fun restorer(
        fileStore: PendingInputFileStore,
        store: PendingInputStore,
        exists: (Long) -> Boolean,
        onRestored: (Long) -> Unit = {},
        onSwept: (Long, List<PendingAttachment>) -> Unit = { _, _ -> },
    ) = PendingInputRestorer(fileStore, store, exists, onRestored, onSwept)

    private fun fileStore() = PendingInputFileStore(filesDir = filesDir)

    private fun writeQueue(fileStore: PendingInputFileStore, conversationId: Long, uri: String) {
        fileStore.writeQueue(
            conversationId,
            encodePendingInputQueue(
                conversationId,
                listOf(
                    PendingInputItem(
                        id = "item-$conversationId",
                        conversationId = conversationId,
                        capture = PendingInputCapture(
                            rawText = "queued",
                            composedInput = "queued",
                            attachments = listOf(
                                PendingAttachment("queued.jpg", "image", "image/jpeg", uri, 1),
                            ),
                        ),
                        createdAt = 1L,
                        state = PendingInputState.PARKED,
                    ),
                ),
            ),
        )
    }

    private fun unknownVersionDocument(conversationId: Long): String = JSONObject()
        .put("schema", "lyra_pending_input_v999")
        .put("conversationId", conversationId)
        .put("items", JSONArray())
        .toString()

    private fun queueDirectory(): File = File(filesDir, "queued_followups")

    private fun quarantineFiles(): List<File> =
        queueDirectory().listFiles().orEmpty().filter { it.name.contains(".json.corrupt-") }
}
