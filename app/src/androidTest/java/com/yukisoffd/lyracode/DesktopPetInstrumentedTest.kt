package com.yukisoffd.lyracode

import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.interaction.pet.DesktopPetWindow
import com.yukisoffd.lyracode.interaction.pet.DevicePetStore
import com.yukisoffd.lyracode.interaction.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopPetInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun defaultScriptDocksHalfOffscreenThenRevealsBeforeOpeningChat() {
        val context = instrumentation.targetContext
        val originalOptions = DevicePetStore.options(context)
        var toggles = 0
        lateinit var pet: DesktopPetWindow
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            pet = DesktopPetWindow(context, { toggles++ }, { fail("Default pet requested a system operation") })
            pet.render(ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE, targetPackage = "example.app"))
            web = android.view.inspector.WindowInspector.getGlobalWindowViews().filterIsInstance<WebView>()
                .first { (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
        }
        try {
            SystemClock.sleep(4200)
            instrumentation.runOnMainSync {
                assertTrue(web.isAttachedToWindow)
                val params = web.layoutParams as WindowManager.LayoutParams
                assertEquals(1f, params.alpha, .01f)
                assertEquals(.42f, web.alpha, .01f)
                assertEquals(-params.width / 2, params.x)
            }
            val newSize = (originalOptions.optInt("size", 72) + 10).coerceIn(40, 180)
            DevicePetStore.saveOptions(context, org.json.JSONObject(originalOptions.toString()).put("size", newSize))
            SystemClock.sleep(1300)
            instrumentation.runOnMainSync {
                val params = web.layoutParams as WindowManager.LayoutParams
                assertEquals((newSize * context.resources.displayMetrics.density).toInt(), params.width)
                assertEquals(-params.width / 2, params.x)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.externalCacheDir, "desktop-pet-docked.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            tap(web, visibleHalf = true)
            instrumentation.runOnMainSync {
                assertEquals(0, toggles)
                assertEquals(1f, web.alpha, .01f)
            }
            tap(web)
            instrumentation.runOnMainSync { assertEquals(1, toggles) }
            tap(web)
            instrumentation.runOnMainSync { assertEquals(2, toggles) }
        } finally { instrumentation.runOnMainSync { pet.destroy() }; DevicePetStore.saveOptions(context, originalOptions) }
    }
    @Test fun approvalStageSurvivesPrivateIpcAndDefaultPackageDeclaresControls() {
        val original = ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE, sessionId = 123,
            approval = DeviceApproval("second-token", "删除", "draft.txt", "example.app", true, 2))
        val decoded = com.yukisoffd.lyracode.interaction.overlay.ManualControlOverlayProtocol.decode(
            com.yukisoffd.lyracode.interaction.overlay.ManualControlOverlayProtocol.encode(original))!!
        assertEquals(123L, decoded.sessionId)
        assertEquals(original.approval, decoded.approval)
        val script = DevicePetStore.validate(instrumentation.targetContext.assets.open("desktop-pet/default.json")
            .bufferedReader().use { it.readText() })
        assertEquals(1, script.getInt("apiVersion"))
        assertTrue(script.getJSONArray("controls").length() >= 3)
        assertTrue(script.getString("html").contains("operationResult"))
    }
    private fun tap(view: WebView, visibleHalf: Boolean = false) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val x = if (visibleHalf) (location[0] + view.width).coerceAtLeast(4) / 2f else location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        android.util.Log.i("LyraPetTest", "tap location=${location.toList()} size=${view.width}x${view.height} point=$x,$y")
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        SystemClock.sleep(200)
        instrumentation.waitForIdleSync()
    }
}
