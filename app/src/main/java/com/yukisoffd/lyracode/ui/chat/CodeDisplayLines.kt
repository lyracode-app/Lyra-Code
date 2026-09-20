package com.yukisoffd.lyracode

internal data class CodeDisplayLine(val start: Int, val end: Int, val sourceLineStart: Int)

/** Store offsets only. Text/layout is created for visible rows, with bounded single-line length. */
internal fun codeDisplayLines(text: String, checkActive: () -> Unit = {}): List<CodeDisplayLine> = buildList {
    var start = 0
    while (true) {
        checkActive()
        val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val end = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
        var chunkStart = start
        do {
            checkActive()
            var chunkEnd = (chunkStart + 2_048).coerceAtMost(end)
            if (chunkEnd < end && text[chunkEnd - 1].isHighSurrogate() && text[chunkEnd].isLowSurrogate()) chunkEnd--
            add(CodeDisplayLine(chunkStart, chunkEnd, start))
            chunkStart = chunkEnd
        } while (chunkStart < end)
        if (newline == text.length) break
        start = newline + 1
    }
}
