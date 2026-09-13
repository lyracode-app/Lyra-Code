package com.yukisoffd.lyracode

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.overlay.DeviceChatPanel
import com.yukisoffd.lyracode.interaction.overlay.TaskHudController
import com.yukisoffd.lyracode.interaction.session.DeviceChatState
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import org.junit.Assert.*
import org.junit.Test

class FloatingUiLocaleInstrumentedTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun windows() = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants)

    @Test fun hostLanguageAndCurrentWorkspaceMenuExcludePreviousFolders() {
        val settings = AppSettings(context)
        val old = settings.languageMode
        try {
            for ((language, hint, sendLabel) in listOf(
                Triple(AppSettings.LANGUAGE_ZH_CN, "输入消息", "发送任务"),
                Triple(AppSettings.LANGUAGE_ZH_TW, "輸入訊息", "傳送任務"),
                Triple(AppSettings.LANGUAGE_EN, "Input message", "Send task"))) {
                settings.languageMode = language
                lateinit var hud: TaskHudController
                lateinit var panel: DeviceChatPanel
                i.runOnMainSync {
                    hud = TaskHudController(context, {}, {}, {}, {}, {})
                    hud.render(ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE, chat = DeviceChatState(
                        modelLabel = "fixture-model", workspaceLabel = "Current project",
                        configurationOptions = """{"workspaces":[{"label":"Old project","workspace":"content://old"}]}""")))
                    panel = windows().filterIsInstance<DeviceChatPanel>().single()
                }
                try {
                    i.waitForIdleSync()
                    i.runOnMainSync {
                        assertEquals(hint, descendants(panel).filterIsInstance<EditText>().single().hint.toString())
                        assertTrue(descendants(panel).any { it.contentDescription == sendLabel })
                        descendants(panel).filterIsInstance<Button>().single { it.contentDescription == "Current project" }.performClick()
                    }
                    i.waitForIdleSync(); SystemClock.sleep(100)
                    i.runOnMainSync {
                        val texts = windows().filterIsInstance<TextView>()
                        assertTrue(texts.any { it.text.toString() == panel.context.getString(R.string.floating_current_workspace, "Current project") })
                        assertFalse(texts.any { it.text.toString().contains("Old project") })
                    }
                } finally { i.runOnMainSync { hud.destroy() } }
            }
        } finally {
            settings.languageMode = old
            context.getSharedPreferences("lyra_settings", android.content.Context.MODE_PRIVATE).edit().commit()
            AppStrings.initialize(context.localizedContext(old))
        }
    }

    @Test fun sendAnimationSurvivesRapidDraftChanges() {
        lateinit var hud: TaskHudController
        lateinit var editor: EditText
        lateinit var send: Button
        i.runOnMainSync {
            hud = TaskHudController(context, {}, {}, {}, {}, {})
            hud.render(ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE))
            val panel = windows().filterIsInstance<DeviceChatPanel>().single()
            editor = descendants(panel).filterIsInstance<EditText>().single()
            send = descendants(panel).filterIsInstance<Button>().single { it.text == "↑" }
        }
        try {
            i.waitForIdleSync()
            i.runOnMainSync { assertEquals(View.GONE, send.visibility); editor.setText("hello") }
            SystemClock.sleep(240)
            i.runOnMainSync { assertEquals(View.VISIBLE, send.visibility); assertEquals(1f, send.alpha, .01f); editor.text.clear() }
            SystemClock.sleep(40)
            i.runOnMainSync { editor.setText("again") }
            SystemClock.sleep(240)
            i.runOnMainSync { assertEquals(View.VISIBLE, send.visibility); assertEquals(1f, send.alpha, .01f); editor.text.clear() }
            SystemClock.sleep(200)
            i.runOnMainSync { assertEquals(View.GONE, send.visibility) }
        } finally { i.runOnMainSync { hud.destroy() } }
    }
}
