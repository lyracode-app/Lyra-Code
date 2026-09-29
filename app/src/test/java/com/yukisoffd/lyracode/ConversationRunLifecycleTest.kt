package com.yukisoffd.lyracode

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConversationRunLifecycleTest {
    @Test fun resumeWaitsForOldCancellationBeforePublishingRunningState() = runBlocking {
        val cleanup = CompletableDeferred<Unit>()
        val gate = ConversationRunGate()
        var state = "running"
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(1) {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleanup.await(); state = "interrupted" }
                }
            }
        }
        old.cancel()
        val resumed = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(1) { state = "resumed" }
        }
        assertFalse(resumed.isCompleted)
        cleanup.complete(Unit)
        resumed.join()
        assertEquals("resumed", state)
    }

    @Test fun repeatedStopAndContinuePreservesCleanupOrder() = runBlocking {
        val cleanup = CompletableDeferred<Unit>()
        val gate = ConversationRunGate()
        val events = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(1) {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleanup.await(); events += "old stopped" }
                }
            }
        }
        first.cancel()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(1) { events += "cancelled run started" }
        }
        second.cancel()
        val third = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(1) { events += "latest started" }
        }
        assertTrue(events.isEmpty())
        cleanup.complete(Unit)
        third.join()
        assertEquals(listOf("old stopped", "latest started"), events)
    }

    @Test fun continueButtonIsHiddenWhileRunningEvenWithStaleInterruptedMetadata() {
        assertFalse(showContinueConversation(true, true, true))
        assertTrue(showContinueConversation(true, false, true))
        assertFalse(showContinueConversation(false, false, true))
        assertFalse(showContinueConversation(true, false, false))
    }

    @Test fun stoppingOneConversationDoesNotBlockAnother() = runBlocking {
        val gate = ConversationRunGate()
        val old = launch(start = CoroutineStart.UNDISPATCHED) { gate.run(1) { awaitCancellation() } }
        try {
            assertEquals("other finished", withTimeout(1_000) { gate.run(2) { "other finished" } })
        } finally { old.cancelAndJoin() }
    }
}
