package com.yukisoffd.lyracode.ssh

/** Inclusive cell indices, covering wide glyphs and any soft-wrapped continuation rows. */
internal data class TerminalLink(val text: String, val startIndex: Int, val endIndex: Int) {
    fun contains(position: TerminalPosition, columns: Int): Boolean =
        position.row * columns + position.column in startIndex..endIndex
}

internal object TerminalLinks {
    private val pattern = Regex(
        """(?<![A-Za-z0-9_])(?:[A-Za-z][A-Za-z0-9+.-]*://|www\.)[^\s\p{Z}<>"'\x60“”‘’，。；：！？、【】《》「」『』]+""",
        RegexOption.IGNORE_CASE,
    )
    private val closingPairs = mapOf(')' to '(', ']' to '[', '}' to '{', '）' to '（')

    /** Scan visible logical lines, including offscreen parts of URLs split by terminal wrapping. */
    fun find(snapshot: TerminalSnapshot, firstRow: Int = 0, lastRow: Int = snapshot.rows): List<TerminalLink> {
        if (snapshot.columns <= 0 || snapshot.rows <= 0) return emptyList()
        val first = firstRow.coerceIn(0, snapshot.rows)
        val last = lastRow.coerceIn(first, snapshot.rows)
        if (first == last) return emptyList()
        fun wrapped(row: Int) = snapshot.cells[(row + 1) * snapshot.columns - 1].softWrapped
        var row = first
        while (row > 0 && wrapped(row - 1)) row--
        return buildList {
            while (row < last) {
                val text = StringBuilder()
                val cellIndices = ArrayList<Int>()
                do {
                    for (column in 0 until snapshot.columns) {
                        val index = row * snapshot.columns + column
                        val cell = snapshot.cells[index]
                        if (cell.continuation || cell.wrapPadding) continue
                        val glyph = cell.text
                        text.append(glyph)
                        repeat(glyph.length) { cellIndices.add(index) }
                    }
                    row++
                } while (row < snapshot.rows && wrapped(row - 1))
                for (match in pattern.findAll(text)) {
                    val value = trimEnding(match.value)
                    val prefixEnd = if (value.startsWith("www.", ignoreCase = true)) 4 else value.indexOf("://") + 3
                    if (value.length <= prefixEnd) continue
                    val start = cellIndices[match.range.first]
                    var end = cellIndices[match.range.first + value.length - 1]
                    if (end % snapshot.columns + 1 < snapshot.columns && snapshot.cells[end + 1].continuation) end++
                    if (end / snapshot.columns >= first && start / snapshot.columns < last) {
                        add(TerminalLink(value, start, end))
                    }
                }
            }
        }
    }

    private fun trimEnding(value: String): String {
        // Keep balanced URL parentheses and IPv6 brackets; drop surrounding prose/shell punctuation.
        val excessClosings = closingPairs.mapValues { (closing, opening) ->
            value.count { it == closing } - value.count { it == opening }
        }.toMutableMap()
        var end = value.length
        while (end > 0) {
            val last = value[end - 1]
            when {
                last in ".,;:!?" -> end--
                (excessClosings[last] ?: 0) > 0 -> {
                    excessClosings[last] = excessClosings.getValue(last) - 1
                    end--
                }
                else -> break
            }
        }
        return value.substring(0, end)
    }
}
