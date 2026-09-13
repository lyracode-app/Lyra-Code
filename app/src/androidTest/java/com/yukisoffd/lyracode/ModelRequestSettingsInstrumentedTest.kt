package com.yukisoffd.lyracode

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.ModelRequestCustomization
import org.junit.Assert.*
import org.junit.Test

@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ModelRequestSettingsInstrumentedTest {
    @Test fun editHeaderBodyThinkingAndRestoreDefaults() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val profile = mutableStateOf(ApiProfile("ui-fixture", "Fixture", "", "https://example.invalid", selectedModel = "a", savedModels = listOf("a", "b")))
        var saved: ModelRequestCustomization? = null
        var saves = 0
        fun roots(view: View): List<ViewRootForTest> = buildList {
            if (view is ViewRootForTest) add(view)
            if (view is ViewGroup) for (n in 0 until view.childCount) addAll(roots(view.getChildAt(n)))
        }
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        fun nodes() = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::roots).flatMap { all(it.semanticsOwner.rootSemanticsNode) }
        fun settle() { i.waitForIdleSync(); SystemClock.sleep(80) }
        fun change(value: String, replacement: String) {
            i.runOnMainSync {
                val field = nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == value }
                assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(replacement)))
            }
            settle()
        }
        fun click(text: String) {
            i.runOnMainSync {
                var node = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true || it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true }
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent!!
                node.config[SemanticsActions.OnClick].action!!.invoke()
            }
            settle()
        }
        try {
            i.runOnMainSync { activity.setContent { MaterialTheme {
                ModelRequestSettings(profile.value) { model, config ->
                    saves++; saved = config
                    profile.value = profile.value.copy(modelRequestOverrides = if (config == null) emptyMap() else mapOf(model to config))
                }
            } } }
            settle()
            change("Content-Type", "X-Fixture")
            change("application/json", "custom-value")
            change("0.2", "0.75")
            i.runOnMainSync {
                nodes().single { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
                    .config[SemanticsActions.OnClick].action!!.invoke()
            }
            settle()
            click(activity.getString(R.string.request_save))
            assertEquals(1, saves)
            assertEquals("custom-value", saved!!.headers["X-Fixture"])
            assertTrue(saved!!.removedHeaders.contains("Content-Type"))
            assertEquals(0.75, org.json.JSONObject(saved!!.body).getDouble("temperature"), 0.001)
            assertFalse(saved!!.replayThinking)
            assertFalse(org.json.JSONObject(saved!!.body).has("messages"))
            click(activity.getString(R.string.request_restore))
            assertEquals(2, saves); assertNull(saved)
            i.runOnMainSync {
                assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Content-Type" })
            }
            fun fieldExists(name: String): Boolean {
                var found = false
                i.runOnMainSync { found = nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == name } }
                return found
            }
            // A trash tap alone must not remove the field, and cancellation keeps its value.
            click(activity.getString(R.string.request_delete_field, "Content-Type"))
            assertTrue(fieldExists("Content-Type"))
            click(activity.getString(R.string.action_cancel))
            assertTrue(fieldExists("Content-Type"))
            for (name in listOf("Content-Type", "temperature")) {
                click(activity.getString(R.string.request_delete_field, name))
                click(activity.getString(R.string.action_delete))
                SystemClock.sleep(350); settle()
                assertFalse(fieldExists(name))
            }
            // Removing one keyed card must not remove or overwrite its neighbour.
            assertTrue(fieldExists("model")); assertTrue(fieldExists("messages"))
            click(activity.getString(R.string.request_save))
            assertTrue(saved!!.removedHeaders.contains("Content-Type"))
            assertTrue(saved!!.removedBodyKeys.contains("temperature"))
            click(activity.getString(R.string.request_restore))
            assertTrue(fieldExists("Content-Type")); assertTrue(fieldExists("temperature"))

        } finally { i.runOnMainSync { activity.finish() } }
    }
}
