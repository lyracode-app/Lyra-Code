package com.yukisoffd.lyracode.ai

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentTaskLimitTest {
    @Test
    fun `a batch at the limit is accepted`() {
        assertNull(SubAgentTaskLimit.overLimitError(SubAgentTaskLimit.MAX_TASKS))
    }

    @Test
    fun `a batch over the limit is refused, not truncated`() {
        val error = SubAgentTaskLimit.overLimitError(SubAgentTaskLimit.MAX_TASKS + 1).orEmpty()
        assertTrue(error, error.startsWith("ERROR: TOO_MANY_SUB_AGENT_TASKS\n"))
        assertTrue(error, "7 subtasks" in error)
        assertTrue(error, "No task ran" in error)
    }
}
