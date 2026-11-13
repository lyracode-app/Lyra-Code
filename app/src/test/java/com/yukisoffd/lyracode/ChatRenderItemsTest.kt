package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.MEDIA_MESSAGE_ROLE
import com.yukisoffd.lyracode.data.AppSettings
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

    /** The transcript shape from the reported bug: a status line, an answer, a trailing tool call, a closing line. */
    private fun workedTurn(
        answer: String,
        question: String = "freebsd docker?",
        status: String = "I will check the current landscape.",
        statusThinking: String = "plan",
        closing: String = "Sources are linked above.",
    ) = listOf(
        ChatRecord(id = 1L, role = "user", content = question, createdAt = 1_000L),
        ChatRecord(id = 2L, role = "assistant", content = status, thinking = statusThinking, createdAt = 2_000L),
        ChatRecord(id = 3L, role = "tool", content = "search results", createdAt = 3_000L),
        ChatRecord(id = 4L, role = "assistant", content = answer, thinking = "long reasoning", createdAt = 4_000L),
        ChatRecord(id = 5L, role = "tool", content = "sources marked", createdAt = 5_000L),
        ChatRecord(id = 6L, role = "assistant", content = closing, createdAt = 6_000L),
    )

    /** The same six records, with only the answer and the closing line readable. */
    private val workedTurnKeys = listOf("message-1", "process-2", "message-4", "process-5", "message-6")

    /** A four-record turn whose single body either folds or stays, depending on the rule. */
    private fun plainTurn(body: String) = listOf(
        ChatRecord(id = 1L, role = "user", content = "ask", createdAt = 1_000L),
        ChatRecord(id = 2L, role = "assistant", content = body, createdAt = 2_000L),
        ChatRecord(id = 3L, role = "tool", content = "result", createdAt = 3_000L),
        ChatRecord(id = 4L, role = "assistant", content = "Done.", createdAt = 4_000L),
    )

    private val bodyFoldedKeys = listOf("message-1", "process-2", "message-4")
    private val bodyVisibleKeys = listOf("message-1", "message-2", "process-3", "message-4")

    @Test
    fun `keeps a written answer visible when tool work follows it`() {
        val answer = """
            ## Short answer

            Yes, Podman plus Buildah is the Docker equivalent.

            - Containerfile builds
            - Official base images
        """.trimIndent()
        val items = chatRenderItems(workedTurn(answer = answer))

        assertEquals(workedTurnKeys, items.map { it.key })
        // Status prose and the answer's own reasoning stay in the log, text intact.
        assertEquals(listOf(2L, 3L, -4L), items[1].process.map { it.id })
        assertTrue(items[1].process.any { it.content == "I will check the current landscape." })
        assertEquals(answer, items[2].message?.content)
        assertEquals("", items[2].message?.thinking)
        assertEquals(listOf(5L), items[3].process.map { it.id })
        assertEquals("Sources are linked above.", items[4].message?.content)
        // A card spans its own records, not the whole turn: this one ends at the answer's
        // reasoning copy rather than running on to the closing line.
        assertEquals(2_000L, items[1].processStartedAt)
        assertEquals(4_000L, items[1].processFinishedAt)
        assertEquals(5_000L, items[3].processStartedAt)
        assertEquals(5_000L, items[3].processFinishedAt)
    }

    @Test
    fun `folds a one-sentence status line but keeps multi-block prose`() {
        val twoBlocks = """
            First block.

            Second block.
        """.trimIndent()
        val items = chatRenderItems(
            workedTurn(
                answer = twoBlocks,
                status = "Checking the handbook first.",
                statusThinking = "",
                closing = "Done.",
            ),
        )

        assertEquals(workedTurnKeys, items.map { it.key })
        assertTrue(items[1].process.any { it.content == "Checking the handbook first." })
        assertEquals(twoBlocks, items[2].message?.content)
    }

    @Test
    fun `keeps a long answer visible when a short closing line follows it`() {
        val answer = "## Short answer\n\n" + "Podman plus Buildah is the Docker equivalent. ".repeat(40).trim()
        val items = chatRenderItems(workedTurn(answer = answer))

        assertEquals(workedTurnKeys, items.map { it.key })
        assertEquals(answer, items[2].message?.content)
    }

    @Test
    fun `folds a short aside that only looks like prose`() {
        val aside = "No DNS changes. You literally just go to that address.\n\nQuick confirmation that it's still serving:"
        val answer = "It ships its own package manager and prefix. ".repeat(15).trim()
        val items = chatRenderItems(
            workedTurn(
                answer = answer,
                question = "how do I reach it?",
                status = aside,
                statusThinking = "",
                closing = "Asked and answered.",
            ),
        )

        assertEquals(workedTurnKeys, items.map { it.key })
        // The aside has a blank line, so the shape test alone would surface it. A larger
        // body follows it, so it stays in the log with its text intact.
        assertTrue(items[1].process.any { it.content == aside })
        assertEquals(answer, items[2].message?.content)
    }

    @Test
    fun `keeps a short CJK answer visible because no larger body follows it`() {
        val answer = """
            简短回答：Podman 配合 Buildah 就是 FreeBSD 上的容器方案。

            它支持 OCI 镜像规范和 Containerfile 构建，官方基础镜像也已经可用。
        """.trimIndent()

        // CJK carries about a word per character, so this answer is short by character
        // count and long by meaning. It must not depend on the character threshold.
        assertTrue(answer.length < 400)

        val items = chatRenderItems(
            workedTurn(
                answer = answer,
                question = "freebsd 上怎么跑容器？",
                status = "我先确认一下最新的版本情况。",
                statusThinking = "计划",
                closing = "来源链接见上方。",
            ),
        )

        assertEquals(workedTurnKeys, items.map { it.key })
        assertTrue(items[1].process.any { it.content == "我先确认一下最新的版本情况。" })
        assertEquals(answer, items[2].message?.content)
    }

    @Test
    fun `switches on the answer length at its boundary`() {
        // A single unbroken line, so only the length carries the body.
        assertEquals(bodyFoldedKeys, chatRenderItems(plainTurn("a".repeat(399))).map { it.key })
        assertEquals(bodyVisibleKeys, chatRenderItems(plainTurn("a".repeat(400))).map { it.key })
    }

    @Test
    fun `shows every reply when folded replies is all`() {
        val items = chatRenderItems(
            plainTurn("Checking the handbook first."),
            foldedReplies = AppSettings.FOLDED_REPLIES_ALL,
        )

        assertEquals(bodyVisibleKeys, items.map { it.key })
        // The status line that folds under the default stays visible, and the tool row
        // still sits in a process card.
        assertEquals("Checking the handbook first.", items[1].message?.content)
        assertEquals(listOf(3L), items[2].process.map { it.id })
        assertEquals("Done.", items[3].message?.content)
    }
}
