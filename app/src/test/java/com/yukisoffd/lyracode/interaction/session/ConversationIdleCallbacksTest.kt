package com.yukisoffd.lyracode.interaction.session

import com.yukisoffd.lyracode.chat.pending.PendingInputCapture
import com.yukisoffd.lyracode.chat.pending.PendingInputStore
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdleCallbacksTest {

    private fun store(documents: MutableMap<Long, String> = mutableMapOf()): PendingInputStore =
        PendingInputStore(
            readQueue = { conversationId -> documents[conversationId] },
            writeQueue = { conversationId, document ->
                if (document == null) documents.remove(conversationId) else documents[conversationId] = document
            },
        )

    /** Exercises the same production decision that the ChatController listener dispatches. */
    private fun applyIdle(store: PendingInputStore, outcome: ConversationIdleOutcome) {
        dispatchConversationIdle(
            outcome = outcome,
            deliverNext = { store.deliverNextQueued(7L) },
            parkAll = { store.parkAllQueued(7L) },
        )
    }

    @Test
    fun `registered listeners receive the conversation id and outcome`() {
        val callbacks = ConversationIdleCallbacks()
        val events = mutableListOf<Pair<Long, ConversationIdleOutcome>>()
        val listener: (Long, ConversationIdleOutcome) -> Unit = { id, outcome -> events += id to outcome }

        callbacks.add(listener)
        callbacks.notify(7L, ConversationIdleOutcome.COMPLETED)
        callbacks.notify(8L, ConversationIdleOutcome.FAILED)
        callbacks.remove(listener)
        callbacks.notify(9L, ConversationIdleOutcome.CANCELLED)

        assertEquals(
            listOf(7L to ConversationIdleOutcome.COMPLETED, 8L to ConversationIdleOutcome.FAILED),
            events,
        )
    }

    @Test
    fun `a failing listener does not stop the others`() {
        val callbacks = ConversationIdleCallbacks()
        var received = 0L
        callbacks.add { _, _ -> error("broken listener") }
        callbacks.add { id, _ -> received = id }

        callbacks.notify(11L, ConversationIdleOutcome.COMPLETED)

        assertEquals(11L, received)
    }

    @Test
    fun `a completed device task drains exactly one queued follow-up`() {
        val store = store()
        store.enqueue(7L, PendingInputCapture(rawText = "first", composedInput = "first"))
        store.enqueue(7L, PendingInputCapture(rawText = "second", composedInput = "second"))

        applyIdle(store, ConversationIdleOutcome.COMPLETED)

        assertEquals(1, store.snapshot(7L).queuedCount)
        assertEquals(listOf("second"), store.snapshot(7L).queue.map { it.capture.composedInput })
    }

    @Test
    fun `a failed device task parks the queue and never delivers`() {
        val store = store()
        store.enqueue(7L, PendingInputCapture(rawText = "follow-up", composedInput = "follow-up"))

        applyIdle(store, ConversationIdleOutcome.FAILED)

        assertEquals(0, store.snapshot(7L).queuedCount)
        assertEquals(1, store.snapshot(7L).parkedCount)
    }

    @Test
    fun `a cancelled device task parks the queue`() {
        val store = store()
        store.enqueue(7L, PendingInputCapture(rawText = "follow-up", composedInput = "follow-up"))

        applyIdle(store, ConversationIdleOutcome.CANCELLED)

        assertEquals(0, store.snapshot(7L).queuedCount)
        assertEquals(1, store.snapshot(7L).parkedCount)
    }

    @Test
    fun `a swallowed failure is never a normal completion`() {
        assertEquals(
            ConversationIdleOutcome.FAILED,
            deviceTaskIdleOutcome(ConversationIdleOutcome.FAILED, completionCause = null),
        )
        assertEquals(
            ConversationIdleOutcome.COMPLETED,
            deviceTaskIdleOutcome(ConversationIdleOutcome.COMPLETED, completionCause = null),
        )
        assertEquals(
            ConversationIdleOutcome.CANCELLED,
            deviceTaskIdleOutcome(ConversationIdleOutcome.CANCELLED, completionCause = null),
        )
        // An exception that escapes the body (for example from its finally block) is a
        // failure even though the body had reached its final status update.
        assertEquals(
            ConversationIdleOutcome.FAILED,
            deviceTaskIdleOutcome(ConversationIdleOutcome.COMPLETED, IllegalStateException("close failed")),
        )
        assertEquals(
            ConversationIdleOutcome.CANCELLED,
            deviceTaskIdleOutcome(ConversationIdleOutcome.COMPLETED, CancellationException("stop")),
        )
    }

    @Test
    fun `a superseded task stays silent while a newer task owns the conversation`() {
        assertTrue(isSupersededIdleNotification(token = 1L, generation = 2L, newerTaskActiveOnConversation = true))
        assertFalse(isSupersededIdleNotification(token = 2L, generation = 2L, newerTaskActiveOnConversation = true))
        assertFalse(isSupersededIdleNotification(token = 1L, generation = 2L, newerTaskActiveOnConversation = false))
    }
}
