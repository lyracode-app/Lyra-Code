package com.yukisoffd.lyracode

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.interaction.pet.*
import com.yukisoffd.lyracode.interaction.overlay.*
import com.yukisoffd.lyracode.interaction.session.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeepSeekMaidInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun descendants(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun evaluate(web: WebView, script: String): String {
        val done = CountDownLatch(1); var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5,TimeUnit.SECONDS)); return result
    }
    @Test fun importedPetStaysStillAndReceivesTaskFeedbackWithPanelHidden() {
        val original = DevicePetStore.activeKey(context)
        val originalOptions = DevicePetStore.options(context)
        var hud: TaskHudController? = null
        var installed: String? = null
        var success = false
        val older = DevicePetStore.catalog(context).filter { it.name == "DeepSeek 娘 · 静谧蓝" }.map { it.key }
        try {
            instrumentation.context.assets.open("desktop-pet/deepseek-maid-lyra.zip").use { DevicePetStore.installZip(context,it) }
            installed = DevicePetStore.activeKey(context)
            assertEquals("1.0.1", DevicePetStore.load(context).getString("version"))
            var state = ManualControlState(activeUntilEpochMillis=Long.MAX_VALUE,chat=DeviceChatState())
            instrumentation.runOnMainSync {
                hud = TaskHudController(context, {}, {}, { error("Hide must not stop") }, {}, { error("Hide must not pause") })
                hud!!.render(state)
            }
            lateinit var web: WebView
            val deadline=SystemClock.uptimeMillis()+10000
            while(true){
                instrumentation.runOnMainSync { web = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<WebView>().last() }
                if(evaluate(web,"window.deepseekMaidReady===true")=="true")break
                assertTrue("Pet runtime did not mount",SystemClock.uptimeMillis()<deadline);SystemClock.sleep(100)
            }
            assertEquals("\"idle\"",evaluate(web,"document.querySelector('[data-pose]').dataset.pose"))
            assertEquals("1", evaluate(web,"document.querySelectorAll('[data-pose] img').length"))
            SystemClock.sleep(800)
            assertEquals("0",evaluate(web,"document.getAnimations().filter(a=>a.playState==='running').length"))
            instrumentation.runOnMainSync { hud!!.setChatVisible(false) }
            for((status,pose) in listOf("思考中" to "thinking", "执行中 · list_directory" to "executing", "已完成" to "completed")){
                state=state.copy(chat=DeviceChatState(running=pose!="completed",status=status))
                instrumentation.runOnMainSync { hud!!.render(state) }
                SystemClock.sleep(450)
                assertEquals("\"$pose\"",evaluate(web,"document.querySelector('[data-pose]').dataset.pose"))
                instrumentation.runOnMainSync {
                    val panel=android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<DeviceChatPanel>().single()
                    assertFalse(panel.isShown)
                }
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.externalCacheDir,"deepseek-maid-device.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            success = true
        } finally {
            instrumentation.runOnMainSync { hud?.destroy() }
            if (success && InstrumentationRegistry.getArguments().getString("installPet") == "true") {
                older.forEach { DevicePetStore.remove(context, it) }
                DevicePetStore.select(context, installed!!)
                DevicePetStore.saveOptions(context, org.json.JSONObject(originalOptions.toString()).apply { remove("controls") })
            } else {
                DevicePetStore.select(context, original); DevicePetStore.saveOptions(context,originalOptions)
            }
        }
    }
}

