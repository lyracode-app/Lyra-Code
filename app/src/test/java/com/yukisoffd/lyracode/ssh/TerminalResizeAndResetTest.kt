package com.yukisoffd.lyracode.ssh

import org.junit.Assert.*
import org.junit.Test

class TerminalResizeAndResetTest {
    private fun text(terminal: TerminalEmulator): String {
        val screen = terminal.snapshot()
        return TerminalSelectionText.extract(screen, TerminalSelection(
            TerminalPosition(0, 0), TerminalPosition(screen.rows - 1, screen.columns - 1),
        )).trimEnd()
    }

    @Test fun `keyboard shrink and grow preserves every line and the cursor`() {
        val terminal = TerminalEmulator(16, 12)
        val content = (0..11).joinToString("\r\n") { "line-$it" }
        terminal.accept(content.toByteArray())
        val original = terminal.snapshot()
        terminal.resize(16, 4)
        assertEquals(content.replace("\r", ""), text(terminal))
        assertEquals(original.cursorRow, terminal.snapshot().cursorRow)
        terminal.resize(16, 12)
        assertEquals(original, terminal.snapshot())
    }

    @Test fun `output received while keyboard is open survives closing it`() {
        val terminal = TerminalEmulator(16, 12)
        terminal.accept((0..11).joinToString("\r\n") { "line-$it" }.toByteArray())
        terminal.resize(16, 4)
        terminal.accept("\r\nnew-output".toByteArray())
        terminal.resize(16, 12)
        assertEquals((0..11).joinToString("\n") { "line-$it" } + "\nnew-output", text(terminal))
    }

    @Test fun `height changes preserve content below a cursor moved to the top`() {
        val terminal = TerminalEmulator(16, 8)
        terminal.accept((0..7).joinToString("\r\n") { "line-$it" }.toByteArray())
        terminal.accept("\u001b[H".toByteArray())
        val original = terminal.snapshot()
        terminal.resize(16, 2)
        terminal.resize(16, 8)
        assertEquals(original, terminal.snapshot())
    }

    @Test fun `column changes reflow scrollback and preserve CJK styles and combining text`() {
        val terminal = TerminalEmulator(12, 3)
        val content = "history one\r\nhistory two\r\n\u001b[91m中文한글e\u0301 Japanese 日本語 end"
        terminal.accept(content.toByteArray())
        val original = text(terminal)
        repeat(4) {
            terminal.resize(5, 2)
            assertEquals(original, text(terminal))
            terminal.resize(20, 9)
            assertEquals(original, text(terminal))
        }
        assertEquals(9, terminal.snapshot().cells.first { it.codePoint == '中'.code }.foreground)
        terminal.accept("X".toByteArray())
        assertEquals(original + "X", text(terminal))
    }

    @Test fun `alternate screen keyboard crop preserves hidden rows and wide columns`() {
        val terminal = TerminalEmulator(8, 8)
        terminal.accept("shell\u001b[?1049h".toByteArray())
        terminal.accept((0..7).joinToString("\r\n") { "a你-$it" }.toByteArray())
        val original = text(terminal)
        terminal.resize(2, 2)
        terminal.resize(8, 8)
        assertEquals(original, text(terminal))
        terminal.accept("\u001b[?1049l".toByteArray())
        assertEquals("shell", text(terminal))
    }

    @Test fun `clearing removes history saved alternate content and stale input fragments`() {
        val terminal = TerminalEmulator(8, 2)
        terminal.accept("one\r\ntwo\r\nthree\u001b[?1049hOLD".toByteArray())
        terminal.accept(byteArrayOf(0xe4.toByte(), 0xbd.toByte()))
        terminal.clear()
        terminal.accept("new\u001b[?1049l".toByteArray())
        assertEquals("", text(terminal)) // The cleared normal screen cannot restore old output.
        assertEquals(1L, terminal.snapshot().resetGeneration)
        terminal.accept("fresh".toByteArray())
        assertEquals("fresh", text(terminal))
        assertEquals(5, terminal.snapshot().cursorColumn)
    }

    @Test fun `new session resets cursor colors modes margins and incomplete escape sequence`() {
        val terminal = TerminalEmulator(8, 4)
        terminal.accept("old\u001b[31m\u001b[?1049h\u001b[?25l\u001b[2;3r\u001b[38;2;".toByteArray())
        terminal.resetForNewSession()
        terminal.accept("new\r\nready".toByteArray())
        val screen = terminal.snapshot()
        assertEquals("new\nready", text(terminal))
        assertEquals(1, screen.cursorRow)
        assertEquals(5, screen.cursorColumn)
        assertTrue(screen.cursorVisible)
        assertEquals(TERMINAL_DEFAULT_FG, screen.cells[0].foreground)
        assertEquals(1L, screen.resetGeneration)
    }
}
