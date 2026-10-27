package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.chat.pending.PendingInputCapture
import com.yukisoffd.lyracode.chat.pending.PendingInputState
import com.yukisoffd.lyracode.chat.pending.PendingInputStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteerWiringTest {

    private fun store(documents: MutableMap<Long, String> = mutableMapOf()): PendingInputStore =
        PendingInputStore(
            readQueue = { conversationId -> documents[conversationId] },
            writeQueue = { conversationId, document ->
                if (document == null) documents.remove(conversationId) else documents[conversationId] = document
            },
        )

    @Test
    fun `steer source drains each capture once in arrival order per conversation`() {
        val store = store()
        store.enqueueSteer(7L, PendingInputCapture(rawText = "first", composedInput = "steer one"))
        store.enqueueSteer(7L, PendingInputCapture(rawText = "second", composedInput = "steer two"))
        store.enqueueSteer(8L, PendingInputCapture(rawText = "other", composedInput = "other conversation"))

        val source = steerSourceFrom(store::drainSteers)

        assertEquals(listOf("steer one", "steer two"), source.drain(7L))
        assertTrue(source.drain(7L).isEmpty())
        assertTrue(store.snapshot(7L).steers.isEmpty())

        assertEquals(listOf("other conversation"), source.drain(8L))
        assertTrue(source.drain(8L).isEmpty())
        assertTrue(store.snapshot(8L).steers.isEmpty())
    }

    @Test
    fun `steer source skips blank composed captures`() {
        val store = store()
        store.enqueueSteer(3L, PendingInputCapture(rawText = "", composedInput = "  "))
        store.enqueueSteer(3L, PendingInputCapture(rawText = "keep", composedInput = "keep"))

        val source = steerSourceFrom(store::drainSteers)

        assertEquals(listOf("keep"), source.drain(3L))
        assertTrue(store.snapshot(3L).steers.isEmpty())
    }

    @Test
    fun `steers are transient and never written to the queue document`() {
        val documents = mutableMapOf<Long, String>()
        val store = store(documents)

        store.enqueueSteer(5L, PendingInputCapture(rawText = "steer", composedInput = "steer"))

        assertEquals(1, store.snapshot(5L).steers.size)
        assertNull(documents[5L])
        assertFalse(documents.containsKey(5L))
    }

    @Test
    fun `steer source does not touch the queued follow-up channel`() {
        val store = store()
        store.enqueue(9L, PendingInputCapture(rawText = "queued", composedInput = "queued"))
        store.enqueueSteer(9L, PendingInputCapture(rawText = "steer", composedInput = "steer"))

        val source = steerSourceFrom(store::drainSteers)

        assertEquals(listOf("steer"), source.drain(9L))
        assertEquals(1, store.snapshot(9L).queuedCount)
        assertEquals("queued", store.snapshot(9L).queue.single().capture.composedInput)
    }

    @Test
    fun `late steers from a completed run become queued follow-ups`() {
        val store = store()
        store.enqueue(7L, PendingInputCapture(rawText = "queued", composedInput = "queued"))
        store.enqueueSteer(7L, PendingInputCapture(rawText = "late", composedInput = "late one"))
        store.enqueueSteer(7L, PendingInputCapture(rawText = "late2", composedInput = "late two"))

        val carried = carryLateSteers(store, 7L, lateSteerQueueState(QueueDrain.DELIVER_NEXT))

        assertEquals(listOf("late one", "late two"), carried.map { it.capture.composedInput })
        assertTrue(store.snapshot(7L).steers.isEmpty())
        assertEquals(
            listOf("queued", "late one", "late two"),
            store.snapshot(7L).queue.map { it.capture.composedInput },
        )
        assertEquals(3, store.snapshot(7L).queuedCount)
    }

    @Test
    fun `late steers park when the queue parks`() {
        val store = store()
        store.enqueueSteer(7L, PendingInputCapture(rawText = "late", composedInput = "late"))

        val carried = carryLateSteers(store, 7L, lateSteerQueueState(QueueDrain.PARK_ALL))

        assertEquals(PendingInputState.PARKED, carried.single().state)
        assertTrue(store.snapshot(7L).steers.isEmpty())
        assertEquals(0, store.snapshot(7L).queuedCount)
        assertEquals(1, store.snapshot(7L).parkedCount)
    }

    @Test
    fun `queue drain chooses the carried state for every outcome`() {
        assertEquals(
            PendingInputState.QUEUED,
            lateSteerQueueState(queueDrainFor(ConversationRunOutcome.Completed, compressionFailed = false, featureEnabled = true)),
        )
        assertEquals(
            PendingInputState.PARKED,
            lateSteerQueueState(queueDrainFor(ConversationRunOutcome.Completed, compressionFailed = true, featureEnabled = true)),
        )
        assertEquals(
            PendingInputState.PARKED,
            lateSteerQueueState(queueDrainFor(ConversationRunOutcome.Completed, compressionFailed = false, featureEnabled = false)),
        )
        assertEquals(
            PendingInputState.PARKED,
            lateSteerQueueState(queueDrainFor(ConversationRunOutcome.Cancelled, compressionFailed = false, featureEnabled = true)),
        )
        assertEquals(
            PendingInputState.PARKED,
            lateSteerQueueState(queueDrainFor(ConversationRunOutcome.Failed(null), compressionFailed = false, featureEnabled = true)),
        )
    }

    @Test
    fun `cancelled runs park accepted steers instead of dropping them`() {
        val store = store()
        store.enqueueSteer(6L, PendingInputCapture(rawText = "stop", composedInput = "stop now"))

        val drain = queueDrainFor(ConversationRunOutcome.Cancelled, compressionFailed = false, featureEnabled = true)
        val carried = carryLateSteers(store, 6L, lateSteerQueueState(drain))

        assertEquals(PendingInputState.PARKED, carried.single().state)
        assertEquals("stop now", store.snapshot(6L).queue.single().capture.composedInput)
        assertEquals(1, store.snapshot(6L).parkedCount)
        assertTrue(store.snapshot(6L).steers.isEmpty())
    }

    @Test
    fun `a lone late steer becomes the next delivered run`() {
        val store = store()
        store.enqueueSteer(7L, PendingInputCapture(rawText = "late", composedInput = "late"))

        carryLateSteers(store, 7L, lateSteerQueueState(QueueDrain.DELIVER_NEXT))
        val delivered = store.deliverNextQueued(7L)

        assertEquals("late", delivered?.capture?.composedInput)
        assertEquals(0, store.snapshot(7L).queuedCount)
        assertEquals(0, store.snapshot(7L).parkedCount)
    }

    @Test
    fun `carrying late steers is a no-op when the channel is empty`() {
        val store = store()
        store.enqueue(4L, PendingInputCapture(rawText = "queued", composedInput = "queued"))

        val carried = carryLateSteers(store, 4L, PendingInputState.QUEUED)

        assertTrue(carried.isEmpty())
        assertEquals(1, store.snapshot(4L).queue.size)
    }

    @Test
    fun `steers accepted during a wait stay queued until the wait resolves`() {
        val store = store()
        val waits = listOf(
            Triple(
                steerAvailability(featureEnabled = true, generating = true, pendingApprovalForConversation = true),
                R.string.composer_steer_wait_approval,
                "approval",
            ),
            Triple(
                steerAvailability(featureEnabled = true, generating = true, pendingQuestionForConversation = true),
                R.string.composer_steer_wait_question,
                "question",
            ),
            Triple(
                steerAvailability(featureEnabled = true, generating = true, subAgentRunning = true),
                R.string.composer_steer_wait_sub_agent,
                "sub-agent",
            ),
        )
        waits.forEach { (availability, expectedNote, label) ->
            assertTrue("$label wait must accept the steer", availability.acceptsSteer())
            assertEquals("$label wait needs a delayed-delivery note", expectedNote, availability.waitNoteRes())
        }

        store.enqueueSteer(7L, PendingInputCapture(rawText = "keep going", composedInput = "keep going"))
        assertEquals("steer is held until the wait resolves", 1, store.snapshot(7L).steers.size)

        val resolved = steerAvailability(featureEnabled = true, generating = true)
        assertEquals(SteerAvailability.AVAILABLE, resolved)
        assertNull(resolved.waitNoteRes())
        assertEquals(listOf("keep going"), steerSourceFrom(store::drainSteers).drain(7L))
        assertTrue(store.snapshot(7L).steers.isEmpty())
    }
}
