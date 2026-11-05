package com.yukisoffd.lyracode.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    @Test
    fun `parses cursor movement and ansi colors`() {
        val terminal = TerminalEmulator(12, 4)
        terminal.accept("abc\u001b[2;3H\u001b[31mX".toByteArray())

        val snapshot = terminal.snapshot()
        assertEquals('a'.code, snapshot.cells[0].codePoint)
        assertEquals('X'.code, snapshot.cells[1 * snapshot.columns + 2].codePoint)
        assertEquals(1, snapshot.cells[1 * snapshot.columns + 2].foreground)
    }

    @Test
    fun `supports alternate screen used by vim`() {
        val terminal = TerminalEmulator(10, 3)
        terminal.accept("shell".toByteArray())
        terminal.accept("\u001b[?1049hvim".toByteArray())
        assertEquals('v'.code, terminal.snapshot().cells[0].codePoint)

        terminal.accept("\u001b[?1049l".toByteArray())
        val restored = terminal.snapshot()
        assertEquals("shell", restored.cells.take(5).map { it.codePoint.toChar() }.joinToString(""))
    }

    @Test
    fun `preserves split utf8 input and wide cell continuation`() {
        val terminal = TerminalEmulator(10, 2)
        val bytes = "你".toByteArray()
        terminal.accept(bytes.copyOfRange(0, 2))
        terminal.accept(bytes.copyOfRange(2, bytes.size))

        val snapshot = terminal.snapshot()
        assertEquals('你'.code, snapshot.cells[0].codePoint)
        assertTrue(snapshot.cells[1].continuation)
        assertFalse(snapshot.cells[2].continuation)
    }

    @Test
    fun `erase display clears visible buffer`() {
        val terminal = TerminalEmulator(8, 2)
        terminal.accept("content\u001b[2J".toByteArray())
        assertTrue(terminal.snapshot().cells.all { it.codePoint == 32 })
    }

    @Test
    fun `growing rows keeps existing output at top`() {
        val terminal = TerminalEmulator(8, 5)
        terminal.accept("one\r\ntwo".toByteArray())

        terminal.resize(8, 8)

        val snapshot = terminal.snapshot()
        val top = snapshot.cells.take(snapshot.columns)
            .map { it.codePoint.toChar() }
            .joinToString("")
            .trimEnd()
        assertEquals("one", top)
        assertEquals(1, snapshot.cursorRow)
    }

    @Test
    fun `normal screen keeps scrolled output in history`() {
        val terminal = TerminalEmulator(6, 2)
        terminal.accept("one\r\ntwo\r\ntri".toByteArray())

        val snapshot = terminal.snapshot()
        assertEquals(3, snapshot.rows)
        assertEquals("one", snapshot.cells.take(6).map { it.codePoint.toChar() }.joinToString("").trimEnd())
        assertEquals(2, snapshot.cursorRow)
    }

    @Test
    fun `selection extracts text without continuation cells or trailing padding`() {
        val terminal = TerminalEmulator(6, 2)
        terminal.accept("你a\r\nb".toByteArray())
        val snapshot = terminal.snapshot()

        val copied = TerminalSelectionText.extract(
            snapshot,
            TerminalSelection(TerminalPosition(0, 0), TerminalPosition(1, 5)),
        )

        assertEquals("你a\nb", copied)
    }

    @Test
    fun `long press selects a complete word`() {
        val terminal = TerminalEmulator(16, 2)
        terminal.accept("echo hello world".toByteArray())
        val snapshot = terminal.snapshot()

        val selection = TerminalSelectionText.wordAt(snapshot, TerminalPosition(0, 7))

        assertEquals("hello", TerminalSelectionText.extract(snapshot, selection))
    }

    @Test
    fun `mixed CJK combining and supplementary characters keep their columns across byte packets`() {
        val terminal = TerminalEmulator(40, 3)
        val text = "A中文한글かなカナ𠀀e\u0301Z"
        text.toByteArray(Charsets.UTF_8).forEach { terminal.accept(byteArrayOf(it)) }
        val screen = terminal.snapshot()
        assertEquals(21, screen.cursorColumn)
        assertEquals("e\u0301", screen.cells[19].text)
        assertEquals(text, TerminalSelectionText.extract(screen, TerminalSelection(TerminalPosition(0, 0), TerminalPosition(0, 20))))
    }

    @Test
    fun `combining mark after final column does not cause a wrap`() {
        val terminal = TerminalEmulator(2, 2)
        terminal.accept("ae\u0301".toByteArray())
        assertEquals(0, terminal.snapshot().cursorRow)
        assertEquals("e\u0301", terminal.snapshot().cells[1].text)
    }

    @Test
    fun `overwrite or erase either half removes the whole wide glyph`() {
        for (operation in listOf("X", "\u001b[X", "\u001b[K")) {
            val terminal = TerminalEmulator(8, 2)
            terminal.accept(("你好\r\u001b[C" + operation).toByteArray())
            val cells = terminal.snapshot().cells
            assertEquals(32, cells[0].codePoint)
            assertFalse(cells[1].continuation)
        }
    }

    @Test
    fun `resizing through a wide glyph leaves no orphaned half`() {
        val terminal = TerminalEmulator(8, 2)
        terminal.accept("a你".toByteArray())
        terminal.resize(2, 2)
        assertEquals(32, terminal.snapshot().cells[1].codePoint)
    }

    @Test
    fun `resize while using alternate screen restores a valid CJK grid`() {
        val terminal = TerminalEmulator(8, 2)
        terminal.accept("a你\u001b[?1049h".toByteArray())
        terminal.resize(2, 3)
        terminal.accept("\u001b[?1049l".toByteArray())
        val screen = terminal.snapshot()
        assertEquals(6, screen.cells.size)
        assertEquals('a'.code, screen.cells[0].codePoint)
        assertEquals(32, screen.cells[1].codePoint)
    }

    @Test
    fun `selecting the second half of a CJK glyph copies its whole word`() {
        val terminal = TerminalEmulator(12, 2)
        terminal.accept(" 中文 test".toByteArray())
        val screen = terminal.snapshot()
        assertEquals("中文", TerminalSelectionText.extract(screen, TerminalSelectionText.wordAt(screen, TerminalPosition(0, 2))))
        assertEquals("中", TerminalSelectionText.extract(screen, TerminalSelection(TerminalPosition(0, 2), TerminalPosition(0, 2))))
    }

    @Test
    fun `copy joins soft wraps and preserves explicit line breaks`() {
        val terminal = TerminalEmulator(4, 4)
        terminal.accept("abcdef\r\nxy".toByteArray())
        val screen = terminal.snapshot()
        assertEquals("abcdef\nxy", TerminalSelectionText.extract(screen, TerminalSelection(TerminalPosition(0, 0), TerminalPosition(2, 3))))
    }

    @Test
    fun `copy preserves spaces at a soft wrap but skips unused wide character padding`() {
        for (text in listOf("abc def", "abc你好")) {
            val terminal = TerminalEmulator(4, 4)
            terminal.accept(text.toByteArray())
            val screen = terminal.snapshot()
            assertEquals(text, TerminalSelectionText.extract(screen, TerminalSelection(TerminalPosition(0, 0), TerminalPosition(1, 3))))
        }
    }

    @Test
    fun `delete shifts a wide glyph at the end of a line intact`() {
        val terminal = TerminalEmulator(8, 2)
        terminal.accept("abcdef你\r\u001b[P".toByteArray())
        val cells = terminal.snapshot().cells
        assertEquals('你'.code, cells[5].codePoint)
        assertTrue(cells[6].continuation)
        assertFalse(cells[7].continuation)
    }

    @Test
    fun `emoji joiners modifiers and flags occupy one wide cluster`() {
        val terminal = TerminalEmulator(30, 2)
        val text = "👩‍💻👍🏽🇨🇳X"
        terminal.accept(text.toByteArray())
        val screen = terminal.snapshot()
        assertEquals(7, screen.cursorColumn)
        assertEquals(text, TerminalSelectionText.extract(screen, TerminalSelection(TerminalPosition(0, 0), TerminalPosition(0, 6))))
    }

    @Test
    fun `ANSI indices truecolor and reset remain independent when changing palette`() {
        val terminal = TerminalEmulator(20, 2)
        terminal.accept("\u001b[91mR\u001b[38;5;196mX\u001b[38;2;12;34;56mT\u001b[0mD".toByteArray())
        val cells = terminal.snapshot().cells
        assertEquals(9, cells[0].foreground)
        assertEquals(196, cells[1].foreground)
        assertEquals(0xff0c2238.toInt(), cells[2].foreground)
        assertEquals(TERMINAL_DEFAULT_FG, cells[3].foreground)
        assertEquals(0xffe74856.toInt(), TerminalPalette.CAMPBELL.resolve(cells[0].foreground))
        assertEquals(0xffcb4b16.toInt(), TerminalPalette.SOLARIZED_DARK.resolve(cells[0].foreground))
        assertEquals(0xffff0000.toInt(), TerminalPalette.CAMPBELL.resolve(cells[1].foreground))
        assertEquals(cells[2].foreground, TerminalPalette.SOLARIZED_DARK.resolve(cells[2].foreground))
    }
}
