package com.yukisoffd.lyracode.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRunRegistryTest {
    @Test
    fun `runs are tracked per parent conversation until the batch ends`() {
        val registry = SubAgentRunRegistry()
        assertFalse(registry.isRunning(7L))

        registry.begin(7L)
        registry.begin(8L)
        assertTrue(registry.isRunning(7L))
        assertTrue(registry.isRunning(8L))

        registry.end(7L)
        assertFalse(registry.isRunning(7L))
        assertTrue(registry.isRunning(8L))

        registry.end(8L)
        assertFalse(registry.isRunning(8L))
        assertFalse(registry.isRunning(99L))
    }
}
