package com.yukisoffd.lyracode

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.interaction.pet.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PetLibraryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun switchLabel() = context.localizedContext(com.yukisoffd.lyracode.data.AppSettings(context).languageMode).getString(R.string.pet_switch)
    @Test fun oldSinglePetStoreMigratesWithoutLosingLegacyOrRetainedPackages() {
        val directory = java.io.File(context.cacheDir, "pet-migration-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val key = "old/LyraStarCat"
            java.io.File(directory, "desktop-pet-packages/$key").apply { mkdirs() }.let {
                java.io.File(it, "manifest.json").writeText(DevicePetStore.defaultManifest(context).toString())
            }
            java.io.File(directory, "desktop-pet-active.json").writeText(JSONObject().put("root", key).toString())
            DevicePetStore.packageFile(isolated).writeText(context.assets.open("desktop-pet/default.json").bufferedReader().use { it.readText() })
            DevicePetStore.saveOptions(isolated, JSONObject().put("size", 81).put("controls", JSONObject().put("color", "#123456")))
            assertEquals(setOf("builtin", "legacy", key), DevicePetStore.catalog(isolated).map { it.key }.toSet())
            DevicePetStore.select(isolated, "builtin")
            assertEquals(2, DevicePetStore.load(isolated).getInt("apiVersion"))
            DevicePetStore.select(isolated, "legacy")
            assertEquals(1, DevicePetStore.load(isolated).getInt("apiVersion"))
            DevicePetStore.select(isolated, key)
            assertEquals("#123456", DevicePetStore.options(isolated).getJSONObject("controls").getString("color"))
            assertEquals(81, DevicePetStore.options(isolated).getInt("size"))
            DevicePetStore.remove(isolated, key)
            assertEquals("builtin", DevicePetStore.activeKey(isolated))
            assertEquals(setOf("builtin", "legacy"), DevicePetStore.catalog(isolated).map { it.key }.toSet())
            assertTrue(DevicePetStore.packageFile(isolated).isFile)
        } finally { directory.deleteRecursively() }
    }
    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1); var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result
    }
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        fail(message)
    }
    private fun clickText(text: String) {
        awaitCondition("Missing UI: $text") {
            val matches = nodes().filter { it.text?.contains(text) == true || it.contentDescription?.contains(text) == true }
            matches.any { match ->
                var node: AccessibilityNodeInfo? = match
                while (node != null && !node.isClickable) node = node.parent
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    @Test fun libraryKeepsIndependentControlsAndMintyUsesDocumentedRuntime() {
        val original = DevicePetStore.activeKey(context)
        val originalKeys = DevicePetStore.catalog(context).map { it.key }.toSet()
        val originalOptions = DevicePetStore.options(context)
        var pet: DesktopPetWindow? = null
        var mintyKey: String? = null
        try {
            instrumentation.context.assets.open("desktop-pet/animation-starter.zip").use { DevicePetStore.installZip(context, it) }
            val first = DevicePetStore.activeKey(context)
            DevicePetStore.saveOptions(context, JSONObject().put("controls", JSONObject().put("fps", 7)))
            instrumentation.context.assets.open("desktop-pet/minty-lyra.zip").use { DevicePetStore.installZip(context, it) }
            val minty = DevicePetStore.activeKey(context); mintyKey = minty
            DevicePetStore.saveOptions(context, JSONObject().put("autoDock", false).put("size", 120).put("controls", JSONObject().put("speed", 1.6)))
            DevicePetStore.select(context, first)
            assertEquals(7, DevicePetStore.options(context).getJSONObject("controls").getInt("fps"))
            DevicePetStore.select(context, minty)
            assertEquals(1.6, DevicePetStore.options(context).getJSONObject("controls").getDouble("speed"), .01)
            assertTrue(DevicePetStore.catalog(context).map { it.key }.containsAll(listOf(first, minty, "builtin")))
            lateinit var web: WebView
            instrumentation.runOnMainSync {
                pet = DesktopPetWindow(context, {}, {})
                web = android.view.inspector.WindowInspector.getGlobalWindowViews().filterIsInstance<WebView>()
                    .first { (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
            }
            awaitCondition("Minty did not mount") { evaluate(web, "window.mintyReady === true") == "true" }
            for ((state, animation) in listOf("idle" to "idle", "thinking" to "running", "executing" to "running", "approval" to "waiting", "error" to "failed", "completed" to "jumping")) {
                evaluate(web, "window.dispatchEvent(new CustomEvent('lyrapet',{detail:{type:'state',data:{state:'$state'}}}))")
                assertEquals("\"$animation\"", evaluate(web, "document.querySelector('canvas').dataset.animation"))
            }
            val frames = mutableSetOf<String>()
            repeat(6) {
                frames += evaluate(web, "(()=>{const d=document.querySelector('canvas').getContext('2d').getImageData(0,0,192,208).data;let h=0;for(let i=0;i<d.length;i+=17)h=(h*31+d[i])>>>0;return h})()")
                SystemClock.sleep(130)
            }
            assertTrue("Minty atlas must render changing frames", frames.size >= 2)
            val location = IntArray(2); var width = 0; var height = 0
            instrumentation.runOnMainSync { web.getLocationOnScreen(location); width = web.width; height = web.height }
            instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
                val crop = android.graphics.Bitmap.createBitmap(bitmap, location[0], location[1], width, height)
                java.io.File(context.externalCacheDir, "minty-lyra-overlay.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle(); bitmap.recycle()
            }
            // Switch the package under an existing window: the renderer must reload without recreation.
            DevicePetStore.select(context, first)
            awaitCondition("Existing window did not switch to PNG demo") { evaluate(web, "window.petReady === true") == "true" }
            DevicePetStore.select(context, minty)
            awaitCondition("Existing window did not switch back to Minty") { evaluate(web, "window.mintyReady === true") == "true" }
            instrumentation.runOnMainSync { pet?.destroy(); pet = null }
            context.startActivity(Intent(context, DesktopPetSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitCondition("Material settings page did not open") { nodes().any { it.text?.contains(switchLabel()) == true } }
            SystemClock.sleep(500)
            instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
                java.io.File(context.externalCacheDir, "pet-settings-md3.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            clickText(switchLabel())
            clickText("Lyra 星猫")
            awaitCondition("Picker did not select default pet") { DevicePetStore.activeKey(context) == "builtin" }
            clickText(switchLabel())
            clickText("Minty · 薄荷")
            awaitCondition("Picker did not select Minty") { DevicePetStore.load(context).optString("id") == "somnusochi.minty.lyra" }
        } finally {
            instrumentation.runOnMainSync { pet?.destroy() }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DevicePetStore.select(context, original)
            val keepMinty = InstrumentationRegistry.getArguments().getString("keepMinty") == "true"
            DevicePetStore.catalog(context).filter { it.key !in originalKeys && !(keepMinty && it.key == mintyKey) }.forEach { DevicePetStore.remove(context, it.key) }
            DevicePetStore.saveOptions(context, originalOptions)
        }
    }
}
