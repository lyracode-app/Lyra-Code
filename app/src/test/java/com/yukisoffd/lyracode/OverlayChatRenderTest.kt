package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ChatRecord
import org.junit.Assert.*
import org.junit.Test

class OverlayChatRenderTest {
    private val turn = listOf(
        ChatRecord(1, "user", "任务"),
        ChatRecord(2, "assistant", "先观察", thinking = "思考一"),
        ChatRecord(3, "tool", "结果", toolName = "device_observe"),
        ChatRecord(4, "assistant", "继续检查", thinking = "思考二"),
    )
    @Test fun runningOverlayKeepsAllIntermediateContentInOneProcess() {
        val rows = chatRenderItems(turn, isStreaming = true, collapseStreamingProse = true)
        assertEquals(2, rows.size)
        assertEquals("user", rows.first().message?.role)
        assertEquals(turn.drop(1), rows.last().process)
        assertFalse(rows.any { it.message?.role == "assistant" })
    }
    @Test fun completionShowsOnlyFinalBodyAndKeepsEarlierTurnsFolded() {
        val complete = turn + ChatRecord(5, "assistant", "最终结论", thinking = "最后检查")
        val rows = chatRenderItems(complete, collapseStreamingProse = true)
        assertEquals(listOf("任务", "最终结论"), rows.mapNotNull { it.message?.content })
        val process = rows.single { it.process.isNotEmpty() }.process
        assertTrue(process.any { it.content == "先观察" })
        assertTrue(process.any { it.content == "继续检查" })
        assertTrue(process.any { it.thinking == "最后检查" && it.content.isEmpty() })
        assertEquals("", rows.last().message!!.thinking)
        val next = chatRenderItems(complete + ChatRecord(6, "user", "下一任务") + ChatRecord(7, "assistant", "处理中"),
            isStreaming = true, collapseStreamingProse = true)
        assertEquals(rows, next.take(rows.size))
        assertEquals("处理中", next.last().process.single().content)
    }
}
