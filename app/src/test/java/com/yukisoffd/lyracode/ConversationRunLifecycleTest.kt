package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.data.ConversationStore
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

    @Test fun cancellationOutcomeWinsOverInterruptedStoreStatus() {
        assertEquals(
            ConversationRunOutcome.Cancelled,
            classifyRunOutcome(CancellationException("stopped"), ConversationStore.STATUS_INTERRUPTED),
        )
    }

    @Test fun cancellationOutcomeIsReportedWithoutStoreStatus() {
        assertEquals(
            ConversationRunOutcome.Cancelled,
            classifyRunOutcome(CancellationException("stopped"), null),
        )
    }

    @Test fun nonCancellationFailureIsReportedWithItsError() {
        val failure = IllegalStateException("boom")

        assertEquals(ConversationRunOutcome.Failed(failure), classifyRunOutcome(failure, null))
    }

    @Test fun cleanReturnWithInterruptedStoreStatusIsReportedAsFailedWithoutError() {
        assertEquals(
            ConversationRunOutcome.Failed(null),
            classifyRunOutcome(null, ConversationStore.STATUS_INTERRUPTED),
        )
    }

    @Test fun cleanReturnWithOtherStoreStatusesIsReportedAsCompleted() {
        assertEquals(ConversationRunOutcome.Completed, classifyRunOutcome(null, ConversationStore.STATUS_RUNNING))
        assertEquals(ConversationRunOutcome.Completed, classifyRunOutcome(null, ConversationStore.STATUS_IDLE))
        assertEquals(ConversationRunOutcome.Completed, classifyRunOutcome(null, null))
    }

    /**
     * The launch path that chat, Continue, Regenerate and compression all go through. The job is
     * lazy, so a start decision that tests isActive starts nothing and drops the run silently;
     * only a test that runs the block catches that.
     */
    @Test fun `a run created on a live scope starts and its block runs`() = runBlocking {
        val events = mutableListOf<String>()
        val job = launchConversationRun(
            conversationId = 1,
            gate = ConversationRunGate(),
            closed = false,
            onLaunched = { events += "launched" },
            onCompleted = { _, _ -> events += "completed" },
            onStarted = { events += "started" },
            block = { events += "body" },
        )

        withTimeout(5_000) { job.join() }

        assertEquals(listOf("launched", "started", "body", "completed"), events)
    }

    @Test fun `a launch on a cancelled scope starts no run or service`() = runBlocking {
        val outer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        outer.cancel()
        var started = false
        var bodyRan = false
        var completed = false

        val job = outer.launchConversationRun(
            conversationId = 1,
            gate = ConversationRunGate(),
            closed = false,
            onLaunched = {},
            onCompleted = { _, _ -> completed = true },
            onStarted = { started = true },
            block = { bodyRan = true },
        )
        withTimeout(5_000) { job.join() }

        // A launch on a cancelled scope is already cancelled; it must not ask for a foreground
        // run that nothing would ever stop, and its completion must still reach the caller so
        // the registry entry it registered is cleared.
        assertTrue(job.isCancelled)
        assertTrue(completed)
        assertFalse(started)
        assertFalse(bodyRan)
    }

    @Test fun `a run waits for the conversation gate before its block runs`() = runBlocking {
        val gate = ConversationRunGate()
        val release = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(1) { release.await() }
        }
        val second = launchConversationRun(
            conversationId = 1,
            gate = gate,
            closed = false,
            onLaunched = {},
            onCompleted = { _, _ -> },
            onStarted = { secondStarted = true },
            block = { },
        )
        assertTrue(secondStarted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.join()
        withTimeout(5_000) { second.join() }
    }
}
