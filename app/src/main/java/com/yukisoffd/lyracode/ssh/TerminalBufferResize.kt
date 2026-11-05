package com.yukisoffd.lyracode.ssh

/** Resizes normal-screen content without throwing away rows hidden by an IME or rotation. */
internal object TerminalBufferResize {
    data class Result(
        val history: List<List<TerminalCell>>,
        val screen: MutableList<TerminalCell>,
        val belowScreen: List<List<TerminalCell>>,
        val cursor: TerminalPosition,
        val savedCursor: TerminalPosition,
    )

    fun resize(
        history: List<List<TerminalCell>>,
        screen: List<TerminalCell>,
        belowScreen: List<List<TerminalCell>>,
        oldColumns: Int,
        newColumns: Int,
        newRows: Int,
        cursor: TerminalPosition,
        savedCursor: TerminalPosition,
    ): Result {
        val lines = (history + screen.chunked(oldColumns) + belowScreen).toMutableList()
        val absoluteCursor = cursor.copy(row = history.size + cursor.row)
        val absoluteSaved = savedCursor.copy(row = history.size + savedCursor.row)
        // Only unused padding below the cursor can disappear. Content below a moved cursor
        // remains retained separately if it does not fit in the smaller active screen.
        while (lines.lastIndex > maxOf(absoluteCursor.row, absoluteSaved.row) && lines.last().all(::unused)) {
            lines.removeAt(lines.lastIndex)
        }
        val reflow = if (oldColumns == newColumns) {
            Reflow(lines.map { it.map(TerminalCell::copy) }, absoluteCursor, absoluteSaved)
        } else reflow(lines, oldColumns, newColumns, absoluteCursor, absoluteSaved)
        val firstRow = (reflow.lines.size - newRows).coerceAtLeast(0).coerceAtMost(reflow.cursor.row)
        val active = reflow.lines.drop(firstRow).take(newRows).flatten().toMutableList()
        while (active.size < newRows * newColumns) active += TerminalCell()
        return Result(
            reflow.lines.take(firstRow), active, reflow.lines.drop(firstRow + newRows),
            reflow.cursor.copy(row = reflow.cursor.row - firstRow),
            reflow.saved.copy(row = reflow.saved.row - firstRow),
        )
    }

    private data class Reflow(val lines: List<List<TerminalCell>>, val cursor: TerminalPosition, val saved: TerminalPosition)

    private fun reflow(
        source: List<List<TerminalCell>>,
        oldColumns: Int,
        newColumns: Int,
        cursor: TerminalPosition,
        saved: TerminalPosition,
    ): Reflow {
        val output = mutableListOf<List<TerminalCell>>()
        var mappedCursor = TerminalPosition(0, 0)
        var mappedSaved = TerminalPosition(0, 0)
        var first = 0
        while (first < source.size) {
            var last = first
            while (last < source.lastIndex && source[last].last().softWrapped) last++
            val glyphs = mutableListOf<Pair<TerminalCell, Int>>()
            var cursorOffset: Int? = null
            var savedOffset: Int? = null
            var logicalColumns = 0
            for (row in first..last) {
                val line = source[row]
                var used = if (row < last) oldColumns else line.indexOfLast { !unused(it) } + 1
                if (row == cursor.row) used = maxOf(used, cursor.column)
                if (row == saved.row) used = maxOf(used, saved.column)
                if (row == cursor.row) cursorOffset = logicalColumns + cursor.column
                if (row == saved.row) savedOffset = logicalColumns + saved.column
                for (column in 0 until used.coerceAtMost(oldColumns)) {
                    val cell = line[column]
                    if (cell.wrapPadding) continue
                    if (!cell.continuation) {
                        val width = if (column + 1 < oldColumns && line[column + 1].continuation) 2 else 1
                        glyphs += cell.copy(softWrapped = false, wrapPadding = false) to width
                    }
                    logicalColumns++
                }
            }
            var line = MutableList(newColumns) { TerminalCell() }
            var column = 0
            var consumed = 0
            fun mapPosition(offset: Int?, set: (TerminalPosition) -> Unit) {
                if (offset == consumed) set(TerminalPosition(output.size, column))
            }
            glyphs.forEach { (cell, width) ->
                if (column + width > newColumns) {
                    line.last().softWrapped = true
                    if (column < newColumns) line.last().wrapPadding = true
                    output += line
                    line = MutableList(newColumns) { TerminalCell() }
                    column = 0
                }
                mapPosition(cursorOffset) { mappedCursor = it }
                mapPosition(savedOffset) { mappedSaved = it }
                if (width == 2) {
                    if (cursorOffset == consumed + 1) mappedCursor = TerminalPosition(output.size, column + 1)
                    if (savedOffset == consumed + 1) mappedSaved = TerminalPosition(output.size, column + 1)
                }
                line[column] = cell
                if (width == 2) line[column + 1] = cell.copy(codePoint = 32, combining = "", continuation = true)
                column += width
                consumed += width
            }
            mapPosition(cursorOffset) { mappedCursor = it }
            mapPosition(savedOffset) { mappedSaved = it }
            output += line
            first = last + 1
        }
        return Reflow(output, mappedCursor, mappedSaved)
    }

    private fun unused(cell: TerminalCell) = cell.codePoint == 32 && cell.combining.isEmpty() &&
        !cell.continuation && !cell.inverse && !cell.softWrapped && cell.background == TERMINAL_DEFAULT_BG
}
