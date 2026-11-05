package com.yukisoffd.lyracode.ssh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

data class TerminalCell(
    var codePoint: Int = 32,
    var foreground: Int = TERMINAL_DEFAULT_FG,
    var background: Int = TERMINAL_DEFAULT_BG,
    var bold: Boolean = false,
    var inverse: Boolean = false,
    var continuation: Boolean = false,
    var combining: String = "",
    var softWrapped: Boolean = false,
    var wrapPadding: Boolean = false,
) {
    val text: String get() = String(Character.toChars(codePoint)) + combining
}

data class TerminalSnapshot(
    val columns: Int,
    val rows: Int,
    val cells: List<TerminalCell>,
    val cursorColumn: Int,
    val cursorRow: Int,
    val cursorVisible: Boolean,
    val resetGeneration: Long = 0L,
)

/** Small VT100/xterm screen model sufficient for full-screen programs such as vim and top. */
class TerminalEmulator(initialColumns: Int = 80, initialRows: Int = 24) {
    private var columns = initialColumns.coerceAtLeast(2)
    private var rows = initialRows.coerceAtLeast(2)
    private var cells = blankScreen()
    private val scrollback = ArrayDeque<List<TerminalCell>>()
    private var normalScreen: MutableList<TerminalCell>? = null
    private var belowNormalScreen = emptyList<List<TerminalCell>>()
    private var retainedAlternate = mutableListOf<MutableList<TerminalCell>>()
    private var alternateVisibleAtResize: List<TerminalCell>? = null
    private var normalCursor = TerminalPosition(0, 0)
    private var normalSavedCursor = TerminalPosition(0, 0)
    private var resetGeneration = 0L
    private var cursorColumn = 0
    private var cursorRow = 0
    private var savedColumn = 0
    private var savedRow = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var foreground = TERMINAL_DEFAULT_FG
    private var background = TERMINAL_DEFAULT_BG
    private var bold = false
    private var inverse = false
    private var cursorVisible = true
    private var state = ParseState.NORMAL
    private val sequence = StringBuilder()
    private var utf8Remainder = ByteArray(0)
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    @Synchronized
    fun resize(newColumns: Int, newRows: Int) {
        val targetColumns = newColumns.coerceIn(2, 500)
        val targetRows = newRows.coerceIn(2, 300)
        if (targetColumns == columns && targetRows == rows) return
        val oldColumns = columns
        val oldRows = rows
        val alternate = normalScreen != null
        val normal = TerminalBufferResize.resize(
            history = scrollback.toList(),
            screen = normalScreen ?: cells,
            belowScreen = belowNormalScreen,
            oldColumns = oldColumns, newColumns = targetColumns, newRows = targetRows,
            cursor = if (alternate) normalCursor else TerminalPosition(cursorRow, cursorColumn),
            savedCursor = if (alternate) normalSavedCursor else TerminalPosition(savedRow, savedColumn),
        )
        scrollback.clear()
        normal.history.takeLast(MAX_SCROLLBACK_ROWS).forEach(scrollback::addLast)
        belowNormalScreen = normal.belowScreen
        columns = targetColumns
        rows = targetRows
        if (alternate) {
            // Full-screen applications redraw after SIGWINCH. Retain hidden rows/columns as
            // well, so a keyboard resize alone never destroys their existing frame.
            val retainedColumns = maxOf(oldColumns, columns, retainedAlternate.firstOrNull()?.size ?: 0)
            while (retainedAlternate.size < maxOf(oldRows, rows)) retainedAlternate += mutableListOf<TerminalCell>()
            retainedAlternate.forEach { line -> while (line.size < retainedColumns) line += TerminalCell() }
            for (row in 0 until oldRows) for (column in 0 until oldColumns) {
                val i = row * oldColumns + column
                if (alternateVisibleAtResize?.getOrNull(i) != cells[i]) {
                    retainedAlternate[row][column] = cells[i].copy()
                }
            }
            cells = MutableList(columns * rows) { i -> retainedAlternate[i / columns][i % columns].copy() }
            repeat(rows) { repairWideCells(it) }
            alternateVisibleAtResize = cells.map(TerminalCell::copy)
            normalScreen = normal.screen
            normalCursor = normal.cursor
            normalSavedCursor = normal.savedCursor
            cursorColumn = cursorColumn.coerceIn(0, columns - 1)
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            savedColumn = savedColumn.coerceIn(0, columns - 1)
            savedRow = savedRow.coerceIn(0, rows - 1)
        } else {
            cells = normal.screen
            cursorColumn = normal.cursor.column
            cursorRow = normal.cursor.row
            savedColumn = normal.savedCursor.column.coerceIn(0, columns - 1)
            savedRow = normal.savedCursor.row
        }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    @Synchronized
    fun accept(bytes: ByteArray) {
        val combined = ByteArray(utf8Remainder.size + bytes.size)
        utf8Remainder.copyInto(combined)
        bytes.copyInto(combined, utf8Remainder.size)
        val source = ByteBuffer.wrap(combined)
        val chars = CharBuffer.allocate(combined.size + 1)
        decoder.decode(source, chars, false)
        utf8Remainder = ByteArray(source.remaining()).also { source.get(it) }
        chars.flip()
        while (chars.hasRemaining()) {
            val first = chars.get()
            val codePoint = if (Character.isHighSurrogate(first) && chars.hasRemaining()) {
                val second = chars.get()
                if (Character.isLowSurrogate(second)) Character.toCodePoint(first, second) else first.code
            } else first.code
            process(codePoint)
        }
    }

    @Synchronized
    fun snapshot(): TerminalSnapshot = TerminalSnapshot(
        columns = columns,
        rows = if (normalScreen == null) scrollback.size + rows else rows,
        cells = if (normalScreen == null) {
            buildList((scrollback.size + rows) * columns) {
                scrollback.forEach { row -> row.forEach { add(it.copy()) } }
                cells.forEach { add(it.copy()) }
            }
        } else {
            cells.map(TerminalCell::copy)
        },
        cursorColumn = cursorColumn,
        cursorRow = if (normalScreen == null) scrollback.size + cursorRow else cursorRow,
        cursorVisible = cursorVisible,
        resetGeneration = resetGeneration,
    )

    private fun process(codePoint: Int) {
        when (state) {
            ParseState.NORMAL -> when (codePoint) {
                0x1b -> state = ParseState.ESCAPE
                0x08 -> cursorColumn = (cursorColumn - 1).coerceAtLeast(0)
                0x09 -> cursorColumn = (((cursorColumn / 8) + 1) * 8).coerceAtMost(columns - 1)
                0x0a, 0x0b, 0x0c -> lineFeed()
                0x0d -> cursorColumn = 0
                in 0x20..0x10ffff -> put(codePoint)
            }
            ParseState.ESCAPE -> when (codePoint.toChar()) {
                '[' -> { sequence.clear(); state = ParseState.CSI }
                ']' -> { sequence.clear(); state = ParseState.OSC }
                '7' -> { saveCursor(); state = ParseState.NORMAL }
                '8' -> { restoreCursor(); state = ParseState.NORMAL }
                'D' -> { lineFeed(); state = ParseState.NORMAL }
                'M' -> { reverseIndex(); state = ParseState.NORMAL }
                'E' -> { cursorColumn = 0; lineFeed(); state = ParseState.NORMAL }
                'c' -> { reset(); state = ParseState.NORMAL }
                else -> state = ParseState.NORMAL
            }
            ParseState.CSI -> {
                if (codePoint in 0x40..0x7e) {
                    handleCsi(sequence.toString(), codePoint.toChar())
                    sequence.clear()
                    state = ParseState.NORMAL
                } else if (sequence.length < 128) sequence.appendCodePoint(codePoint)
            }
            ParseState.OSC -> when (codePoint) {
                0x07 -> state = ParseState.NORMAL
                0x1b -> state = ParseState.OSC_ESCAPE
                else -> Unit
            }
            ParseState.OSC_ESCAPE -> state = if (codePoint == '\\'.code) ParseState.NORMAL else ParseState.OSC
        }
    }

    private fun put(codePoint: Int) {
        val width = TerminalUnicode.width(codePoint)
        var previousColumn = (cursorColumn - 1).coerceAtMost(columns - 1)
        if (previousColumn >= 0 && cells[index(previousColumn, cursorRow)].continuation) previousColumn--
        val previous = previousColumn.takeIf { it >= 0 }?.let { cells[index(it, cursorRow)] }
        val joinsEmoji = previous?.combining?.endsWith("\u200d") == true
        val joinsFlag = previous?.codePoint in 0x1f1e6..0x1f1ff &&
            previous?.combining.isNullOrEmpty() && codePoint in 0x1f1e6..0x1f1ff
        if (width == 0 || joinsEmoji || joinsFlag) {
            if (previous != null) {
                previous.combining += String(Character.toChars(codePoint))
                // Emoji presentation/keycap sequences may promote a narrow base to two cells.
                if ((codePoint == 0xfe0f || codePoint == 0x20e3) &&
                    previousColumn + 1 < columns && !cells[index(previousColumn + 1, cursorRow)].continuation
                ) {
                    clearGlyph(previousColumn + 1, cursorRow)
                    cells[index(previousColumn + 1, cursorRow)] = previous.copy(codePoint = 32, combining = "", continuation = true)
                    cursorColumn = maxOf(cursorColumn, previousColumn + 2)
                }
            }
            return
        }
        if (cursorColumn >= columns || (width == 2 && cursorColumn == columns - 1)) {
            cells[index(columns - 1, cursorRow)].softWrapped = true
            if (width == 2 && cursorColumn == columns - 1) cells[index(columns - 1, cursorRow)].wrapPadding = true
            cursorColumn = 0
            lineFeed()
        }
        clearGlyph(cursorColumn, cursorRow)
        if (width == 2) clearGlyph(cursorColumn + 1, cursorRow)
        cells[index(cursorColumn, cursorRow)] = TerminalCell(codePoint, foreground, background, bold, inverse)
        if (width == 2 && cursorColumn + 1 < columns) {
            cells[index(cursorColumn + 1, cursorRow)] = TerminalCell(
                foreground = foreground, background = background, bold = bold, inverse = inverse, continuation = true,
            )
        }
        cursorColumn += width
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) scrollUp(1) else cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1) else cursorRow = (cursorRow - 1).coerceAtLeast(0)
    }

    private fun handleCsi(raw: String, command: Char) {
        val privateMode = raw.startsWith('?')
        val clean = raw.trimStart('?', '>', '!').filter { it.isDigit() || it == ';' || it == ':' }
        val params = clean.split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
        fun p(index: Int, default: Int = 1) = params.getOrNull(index)?.takeIf { it != 0 } ?: default
        when (command) {
            'A' -> cursorRow = (cursorRow - p(0)).coerceAtLeast(scrollTop)
            'B', 'e' -> cursorRow = (cursorRow + p(0)).coerceAtMost(scrollBottom)
            'C', 'a' -> cursorColumn = (cursorColumn + p(0)).coerceAtMost(columns - 1)
            'D' -> cursorColumn = (cursorColumn - p(0)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p(0)).coerceAtMost(scrollBottom); cursorColumn = 0 }
            'F' -> { cursorRow = (cursorRow - p(0)).coerceAtLeast(scrollTop); cursorColumn = 0 }
            'G', '`' -> cursorColumn = (p(0) - 1).coerceIn(0, columns - 1)
            'd' -> cursorRow = (p(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (p(0) - 1).coerceIn(0, rows - 1)
                cursorColumn = (p(1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> eraseDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'X' -> repeat(p(0)) { column -> if (cursorColumn + column < columns) blank(cursorColumn + column, cursorRow) }
            '@' -> insertCharacters(p(0))
            'P' -> deleteCharacters(p(0))
            'L' -> insertLines(p(0))
            'M' -> deleteLines(p(0))
            'S' -> scrollUp(p(0))
            'T' -> scrollDown(p(0))
            'm' -> applySgr(params.ifEmpty { listOf(0) })
            'r' -> {
                scrollTop = (p(0) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorColumn = 0
                cursorRow = 0
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h', 'l' -> if (privateMode) setPrivateMode(params, command == 'h')
        }
    }

    private fun setPrivateMode(params: List<Int>, enabled: Boolean) {
        params.forEach { mode ->
            when (mode) {
                25 -> cursorVisible = enabled
                47, 1047, 1049 -> if (enabled) enterAlternateScreen() else leaveAlternateScreen()
            }
        }
    }

    private fun enterAlternateScreen() {
        if (normalScreen != null) return
        normalScreen = cells.map(TerminalCell::copy).toMutableList()
        normalCursor = TerminalPosition(cursorRow, cursorColumn)
        normalSavedCursor = TerminalPosition(savedRow, savedColumn)
        retainedAlternate.clear()
        alternateVisibleAtResize = null
        cells = blankScreen()
        cursorColumn = 0
        cursorRow = 0
    }

    private fun leaveAlternateScreen() {
        val restored = normalScreen ?: return
        cells = restored
        normalScreen = null
        retainedAlternate.clear()
        alternateVisibleAtResize = null
        cursorColumn = normalCursor.column.coerceIn(0, columns - 1)
        cursorRow = normalCursor.row.coerceIn(0, rows - 1)
        savedColumn = normalSavedCursor.column.coerceIn(0, columns - 1)
        savedRow = normalSavedCursor.row.coerceIn(0, rows - 1)
    }

    private fun applySgr(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            when (val value = params[i]) {
                0 -> { foreground = TERMINAL_DEFAULT_FG; background = TERMINAL_DEFAULT_BG; bold = false; inverse = false }
                1 -> bold = true
                7 -> inverse = true
                22 -> bold = false
                27 -> inverse = false
                30, 31, 32, 33, 34, 35, 36, 37 -> foreground = value - 30
                39 -> foreground = TERMINAL_DEFAULT_FG
                40, 41, 42, 43, 44, 45, 46, 47 -> background = value - 40
                49 -> background = TERMINAL_DEFAULT_BG
                90, 91, 92, 93, 94, 95, 96, 97 -> foreground = value - 90 + 8
                100, 101, 102, 103, 104, 105, 106, 107 -> background = value - 100 + 8
                38, 48 -> {
                    val isForeground = value == 38
                    if (params.getOrNull(i + 1) == 5 && params.getOrNull(i + 2) != null) {
                        val color = params[i + 2].coerceIn(0, 255)
                        if (isForeground) foreground = color else background = color
                        i += 2
                    } else if (params.getOrNull(i + 1) == 2 && i + 4 < params.size) {
                        val color = TerminalPalette.rgb(params[i + 2], params[i + 3], params[i + 4])
                        if (isForeground) foreground = color else background = color
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> for (row in cursorRow until rows) for (column in (if (row == cursorRow) cursorColumn else 0) until columns) blank(column, row)
            1 -> for (row in 0..cursorRow) for (column in 0..(if (row == cursorRow) cursorColumn else columns - 1)) blank(column, row)
            2, 3 -> cells = blankScreen()
        }
    }

    private fun eraseLine(mode: Int) {
        val range = when (mode) { 1 -> 0..cursorColumn; 2 -> 0 until columns; else -> cursorColumn until columns }
        range.forEach { blank(it, cursorRow) }
    }

    private fun insertCharacters(count: Int) {
        for (column in columns - 1 downTo cursorColumn + count) cells[index(column, cursorRow)] = cells[index(column - count, cursorRow)].copy()
        repeat(count.coerceAtMost((columns - cursorColumn).coerceAtLeast(0))) { cells[index(cursorColumn + it, cursorRow)] = emptyCell() }
        repairWideCells(cursorRow)
    }

    private fun deleteCharacters(count: Int) {
        for (column in cursorColumn until columns - count) cells[index(column, cursorRow)] = cells[index(column + count, cursorRow)].copy()
        for (column in (columns - count).coerceAtLeast(cursorColumn) until columns) cells[index(column, cursorRow)] = emptyCell()
        repairWideCells(cursorRow)
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (row in scrollBottom downTo cursorRow + 1) copyRow(row - 1, row)
            clearRow(cursorRow)
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (row in cursorRow until scrollBottom) copyRow(row + 1, row)
            clearRow(scrollBottom)
        }
    }

    private fun scrollUp(count: Int) = repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
        if (normalScreen == null && scrollTop == 0 && scrollBottom == rows - 1) {
            scrollback.addLast(List(columns) { column -> cells[index(column, 0)].copy() })
            while (scrollback.size > MAX_SCROLLBACK_ROWS) scrollback.removeFirst()
        }
        for (row in scrollTop until scrollBottom) copyRow(row + 1, row)
        clearRow(scrollBottom)
    }

    private fun scrollDown(count: Int) = repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
        for (row in scrollBottom downTo scrollTop + 1) copyRow(row - 1, row)
        clearRow(scrollTop)
    }

    private fun copyRow(from: Int, to: Int) = repeat(columns) { column -> cells[index(column, to)] = cells[index(column, from)].copy() }
    private fun clearRow(row: Int) = repeat(columns) { blank(it, row) }
    private fun blank(column: Int, row: Int) { clearGlyph(column, row) }
    private fun clearGlyph(column: Int, row: Int) {
        if (column !in 0 until columns) return
        val cell = cells[index(column, row)]
        if (cell.continuation && column > 0) cells[index(column - 1, row)] = emptyCell()
        if (!cell.continuation && column + 1 < columns && cells[index(column + 1, row)].continuation) {
            cells[index(column + 1, row)] = emptyCell()
        }
        cells[index(column, row)] = emptyCell()
    }
    private fun emptyCell() = TerminalCell(foreground = foreground, background = background)
    private fun repairWideCells(row: Int, buffer: MutableList<TerminalCell> = cells) {
        for (column in 0 until columns) {
            val cell = buffer[index(column, row)]
            if (cell.continuation) {
                if (column == 0 || buffer[index(column - 1, row)].continuation ||
                    TerminalUnicode.width(buffer[index(column - 1, row)].codePoint) != 2 &&
                    buffer[index(column - 1, row)].combining.none { it == '\ufe0f' || it == '\u20e3' }
                ) buffer[index(column, row)] = emptyCell()
            } else if (TerminalUnicode.width(cell.codePoint) == 2 &&
                (column + 1 == columns || !buffer[index(column + 1, row)].continuation)
            ) buffer[index(column, row)] = emptyCell()
        }
    }
    private fun index(column: Int, row: Int) = row * columns + column
    private fun blankScreen() = MutableList(columns * rows) { TerminalCell() }
    private fun saveCursor() { savedColumn = cursorColumn; savedRow = cursorRow }
    private fun restoreCursor() { cursorColumn = savedColumn.coerceIn(0, columns - 1); cursorRow = savedRow.coerceIn(0, rows - 1) }

    /** Clears local output and history without writing commands to the remote shell. */
    @Synchronized
    fun clear() {
        cells = blankScreen()
        if (normalScreen != null) normalScreen = blankScreen()
        scrollback.clear()
        belowNormalScreen = emptyList()
        retainedAlternate.clear()
        alternateVisibleAtResize = null
        cursorColumn = 0
        cursorRow = 0
        savedColumn = 0
        savedRow = 0
        normalCursor = TerminalPosition(0, 0)
        normalSavedCursor = TerminalPosition(0, 0)
        scrollTop = 0
        scrollBottom = rows - 1
        foreground = TERMINAL_DEFAULT_FG
        background = TERMINAL_DEFAULT_BG
        bold = false
        inverse = false
        state = ParseState.NORMAL
        sequence.clear()
        utf8Remainder = ByteArray(0)
        decoder.reset()
        resetGeneration++
    }

    /** A new PTY must not inherit the previous connection's cursor, modes or partial UTF-8. */
    @Synchronized
    fun resetForNewSession() {
        normalScreen = null
        cursorVisible = true
        clear()
    }

    private fun reset() = resetForNewSession()

    private enum class ParseState { NORMAL, ESCAPE, CSI, OSC, OSC_ESCAPE }

    private companion object {
        const val MAX_SCROLLBACK_ROWS = 2_000
    }
}
