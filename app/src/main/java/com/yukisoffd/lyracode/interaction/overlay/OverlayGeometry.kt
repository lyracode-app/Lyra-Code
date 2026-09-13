package com.yukisoffd.lyracode.interaction.overlay

/** Pixel geometry independent of IME/window callbacks, so temporary fitting never changes user size. */
internal data class OverlayRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
}

internal object OverlayGeometry {
    fun fit(preferred: OverlayRect, area: OverlayRect): OverlayRect {
        val width = preferred.width.coerceIn(1, area.width.coerceAtLeast(1))
        val height = preferred.height.coerceIn(1, area.height.coerceAtLeast(1))
        return OverlayRect(preferred.x.coerceIn(area.x, (area.right - width).coerceAtLeast(area.x)),
            preferred.y.coerceIn(area.y, (area.bottom - height).coerceAtLeast(area.y)), width, height)
    }

    fun resize(start: OverlayRect, left: Boolean, top: Boolean, dx: Int, dy: Int,
        area: OverlayRect, minWidth: Int, minHeight: Int): OverlayRect {
        val base = fit(start, area)
        val minimumWidth = minWidth.coerceAtMost(base.width)
        val minimumHeight = minHeight.coerceAtMost(base.height)
        val x1 = if (left) (base.x + dx).coerceIn(area.x, base.right - minimumWidth) else base.x
        val x2 = if (left) base.right else (base.right + dx).coerceIn(base.x + minimumWidth, area.right)
        val y1 = if (top) (base.y + dy).coerceIn(area.y, base.bottom - minimumHeight) else base.y
        val y2 = if (top) base.bottom else (base.bottom + dy).coerceIn(base.y + minimumHeight, area.bottom)
        return OverlayRect(x1, y1, x2 - x1, y2 - y1)
    }
}
