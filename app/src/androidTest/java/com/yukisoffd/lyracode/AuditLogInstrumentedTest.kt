package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import com.yukisoffd.lyracode.data.AuditLogStore
import org.junit.Assert.*
import org.junit.Test

class AuditLogInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun prootValidationFailureIncludesCommandAndError() {
        val marker = "audit-proot-${System.nanoTime()}"
        val executor = com.yukisoffd.lyracode.debian.ProotCommandExecutor(context)
        val result = kotlinx.coroutines.runBlocking {
            runCatching { executor.execute("", "echo $marker", null, null, 5) }
        }
        assertTrue(result.isFailure)
        AuditLogStore(context).use { store ->
            val entry = store.recent(kind = "proot", query = marker).single()
            try {
                assertTrue(store.readSection(entry.id, "execution.request").contains(marker))
                assertTrue(store.readSection(entry.id, "execution.error").contains("linux_id"))
                assertTrue(store.readSection(entry.id, "duration_ms").toLong() >= 0)
            } finally { store.delete(entry.id) }
        }
    }

    @Test fun fullPayloadSurvivesCursorWindowLimitsAndFilteringDeletion() {
        AuditLogStore(context, inMemory = true).use { store ->
            val text = "请求😀".repeat(600_000) + "needle-at-end"
            val id = store.add("model_http", "test-model", "POST /chat")
            store.section(id, "request.body", text)
            store.section(id, "response.body", "first")
            store.section(id, "response.body", "第二段😀", append = true)
            val command = store.add("proot", "echo hello", "command details")
            assertEquals(text, store.readSection(id, "request.body"))
            assertEquals("first第二段😀", store.readSection(id, "response.body"))
            assertEquals(listOf(id), store.recent(kind = "model_http", query = "needle-at-end").map { it.id })
            assertTrue(store.recent(kind = "proot", query = "needle-at-end").isEmpty())
            assertEquals(listOf(command), store.recent(kind = "proot").map { it.id })
            assertTrue(store.recent().all { it.detail.length <= 240 })
            store.delete(id)
            store.section(id, "response.body", "late response", append = true)
            assertEquals("", store.readSection(id, "response.body"))
            assertEquals(listOf(command), store.recent().map { it.id })
            store.clear()
            assertTrue(store.recent().isEmpty())
        }
    }

    @Test fun detailsStartCollapsedAndClearRequiresConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AuditLogStore(context, inMemory = true).use { store ->
            val id = store.add("model_http", "audit-ui-fixture", "POST /chat/completions")
            store.section(id, "request.body", """{"messages":[{"role":"user","content":"fixture-message"}],"tools":[{"type":"function"}],"reasoning_effort":"high"}""")
            store.section(id, "request.headers", """[{"name":"Content-Type","value":"application/json"}]""")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme { LogScreen(store) } } }
                waitForText("audit-ui-fixture")
                clickText("audit-ui-fixture")
                waitForText("request.body")
                assertNull(findText("fixture-message"))
                waitForText("\"reasoning_effort\": \"high\"")
                assertNull(findText("fixture-message"))
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "audit-detail.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                clickText(context.getString(R.string.cd_close))
                waitForText(context.getString(R.string.cd_clear_log))
                clickText(context.getString(R.string.cd_clear_log))
                waitForText(context.getString(R.string.log_confirm_clear))
                assertEquals(1, store.recent().size)
                clickText(context.getString(R.string.action_cancel))
                assertEquals(1, store.recent().size)
                clickText(context.getString(R.string.cd_clear_log))
                waitForText(context.getString(R.string.log_confirm_clear))
                clickText(context.getString(R.string.action_delete))
                waitForText(context.getString(R.string.notice_no_log))
                assertTrue(store.recent().isEmpty())
            }
        }
    }

    @Test fun expandedLongResponseCanReachItsLastPageAndBottomText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AuditLogStore(context, inMemory = true).use { store ->
            val id = store.add("model_http", "audit-scroll-fixture", "profile=default\nmodel=deepseek-flash\nPOST https://api.deepseek.com/responses")
            val content = "Long response line with enough words to wrap comfortably.\n".repeat(160) + "TAIL_MARKER"
            store.section(id, "response.body", org.json.JSONObject().put("content", content).toString())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme { LogScreen(store) } } }
                waitForText("audit-scroll-fixture")
                clickText("audit-scroll-fixture")
                waitForText("response.body")
                clickText("response.body")
                waitForText("\"content\"")
                clickText("\"content\"")
                scrollToText(context.getString(R.string.log_next))
                clickText(context.getString(R.string.log_next))
                scrollToText("TAIL_MARKER")
                instrumentation.uiAutomation.waitForIdle(500, 5000)
                val bounds = android.graphics.Rect()
                checkNotNull(findText("TAIL_MARKER")).getBoundsInScreen(bounds)
                assertFalse("Tail must have visible bounds", bounds.isEmpty)
                val screen = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull(screen)
                assertTrue("Tail must fit above system navigation", bounds.bottom < screen!!.height - 16)
                assertViewportAboveNavigation(screen.height)
                File(context.cacheDir, "audit-scroll-bottom.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screen.recycle()
                scrollToText("detail")
                clickText("detail")
                scrollToText("profile=default")
                repeat(3) {
                    val viewport = android.graphics.Rect()
                    auditScrollableNode().getBoundsInScreen(viewport)
                    val command = "input swipe ${viewport.centerX()} ${viewport.bottom - 100} ${viewport.centerX()} ${viewport.top + 100} 400"
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand(command)
                    ).use { it.readBytes() }
                    instrumentation.uiAutomation.waitForIdle(500, 5000)
                }
                instrumentation.uiAutomation.waitForIdle(500, 5000)
                val expandedDetail = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                assertViewportAboveNavigation(expandedDetail.height)
                File(context.cacheDir, "audit-expanded-bottom.png").outputStream().use {
                    expandedDetail.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                expandedDetail.recycle()
            }
        }
    }

    private fun assertViewportAboveNavigation(screenHeight: Int) {
        val bounds = android.graphics.Rect()
        auditScrollableNode().getBoundsInScreen(bounds)
        assertFalse(bounds.isEmpty)
        assertTrue("Viewport $bounds must end above system navigation at screen bottom $screenHeight",
            bounds.bottom < screenHeight)
    }

    private fun auditScrollableNode(): AccessibilityNodeInfo {
        fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                findScrollable(child)?.let { return it }
            }
            return null
        }
        val root = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return checkNotNull(findScrollable(root))
    }

    private fun scrollToText(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(80) {
            val target = findText(text)
            val bounds = android.graphics.Rect()
            target?.getBoundsInScreen(bounds)
            if (target?.isVisibleToUser == true && !bounds.isEmpty && bounds.top >= 0 &&
                bounds.bottom < context.resources.displayMetrics.heightPixels - 48) return
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isScrollable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { scrollable(it)?.let { found -> return found } }
                return null
            }
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            (root?.let(::scrollable) ?: root)?.let { node ->
                val viewport = android.graphics.Rect()
                node.getBoundsInScreen(viewport)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "input swipe ${viewport.centerX()} ${viewport.bottom - 100} ${viewport.centerX()} ${viewport.top + 100} 400"
                )).use { it.readBytes() }
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(200, 5000)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "audit-scroll-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        error("Cannot scroll to: $text")
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            nodes += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::collect)
        return nodes.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            ?: nodes.firstOrNull { it.text?.contains(text) == true || it.contentDescription?.contains(text) == true }
    }

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (findText(text) != null) return
            Thread.sleep(100)
        }
        error("Missing UI text: $text")
    }

    private fun clickText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var node = findText(text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            Thread.sleep(100)
        }
        error("Unable to click: $text")
    }
}
