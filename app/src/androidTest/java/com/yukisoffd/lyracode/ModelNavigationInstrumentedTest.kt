package com.yukisoffd.lyracode

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ModelNavigationInstrumentedTest {
    @Test fun nestedModelTitleSlidesWithPage() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun roots(view: View): List<ViewRootForTest> = buildList {
            if (view is ViewRootForTest) add(view)
            if (view is ViewGroup) for (n in 0 until view.childCount) addAll(roots(view.getChildAt(n)))
        }
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        fun nodes() = roots(activity.window.decorView).flatMap { all(it.semanticsOwner.rootSemanticsNode) }
        fun matches(node: SemanticsNode, text: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        fun click(text: String) {
            i.runOnMainSync {
                var node = nodes().first { matches(it, text) }
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent!!
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            }
        }
        fun x(text: String): Float {
            var result = Float.NaN
            i.runOnMainSync { result = nodes().first { matches(it, text) }.boundsInRoot.left }
            return result
        }
        try {
            SystemClock.sleep(600); i.waitForIdleSync()
            click(activity.getString(R.string.cd_menu)); SystemClock.sleep(350)
            click(activity.getString(R.string.title_settings)); SystemClock.sleep(350)
            click(activity.getString(R.string.detail_model)); SystemClock.sleep(350)
            // Add provider opens a nested route without saving or changing any model settings.
            val positions = java.util.concurrent.CopyOnWriteArrayList<Float>()
            i.runOnMainSync {
                val clock = android.view.Choreographer.getInstance()
                clock.postFrameCallback(object : android.view.Choreographer.FrameCallback {
                    var frames = 0
                    override fun doFrame(time: Long) {
                        nodes().firstOrNull { matches(it, activity.getString(R.string.label_choose_provider)) }?.let {
                            positions += it.boundsInRoot.left
                        }
                        if (++frames < 40) clock.postFrameCallback(this)
                    }
                })
            }
            click(activity.getString(R.string.action_add_model_service))
            SystemClock.sleep(800)
            val resting = x(activity.getString(R.string.label_choose_provider))
            assertTrue("Nested title positions=$positions; resting=$resting", positions.any { it > resting + 5f })
            click(activity.getString(R.string.cd_back)); SystemClock.sleep(350)
            assertFalse(x(activity.getString(R.string.detail_model)).isNaN())
            click(com.yukisoffd.lyracode.data.AppSettings(activity).profiles().first().name); SystemClock.sleep(350)
            click(activity.getString(R.string.request_custom)); SystemClock.sleep(350)
            assertFalse(x(activity.getString(R.string.request_custom_title)).isNaN())
            click(activity.getString(R.string.cd_back)); SystemClock.sleep(350)
            val width = activity.resources.displayMetrics.widthPixels.toFloat()
            val y = 180f * activity.resources.displayMetrics.density
            val down = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, width * (.82f - .55f * step / 12), y, 0)
                try { i.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                SystemClock.sleep(16)
            }
            SystemClock.sleep(400)
            assertFalse(x(activity.getString(R.string.request_custom)).isNaN())
            i.runOnMainSync {
                assertFalse(nodes().any { matches(it, activity.getString(R.string.request_custom_title)) })
            }
        } finally { i.runOnMainSync { activity.finish() } }
    }
}
