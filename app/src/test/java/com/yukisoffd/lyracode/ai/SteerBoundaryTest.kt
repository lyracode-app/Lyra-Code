package com.yukisoffd.lyracode.ai

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
        assertEquals(emptyList<String>(), NoOpSteerSource.drain(1L))
        assertEquals(emptyList<String>(), NoOpSteerSource.drain(42L))
    }

    @Test
    fun `steer source drains only the requested conversation`() {
        val drained = mutableListOf<Long>()
        val source = SteerSource { conversationId ->
            drained += conversationId
            if (conversationId == 7L) listOf("first", "second") else emptyList()
        }

        assertEquals(listOf("first", "second"), source.drain(7L))
        assertTrue(source.drain(8L).isEmpty())
        assertEquals(listOf(7L, 8L), drained)
    }

    @Test
    fun `no-op source has no observable state between drains`() {
        assertFalse(NoOpSteerSource.drain(3L).isNotEmpty())
        assertTrue(NoOpSteerSource.drain(3L).isEmpty())
    }
}
