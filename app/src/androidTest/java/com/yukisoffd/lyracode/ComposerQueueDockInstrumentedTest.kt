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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ComposerQueueDockInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var removed: String? = null
    private var sent: String? = null
    private var steered: String? = null

    private val queued = ComposerQueueRowUi("first", "First queued request", 0, ComposerQueueItemState.QUEUED)
    private val parked = ComposerQueueRowUi("second", "Second parked request", 0, ComposerQueueItemState.PARKED)
    private val attached = ComposerQueueRowUi("attached", "Attached request", 2, ComposerQueueItemState.QUEUED)

    private fun showDock(
        items: List<ComposerQueueRowUi>,
        runActive: Boolean = false,
        steerAccepted: Boolean = true,
    ) {
        removed = null
        sent = null
        steered = null
        compose.setContent {
            MaterialTheme {
                ComposerQueueDock(
                    conversationId = 7L,
                    items = items,
                    runActive = runActive,
                    steerAccepted = steerAccepted,
                    onRemove = { removed = it },
                    onSendParked = { sent = it },
                    onSteer = { steered = it },
                )
            }
        }
    }

    @Test
    fun emptyQueueRendersNothing() {
        showDock(emptyList())

        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_dock_description, 0),
        ).assertCountEquals(0)
        compose.onAllNodesWithText("First queued request").assertCountEquals(0)
    }

    @Test
    fun singleItemRendersOneRowWithoutAHeader() {
        showDock(listOf(attached))

        compose.onNodeWithText("Attached request").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_state_queued)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_attachment_count, 2))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_dock_description, 1),
        ).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_dock_expand),
        ).assertCountEquals(0)
    }

    @Test
    fun multipleItemsStartCollapsedAndToggle() {
        showDock(listOf(queued, parked))

        compose.onNodeWithText(context.getString(R.string.composer_queue_dock_title, 2))
            .assertIsDisplayed()
        compose.onAllNodesWithText("First queued request").assertCountEquals(0)
        compose.onAllNodesWithText("Second parked request").assertCountEquals(0)

        compose.onNodeWithContentDescription(context.getString(R.string.composer_queue_dock_expand))
            .performClick()
        compose.onNodeWithText("First queued request").assertIsDisplayed()
        compose.onNodeWithText("Second parked request").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_state_queued)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_state_parked)).assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.composer_queue_dock_collapse))
            .performClick()
        compose.onAllNodesWithText("First queued request").assertCountEquals(0)
    }

    @Test
    fun removeActionReportsTheRowId() {
        showDock(listOf(parked))

        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_remove_description, "Second parked request"),
        ).performClick()

        compose.runOnIdle { assertEquals("second", removed) }
    }

    @Test
    fun idleParkedRowSendsManually() {
        showDock(listOf(parked), runActive = false)

        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_steer_description, "Second parked request"),
        ).assertCountEquals(0)
        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_send_now_description, "Second parked request"),
        ).performClick()

        compose.runOnIdle { assertEquals("second", sent) }
    }

    @Test
    fun activeRunSteersAQueuedRow() {
        showDock(listOf(queued), runActive = true, steerAccepted = true)

        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_send_now_description, "First queued request"),
        ).assertCountEquals(0)
        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_steer_description, "First queued request"),
        ).performClick()

        compose.runOnIdle { assertEquals("first", steered) }
    }

    @Test
    fun attachmentsAreNotSteerable() {
        showDock(listOf(attached), runActive = true, steerAccepted = true)

        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_steer_description, "Attached request"),
        ).assertCountEquals(0)
        compose.runOnIdle { assertNull(steered) }
    }

    @Test
    fun unavailableSteeringLeavesNoRowAction() {
        showDock(listOf(queued), runActive = true, steerAccepted = false)

        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_steer_description, "First queued request"),
        ).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(
            context.getString(R.string.composer_queue_send_now_description, "First queued request"),
        ).assertCountEquals(0)
    }

    @Test
    fun missingAttachmentIsVisibleAndTheRowStaysRemovable() {
        val restored = ComposerQueueRowUi(
            id = "restored",
            excerpt = "Restored after relaunch",
            attachmentCount = 2,
            state = ComposerQueueItemState.PARKED,
            missingAttachmentCount = 1,
        )
        showDock(listOf(restored))

        compose.onNodeWithText(
            context.getString(R.string.composer_queue_missing_attachment, 1),
        ).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.composer_queue_attachment_count, 2))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(
            context.getString(R.string.composer_queue_remove_description, "Restored after relaunch"),
        ).performClick()
        compose.runOnIdle { assertEquals("restored", removed) }
    }
}
