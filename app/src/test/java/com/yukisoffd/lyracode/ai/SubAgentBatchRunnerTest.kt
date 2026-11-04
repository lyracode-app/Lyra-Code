package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.ConversationRunGate
import com.yukisoffd.lyracode.launchConversationRun
import kotlinx.coroutines.CoroutineScope
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentBatchRunnerTest {
    @Test
    fun `scheduler dispatch order and peak match the configured limit`() = runTest {
        for (permits in listOf(1, 2, 3)) {
            val started = Collections.synchronizedList(mutableListOf<Int>())
            val current = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val outcomes = SubAgentBatchRunner(permits).run(6) { index ->
                started += index
                peak.accumulateAndGet(current.incrementAndGet()) { left, right -> maxOf(left, right) }
                delay(10)
                current.decrementAndGet()
                index
            }

            assertEquals("permits=$permits start order", listOf(0, 1, 2, 3, 4, 5), started.toList())
            assertEquals("permits=$permits peak", minOf(permits, 6), peak.get())
            assertEquals(6, outcomes.size)
        }
    }

    @Test
    fun `limit 1 starts in task order on a real dispatcher even when first dispatch is delayed`() = runBlocking {
        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { workers ->
            val dispatches = AtomicInteger(0)
            val laterDispatchFinished = CountDownLatch(1)
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    val dispatch = dispatches.incrementAndGet()
                    workers.dispatch(context, Runnable {
                        // Dispatch 1 enters withContext. Hold its first child while later work
                        // can overtake it. An acquire inside the child lets task 1 start first.
                        if (dispatch == 2) laterDispatchFinished.await(500, TimeUnit.MILLISECONDS)
                        try {
                            block.run()
                        } finally {
                            if (dispatch > 2) laterDispatchFinished.countDown()
                        }
                    })
                }
            }
            val started = Collections.synchronizedList(mutableListOf<Int>())
            val finished = Collections.synchronizedList(mutableListOf<Int>())
            withTimeout(10_000) {
                withContext(dispatcher) {
                    SubAgentBatchRunner(1).run(6) { index ->
                        assertEquals("previous tasks finished before task $index", (0 until index).toList(), finished.toList())
                        started += index
                        delay(5)
                        finished += index
                        index
                    }.forEachIndexed { index, outcome ->
                        assertEquals(SubAgentCallOutcome.Completed(index), outcome)
                    }
                }
            }
            assertTrue("first child used the real dispatcher", dispatches.get() >= 2)
            assertEquals((0 until 6).toList(), started.toList())
            assertEquals((0 until 6).toList(), finished.toList())
        }
    }

    @Test
    fun `conversation launch reaches the batch runner on a real dispatcher`() = runBlocking {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { workers ->
            val events = Collections.synchronizedList(mutableListOf<String>())
            val job = CoroutineScope(coroutineContext + workers).launchConversationRun(
                conversationId = 1,
                gate = ConversationRunGate(),
                closed = false,
                onLaunched = { events += "registered" },
                onStarted = { events += "started" },
                onCompleted = { _, _ -> events += "completed" },
            ) {
                SubAgentBatchRunner(1).run(3) { index ->
                    events += "task-$index"
                    delay(5)
                }
            }
            try {
                withTimeout(5_000) { job.join() }
                assertEquals(
                    listOf("registered", "started", "task-0", "task-1", "task-2", "completed"),
                    events.toList(),
                )
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun `blocking children run in parallel from a single-thread caller with an IO child context`() = runBlocking {
        // runBlocking has one thread. A child that blocks it, like a model call, runs in
        // parallel only when the runner moves children to a multi-thread context.
        val limit = 3
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val allStarted = CountDownLatch(limit)
        withTimeout(10_000) {
            SubAgentBatchRunner(limit, Dispatchers.IO).run(4) { index ->
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                allStarted.countDown()
                // Blocks the thread, as OkHttp execute() does; never suspends.
                assertTrue("first $limit children did not run together", allStarted.await(2, TimeUnit.SECONDS))
                active.decrementAndGet()
                index
            }.forEachIndexed { index, outcome ->
                assertEquals(SubAgentCallOutcome.Completed(index), outcome)
            }
        }
        assertEquals(limit, peak.get())
    }

    @Test
    fun `blocking children serialize on a single-thread caller without a child context`() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        SubAgentBatchRunner(3).run(4) { index ->
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            Thread.sleep(20)
            active.decrementAndGet()
            index
        }
        assertEquals(1, peak.get())
    }

    @Test
    fun `at most N children run at once`() = runTest {
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val outcomes = SubAgentBatchRunner(maxConcurrency = 2).run(6) { index ->
            val now = current.incrementAndGet()
            peak.accumulateAndGet(now) { left, right -> maxOf(left, right) }
            delay(50)
            current.decrementAndGet()
            index
        }

        assertEquals(2, peak.get())
        assertEquals(6, outcomes.size)
        outcomes.forEachIndexed { index, outcome ->
            assertEquals(SubAgentCallOutcome.Completed(index), outcome)
        }
    }

    @Test
    fun `results stay in task order when tasks finish out of order`() = runTest {
        val completionOrder = Collections.synchronizedList(mutableListOf<Int>())
        val outcomes = SubAgentBatchRunner(maxConcurrency = 6).run(5) { index ->
            delay(if (index == 0) 500 else 10)
            completionOrder += index
            "result-$index"
        }

        assertEquals(listOf(1, 2, 3, 4, 0), completionOrder.toList())
        assertEquals(
            listOf("result-0", "result-1", "result-2", "result-3", "result-4"),
            outcomes.map { (it as SubAgentCallOutcome.Completed).value },
        )
    }

    @Test
    fun `one child failure leaves the others running`() = runTest {
        val finished = AtomicInteger(0)
        val outcomes = SubAgentBatchRunner(maxConcurrency = 3).run(3) { index ->
            if (index == 1) {
                delay(10)
                error("boom")
            }
            delay(20)
            finished.incrementAndGet()
            "result-$index"
        }

        assertTrue(outcomes[0] is SubAgentCallOutcome.Completed)
        val failure = outcomes[1] as SubAgentCallOutcome.Failed
        assertEquals("boom", failure.error.message)
        assertTrue(outcomes[2] is SubAgentCallOutcome.Completed)
        assertEquals(2, finished.get())
    }

    @Test
    fun `peak concurrency follows the configured setting`() = runTest {
        for (configured in 1..4) {
            val current = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val outcomes = SubAgentBatchRunner(SubAgentLimits.effectiveConcurrency(configured, 6)).run(6) { index ->
                peak.accumulateAndGet(current.incrementAndGet()) { left, right -> maxOf(left, right) }
                delay(20)
                current.decrementAndGet()
                index
            }

            assertEquals("configured=$configured", configured, peak.get())
            assertEquals(6, outcomes.size)
        }

        // A concurrency above the batch size cannot raise the peak above the batch.
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)
        SubAgentBatchRunner(SubAgentLimits.effectiveConcurrency(9, 3)).run(3) { index ->
            peak.accumulateAndGet(current.incrementAndGet()) { left, right -> maxOf(left, right) }
            delay(20)
            current.decrementAndGet()
            index
        }
        assertEquals(3, peak.get())
    }

    @Test
    fun `parent cancellation throws to the caller and returns no outcomes`() = runTest {
        val started = AtomicInteger(0)
        val twoStarted = CompletableDeferred<Unit>()
        var returned: List<SubAgentCallOutcome<Unit>>? = null
        var thrown: Throwable? = null
        val parent = launch {
            try {
                returned = SubAgentBatchRunner(maxConcurrency = 2).run(2) {
                    if (started.incrementAndGet() == 2) twoStarted.complete(Unit)
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                thrown = error
            }
        }

        twoStarted.await()
        parent.cancelAndJoin()

        assertTrue(thrown is CancellationException)
        assertNull(returned)
    }

    @Test
    fun `cancelling during permit acquisition stops every running child`() = runTest {
        val started = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val twoStarted = CompletableDeferred<Unit>()
        val parent = launch {
            SubAgentBatchRunner(maxConcurrency = 2).run(4) {
                started.incrementAndGet()
                if (started.get() == 2) twoStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.incrementAndGet()
                }
            }
        }

        twoStarted.await()
        parent.cancelAndJoin()

        assertEquals(2, started.get())
        assertEquals(2, cancelled.get())
    }

    @Test
    fun `a cancellation exception from one child does not cancel its siblings`() = runTest {
        val outcomes = SubAgentBatchRunner(maxConcurrency = 3).run(3) { index ->
            delay(10)
            if (index == 1) throw CancellationException("child stopped")
            "result-$index"
        }

        assertTrue(outcomes[0] is SubAgentCallOutcome.Completed)
        assertTrue((outcomes[1] as SubAgentCallOutcome.Failed).error is CancellationException)
        assertTrue(outcomes[2] is SubAgentCallOutcome.Completed)
    }

    @Test
    fun `zero tasks produce no calls`() = runTest {
        var calls = 0
        val outcomes = SubAgentBatchRunner(maxConcurrency = 3).run(0) {
            calls++
            it
        }

        assertEquals(0, calls)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `progress counts running and completed children`() {
        val progress = SubAgentBatchProgress()

        progress.started(0, "one")
        progress.started(1, "two")
        assertEquals(0, progress.completedCount)
        assertEquals(2, progress.runningCount)

        progress.finished(0)
        assertEquals(1, progress.completedCount)
        assertEquals(1, progress.runningCount)

        progress.finished(1)
        assertEquals(2, progress.completedCount)
        assertEquals(0, progress.runningCount)
    }

}
