package com.yukisoffd.lyracode

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.charts.ChartDocument
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assume.assumeTrue
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChartRenderingInstrumentedTest {
    @Test fun allRequestedChartFamiliesRenderOfflineAndExportInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        FirstUseConsentStore(instrumentation.targetContext).accept()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val samples = listOf(
            "mermaid" to "flowchart LR\n A[开始] --> B[结束]",
            "mermaid" to "sequenceDiagram\n participant A as 用户\n participant B as 服务\n A->>B: 请求\n B-->>A: 响应",
            "mermaid" to "gantt\n title 项目计划\n dateFormat YYYY-MM-DD\n section 开发\n 设计 :a, 2026-10-01, 2d\n 实现 :after a, 3d",
            "mermaid" to "mindmap\n root((项目))\n  客户端\n  服务端",
            "mermaid" to "flowchart TB\n subgraph 客户端\n A[应用]\n end\n subgraph 服务端\n B[接口] --> C[(数据库)]\n end\n A --> B",
            "mermaid" to "erDiagram\n USER ||--o{ ORDER : places",
            "mermaid" to "architecture-beta\n service app(server)[App]\n service db(database)[Database]\n app:R -- L:db",
            "echarts" to "{\"xAxis\":{\"type\":\"category\",\"data\":[\"一月\",\"二月\"]},\"yAxis\":{\"type\":\"value\"},\"series\":[{\"type\":\"line\",\"data\":[12,18]}]}",
            "echarts" to "{\"xAxis\":{\"type\":\"category\",\"data\":[\"一月\",\"二月\"]},\"yAxis\":{\"type\":\"value\"},\"series\":[{\"type\":\"bar\",\"data\":[12,18]}]}",
            "echarts" to "{\"series\":[{\"type\":\"pie\",\"data\":[{\"name\":\"客户\",\"value\":12},{\"name\":\"服务\",\"value\":18}]}]}",
            "echarts" to "{\"series\":[{\"type\":\"graph\",\"layout\":\"force\",\"label\":{\"show\":true},\"data\":[{\"name\":\"用户\"},{\"name\":\"订单\"}],\"links\":[{\"source\":0,\"target\":1}]}]}",
            "mermaid" to "flowchart LR\n" + (0..12).joinToString(" --> ") { "N$it[开始步骤$it]" },
            "mermaid" to "flowchart TB\n" + (0..12).joinToString(" --> ") { "N$it[开始步骤$it]" },
        )
        try {
            for (dark in listOf(false, true)) for ((index, sample) in samples.withIndex()) {
                val (engine, source) = sample
                val ready = CountDownLatch(1)
                val exported = AtomicReference(CountDownLatch(1))
                val failed = AtomicReference<String>()
                val bytes = AtomicReference<ByteArray>()
                val previewHeight = AtomicReference<Float>()
                var view: WebView? = null
                instrumentation.runOnMainSync {
                    view = createChartWebView(activity, ChartDocument.create(engine, source), dark,
                        onReady = { previewHeight.set(it); ready.countDown() }, onError = { failed.set(it); ready.countDown() },
                        onExport = { _, data -> bytes.set(data); exported.get().countDown() },
                        onExportError = { failed.set(it); exported.get().countDown() })
                    activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) })
                }
                try {
                    assertTrue("Render timed out: $engine $source", ready.await(30, TimeUnit.SECONDS))
                    if (failed.get() != null) {
                        val diagnosed = CountDownLatch(1)
                        instrumentation.runOnMainSync {
                            view!!.evaluateJavascript("JSON.stringify({url:location.href,state:document.readyState,mermaid:typeof mermaid,renderer:typeof lyraExport,bridge:typeof LyraChart,html:document.documentElement.outerHTML.slice(-1500)})") {
                                failed.set(failed.get() + "\n" + it); diagnosed.countDown()
                            }
                        }
                        diagnosed.await(3, TimeUnit.SECONDS)
                    }
                    assertNull("Render failed: $engine $source", failed.get())
                    assertTrue("Inline preview is not compact", previewHeight.get() in 96f..240f)
                    val fitted = CountDownLatch(1)
                    val fits = AtomicReference<String>()
                    instrumentation.runOnMainSync {
                        assertFalse("Inline preview must stay fitted", view!!.settings.supportZoom())
                        view!!.evaluateJavascript("(()=>{const v=document.getElementById('chart-viewport').getBoundingClientRect(),s=document.querySelector('#chart svg').getBoundingClientRect();return s.left>=v.left-1&&s.right<=v.right+1&&s.top>=v.top-1&&s.bottom<=v.bottom+1&&document.documentElement.scrollWidth<=window.innerWidth+1})()") {
                            fits.set(it); fitted.countDown()
                        }
                    }
                    assertTrue(fitted.await(5, TimeUnit.SECONDS))
                    assertEquals("Inline chart is clipped: $engine $source", "true", fits.get())
                    if (source.startsWith("mindmap") || source.startsWith("gantt")) {
                        val checked = CountDownLatch(1)
                        val layout = AtomicReference<String>()
                        val expression = if (source.startsWith("mindmap")) {
                            "(()=>{const n=document.querySelector('.section-root'),c=n.querySelector('circle').getBoundingClientRect(),t=n.querySelector('text').getBoundingClientRect();return Math.abs(c.left+c.width/2-t.left-t.width/2)<1})()"
                        } else {
                            "(()=>{const labels=Array.from(document.querySelectorAll('.grid .tick text')).filter(t=>getComputedStyle(t).display!=='none').map(t=>t.getBoundingClientRect());return labels.length>=2&&labels.every((t,i)=>!i||t.left>=labels[i-1].right+4)})()"
                        }
                        instrumentation.runOnMainSync { view!!.evaluateJavascript(expression) { layout.set(it); checked.countDown() } }
                        assertTrue(checked.await(5, TimeUnit.SECONDS))
                        assertEquals("Diagram labels are misaligned or overlap", "true", layout.get())
                    }
                    instrumentation.runOnMainSync { view!!.evaluateJavascript("window.lyraExport('png')", null) }
                    assertTrue("PNG export timed out", exported.get().await(15, TimeUnit.SECONDS))
                    assertNull("PNG export failed", failed.get())
                    val bitmap = BitmapFactory.decodeByteArray(bytes.get(), 0, bytes.get().size)
                    assertNotNull("Invalid PNG export", bitmap)
                    assertTrue(bitmap.width > 10 && bitmap.height > 10)
                    bitmap.recycle()
                    File(instrumentation.targetContext.cacheDir, "chart-$index-${if (dark) "dark" else "light"}.png").writeBytes(bytes.get())
                    exported.set(CountDownLatch(1))
                    instrumentation.runOnMainSync {
                        view!!.evaluateJavascript("window.lyraExport('svg')", null)
                    }
                    assertTrue("SVG export timed out", exported.get().await(5, TimeUnit.SECONDS))
                    assertNull("SVG export failed", failed.get())
                    val svg = bytes.get().toString(Charsets.UTF_8)
                    File(instrumentation.targetContext.cacheDir, "chart-$index-${if (dark) "dark" else "light"}.svg").writeBytes(bytes.get())
                    assertTrue("Missing SVG", svg.contains("<svg"))
                    assertFalse(svg.contains("<foreignObject"))
                    assertFalse(svg.contains("<script"))
                    val expectedLabel = listOf("开始", "用户", "项目计划", "项目", "应用", "USER", "App", "一月", "客户").first { source.contains(it) }
                    assertTrue("Export lost labels: $expectedLabel", svg.contains(expectedLabel))
                } finally { instrumentation.runOnMainSync { (view!!.parent as? ViewGroup)?.removeView(view); releaseChartWebView(view!!) } }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun inlineMarkdownChartHasFullscreenSourceAndSaveControls() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        FirstUseConsentStore(instrumentation.targetContext).accept()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun roots(view: View): List<ViewRootForTest> = buildList {
            if (view is ViewRootForTest) add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
        }
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        fun matches(node: SemanticsNode, text: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        fun nodes() = WindowInspector.getGlobalWindowViews().flatMap { roots(it) }.flatMap { all(it.semanticsOwner.rootSemanticsNode) }
        fun awaitNode(text: String): SemanticsNode {
            repeat(100) {
                var found: SemanticsNode? = null
                instrumentation.runOnMainSync { found = nodes().firstOrNull { matches(it, text) } }
                if (found != null) return found!!
                Thread.sleep(100)
            }
            throw AssertionError("Missing chart control: $text")
        }
        fun click(text: String) {
            awaitNode(text)
            instrumentation.runOnMainSync {
                var node = nodes().first { matches(it, text) }
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent!!
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            }
        }
        fun chartViews(view: View): List<WebView> = buildList {
            if (view is WebView && view.tag is ChartBridge) add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) addAll(chartViews(view.getChildAt(index)))
        }
        fun fullscreenView(landscape: Boolean = false): WebView {
            repeat(100) {
                var found: WebView? = null
                instrumentation.runOnMainSync {
                    found = WindowInspector.getGlobalWindowViews().flatMap(::chartViews).firstOrNull {
                        it.rootView !== activity.window.decorView && (it.tag as ChartBridge).finished &&
                            it.width > 0 && (!landscape || it.rootView.width > it.rootView.height)
                    }
                }
                if (found != null) return found!!
                Thread.sleep(100)
            }
            throw AssertionError("Fullscreen chart did not finish layout")
        }
        fun checkFullscreenBounds(landscape: Boolean = false) {
            val view = fullscreenView(landscape)
            // Window layout and WebView readiness precede surface presentation,
            // especially while Android animates a screen rotation.
            Thread.sleep(1200)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val root = view.rootView
                val insets = ViewCompat.getRootWindowInsets(root)!!
                val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
                val location = IntArray(2).also(view::getLocationOnScreen)
                val origin = IntArray(2).also(root::getLocationOnScreen)
                val bottomSpace = maxOf(bars.bottom, gestures.bottom) + 8 * view.resources.displayMetrics.density
                assertTrue("Fullscreen overlaps the status bar", location[1] >= origin[1] + bars.top)
                assertTrue("Fullscreen overlaps the bottom gesture area", location[1] + view.height <= origin[1] + root.height - bottomSpace + 2)
                assertTrue("Fullscreen zoom is disabled", view.settings.supportZoom())
            }
            val fitted = CountDownLatch(1)
            val fits = AtomicReference<String>()
            instrumentation.runOnMainSync {
                view.evaluateJavascript("(()=>{const s=document.querySelector('#chart svg').getBoundingClientRect();return s.width>=window.innerWidth-1&&s.bottom<=window.innerHeight+1&&document.documentElement.scrollHeight<=window.innerHeight+1})()") {
                    fits.set(it); fitted.countDown()
                }
            }
            assertTrue(fitted.await(5, TimeUnit.SECONDS))
            assertEquals("Fullscreen chart overflows the available height", "true", fits.get())
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "chart-fullscreen-${if (landscape) "landscape" else "portrait"}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        fun swipeLeft(text: String) {
            val node = awaitNode(text)
            var y = 0f
            var width = 0
            instrumentation.runOnMainSync { y = node.boundsInWindow.center.y; width = activity.window.decorView.width }
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                instrumentation.sendPointerSync(event)
                event.recycle()
            }
            val start = width - 60f
            send(MotionEvent.ACTION_DOWN, start)
            for (step in 1..10) {
                Thread.sleep(15)
                send(MotionEvent.ACTION_MOVE, start + (60 - start) * step / 10)
            }
            send(MotionEvent.ACTION_UP, 60f)
            instrumentation.waitForIdleSync()
        }
        try {
            val document = ChartDocument.create("echarts", "{\"xAxis\":{\"type\":\"category\",\"data\":[\"一月\",\"二月\"]},\"yAxis\":{\"type\":\"value\"},\"series\":[{\"type\":\"bar\",\"data\":[12,18]}]}", "Inline chart test")
            val guard = NavigationSwipeGuard()
            var navigationReveals = 0
            instrumentation.runOnMainSync {
                activity.setContent { MaterialTheme {
                    CompositionLocalProvider(LocalNavigationSwipeGuard provides guard) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).observeLeftSwipe("chart", guard) { navigationReveals++ }) {
                            RichMarkdownContent("Before\n\n${document.markdown()}\n\nAfter")
                        }
                    }
                } }
            }
            awaitNode("Inline chart test")
            click(uiText(R.string.chart_source))
            awaitNode(document.source)
            swipeLeft(document.source)
            assertEquals("Source scrolling revealed conversation navigation", 0, navigationReveals)
            swipeLeft("Before")
            assertEquals("The surrounding answer lost its navigation gesture", 1, navigationReveals)
            click(uiText(R.string.chart_fullscreen))
            awaitNode(uiText(R.string.chart_close_fullscreen))
            checkFullscreenBounds()
            click(uiText(R.string.chart_save))
            awaitNode(uiText(R.string.chart_save_png))
            awaitNode(uiText(R.string.chart_save_svg))
            awaitNode(uiText(R.string.chart_save_source))
            instrumentation.runOnMainSync {
                // Dismiss the save popup before closing the fullscreen dialog.
                nodes().firstOrNull { it.config.getOrNull(SemanticsActions.Dismiss) != null }
                    ?.config?.getOrNull(SemanticsActions.Dismiss)?.action?.invoke()
            }
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            checkFullscreenBounds(landscape = true)
            click(uiText(R.string.chart_close_fullscreen))
            awaitNode("After")
        } finally { instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity.finish() } }
    }
}
