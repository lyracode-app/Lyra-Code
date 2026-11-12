package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.MEDIA_MESSAGE_ROLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderItemsTest {
    @Test fun `tool call placeholders do not create empty reasoning rows`() {
        val messages = listOf(
            ChatRecord(1, "user", "task"),
            ChatRecord(2, "assistant", "", rawJson = "tool metadata"),
            ChatRecord(3, "tool", "result"),
            ChatRecord(4, "assistant", "summary", thinking = "check"),
        )
        for (streaming in listOf(false, true)) {
            val process = chatRenderItems(messages, isStreaming = streaming).flatMap { it.process }
            assertEquals(listOf(3L, -4L), process.map { it.id })
        }
    }

    @Test fun `interrupted turn keeps intermediate prose before its tools`() {
        val messages = listOf(
            ChatRecord(1, "user", "task"),
            ChatRecord(2, "assistant", "I will scroll", thinking = "plan"),
            ChatRecord(3, "tool", "interrupted"),
        )
        val items = chatRenderItems(messages)
        assertEquals(listOf(2L, 3L), items.last().process.map { it.id })
        assertEquals(listOf("task"), items.mapNotNull { it.message?.content })
    }

    @Test
    fun `collapses intermediate assistant output into one process item`() {
        val messages = listOf(
            ChatRecord(id = 1L, role = "user", content = "start", createdAt = 1_000L),
            ChatRecord(id = 2L, role = "assistant", content = "intermediate one", thinking = "plan", createdAt = 2_000L),
            ChatRecord(id = 3L, role = "tool", content = "tool result", createdAt = 3_000L),
            ChatRecord(id = 4L, role = "assistant", content = "intermediate two", createdAt = 4_000L),
            ChatRecord(id = 5L, role = "assistant", content = "final answer", thinking = "final check", createdAt = 5_000L),
        )

        val items = chatRenderItems(messages)

        assertEquals(3, items.size)
        assertEquals("user", items[0].message?.role)
        assertEquals(listOf(2L, 3L, 4L, -5L), items[1].process.map { it.id })
        assertEquals(2_000L, items[1].processStartedAt)
        assertEquals(5_000L, items[1].processFinishedAt)
        assertEquals("final answer", items[2].message?.content)
        assertTrue(items[2].message?.thinking.isNullOrBlank())
    }

    @Test
    fun `keeps a simple assistant response without a process item`() {
        val items = chatRenderItems(
            listOf(
                ChatRecord(id = 1L, role = "user", content = "hello"),
                ChatRecord(id = 2L, role = "assistant", content = "hi"),
            ),
        )

        assertEquals(2, items.size)
        assertTrue(items.none { it.process.isNotEmpty() })
        assertEquals("hi", items.last().message?.content)
    }

    @Test
    fun `groups adjacent process activity around intermediate output while streaming`() {
        val messages = listOf(
            ChatRecord(id = 1L, role = "user", content = "start", createdAt = 1_000L),
            ChatRecord(id = 2L, role = "assistant", content = "intermediate one", thinking = "plan one", createdAt = 2_000L),
            ChatRecord(id = 3L, role = "tool", content = "tool result", createdAt = 3_000L),
            ChatRecord(id = 4L, role = "assistant", content = "intermediate two", thinking = "plan two", createdAt = 4_000L),
        )

        val items = chatRenderItems(messages, isStreaming = true)

        assertEquals(5, items.size)
        assertEquals("user", items[0].message?.role)
        assertEquals("plan one", items[1].process.single().thinking)
        assertEquals("intermediate one", items[2].message?.content)
        assertEquals(listOf(3L, -4L), items[3].process.map { it.id })
        assertEquals("tool", items[3].process.first().role)
        assertEquals("plan two", items[3].process.last().thinking)
        assertEquals("intermediate two", items[4].message?.content)
    }

    @Test
    fun `keeps completed history collapsed while latest turn is streaming`() {
        val messages = listOf(
            ChatRecord(id = 1L, role = "user", content = "first request", createdAt = 1_000L),
            ChatRecord(id = 2L, role = "assistant", content = "intermediate", thinking = "plan", createdAt = 2_000L),
            ChatRecord(id = 3L, role = "tool", content = "tool result", createdAt = 3_000L),
            ChatRecord(id = 4L, role = "assistant", content = "first answer", createdAt = 4_000L),
            ChatRecord(id = 5L, role = "user", content = "second request", createdAt = 5_000L),
            ChatRecord(id = 6L, role = "assistant", content = "streaming answer", thinking = "working", createdAt = 6_000L),
        )

        val items = chatRenderItems(messages, isStreaming = true)

        assertEquals(
            listOf("message-1", "process-2", "message-4", "message-5", "process-6", "message-6"),
            items.map { it.key },
        )
        assertEquals(listOf(2L, 3L), items[1].process.map { it.id })
        assertEquals("working", items[4].process.single().thinking)
    }

    @Test
    fun `keeps all adjacent thinking and tools in one active process item`() {
        val messages = listOf(
            ChatRecord(id = 1L, role = "user", content = "start", createdAt = 1_000L),
            ChatRecord(id = 2L, role = "assistant", content = "", thinking = "plan", createdAt = 2_000L),
            ChatRecord(id = 3L, role = "tool", content = "first result", createdAt = 3_000L),
            ChatRecord(id = 4L, role = "assistant", content = "", thinking = "inspect result", createdAt = 4_000L),
            ChatRecord(id = 5L, role = "tool", content = "second result", createdAt = 5_000L),
        )

        val items = chatRenderItems(messages, isStreaming = true)

        assertEquals(listOf("message-1", "process-2"), items.map { it.key })
        assertEquals(listOf(2L, 3L, 4L, 5L), items.last().process.map { it.id })
        assertEquals(2_000L, items.last().processStartedAt)
        assertEquals(5_000L, items.last().processFinishedAt)
    }

    @Test
    fun `renders generated media as a standalone chat message`() {
        val items = chatRenderItems(
            listOf(
                ChatRecord(id = 1L, role = "user", content = "draw a landscape"),
                ChatRecord(id = 2L, role = "assistant", content = "", thinking = "calling image tool"),
                ChatRecord(id = 3L, role = "tool", content = "{\"status\":\"success\",\"media_message_id\":4}"),
                ChatRecord(id = 4L, role = MEDIA_MESSAGE_ROLE, content = "![Generated image 1](C:/media/landscape.png)"),
                ChatRecord(id = 5L, role = "assistant", content = "The image has been generated."),
            ),
        )

        assertEquals(
            listOf("message-1", "process-2", "message-4", "message-5"),
            items.map { it.key },
        )
        assertEquals(MEDIA_MESSAGE_ROLE, items[2].message?.role)
        assertTrue(items[2].process.isEmpty())
    }
}
