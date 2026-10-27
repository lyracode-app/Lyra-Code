package com.yukisoffd.lyracode.chat.pending

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingInputAttachmentReaperTest {
    private lateinit var filesDir: File
    private lateinit var uploads: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("pending-input-reaper").toFile()
        uploads = File(filesDir, "chat_uploads").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun deletesOnlyAttachmentsNoOutsideHolderReferences() {
        val orphan = attachmentFile("orphan.jpg")
        val delivered = attachmentFile("delivered.jpg")
        val queuedElsewhere = attachmentFile("queued-elsewhere.jpg")
        val fileStore = PendingInputFileStore(
            filesDir = filesDir,
            deliveredAttachmentUris = { setOf(delivered.absolutePath) },
        )
        fileStore.writeQueue(2L, encodedQueue(2L, queuedElsewhere.absolutePath))

        PendingInputAttachmentReaper(fileStore, DIRECT_EXECUTOR).reap(
            1L,
            listOf(orphan, delivered, queuedElsewhere).map { attachment(it.absolutePath) },
        )

        assertFalse(orphan.exists())
        assertTrue(delivered.exists())
        assertTrue(queuedElsewhere.exists())
    }

    @Test
    fun checkerFailureKeepsEveryCandidate() {
        val kept = attachmentFile("kept.jpg")
        val fileStore = PendingInputFileStore(
            filesDir = filesDir,
            deliveredAttachmentUris = { error("database unavailable") },
        )

        PendingInputAttachmentReaper(fileStore, DIRECT_EXECUTOR).reap(1L, listOf(attachment(kept.absolutePath)))

        assertTrue(kept.exists())
    }

    @Test
    fun reaperWorkIsScheduledOnTheInjectedExecutor() {
        val orphan = attachmentFile("scheduled.jpg")
        val fileStore = PendingInputFileStore(filesDir = filesDir)
        val scheduled = ArrayDeque<Runnable>()
        val reaper = PendingInputAttachmentReaper(fileStore) { task -> scheduled.addLast(task) }

        reaper.reap(1L, listOf(attachment(orphan.absolutePath)))

        assertTrue(orphan.exists())
        scheduled.removeFirst().run()
        assertFalse(orphan.exists())
    }

    private fun attachmentFile(name: String): File = File(uploads, name).apply { writeText("payload") }

    private fun attachment(uri: String): PendingAttachment =
        PendingAttachment(name = uri.substringAfterLast('/'), kind = "image", mimeType = "image/jpeg", uri = uri, size = 1)

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

    private companion object {
        val DIRECT_EXECUTOR = Executor { task -> task.run() }
    }
}
