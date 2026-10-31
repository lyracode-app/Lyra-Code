package com.yukisoffd.lyracode.ssh

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/** Shares exactly the same fractional grid between output, hit testing, PTY size and magnifier. */
internal class TerminalGridRenderer(typeface: Typeface, textSizePx: Float, val palette: TerminalPalette) {
    private val regular = typeface
    private val bold = Typeface.create(typeface, Typeface.BOLD)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = regular
        textSize = textSizePx
        fontFeatureSettings = "'liga' 0, 'clig' 0, 'calt' 0"
    }
    // Rounding the advance up once PER CHARACTER inserts a visible gap across every word.
    val cellWidth = paint.measureText("0").coerceAtLeast(1f)
    val cellHeight: Float
    private val baseline: Float
    private val boldScale: Float

    init {
        val regularMetrics = paint.fontMetrics
        paint.typeface = bold
        boldScale = cellWidth / paint.measureText("0").coerceAtLeast(1f)
        val boldMetrics = paint.fontMetrics
        val ascent = minOf(regularMetrics.ascent, boldMetrics.ascent)
        val descent = maxOf(regularMetrics.descent, boldMetrics.descent)
        cellHeight = ceil((descent - ascent) * 1.12f)
        baseline = -ascent + (cellHeight - (descent - ascent)) / 2f
    }

    fun draw(
        canvas: Canvas,
        snapshot: TerminalSnapshot,
        firstRow: Int,
        lastRow: Int,
        selection: TerminalSelection?,
        showCursor: Boolean,
    ) {
        val ordered = selection?.let { TerminalSelectionText.normalize(snapshot, it).ordered(snapshot.columns) }
        val start = ordered?.first?.let { it.row * snapshot.columns + it.column } ?: -1
        val end = ordered?.second?.let { it.row * snapshot.columns + it.column } ?: -1
        for (row in firstRow until lastRow) {
            for (column in 0 until snapshot.columns) {
                val index = row * snapshot.columns + column
                val cell = snapshot.cells[index]
                if (cell.continuation) continue
                val span = if (column + 1 < snapshot.columns && snapshot.cells[index + 1].continuation) 2 else 1
                val x = column * cellWidth
                val y = (row - firstRow) * cellHeight
                val right = x + cellWidth * span
                val foreground = palette.resolve(if (cell.inverse) cell.background else cell.foreground)
                val background = palette.resolve(if (cell.inverse) cell.foreground else cell.background)
                paint.color = background
                canvas.drawRect(x, y, right, y + cellHeight, paint)
                if (index <= end && index + span - 1 >= start && start >= 0) {
                    paint.color = 0x996285ab.toInt()
                    canvas.drawRect(x, y, right, y + cellHeight, paint)
                }
                if (cell.codePoint == 32 && cell.combining.isEmpty()) continue
                paint.color = foreground
                paint.typeface = if (cell.bold) bold else regular
                paint.textScaleX = if (cell.bold) boldScale else 1f
                // Keep a fixed origin, never center individual letters within their cells.
                // Fit only oversized fallback glyphs; normal monospace ASCII needs no fitting.
                val glyph = cell.text
                val measured = paint.measureText(glyph)
                if (measured > right - x + 0.01f) paint.textScaleX *= (right - x) / measured
                canvas.save()
                canvas.clipRect(x, y, right, y + cellHeight)
                canvas.drawText(glyph, x, y + baseline, paint)
                canvas.restore()
            }
        }
        if (showCursor && snapshot.cursorVisible && snapshot.cursorRow in firstRow until lastRow) {
            paint.color = palette.resolve(12)
            val x = snapshot.cursorColumn * cellWidth
            val y = (snapshot.cursorRow - firstRow + 1) * cellHeight
            canvas.drawRect(x, y - (cellHeight * .09f).coerceAtLeast(2f), x + cellWidth, y, paint)
        }
    }
}
