package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.chat.pending.PendingAttachment
import com.yukisoffd.lyracode.chat.pending.PendingInputCapture
import com.yukisoffd.lyracode.chat.pending.PendingInputItem
import com.yukisoffd.lyracode.chat.pending.PendingInputSnapshot
import com.yukisoffd.lyracode.chat.pending.PendingInputState
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerQueueWiringTest {

    private val capture = CapturedChatRequest(
        rawText = "  Follow   up  ",
        composedInput = "Follow up\n\n<lyra_attachment_v1>{\"name\":\"notes.txt\"}</lyra_attachment_v1>",
        forcedSkillIds = listOf("skill-a", "skill-b"),
        attachments = listOf(
            CapturedChatAttachment(
                name = "notes.txt",
                size = 42,
                mimeType = "text/plain",
                mediaKind = "text",
                uri = "content://notes",
            ),
        ),
    )

    @Test
    fun capturedRequestRoundTripsThroughPendingCapture() {
        val pending = capture.toPendingInputCapture()
        assertEquals("  Follow   up  ", pending.rawText)
        assertEquals(capture.composedInput, pending.composedInput)
        assertEquals(listOf("skill-a", "skill-b"), pending.forcedSkillIds)
        assertEquals(1, pending.attachments.size)
        assertEquals(
            PendingAttachment(
                name = "notes.txt",
                kind = "text",
                mimeType = "text/plain",
                uri = "content://notes",
                size = 42,
            ),
            pending.attachments.single(),
        )

        assertEquals(capture, pending.toCapturedChatRequest())
    }

    @Test
    fun excerptCollapsesWhitespaceAndFallsBackToAttachmentName() {
        assertEquals("Follow up", pendingInputExcerpt(capture.toPendingInputCapture()))

        val attachmentOnly = PendingInputCapture(
            rawText = "   ",
            composedInput = "<lyra_attachment_v1>{\"name\":\"photo.png\"}</lyra_attachment_v1>",
            attachments = listOf(PendingAttachment("photo.png", "image", "image/png", "content://photo", 7)),
        )
        assertEquals("photo.png", pendingInputExcerpt(attachmentOnly))
    }

    @Test
    fun excerptFallsBackToWorkspaceFileNameThenMarkerFreeText() {
        val workspaceOnly = PendingInputCapture(
            rawText = "",
            composedInput = "<lyra_workspace_refs_v1>{\"files\":[{\"name\":\"Main.kt\",\"path\":\"src/Main.kt\"}]}</lyra_workspace_refs_v1>",
        )
        assertEquals("Main.kt", pendingInputExcerpt(workspaceOnly))

        val editorOnly = PendingInputCapture(
            rawText = "",
            composedInput = "<lyra_editor_context_v1>{\"path\":\"/tmp/notes.md\"}</lyra_editor_context_v1>",
        )
        assertEquals("", pendingInputExcerpt(editorOnly))
    }

    @Test
    fun excerptIsBounded() {
        val long = PendingInputCapture(rawText = "x".repeat(200), composedInput = "x".repeat(200))
        assertEquals(pendingInputExcerptMaxLength, pendingInputExcerpt(long).length)
    }

    @Test
    fun snapshotMapsToOrderedChipsWithStatesAndAttachmentCounts() {
        val snapshot = PendingInputSnapshot(
            conversationId = 9L,
            queue = listOf(
                PendingInputItem(
                    id = "a",
                    conversationId = 9L,
                    capture = capture.toPendingInputCapture(),
                    createdAt = 1L,
                    state = PendingInputState.QUEUED,
                ),
                PendingInputItem(
                    id = "b",
                    conversationId = 9L,
                    capture = PendingInputCapture(rawText = "Second", composedInput = "Second"),
                    createdAt = 2L,
                    state = PendingInputState.PARKED,
                ),
            ),
        )

        assertEquals(
            listOf(
                ComposerQueueRowUi("a", "Follow up", 1, ComposerQueueItemState.QUEUED),
                ComposerQueueRowUi("b", "Second", 0, ComposerQueueItemState.PARKED),
            ),
            composerQueueRowUis(snapshot),
        )
        assertEquals(emptyList<ComposerQueueRowUi>(), composerQueueRowUis(null))
    }

    @Test
    fun chipsReportMissingAttachmentsWithoutDroppingTheItem() {
        val missing = PendingAttachment("missing.png", "image", "image/png", "/uploads/missing.png", 9)
        val present = PendingAttachment("present.png", "image", "image/png", "/uploads/present.png", 9)
        val snapshot = PendingInputSnapshot(
            conversationId = 9L,
            queue = listOf(
                PendingInputItem(
                    id = "restored",
                    conversationId = 9L,
                    capture = PendingInputCapture(
                        rawText = "restored",
                        composedInput = "restored",
                        attachments = listOf(missing, present),
                    ),
                    createdAt = 1L,
                    state = PendingInputState.PARKED,
                ),
            ),
        )

        val chip = composerQueueRowUis(snapshot.copy(missingAttachmentUris = setOf(missing.uri))).single()

        assertEquals("restored", chip.id)
        assertEquals(2, chip.attachmentCount)
        assertEquals(1, chip.missingAttachmentCount)
        assertEquals(ComposerQueueItemState.PARKED, chip.state)
    }

    @Test
    fun missingAttachmentFlagsFlowFromBackingCheckIntoTheChip() {
        val missing = PendingAttachment("missing.png", "image", "image/png", "/uploads/missing.png", 9)
        val present = PendingAttachment("present.png", "image", "image/png", "/uploads/present.png", 9)
        val snapshot = PendingInputSnapshot(
            conversationId = 9L,
            queue = listOf(
                PendingInputItem(
                    id = "restored",
                    conversationId = 9L,
                    capture = PendingInputCapture(
                        rawText = "Follow up",
                        composedInput = "Follow up",
                        attachments = listOf(missing, present),
                    ),
                    createdAt = 1L,
                    state = PendingInputState.PARKED,
                ),
            ),
        )

        val missingUris = missingAttachmentUris(snapshot) { it.uri == present.uri }
        val chip = composerQueueRowUis(snapshot.copy(missingAttachmentUris = missingUris)).single()

        assertEquals(setOf(missing.uri), missingUris)
        assertEquals(1, chip.missingAttachmentCount)
    }

    @Test
    fun completedRunWithoutCompressionFailureDeliversExactlyOneItem() {
        assertEquals(
            QueueDrain.DELIVER_NEXT,
            queueDrainFor(ConversationRunOutcome.Completed, compressionFailed = false, featureEnabled = true),
        )
        assertEquals(
            QueueDrain.PARK_ALL,
            queueDrainFor(ConversationRunOutcome.Completed, compressionFailed = true, featureEnabled = true),
        )
        assertEquals(
            QueueDrain.PARK_ALL,
            queueDrainFor(ConversationRunOutcome.Cancelled, compressionFailed = false, featureEnabled = true),
        )
        assertEquals(
            QueueDrain.PARK_ALL,
            queueDrainFor(ConversationRunOutcome.Failed(null), compressionFailed = false, featureEnabled = true),
        )
        assertEquals(
            QueueDrain.PARK_ALL,
            queueDrainFor(ConversationRunOutcome.Completed, compressionFailed = false, featureEnabled = false),
        )
    }

}
