package com.yukisoffd.lyracode

import android.os.SystemClock
import android.webkit.WebView
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.interaction.pet.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PetPackageInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun evaluate(web: WebView, expression: String): String {
        val latch = CountDownLatch(1); var value = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(expression) { value = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); return value
    }
    private fun awaitReady(web: WebView) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate(web, "window.petReady === true") == "true") return
            SystemClock.sleep(100)
        }
        fail("Package module did not mount: ${evaluate(web, "document.body.innerText")}")
    }
    @Test fun zipModulesDataAndAllThreeAnimationFormatsPlayInRealOverlay() {
        assertTrue("Real animation sampling requires an awake device", context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        assertFalse("Unlock the device before sampling the overlay", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val backup = ByteArrayOutputStream().also { DevicePetStore.exportZip(context, it) }.toByteArray()
        val wasDefault = !java.io.File(context.filesDir, "desktop-pet-active.json").exists() && !DevicePetStore.packageFile(context).exists()
        val options = DevicePetStore.options(context)
        var pet: DesktopPetWindow? = null
        try {
            instrumentation.context.assets.open("desktop-pet/animation-starter.zip").use { DevicePetStore.installZip(context, it) }
            assertEquals(2, DevicePetStore.load(context).getInt("apiVersion"))
            DevicePetStore.saveOptions(context, JSONObject().put("size", 96).put("autoDock", false).put("activeOpacity", 1))
            lateinit var web: WebView
            instrumentation.runOnMainSync {
                pet = DesktopPetWindow(context, {}, {})
                web = android.view.inspector.WindowInspector.getGlobalWindowViews().filterIsInstance<WebView>()
                    .first { (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
            }
            awaitReady(web)
            assertEquals("96", evaluate(web, "lyraPet.config.size"))
            assertEquals("false", evaluate(web, "lyraPet.config.autoDock"))
            assertEquals("[\"assets/sprites/idle/1.png\",\"assets/sprites/idle/2.png\",\"assets/sprites/idle/10.png\"]",
                evaluate(web, "lyraPet.files('assets/sprites/idle')"))
            for (format in listOf("PNG", "GIF", "APNG")) {
                evaluate(web, "window.dispatchEvent(new CustomEvent('lyrapet',{detail:{type:'test-animation',data:{name:'$format'}}}))")
                SystemClock.sleep(350)
                val colors = mutableSetOf<Int>()
                repeat(12) {
                    val location = IntArray(2); var width = 0; var height = 0
                    instrumentation.runOnMainSync { web.getLocationOnScreen(location); width = web.width; height = web.height }
                    instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
                        colors += bitmap.getPixel(location[0] + width / 2, location[1] + height / 2)
                        if (it == 0) {
                            val crop = android.graphics.Bitmap.createBitmap(bitmap, location[0], location[1], width, height)
                            java.io.File(context.externalCacheDir, "pet-package-$format.png").outputStream().use { output -> crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }; crop.recycle()
                        }
                        bitmap.recycle()
                    }
                    SystemClock.sleep(110)
                }
                assertTrue("$format must advance real rendered frames, colors=$colors", colors.size >= 2)
            }
            val changed = JSONObject(DevicePetStore.options(context).toString())
                .put("controls", JSONObject().put("animation", "GIF").put("fps", 9).put("greeting", "配置已更新"))
            DevicePetStore.saveOptions(context, changed)
            SystemClock.sleep(1300)
            assertEquals("9", evaluate(web, "lyraPet.config.fps"))
            assertEquals("\"配置已更新\"", evaluate(web, "lyraPet.config.greeting"))
            assertTrue(evaluate(web, "document.querySelector('img').src").contains("idle.gif"))
            evaluate(web, "fetch('https://example.com/blocked').then(r=>window.networkBlocked=!r.ok).catch(()=>window.networkBlocked=true)")
            SystemClock.sleep(300)
            assertEquals("true", evaluate(web, "window.networkBlocked"))
            val export = ByteArrayOutputStream().also { DevicePetStore.exportZip(context, it) }.toByteArray()
            val root = java.io.File(context.cacheDir, "pet-export-${System.nanoTime()}")
            try {
                val extracted = PetArchive.extract(export.inputStream(), root)
                assertTrue(java.io.File(extracted, "assets/sprites/idle.apng").length() > 0)
                assertTrue(java.io.File(extracted, "scripts/behaviors/feedback.js").isFile)
            } finally { root.deleteRecursively() }
            val before = DevicePetStore.load(context).toString()
            assertTrue(runCatching { DevicePetStore.installZip(context, byteArrayOf(1, 2, 3).inputStream()) }.isFailure)
            assertEquals(before, DevicePetStore.load(context).toString())
        } finally {
            instrumentation.runOnMainSync { pet?.destroy() }
            if (wasDefault) DevicePetStore.reset(context) else DevicePetStore.installZip(context, backup.inputStream())
            DevicePetStore.saveOptions(context, options)
        }
    }
}
