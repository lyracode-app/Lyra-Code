package com.yukisoffd.lyracode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentTaskSchemaTest {
    @Test
    fun `the batch table carries every column the resume logic reads`() {
        val ddl = SubAgentTaskSchema.CREATE_TABLE
        listOf(
            "parent_conversation_id",
            "batch_id",
            "task_index",
            "child_conversation_id",
            "task_text",
            "write_paths",
            "read_only",
            "expected_output",
            "capability_hint",
            "preferred_agent",
            "user_message_id",
            "tool_call_id",
            "status",
            "delivered",
            "created_at",
            "updated_at",
        ).forEach { column ->
            assertTrue("missing column $column", ddl.contains(column))
        }
        assertTrue(ddl.contains("PRIMARY KEY (parent_conversation_id, batch_id, task_index)"))
        assertEquals(14, SubAgentTaskSchema.DATABASE_VERSION)
        assertEquals("ALTER TABLE sub_agent_tasks ADD COLUMN tool_call_id TEXT NOT NULL DEFAULT ''", SubAgentTaskSchema.UPGRADE_13_TO_14)
        assertTrue(SubAgentTaskSchema.CREATE_INDEX.contains("idx_sub_agent_tasks_open"))
    }
}
