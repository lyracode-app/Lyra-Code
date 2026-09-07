package com.yukisoffd.lyracode

import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.view.KeyEvent
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.interaction.overlay.DeviceChatPanel
import com.yukisoffd.lyracode.interaction.overlay.ManualControlOverlayProtocol
import com.yukisoffd.lyracode.interaction.session.DeviceChatMessage
import com.yukisoffd.lyracode.interaction.session.DeviceChatState
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import androidx.compose.ui.semantics.getOrNull

@RunWith(AndroidJUnit4::class)
class DeviceChatInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun realOverlayRendersApplicationMarkdownAndToolCards() {
        lateinit var hud: com.yukisoffd.lyracode.interaction.overlay.TaskHudController
        lateinit var panel: DeviceChatPanel
        instrumentation.runOnMainSync {
            hud = com.yukisoffd.lyracode.interaction.overlay.TaskHudController(instrumentation.targetContext, {}, {}, {}, {}, {})
            hud.render(ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE, targetPackage = "example.app",
                chat = DeviceChatState(messages = listOf(
                    DeviceChatMessage(11, "assistant", "## Markdown fixture\n**加粗正文**与 `code`\n\n- 自动执行\n- 保留对话", thinking = "模型返回的思考摘要"),
                    DeviceChatMessage(12, "tool", "已观察当前页面", toolName = "device_observe"),
                ))))
            panel = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<DeviceChatPanel>().single()
        }
        try {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(700)
            val location = IntArray(2)
            var width = 0; var height = 0
            instrumentation.runOnMainSync {
                assertTrue(panel.isShown)
                panel.getLocationOnScreen(location)
                width = panel.width; height = panel.height
            }
            assertTrue(width > 0 && height > 0)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
            try {
                val cropped = android.graphics.Bitmap.createBitmap(screenshot, location[0], location[1], width, height)
                try {
                    java.io.File(instrumentation.targetContext.externalCacheDir, "device-chat-markdown.png").outputStream().use {
                        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { cropped.recycle() }
            } finally { screenshot.recycle() }
            fun all(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                listOf(node) + node.children.flatMap(::all)
            lateinit var compose: androidx.compose.ui.platform.ViewRootForTest
            instrumentation.runOnMainSync { compose = descendants(panel).filterIsInstance<androidx.compose.ui.platform.ViewRootForTest>().single() }
            fun tapText(matches: (String) -> Boolean) {
                val point = FloatArray(2)
                instrumentation.runOnMainSync {
                    val node = all(compose.semanticsOwner.rootSemanticsNode).firstOrNull {
                        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { text -> matches(text.text) } == true &&
                            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null
                    }
                    assertNotNull("Expected expandable process/tool card", node)
                    val origin = IntArray(2); compose.view.getLocationOnScreen(origin)
                    point[0] = origin[0] + node!!.boundsInRoot.center.x
                    point[1] = origin[1] + node.boundsInRoot.center.y
                }
                val down = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            }
            tapText { it.contains("工具") || it.contains("tools", true) }
            tapText { it.contains("device_observe") }
            instrumentation.runOnMainSync {
                assertTrue(panel.isShown)
                assertTrue("Expanded tool result must stay in the overlay", all(compose.semanticsOwner.rootSemanticsNode).any {
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { text -> text.text.contains("已观察当前页面") } == true
                })
            }
        } finally { instrumentation.runOnMainSync { hud.destroy() } }
    }

    @Test fun allFourCornersResizeWindowAndPreserveDraft() {
        lateinit var hud: com.yukisoffd.lyracode.interaction.overlay.TaskHudController
        lateinit var panel: DeviceChatPanel
        lateinit var editor: EditText
        instrumentation.runOnMainSync {
            hud = com.yukisoffd.lyracode.interaction.overlay.TaskHudController(instrumentation.targetContext, {}, {}, {}, {}, {})
            hud.render(ManualControlState(activeUntilEpochMillis = Long.MAX_VALUE, targetPackage = "example.app"))
            panel = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<DeviceChatPanel>().single()
            editor = descendants(panel).filterIsInstance<EditText>().single()
            editor.setText("缩放保留草稿")
        }
        try {
            instrumentation.waitForIdleSync()
            for (left in listOf(true, false)) for (top in listOf(true, false)) {
                var oldWidth = 0; var oldHeight = 0
                val location = IntArray(2)
                instrumentation.runOnMainSync { panel.getLocationOnScreen(location); oldWidth = panel.width; oldHeight = panel.height }
                val density = panel.resources.displayMetrics.density
                val x = location[0] + if (left) 12 * density else oldWidth - 12 * density
                val y = location[1] + if (top) 12 * density else oldHeight - 12 * density
                val dx = (if (left) 30 else -30) * density
                val dy = (if (top) 30 else -30) * density
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                    val moved = action != MotionEvent.ACTION_DOWN
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + if (moved) dx else 0f, y + if (moved) dy else 0f, 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(200)
                instrumentation.runOnMainSync {
                    assertTrue("Corner did not reduce width", panel.width < oldWidth)
                    assertTrue("Corner did not reduce height", panel.height < oldHeight)
                    assertEquals("缩放保留草稿", editor.text.toString())
                    assertTrue(panel.isAttachedToWindow)
                }
            }
        } finally { instrumentation.runOnMainSync { hud.destroy() } }
    }

    @Test fun thinkingAndToolRecordsSurviveOverlayIpcAndComposerClearsWithoutClosing() {
        val message = DeviceChatMessage(42, "assistant", "# Markdown\n**正文**", thinking = "模型提供的思考摘要")
        val tool = DeviceChatMessage(43, "tool", "BLOCKED: FINANCIAL_OPERATION", toolName = "device_activate")
        val state = ManualControlState(chat = DeviceChatState(messages = listOf(message, tool)))
        assertEquals(state.chat.messages, ManualControlOverlayProtocol.decode(ManualControlOverlayProtocol.encode(state))!!.chat.messages)
        instrumentation.runOnMainSync {
            var clears = 0
            var stops = 0
            val panel = DeviceChatPanel(instrumentation.targetContext, View.OnTouchListener { _, _ -> false },
                {}, {}, {}, { stops++ }, {}, {}, onClearContext = { clears++ })
            val editor = descendants(panel).filterIsInstance<EditText>().single()
            editor.setText("旧草稿")
            descendants(panel).filterIsInstance<android.widget.Button>().single { it.text == "清空上下文" }.performClick()
            assertEquals(1, clears)
            assertEquals(0, stops)
            assertEquals("", editor.text.toString())
            assertFalse(descendants(panel).filterIsInstance<android.widget.Button>().any { it.text == "停止" })
        }
    }

    @Test fun streamingRenderPreservesEditorAndDraft() {
        instrumentation.runOnMainSync {
            val panel = DeviceChatPanel(instrumentation.targetContext, View.OnTouchListener { _, _ -> false },
                {}, {}, {}, {}, {}, {})
            val state = ManualControlState(activeUntilEpochMillis = System.currentTimeMillis() + 30_000, targetPackage = "example.app")
            panel.render(state, true)
            val editor = descendants(panel).filterIsInstance<EditText>().single()
            editor.setText("未发送的草稿")
            panel.render(state.copy(chat = DeviceChatState(messages = listOf(DeviceChatMessage(1, "assistant", "流式回复")))), true)
            assertSame(editor, descendants(panel).filterIsInstance<EditText>().single())
            assertEquals("未发送的草稿", editor.text.toString())
            panel.render(state, false)
            panel.render(state, true)
            assertEquals("未发送的草稿", editor.text.toString())
            panel.render(state.copy(chat = DeviceChatState(messages = listOf(
                DeviceChatMessage(1, "user", "帮我找到下载列表"),
                DeviceChatMessage(2, "assistant", "我会先观察当前页面，再请你确认要点击的入口。"),
            ), providerLabel = "示例 Provider · 对话模型")), true)
            val width = (340 * panel.resources.displayMetrics.density).toInt()
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, width, panel.measuredHeight)
            assertTrue(panel.measuredHeight in 1..panel.resources.displayMetrics.heightPixels)
            val bitmap = android.graphics.Bitmap.createBitmap(width, panel.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            panel.draw(android.graphics.Canvas(bitmap))
            java.io.File(instrumentation.targetContext.externalCacheDir, "device-chat-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun keyboardBackIsConsumedAndOnlyReleasesInputFocus() {
        val focusChanges = mutableListOf<Boolean>()
        lateinit var editor: DeviceChatPanel.ImeAwareEditText
        instrumentation.runOnMainSync {
            val panel = DeviceChatPanel(instrumentation.targetContext, View.OnTouchListener { _, _ -> false },
                focusChanges::add, {}, {}, {}, {}, {})
            editor = descendants(panel).filterIsInstance<DeviceChatPanel.ImeAwareEditText>().single()
            editor.beginInput()
            focusChanges += true
            editor.requestFocus()
            editor.setText("键盘收起后应保留")
            assertTrue(editor.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            assertTrue(editor.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertFalse(editor.hasFocus())
            assertEquals("键盘收起后应保留", editor.text.toString())
            assertEquals(listOf(true, false), focusChanges)
        }
    }

    @Test fun realApplicationOverlaySurvivesKeyboardBack() {
        verifyOverlaySurvivesImeDismissal(useBack = true)
    }

    @Test fun realApplicationOverlaySurvivesKeyboardHideWithoutBack() {
        verifyOverlaySurvivesImeDismissal(useBack = false)
    }

    @Test fun realApplicationOverlaySurvivesImeToolbarButton() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Provide observed IME toolbar button coordinates for this keyboard/device",
            args.containsKey("imeHideX") && args.containsKey("imeHideY"))
        verifyOverlaySurvivesImeDismissal(useBack = false, toolbarButton = true)
    }

    private fun verifyOverlaySurvivesImeDismissal(useBack: Boolean, toolbarButton: Boolean = false) {
        val context = instrumentation.targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        fun onOverlay(action: () -> Unit) {
            instrumentation.runOnMainSync(action)
        }
        lateinit var hud: com.yukisoffd.lyracode.interaction.overlay.TaskHudController
        lateinit var panel: DeviceChatPanel
        lateinit var editor: DeviceChatPanel.ImeAwareEditText
        onOverlay {
            hud = com.yukisoffd.lyracode.interaction.overlay.TaskHudController(context, {}, {}, {}, {}, {})
            hud.render(ManualControlState(
                activeUntilEpochMillis = System.currentTimeMillis() + 30_000,
                targetPackage = "example.app",
            ))
            panel = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .flatMap(::descendants).filterIsInstance<DeviceChatPanel>().single()
            editor = descendants(panel).filterIsInstance<DeviceChatPanel.ImeAwareEditText>().single()
            // An opaque marker lets the screenshot assertion detect a hidden Surface even while
            // the View remains attached, laid out and VISIBLE.
            panel.setBackgroundColor(android.graphics.Color.MAGENTA)
        }
        try {
            repeat(3) { cycle ->
                instrumentation.waitForIdleSync()
                val location = IntArray(2)
                var normalWidth = 0; var normalHeight = 0
                onOverlay { normalWidth = panel.width; normalHeight = panel.height; editor.getLocationOnScreen(location) }
                val x = location[0] + editor.width / 2f
                val y = location[1] + editor.height / 2f
                val downAt = SystemClock.uptimeMillis()
                instrumentation.uiAutomation.injectInputEvent(
                    MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0), true,
                )
                instrumentation.uiAutomation.injectInputEvent(
                    MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0), true,
                )
                instrumentation.waitForIdleSync()
                var imeVisible = false
                for (attempt in 0 until 30) {
                    onOverlay {
                        imeVisible = editor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                    }
                    if (imeVisible) break
                    SystemClock.sleep(100)
                }
                onOverlay {
                    assertTrue(editor.hasFocus())
                    assertTrue("IME did not become visible for the real overlay regression test", imeVisible)
                    if (cycle > 0) assertEquals("真实悬浮窗草稿", editor.text.toString())
                    editor.setText("真实悬浮窗草稿")
                }
                // A real toolbar target must finish moving into place before coordinate input.
                if (toolbarButton || cycle != 1) SystemClock.sleep(700)
                if (cycle != 1) {
                    val info = instrumentation.uiAutomation.serviceInfo
                    val originalFlags = info.flags
                    try {
                        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        instrumentation.uiAutomation.serviceInfo = info
                        SystemClock.sleep(100)
                        val ime = instrumentation.uiAutomation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        val imeBounds = android.graphics.Rect()
                        assertNotNull("Visible keyboard window must be discoverable", ime)
                        ime!!.getBoundsInScreen(imeBounds)
                        onOverlay {
                            editor.getLocationOnScreen(location)
                            val panelLocation = IntArray(2); panel.getLocationOnScreen(panelLocation)
                            android.util.Log.i("LyraResizeTest", "ime=$imeBounds panelY=${panelLocation[1]} panelH=${panel.height} editorBottom=${location[1] + editor.height} inset=${editor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom}")
                            assertTrue("Editor must stay above the keyboard", location[1] + editor.height <= imeBounds.top)
                            assertTrue("Composer must stay inside the resized overlay", location[1] + editor.height <= panelLocation[1] + panel.height)
                        }
                    } finally { info.flags = originalFlags; instrumentation.uiAutomation.serviceInfo = info }
                }
                if (toolbarButton) {
                    val args = InstrumentationRegistry.getArguments()
                    val tapX = args.getString("imeHideX")!!.toFloat()
                    val tapY = args.getString("imeHideY")!!.toFloat()
                    val tapAt = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val tap = MotionEvent.obtain(tapAt, SystemClock.uptimeMillis(), action, tapX, tapY, 0)
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(tap, true)) }
                        finally { tap.recycle() }
                    }
                } else if (useBack) {
                    assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                } else {
                    onOverlay {
                        editor.windowInsetsController!!.hide(android.view.WindowInsets.Type.ime())
                    }
                }
                instrumentation.waitForIdleSync()
                var imeStillVisible = true
                for (attempt in 0 until 30) {
                    var focused = true
                    onOverlay {
                        focused = editor.hasFocus()
                        imeStillVisible = editor.rootWindowInsets
                            ?.isVisible(android.view.WindowInsets.Type.ime()) == true
                    }
                    if (!focused && !imeStillVisible) break
                    SystemClock.sleep(100)
                }
                onOverlay {
                    assertTrue(panel.isAttachedToWindow)
                    assertFalse("IME remained visible after dismissal", imeStillVisible)
                    assertFalse("Overlay editor retained focus after the IME closed", editor.hasFocus())
                    assertEquals("真实悬浮窗草稿", editor.text.toString())
                }
                // Wait beyond the IME animation; attachment/focus checks alone missed the regression.
                SystemClock.sleep(700)
                onOverlay {
                    panel.getLocationOnScreen(location)
                    assertEquals("Normal width must be restored", normalWidth, panel.width)
                    assertEquals("Normal height must be restored", normalHeight, panel.height)
                }
                val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
                try {
                    assertEquals("Overlay Surface disappeared after IME dismissal",
                        android.graphics.Color.MAGENTA, screenshot.getPixel(location[0] + 12, location[1] + 12))
                } finally { screenshot.recycle() }
            }
        } finally {
            onOverlay { hud.destroy() }
        }
    }

    @Test fun overlayProtocolRoundTripsBoundedChatWithoutCredentials() {
        val chat = DeviceChatState(List(20) { DeviceChatMessage(it.toLong(), "assistant", "x".repeat(5000)) },
            running = true, status = "等待确认", providerLabel = "Provider · model")
        val state = ManualControlState(activeUntilEpochMillis = System.currentTimeMillis() + 30_000,
            agentTargetPackage = "example.app", chat = chat)
        val restored = ManualControlOverlayProtocol.decode(ManualControlOverlayProtocol.encode(state))!!
        assertEquals(16, restored.chat.messages.size)
        assertEquals(4000, restored.chat.messages.first().text.length)
        assertEquals("example.app", restored.agentTargetPackage)
        assertEquals(chat.providerLabel, restored.chat.providerLabel)
        assertTrue(restored.chat.running)
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(ManualControlOverlayProtocol.encode(state))
            assertTrue("Transcript must fit safely in a Binder transaction", parcel.dataSize() < 200_000)
        } finally { parcel.recycle() }
    }

    @Test fun approvalIdentitySurvivesIpc() {
        val selection = com.yukisoffd.lyracode.interaction.model.ManualActionSelection("snapshot", "handle",
            com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.ACTIVATE, "example.app")
        val original = ManualControlState(selection = selection)
        assertEquals(selection, ManualControlOverlayProtocol.decode(ManualControlOverlayProtocol.encode(original))!!.selection)
    }

    @Test fun textApprovalKeepsExactPayloadAndFullScrollablePreview() {
        val text = "第一行  保留空格\n" + "普通草稿".repeat(110)
        val selection = com.yukisoffd.lyracode.interaction.model.ManualActionSelection("snapshot", "field",
            com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.SET_TEXT, "example.app", inputText = text)
        val original = ManualControlState(selection = selection,
            status = com.yukisoffd.lyracode.interaction.session.ManualControlStatus.TARGET_SELECTED)
        val encoded = ManualControlOverlayProtocol.encode(original)
        assertEquals(selection, ManualControlOverlayProtocol.decode(encoded)!!.selection)
        encoded.getBundle("selection")!!.putString("input_text", "x".repeat(501))
        assertNull(ManualControlOverlayProtocol.decode(encoded)!!.selection)
        instrumentation.runOnMainSync {
            val panel = DeviceChatPanel(instrumentation.targetContext, View.OnTouchListener { _, _ -> false },
                {}, {}, {}, {}, {}, {})
            panel.render(original, true)
            val preview = descendants(panel).filterIsInstance<android.widget.TextView>()
                .single { it.text.toString().contains("将替换输入框内容") }
            assertTrue(preview.text.toString().contains(text))
            assertTrue(preview.parent is android.widget.ScrollView)
            val width = (340 * panel.resources.displayMetrics.density).toInt()
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, width, panel.measuredHeight)
            assertTrue(panel.measuredHeight < panel.resources.displayMetrics.heightPixels)
            val bitmap = android.graphics.Bitmap.createBitmap(width, panel.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            panel.draw(android.graphics.Canvas(bitmap))
            java.io.File(instrumentation.targetContext.externalCacheDir, "device-text-approval-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun localMcpCannotDiscoverOrInvokeDeviceTools() = kotlinx.coroutines.runBlocking {
        val context = instrumentation.targetContext
        val store = ConversationStore(context, inMemory = true)
        try {
            val agent = com.yukisoffd.lyracode.interaction.agent.DeviceAgentFactory.create(
                context, com.yukisoffd.lyracode.data.AppSettings(context), store,
            )
            agent.scopedTools = com.yukisoffd.lyracode.interaction.agent.DeviceInteractionToolProvider(
                123, "example.app", 0, com.yukisoffd.lyracode.interaction.session.ExecutionBudget({ 0 }), {}, {}, {},
            )
            val tools = agent.localMcpToolDefinitions()
            for (index in 0 until tools.length()) {
                assertFalse(tools.getJSONObject(index).getJSONObject("function").getString("name").startsWith("device_"))
            }
            assertEquals("ERROR: DEVICE_FOREGROUND_SESSION_REQUIRED", agent.executeLocalMcpTool("device_activate", org.json.JSONObject()))
        } finally { store.close() }
    }

    @Test fun screenConversationDatabaseIsEphemeral() {
        val context = instrumentation.targetContext
        val store = ConversationStore(context, inMemory = true)
        val id = store.createConversation("test", "test")
        store.addMessage(id, "tool", "UNTRUSTED_SCREEN_CONTENT")
        assertNotNull(store.conversation(id))
        store.close()
        val reopened = ConversationStore(context, inMemory = true)
        assertNull(reopened.conversation(id))
        reopened.close()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
