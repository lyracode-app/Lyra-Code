package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.overlay.OverlayGeometry
import com.yukisoffd.lyracode.interaction.overlay.OverlayRect
import org.junit.Assert.*
import org.junit.Test

class OverlayGeometryTest {
    @Test fun keyboardFittingDoesNotOverwritePreferredSize() {
        val preferred = OverlayRect(20, 180, 340, 480)
        val keyboardArea = OverlayRect(8, 30, 384, 350)
        val fitted = OverlayGeometry.fit(preferred, keyboardArea)
        assertEquals(350, fitted.height)
        assertEquals(380, fitted.bottom)
        assertEquals(preferred, OverlayGeometry.fit(preferred, OverlayRect(8, 30, 384, 800)))
    }

    @Test fun eachCornerKeepsOppositeCornerFixed() {
        val start = OverlayRect(50, 100, 300, 400)
        val area = OverlayRect(8, 20, 500, 700)
        for (left in listOf(true, false)) for (top in listOf(true, false)) {
            val result = OverlayGeometry.resize(start, left, top, if (left) 60 else -60,
                if (top) 80 else -80, area, 200, 200)
            assertEquals(240, result.width); assertEquals(320, result.height)
            assertEquals(if (left) start.right else start.x, if (left) result.right else result.x)
            assertEquals(if (top) start.bottom else start.y, if (top) result.bottom else result.y)
        }
    }

    @Test fun resizingClampsToScreenAndMinimumWithoutInvertingCorners() {
        val area = OverlayRect(8, 20, 500, 700)
        val start = OverlayRect(50, 100, 300, 400)
        val small = OverlayGeometry.resize(start, false, false, -10000, -10000, area, 240, 240)
        assertEquals(240, small.width); assertEquals(240, small.height)
        val big = OverlayGeometry.resize(start, false, false, 10000, 10000, area, 240, 240)
        assertEquals(area.right, big.right); assertEquals(area.bottom, big.bottom)
        val tinyArea = OverlayRect(0, 0, 100, 100)
        val tiny = OverlayGeometry.resize(start, true, true, 10000, 10000, tinyArea, 240, 240)
        assertEquals(tinyArea, tiny)
    }
}
