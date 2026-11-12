package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.chat.pending.PendingInputCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteerBoundaryTest {
    @Test
    fun `turn ends when there are no tool calls and no pending steer`() {
        assertEquals(RunBoundary.Return, runBoundaryAfterTurn(hasToolCalls = false, hasPendingSteer = false))
    }

    @Test
    fun `turn continues while tool calls are pending`() {
        assertEquals(RunBoundary.Continue, runBoundaryAfterTurn(hasToolCalls = true, hasPendingSteer = false))
    }

    @Test
    fun `turn continues when a steer is pending`() {
        assertEquals(RunBoundary.Continue, runBoundaryAfterTurn(hasToolCalls = false, hasPendingSteer = true))
    }

    @Test
    fun `turn continues when both tool calls and a steer are pending`() {
        assertEquals(RunBoundary.Continue, runBoundaryAfterTurn(hasToolCalls = true, hasPendingSteer = true))
    }

    @Test
    fun `steering is available only outside scoped runs`() {
        assertTrue(steeringAvailable(hasScopedTools = false))
        assertFalse(steeringAvailable(hasScopedTools = true))
    }

    @Test
    fun `default steer source is silent and empty`() {
        NoOpSteerSource.drain(1L) { error("Unexpected insertion") }
        NoOpSteerSource.drain(42L) { error("Unexpected insertion") }
    }

    @Test
    fun `steer source drains only the requested conversation`() {
        val drained = mutableListOf<Long>()
        val inserted = mutableListOf<String>()
        val source = SteerSource { conversationId, insert ->
            drained += conversationId
            if (conversationId == 7L) {
                insert(PendingInputCapture("first", "first"))
                insert(PendingInputCapture("second", "second"))
            }
        }

        source.drain(7L) { inserted.add(it.composedInput) }
        source.drain(8L) { inserted.add(it.composedInput) }
        assertEquals(listOf("first", "second"), inserted)
        assertEquals(listOf(7L, 8L), drained)
    }
}
