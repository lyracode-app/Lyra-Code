package com.yukisoffd.lyracode

import org.junit.Assert.*
import org.junit.Test

class FileChangeLayoutTest {
    private val geometry = ChatOutputGeometry(10, 0, 300, 300, 6, 200, 100)

    @Test fun `zero height and stale anchor never trigger scrolling`() {
        val guard = ChatOutputFollowGuard()
        assertFalse(guard.shouldScroll(geometry.copy(viewportHeight = 0), 9, true, false, true))
        assertFalse(guard.shouldScroll(geometry, 10, true, false, true))
        assertTrue(guard.shouldScroll(geometry, 9, true, false, true))
    }

    @Test fun `scroll state and repeated measurements cannot create a feedback loop`() {
        val guard = ChatOutputFollowGuard()
        assertTrue(guard.shouldScroll(geometry, 9, true, false, true))
        repeat(1_000) {
            assertFalse(guard.shouldScroll(geometry.copy(), 9, true, true, true))
            assertFalse(guard.shouldScroll(geometry.copy(), 9, true, false, true))
        }
        assertTrue(guard.shouldScroll(geometry.copy(lastSize = 110), 9, true, false, true))
        assertFalse(guard.shouldScroll(geometry, 9, false, false, true))
        assertTrue(guard.shouldScroll(geometry, 9, true, false, true))
    }

    @Test fun `large single lines have bounded layout without losing text or splitting emoji`() {
        val text = "a".repeat(2_047) + "😀" + "b".repeat(200_000)
        val rows = codeDisplayLines(text)
        assertTrue(rows.all { it.end - it.start <= 2_048 })
        assertEquals(text, rows.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(rows.none { text[it.start].isLowSurrogate() || text[it.end - 1].isHighSurrogate() })
        assertTrue(rows.all { it.sourceLineStart == 0 })
    }

    @Test fun `code rows preserve empty lines CRLF and trailing newline`() {
        val text = "first\r\n\nlast\n"
        assertEquals(listOf("first", "", "last", ""), codeDisplayLines(text).map { text.substring(it.start, it.end) })
        assertEquals(listOf(CodeDisplayLine(0, 0, 0)), codeDisplayLines(""))
    }
}
