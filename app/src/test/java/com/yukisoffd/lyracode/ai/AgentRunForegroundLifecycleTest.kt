package com.yukisoffd.lyracode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunForegroundLifecycleTest {
    @Test
    fun `a stop after startForeground stops now`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onRunStarted()
        lifecycle.onForegroundStarted()

        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())
    }

    @Test
    fun `a stop before startForeground is deferred and served once onCreate reaches it`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onRunStarted()
        // The run ended before onCreate called startForeground: stopping the service now would
        // bring it down while it is still waiting for startForeground.
        assertEquals(StopDecision.DEFER, lifecycle.onStopRequested())

        // The instance that finally appears must stop itself at once.
        assertTrue(lifecycle.onForegroundStarted().stopNow)
        assertEquals(StopDecision.IGNORE, lifecycle.onStopRequested())
    }

    @Test
    fun `a stop with no start request is ignored`() {
        val lifecycle = AgentRunForegroundLifecycle()

        assertEquals(StopDecision.IGNORE, lifecycle.onStopRequested())
    }

    @Test
    fun `a second start then a stop stops now`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onRunStarted()
        lifecycle.onForegroundStarted()
        lifecycle.onRunStarted()

        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())
    }

    @Test
    fun `a destroyed service serves no stale stop`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onRunStarted()
        assertEquals(StopDecision.DEFER, lifecycle.onStopRequested())
        lifecycle.onDestroyed(0)

        assertEquals(StopDecision.IGNORE, lifecycle.onStopRequested())
    }

    @Test
    fun `a start after a deferred stop serves the newer run`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onRunStarted()
        assertEquals(StopDecision.DEFER, lifecycle.onStopRequested())
        // The previous start never reached onCreate (a blocked start); a new run starts.
        lifecycle.onRunStarted()
        assertFalse(lifecycle.onForegroundStarted().stopNow)
    }

    @Test
    fun `an old instance teardown does not unarm the run that replaced it`() {
        val lifecycle = AgentRunForegroundLifecycle()

        // Run 1 owns the foreground, then ends and asks the service to stop.
        lifecycle.onRunStarted()
        val first = lifecycle.onForegroundStarted()
        assertTrue(lifecycle.onRunFinished())
        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())

        // Run 2 starts before run 1's instance has actually been destroyed.
        lifecycle.onRunStarted()

        // The old instance's teardown arrives late and must not clear run 2's start.
        lifecycle.onDestroyed(first.instance)
        val second = lifecycle.onForegroundStarted()
        assertFalse(second.stopNow)

        // The probe that failed before this fix reported IGNORE here: the notification and the
        // wake lock stayed and the Stop button did nothing until the next run ended.
        assertTrue(lifecycle.onRunFinished())
        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())
    }

    @Test
    fun `the last run in the process is the only one that stops the service`() {
        val lifecycle = AgentRunForegroundLifecycle()

        // Two controllers share the process: a recreated one may start a run before the old
        // controller's job unwinds.
        lifecycle.onRunStarted()
        lifecycle.onRunStarted()
        lifecycle.onForegroundStarted()

        // The old controller's run ends first; the new run still needs the foreground.
        assertFalse(lifecycle.onRunFinished())
        // The new controller's run ends last.
        assertTrue(lifecycle.onRunFinished())
        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())
    }

    @Test
    fun `a service created after its run already stopped stops itself`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onRunStarted()
        lifecycle.onForegroundStarted()
        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())
        assertTrue(lifecycle.onRunFinished())

        // Android delivers the create late, after the stop was served.
        assertTrue(lifecycle.onForegroundStarted().stopNow)
    }
}
