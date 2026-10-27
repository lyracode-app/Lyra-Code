package com.yukisoffd.lyracode

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.chat.pending.PendingAttachment
import com.yukisoffd.lyracode.chat.pending.PendingInputCapture
import com.yukisoffd.lyracode.chat.pending.PendingInputItem
import com.yukisoffd.lyracode.chat.pending.PendingInputSnapshot
import com.yukisoffd.lyracode.chat.pending.PendingInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Covers the mapping from a live queue snapshot to the rendered dock and its actions. */
class ComposerQueueWiringInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var removed: String? = null
    private var sent: String? = null
    private var steered: String? = null

    private val snapshot = PendingInputSnapshot(
        conversationId = 7L,
        queue = listOf(
            PendingInputItem(
                id = "queued-1",
                conversationId = 7L,
                capture = PendingInputCapture(
                    rawText = "Queued follow up",
                    composedInput = "Queued follow up",
                    attachments = listOf(PendingAttachment("notes.txt", "text", "text/plain", "content://notes", 12)),
                ),
                createdAt = 1L,
                state = PendingInputState.QUEUED,
            ),
            PendingInputItem(
                id = "parked-1",
                conversationId = 7L,
                capture = PendingInputCapture(rawText = "Parked follow up", composedInput = "Parked follow up"),
                createdAt = 2L,
                state = PendingInputState.PARKED,
            ),
        ),
    )

    private fun showWiredDock(
        state: PendingInputSnapshot? = snapshot,
        runActive: Boolean = false,
        steerAccepted: Boolean = true,
    ) {
        removed = null
        sent = null
        steered = null
        compose.setContent {
            MaterialTheme {
                ComposerQueueSection(
                    snapshot = state,
                    runActive = runActive,
                    steerAccepted = steerAccepted,
                    onRemove = { removed = it },
                    onSendParked = { sent = it },
                    onSteerQueued = { steered = it },
                )
            }
        }
    }

    private fun expand() {
        compose.onNodeWithContentDescription(context.getString(R.string.composer_queue_dock_expand))
            .performClick()
    }

    @Test
    fun wiredDockStartsCollapsedAndShowsItsRowsAfterExpanding() {
        showWiredDock()

        compose.onNodeWithText(context.getString(R.string.composer_queue_dock_title, 2))
            .assertIsDisplayed()
        compose.onAllNodesWithText("Queued follow up").assertCountEquals(0)

        expand()

        compose.onNodeWithText("Queued follow up").assertIsDisplayed()
        compose.onNodeWithText("Parked follow up").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_attachment_count, 1))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_state_queued)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_state_parked)).assertIsDisplayed()
    }

    @Test
    fun wiredDockRemoveUsesTheQueuedItemId() {
        showWiredDock()
        expand()

        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_remove_description, "Queued follow up"),
        ).performClick()

        compose.runOnIdle { assertEquals("queued-1", removed) }
    }

    @Test
    fun wiredDockParkedSendReportsIdOnlyWhenIdle() {
        showWiredDock(runActive = false)
        expand()

        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_send_now_description, "Parked follow up"),
        ).performClick()
        compose.runOnIdle { assertEquals("parked-1", sent) }
    }

    @Test
    fun wiredDockSteersAParkedItemWhileRunningInsteadOfSendingItNow() {
        showWiredDock(runActive = true)
        expand()

        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_send_now_description, "Parked follow up"),
        ).assertCountEquals(0)
        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_steer_description, "Parked follow up"),
        ).performClick()
        compose.runOnIdle {
            assertEquals("parked-1", steered)
            assertNull(sent)
        }
    }

    @Test
    fun wiredDockDoesNotSteerAnAttachmentItem() {
        showWiredDock(runActive = true, steerAccepted = true)
        expand()

        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_steer_description, "Queued follow up"),
        ).assertCountEquals(0)
        compose.runOnIdle { assertNull(steered) }
    }

    @Test
    fun wiredDockRendersNothingForNoConversationQueue() {
        showWiredDock(state = null)

        compose.onAllNodesWithText("Queued follow up").assertCountEquals(0)
        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_dock_description, 0),
        ).assertCountEquals(0)
    }
}
