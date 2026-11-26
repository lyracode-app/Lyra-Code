package com.yukisoffd.lyracode

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.ChatRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yukisoffd.lyracode.widgets.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InteractiveWidgetInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun initializeStrings() { AppStrings.initialize(compose.activity) }

    @Test fun preflightReportsStartupFailuresAndAllowsLocalInteraction() = runBlocking {
        val context = compose.activity
        validateWidget(context, """<input id="n" value="3"><output id="r"></output>
            <script>n.oninput=()=>r.value=n.value*2; n.oninput();
            if(r.value!=='6')throw Error('calculation failed');</script>""")
        val bad = runCatching { validateWidget(context, "<script>throw Error('widget-fixture-error')</script>") }
        assertTrue(bad.exceptionOrNull()?.message.orEmpty(), bad.exceptionOrNull()?.message?.contains("widget-fixture-error") == true)
        val denied = runCatching { validateWidget(context, "<script>fetch('https://example.invalid/data')</script>") }
        assertTrue("External requests must fail validation", denied.isFailure)
    }

    @Test fun finishedFencesRenderInPlaceAndRetainStateAcrossFullscreen() {
        val start = "```lyra-widget\n<button id='b' onclick='this.textContent=\"updated\"'>calc</button>"
        val markdown = mutableStateOf(start)
        val diagnostic = mutableStateOf("")
        val host = WidgetHost({ "<b>file content</b>" }, { diagnostic.value = it; true })
        compose.setContent { Column {
            WidgetAnswer(host) { RichMarkdownContent(markdown.value, isStreaming = true) }
        } }
        compose.onNodeWithText(uiText(R.string.widget_waiting)).assertExists()
        compose.runOnIdle { markdown.value = start + "\n```\n\nMore text." }
        compose.waitUntil(10000) { findWidget(compose.activity.window.decorView) != null }
        val web = requireNotNull(findWidget(compose.activity.window.decorView))
        fun js(expression: String): String {
            val result = java.util.concurrent.CompletableFuture<String>()
            compose.runOnUiThread { web.evaluateJavascript(expression) { result.complete(it) } }
            return result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        compose.waitUntil(10000) { js("!!document.getElementById('b')") == "true" }
        assertEquals("\"updated\"", js("b.click();b.textContent"))
        compose.onNodeWithContentDescription(uiText(R.string.chart_fullscreen)).performClick()
        compose.onNodeWithContentDescription(uiText(R.string.chart_close_fullscreen)).performClick()
        compose.waitUntil(10000) { findWidget(compose.activity.window.decorView) === web }
        assertEquals("\"updated\"", js("b.textContent"))
        compose.runOnIdle { markdown.value += "\n\n```lyra-html\npage.html\n```" }
        compose.waitUntil(10000) { countWidgets(compose.activity.window.decorView) == 2 }
        js("setTimeout(()=>{throw Error('late widget failure')},0)")
        compose.waitUntil(10000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(uiText(R.string.widget_repair))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(uiText(R.string.widget_repair)).performClick()
        assertTrue(diagnostic.value, diagnostic.value.contains("late widget failure"))
    }

    @Test fun workspacePreviewSupportsEncodedNamesAndRejectsEscapes() {
        val context = compose.activity
        val dir = File.createTempFile("widget", "test", context.cacheDir).apply { delete(); mkdir() }
        try {
            File(dir, "例 1.html").writeText("<p>test</p>")
            val path = htmlPathFromLink(Uri.parse("content://com.yukisoffd.lyracode/%E4%BE%8B%201.html"))
            assertEquals("<p>test</p>", readWorkspaceHtml(context, DocumentFile.fromFile(dir), path))
            val snapshot = htmlSnapshotUri(context, "<p>test</p>")
            assertEquals("text/html", context.contentResolver.getType(snapshot))
            assertEquals("<p>test</p>", context.contentResolver.openInputStream(snapshot)!!.readPreviewHtml())
            for (bad in listOf("%2E%2E/a.html", "a%2Fb.html", "a.js", "/outside.html")) {
                assertTrue(runCatching { htmlPathFromLink(Uri.parse("content://com.yukisoffd.lyracode/$bad")) }.isFailure)
            }
            assertTrue(runCatching { ByteArray(MAX_HTML_BYTES + 1).inputStream().readPreviewHtml() }.isFailure)
        } finally { dir.deleteRecursively() }
    }

    @Test fun fullPageLinkOpensNativePreviewWithoutCustomTabs() {
        org.junit.Assume.assumeTrue(androidx.browser.customtabs.CustomTabsClient.getPackageName(compose.activity, null) == null)
        val host = WidgetHost({ path ->
            assertEquals("pages/example 1.html", path)
            "<h1>Example</h1>"
        }, { false })
        compose.setContent { WidgetAnswer(host) {
            RichMarkdownContent("[View page](content://com.yukisoffd.lyracode/pages/example%201.html)")
        } }
        compose.onNodeWithText("View page").performTouchInput { click(androidx.compose.ui.geometry.Offset(8f, center.y)) }
        compose.waitUntil(10000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(uiText(R.string.chart_close_fullscreen)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(uiText(R.string.chart_close_fullscreen)).performClick()
        compose.onNodeWithText("View page").assertExists()
    }

    @Test fun browserIntentGrantsOnlyTheSelectedSnapshot() {
        val activity = compose.activity
        org.junit.Assume.assumeNotNull(androidx.browser.customtabs.CustomTabsClient.getPackageName(activity, null))
        val uri = htmlSnapshotUri(activity, "<!doctype html><title>Preview test</title><h1>Lyra preview grant verified</h1>")
        var captured: android.content.Intent? = null
        val recording = object : android.content.ContextWrapper(activity) {
            override fun startActivity(intent: android.content.Intent) { captured = intent }
        }
        assertTrue(openHtmlTab(recording, uri, false, android.graphics.Color.WHITE))
        val intent = requireNotNull(captured)
        assertEquals(uri, intent.data)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals("text/html", intent.type)
        assertTrue(intent.hasExtra(androidx.browser.customtabs.CustomTabsIntent.EXTRA_SESSION))
        assertEquals(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and
            (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    @Test fun dynamicPageAndSecondAnswerSurviveTapsAndLazyListRoundTrips() {
        val host = WidgetHost({ error(it) }, { false })
        val html = "<input id='entry'><div id='body' style='height:950px'></div>"
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="160" height="36"><rect width="20" height="20">
            <animate attributeName="x" from="0" to="50" dur="2s" repeatCount="indefinite"/></rect></svg>"""
        val chart = """{"xAxis":{"type":"category","data":["A"]},"yAxis":{},"series":[{"type":"bar","data":[1]}]}"""
        val firstAnswer = "Stable body\n\n```svg\n$svg\n```\n\n```echarts\n$chart\n```\n\n```lyra-widget dynamic\n$html\n```"
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalRichPreviewPins provides remember { RichPreviewPins() }) {
                LazyColumn(state = list) {
                    item("first") {
                        MessageCard(ChatRecord(id = 1, role = "assistant", content = firstAnswer),
                            widgetHost = host)
                    }
                    items(25) { Spacer(Modifier.fillMaxWidth().height(150.dp)) }
                    item("second") {
                        MessageCard(ChatRecord(id = 2, role = "assistant", content = "Second answer\n\n```lyra-widget\n$html\n```"),
                            widgetHost = host)
                    }
                }
            }
        }
        val density = compose.activity.resources.displayMetrics.density
        compose.waitUntil(15000) { findWidget(compose.activity.window.decorView)?.height ?: 0 > 950 * density }
        compose.waitUntil(10000) { webViews(compose.activity.window.decorView).size >= 3 }
        val initialViews = webViews(compose.activity.window.decorView)
        val first = requireNotNull(findWidget(compose.activity.window.decorView))
        assertEquals("\"keep\"", evaluate(first, "entry.value='keep'"))
        compose.onNodeWithText("Stable body").performTouchInput { click() }
        compose.waitForIdle()
        assertSame(first, findWidget(compose.activity.window.decorView))
        evaluate(first, "document.getElementById('body').style.height='1250px'")
        compose.waitUntil(5000) { first.height > 1250 * density }
        compose.runOnIdle { scope.launch { list.scrollToItem(26) } }
        compose.waitUntil(10000) { countWidgets(compose.activity.window.decorView) == 2 }
        compose.runOnIdle { scope.launch { list.scrollToItem(0) } }
        compose.waitUntil(10000) { list.firstVisibleItemIndex == 0 }
        assertTrue(webViews(compose.activity.window.decorView).containsAll(initialViews))
        assertTrue(first.isAttachedToWindow)
        assertEquals("\"keep\"", evaluate(first, "entry.value"))
        assertTrue(first.height > 1250 * density)
    }

    @Test fun inlineDragsOperateControlsAndHandOffAtBothDocumentEdges() {
        val source = """<style>body{margin:0}input{display:block;margin:0}
            #h{width:260px;height:36px}#v{writing-mode:vertical-lr;width:40px;height:140px}</style>
            <input id="h" type="range"><input id="v" type="range"><div style="height:1600px;width:800px"></div>"""
        val host = WidgetHost({ error(it) }, { false })
        val guard = NavigationSwipeGuard()
        var reveals = 0
        lateinit var list: LazyListState
        lateinit var drawer: DrawerState
        lateinit var scope: CoroutineScope
        compose.setContent {
            list = rememberLazyListState(initialFirstVisibleItemIndex = 1)
            drawer = rememberDrawerState(DrawerValue.Closed)
            scope = rememberCoroutineScope()
            ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { Text("Drawer") } }, drawerState = drawer) {
                CompositionLocalProvider(LocalNavigationSwipeGuard provides guard) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().observeLeftSwipe(1, guard) { reveals++ }) {
                        item { Spacer(Modifier.height(900.dp)) }
                        item { WidgetAnswer(host) { RichMarkdownContent("```lyra-widget\n$source\n```") } }
                        item { Spacer(Modifier.height(1200.dp)) }
                    }
                }
            }
        }
        compose.waitUntil(15000) { findWidget(compose.activity.window.decorView)?.canScrollVertically(1) == true }
        val web = requireNotNull(findWidget(compose.activity.window.decorView))
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(web.height <= 420 * density + 1)
        fun dragCss(x: Int, y: Int, endX: Int, endY: Int) = drag(web, x * density, y * density, endX * density, endY * density)
        dragCss(130, 18, 235, 18)
        assertTrue("Horizontal slider failed: " + evaluate(web, "h.value"), evaluate(web, "+h.value").toInt() > 75)
        dragCss(235, 18, 30, 18)
        assertEquals(0, reveals)
        assertTrue(drawer.isClosed)
        dragCss(20, 106, 20, 155)
        assertTrue("Vertical slider did not change", evaluate(web, "+v.value").toInt() > 65)
        assertEquals(0, web.scrollY)
        assertEquals(1, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
        dragCss(230, 220, 60, 220)
        compose.waitUntil(4000) { web.scrollX > 0 }
        assertEquals(0, reveals)
        dragCss(150, 245, 150, 170)
        compose.waitUntil(4000) { web.scrollY > 0 }
        assertEquals(0, list.firstVisibleItemScrollOffset)
        // Use the actual visual viewport (the test page is deliberately wider than the screen).
        compose.runOnUiThread { web.pageDown(true) }
        compose.waitUntil(5000) { !web.canPanVertically(1) }
        dragCss(150, 220, 150, 110)
        compose.waitUntil(4000) { list.firstVisibleItemIndex > 1 || list.firstVisibleItemScrollOffset > 0 }
        compose.runOnIdle { scope.launch { list.scrollToItem(1) } }
        compose.waitUntil(5000) { list.firstVisibleItemIndex == 1 && list.firstVisibleItemScrollOffset == 0 }
        val topWeb = requireNotNull(findWidget(compose.activity.window.decorView))
        compose.runOnUiThread { topWeb.pageUp(true) }
        compose.waitUntil(4000) { !topWeb.canPanVertically(-1) }
        drag(topWeb, 150 * density, 190 * density, 150 * density, 270 * density)
        compose.waitUntil(4000) { list.firstVisibleItemIndex == 0 }
    }

    @Test fun innerScrollAreaHandsRemainingDragToConversation() {
        val html = "<div id='area' style='height:180px;overflow:auto'><div style='height:500px'></div></div>"
        val host = WidgetHost({ error(it) }, { false })
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState(initialFirstVisibleItemIndex = 1)
            LazyColumn(state = list) {
                item { Spacer(Modifier.height(900.dp)) }
                item { WidgetAnswer(host) { RichMarkdownContent("```lyra-widget\n$html\n```") } }
                item { Spacer(Modifier.height(900.dp)) }
            }
        }
        val density = compose.activity.resources.displayMetrics.density
        compose.waitUntil(10000) {
            val web = findWidget(compose.activity.window.decorView)
            web != null && web.height in (185 * density).toInt()..(205 * density).toInt()
        }
        val web = requireNotNull(findWidget(compose.activity.window.decorView))
        evaluate(web, "area.scrollTop = 290")
        val frame = CompletableFuture<Unit>()
        compose.runOnUiThread {
            web.postVisualStateCallback(0, object : android.webkit.WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { frame.complete(Unit) }
            })
        }
        frame.get(5, TimeUnit.SECONDS)
        assertEquals(290.0, evaluate(web, "area.scrollTop").toDouble(), 1.0)
        drag(web, 80 * density, 160 * density, 80 * density, 20 * density)
        val remaining = evaluate(web, "area.scrollHeight - area.clientHeight - area.scrollTop").toDouble()
        assertTrue("Inner scroller still has $remaining CSS pixels, chat offset = " +
            list.firstVisibleItemScrollOffset, remaining <= 1)
        compose.waitUntil(4000) { list.firstVisibleItemIndex > 1 || list.firstVisibleItemScrollOffset > 0 }
    }

    private fun evaluate(web: WidgetWebView, expression: String): String {
        val result = CompletableFuture<String>()
        compose.runOnUiThread { web.evaluateJavascript(expression) { result.complete(it) } }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun drag(web: WidgetWebView, x: Float, y: Float, endX: Float, endY: Float) {
        val location = IntArray(2)
        compose.runOnUiThread { web.getLocationOnScreen(location) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                location[0] + x + (endX - x) * fraction, location[1] + y + (endY - y) * fraction, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) {
            SystemClock.sleep(20)
            send(MotionEvent.ACTION_MOVE, step / 12f)
        }
        SystemClock.sleep(90)
        send(MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
    }

    private fun findWidget(root: android.view.View): WidgetWebView? {
        if (root is WidgetWebView) return root
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) {
            findWidget(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun webViews(root: android.view.View): List<android.webkit.WebView> = when (root) {
        is android.webkit.WebView -> listOf(root)
        is android.view.ViewGroup -> (0 until root.childCount).flatMap { webViews(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun countWidgets(root: android.view.View): Int = when (root) {
        is WidgetWebView -> 1
        is android.view.ViewGroup -> (0 until root.childCount).sumOf { countWidgets(root.getChildAt(it)) }
        else -> 0
    }
}
