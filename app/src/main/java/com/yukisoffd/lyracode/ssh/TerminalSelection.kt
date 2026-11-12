package com.yukisoffd.lyracode.ssh

data class TerminalPosition(val row: Int, val column: Int)

data class TerminalSelection(val anchor: TerminalPosition, val extent: TerminalPosition) {
    fun ordered(columns: Int): Pair<TerminalPosition, TerminalPosition> {
        val anchorIndex = anchor.row * columns + anchor.column
        val extentIndex = extent.row * columns + extent.column
        return if (anchorIndex <= extentIndex) anchor to extent else extent to anchor
    }
}

internal object TerminalSelectionText {
    fun wordAt(snapshot: TerminalSnapshot, position: TerminalPosition): TerminalSelection {
        val safe = leadingPosition(snapshot, position.clamp(snapshot))
        fun cell(column: Int) = snapshot.cells[safe.row * snapshot.columns + column]
        fun selectable(column: Int): Boolean {
            val value = cell(column)
            return !value.continuation && value.codePoint != 32 && !Character.isWhitespace(value.codePoint)
        }
        if (!selectable(safe.column)) return TerminalSelection(safe, safe)
        var start = safe.column
        var end = safe.column
        while (start > 0 && (selectable(start - 1) || cell(start - 1).continuation)) start--
        while (end + 1 < snapshot.columns && (selectable(end + 1) || cell(end + 1).continuation)) end++
        return TerminalSelection(
            TerminalPosition(safe.row, start),
            TerminalPosition(safe.row, end),
        )
    }

    fun extract(snapshot: TerminalSnapshot, selection: TerminalSelection): String {
        if (snapshot.columns <= 0 || snapshot.rows <= 0) return ""
        val (rawStart, rawEnd) = normalize(snapshot, selection).ordered(snapshot.columns)
        val start = rawStart.clamp(snapshot)
        val end = rawEnd.clamp(snapshot)
        return buildString {
            for (row in start.row..end.row) {
                val fromColumn = if (row == start.row) start.column else 0
                val toColumn = if (row == end.row) end.column else snapshot.columns - 1
                val line = buildString {
                    for (column in fromColumn..toColumn) {
                        val cell = snapshot.cells[row * snapshot.columns + column]
                        if (!cell.continuation && !cell.wrapPadding) append(cell.text)
                    }
                }.let { line ->
                    if (row < end.row && snapshot.cells[row * snapshot.columns + snapshot.columns - 1].softWrapped) line else line.trimEnd()
                }
                append(line)
                if (row != end.row && !snapshot.cells[row * snapshot.columns + snapshot.columns - 1].softWrapped) append('\n')
            }
        }
    }

    fun normalize(snapshot: TerminalSnapshot, selection: TerminalSelection): TerminalSelection {
        val (start, end) = selection.ordered(snapshot.columns)
        val first = leadingPosition(snapshot, start.clamp(snapshot))
        var last = end.clamp(snapshot)
        if (last.column + 1 < snapshot.columns && snapshot.cells[last.row * snapshot.columns + last.column + 1].continuation) {
            last = last.copy(column = last.column + 1)
        }
        return TerminalSelection(first, last)
    }

    private fun leadingPosition(snapshot: TerminalSnapshot, position: TerminalPosition): TerminalPosition =
        if (position.column > 0 && snapshot.cells[position.row * snapshot.columns + position.column].continuation) {
            position.copy(column = position.column - 1)
        } else position

    private fun TerminalPosition.clamp(snapshot: TerminalSnapshot) = TerminalPosition(
        row = row.coerceIn(0, snapshot.rows - 1),
        column = column.coerceIn(0, snapshot.columns - 1),
    )
}
