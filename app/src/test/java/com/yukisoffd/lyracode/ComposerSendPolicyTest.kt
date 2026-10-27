package com.yukisoffd.lyracode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSendPolicyTest {
    @Test
    fun `sendable content requires text attachments or workspace selections`() {
        assertFalse(composerHasSendableContent(text = "", attachmentCount = 0, workspaceFileCount = 0))
        assertFalse(composerHasSendableContent(text = "   \n\t", attachmentCount = 0, workspaceFileCount = 0))

        assertTrue(composerHasSendableContent(text = "hello", attachmentCount = 0, workspaceFileCount = 0))
        assertTrue(composerHasSendableContent(text = "", attachmentCount = 1, workspaceFileCount = 0))
        assertTrue(composerHasSendableContent(text = "", attachmentCount = 0, workspaceFileCount = 1))
        assertTrue(composerHasSendableContent(text = "hello", attachmentCount = 2, workspaceFileCount = 3))
    }

    @Test
    fun `parked send is allowed only while idle`() {
        assertTrue(composerCanSendParked(runActive = false))
        assertFalse(composerCanSendParked(runActive = true))
    }

    @Test
    fun `send policy covers every input combination`() {
        var checked = 0
        listOf(false, true).forEach { hasSendableContent ->
            listOf(false, true).forEach { runActive ->
                listOf(false, true).forEach { featureEnabled ->
                    val expected = when {
                        !hasSendableContent -> ComposerSendAction.BLOCKED
                        !runActive -> ComposerSendAction.SEND
                        !featureEnabled -> ComposerSendAction.BLOCKED
                        else -> ComposerSendAction.QUEUE
                    }
                    assertEquals(
                        "hasSendableContent=$hasSendableContent runActive=$runActive " +
                            "featureEnabled=$featureEnabled",
                        expected,
                        composerSendAction(
                            hasSendableContent = hasSendableContent,
                            runActive = runActive,
                            featureEnabled = featureEnabled,
                        ),
                    )
                    checked++
                }
            }
        }
        assertEquals(8, checked)
    }

    @Test
    fun `mid-run sends always queue and idle sends always send`() {
        assertEquals(
            ComposerSendAction.QUEUE,
            composerSendAction(hasSendableContent = true, runActive = true, featureEnabled = true),
        )
        assertEquals(
            ComposerSendAction.SEND,
            composerSendAction(hasSendableContent = true, runActive = false, featureEnabled = true),
        )
    }

    @Test
    fun `a disabled composer blocks only the mid-run send`() {
        assertEquals(
            ComposerSendAction.BLOCKED,
            composerSendAction(hasSendableContent = true, runActive = true, featureEnabled = false),
        )
        assertEquals(
            ComposerSendAction.SEND,
            composerSendAction(hasSendableContent = true, runActive = false, featureEnabled = false),
        )
    }

    @Test
    fun `steer availability requires the feature and a generating run`() {
        assertEquals(SteerAvailability.AVAILABLE, steerAvailability(featureEnabled = true, generating = true))
        assertEquals(SteerAvailability.FEATURE_DISABLED, steerAvailability(featureEnabled = false, generating = true))
        assertEquals(
            SteerAvailability.NO_ACTIVE_GENERATING_RUN,
            steerAvailability(featureEnabled = true, generating = false),
        )
        assertEquals(SteerAvailability.FEATURE_DISABLED, steerAvailability(featureEnabled = false, generating = false))
    }

    @Test
    fun `steer availability precedence is feature scoped compression media target run waits`() {
        // Every lower-priority input is true so the highest-priority reason must win.
        assertEquals(
            SteerAvailability.FEATURE_DISABLED,
            steerAvailability(
                featureEnabled = false,
                generating = true,
                compressing = true,
                scopedDeviceRunning = true,
                mediaGeneration = true,
                subAgentTarget = true,
                pendingApprovalForConversation = true,
                pendingQuestionForConversation = true,
                subAgentRunning = true,
            ),
        )
        assertEquals(
            SteerAvailability.SCOPED_DEVICE,
            steerAvailability(featureEnabled = true, generating = true, compressing = true, scopedDeviceRunning = true),
        )
        assertEquals(
            SteerAvailability.COMPRESSING,
            steerAvailability(featureEnabled = true, generating = true, compressing = true, mediaGeneration = true),
        )
        assertEquals(
            SteerAvailability.MEDIA_GENERATION,
            steerAvailability(featureEnabled = true, generating = true, mediaGeneration = true, subAgentTarget = true),
        )
        assertEquals(
            SteerAvailability.SUB_AGENT_TARGET,
            steerAvailability(featureEnabled = true, generating = false, subAgentTarget = true),
        )
        assertEquals(
            SteerAvailability.NO_ACTIVE_GENERATING_RUN,
            steerAvailability(featureEnabled = true, generating = false, pendingApprovalForConversation = true),
        )
        assertEquals(
            SteerAvailability.AWAITING_APPROVAL,
            steerAvailability(
                featureEnabled = true,
                generating = true,
                pendingApprovalForConversation = true,
                pendingQuestionForConversation = true,
            ),
        )
        assertEquals(
            SteerAvailability.AWAITING_QUESTION,
            steerAvailability(featureEnabled = true, generating = true, pendingQuestionForConversation = true),
        )
        assertEquals(
            SteerAvailability.SUB_AGENT_RUNNING,
            steerAvailability(featureEnabled = true, generating = true, subAgentRunning = true),
        )
        assertEquals(
            SteerAvailability.AVAILABLE,
            steerAvailability(featureEnabled = true, generating = true),
        )
    }

    @Test
    fun `compression inside a generating job blocks steering while a plain generating run allows it`() {
        // The auto-compression path overwrites the operation with COMPRESSING inside the
        // still-running GENERATING job, so the generating bit is false there.
        assertEquals(
            SteerAvailability.COMPRESSING,
            steerAvailability(featureEnabled = true, generating = false, compressing = true),
        )
        assertEquals(
            SteerAvailability.COMPRESSING,
            steerAvailability(featureEnabled = true, generating = true, compressing = true),
        )
        assertEquals(
            SteerAvailability.AVAILABLE,
            steerAvailability(featureEnabled = true, generating = true, compressing = false),
        )
    }

    @Test
    fun `auto compression restores the generating marker only for the current job`() {
        assertTrue(shouldRestoreGeneratingMarker(operationIsCompressing = true, owningJobIsCurrent = true))
        assertFalse(shouldRestoreGeneratingMarker(operationIsCompressing = false, owningJobIsCurrent = true))
        assertFalse(shouldRestoreGeneratingMarker(operationIsCompressing = true, owningJobIsCurrent = false))
        // Once restored, steering is allowed again without a compression explanation.
        assertEquals(SteerAvailability.AVAILABLE, steerAvailability(featureEnabled = true, generating = true))
    }

    @Test
    fun `wait states accept the steer and every hard block refuses it`() {
        listOf(
            SteerAvailability.AVAILABLE,
            SteerAvailability.AWAITING_APPROVAL,
            SteerAvailability.AWAITING_QUESTION,
            SteerAvailability.SUB_AGENT_RUNNING,
        ).forEach { assertTrue("$it should accept", it.acceptsSteer()) }
        listOf(
            SteerAvailability.FEATURE_DISABLED,
            SteerAvailability.NO_ACTIVE_GENERATING_RUN,
            SteerAvailability.COMPRESSING,
            SteerAvailability.SCOPED_DEVICE,
            SteerAvailability.MEDIA_GENERATION,
            SteerAvailability.SUB_AGENT_TARGET,
        ).forEach { assertFalse("$it should refuse", it.acceptsSteer()) }
    }

    @Test
    fun `wait states expose a distinct note and hard blocks expose a reason`() {
        assertEquals(R.string.composer_steer_wait_approval, SteerAvailability.AWAITING_APPROVAL.waitNoteRes())
        assertEquals(R.string.composer_steer_wait_question, SteerAvailability.AWAITING_QUESTION.waitNoteRes())
        assertEquals(R.string.composer_steer_wait_sub_agent, SteerAvailability.SUB_AGENT_RUNNING.waitNoteRes())
        assertNull(SteerAvailability.AVAILABLE.waitNoteRes())
        SteerAvailability.entries.filterNot { it.acceptsSteer() }.forEach { reason ->
            assertEquals(
                "Refusal $reason needs a reason-specific explanation",
                when (reason) {
                    SteerAvailability.FEATURE_DISABLED -> R.string.composer_steer_unavailable_feature_disabled
                    SteerAvailability.NO_ACTIVE_GENERATING_RUN -> R.string.composer_steer_unavailable_no_run
                    SteerAvailability.COMPRESSING -> R.string.composer_steer_unavailable_compressing
                    SteerAvailability.SCOPED_DEVICE -> R.string.composer_steer_unavailable_scoped_device
                    SteerAvailability.MEDIA_GENERATION -> R.string.composer_steer_unavailable_media_generation
                    SteerAvailability.SUB_AGENT_TARGET -> R.string.composer_steer_unavailable_sub_agent_target
                    else -> error("unexpected refusal $reason")
                },
                reason.unavailableExplanationRes(),
            )
        }
    }

    @Test
    fun `a pending approval or question only blocks its own conversation`() {
        assertTrue(isPendingInteractionForConversation(pendingConversationId = 7L, conversationId = 7L))
        assertFalse(isPendingInteractionForConversation(pendingConversationId = 7L, conversationId = 8L))
        assertFalse(isPendingInteractionForConversation(pendingConversationId = null, conversationId = 7L))
        assertFalse(isPendingInteractionForConversation(pendingConversationId = 0L, conversationId = 0L))
    }

    @Test
    fun `scoped device provider marks only its conversation busy`() {
        val scopedDeviceRunning: (Long) -> Boolean = { id -> id == 7L }
        assertTrue(conversationBusy(7L, operationActive = false, scopedDeviceRunning = scopedDeviceRunning))
        assertFalse(conversationBusy(8L, operationActive = false, scopedDeviceRunning = scopedDeviceRunning))
        assertTrue(conversationBusy(8L, operationActive = true, scopedDeviceRunning = scopedDeviceRunning))
        assertFalse(conversationBusy(8L, operationActive = false, scopedDeviceRunning = scopedDeviceRunning))
    }

    @Test
    fun `active run turns every steerable queue row into a steer action`() {
        ComposerQueueItemState.entries.forEach { state ->
            assertEquals(
                ComposerQueueRowAction.STEER,
                composerQueueRowAction(
                    state = state,
                    runActive = true,
                    steerAccepted = true,
                    attachmentCount = 0,
                ),
            )
        }
    }

    @Test
    fun `attachments and unavailable steering leave the row to the queue`() {
        ComposerQueueItemState.entries.forEach { state ->
            assertEquals(
                ComposerQueueRowAction.NONE,
                composerQueueRowAction(
                    state = state,
                    runActive = true,
                    steerAccepted = true,
                    attachmentCount = 1,
                ),
            )
            assertEquals(
                ComposerQueueRowAction.NONE,
                composerQueueRowAction(
                    state = state,
                    runActive = true,
                    steerAccepted = false,
                    attachmentCount = 0,
                ),
            )
        }
    }

    @Test
    fun `idle rows only offer manual send for parked items`() {
        assertEquals(
            ComposerQueueRowAction.SEND_NOW,
            composerQueueRowAction(
                state = ComposerQueueItemState.PARKED,
                runActive = false,
                steerAccepted = true,
                attachmentCount = 0,
            ),
        )
        assertEquals(
            ComposerQueueRowAction.SEND_NOW,
            composerQueueRowAction(
                state = ComposerQueueItemState.PARKED,
                runActive = false,
                steerAccepted = true,
                attachmentCount = 2,
            ),
        )
        assertEquals(
            ComposerQueueRowAction.NONE,
            composerQueueRowAction(
                state = ComposerQueueItemState.QUEUED,
                runActive = false,
                steerAccepted = true,
                attachmentCount = 0,
            ),
        )
    }

}
