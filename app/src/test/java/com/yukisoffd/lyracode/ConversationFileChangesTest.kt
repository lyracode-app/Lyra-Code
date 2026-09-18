package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ChatRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFileChangesTest {
    private fun message(id: Long, count: Int = 1) = ChatRecord(
        id = id,
        role = "tool",
        content = (0 until count).joinToString("\n") { index ->
            "LYRA_FILE_CHANGE_BEGIN\npath: $id-$index.kt\nadded: 1\nremoved: 0\ndiff:\n+new\nLYRA_FILE_CHANGE_END"
        },
    )

    @Test
    fun `long history stops scanning after the latest twenty changes`() {
        var scanned = 0
        val events = recentConversationFileChanges((1L..10_000L).map { message(it) }) { scanned++ }
        assertEquals(20, scanned)
        assertEquals((9_981L..10_000L).toList(), events.map { it.messageId })
    }

    @Test
    fun `retains original event indices and chronological order across messages`() {
        val events = recentConversationFileChanges(listOf(message(1, 25), message(2, 3)))
        assertEquals((8..24).toList() + (0..2).toList(), events.map { it.index })
        assertEquals(List(17) { 1L } + List(3) { 2L }, events.map { it.messageId })
        assertEquals("1:8:1-8.kt", events.first().key)
    }

    @Test
    fun `messages without changes do not consume the limit`() {
        val events = recentConversationFileChanges(listOf(message(1), ChatRecord(id = 2, role = "assistant", content = "done")))
        assertEquals(listOf(1L), events.map { it.messageId })
        assertEquals(emptyList<ConversationFileChange>(), recentConversationFileChanges(emptyList()))
    }
}
