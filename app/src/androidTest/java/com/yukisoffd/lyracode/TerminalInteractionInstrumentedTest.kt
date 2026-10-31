package com.yukisoffd.lyracode

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.ssh.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalInteractionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun initializeStrings() { AppStrings.initialize(context) }

    @Test fun imeKeepsCjkCandidatesLocalAndPreservesUnicodeAndRawAscii() {
        instrumentation.runOnMainSync {
            val sent = mutableListOf<String>()
            val input = TerminalInputView(context).apply {
                onText = { sent += it }
                onBackspace = { sent += "DELETE" }
                onEnter = { sent += "ENTER" }
            }
            val info = EditorInfo()
            val connection = input.onCreateInputConnection(info)!!
            assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
            connection.setComposingText("ㅎ", 1)
            connection.setComposingText("하", 1)
            assertEquals("하", connection.getTextBeforeCursor(10, 0).toString())
            assertTrue(sent.isEmpty())
            connection.commitText("한", 1)
            connection.setComposingText("にほん", 1)
            connection.commitText("日本", 1)
            connection.setComposingText("你𠀀", 1)
            connection.deleteSurroundingText(2, 0)
            assertEquals("你", connection.getTextBeforeCursor(10, 0).toString())
            connection.finishComposingText()
            connection.setComposingText("i", 1)
            connection.commitText("i", 1)
            connection.commitText("echo 中文\r\necho 한국어\n", 1)
            assertEquals(listOf("한", "日本", "你", "i", "echo 中文", "ENTER", "echo 한국어", "ENTER"), sent)
            input.discardComposingInput()
            assertFalse(connection.commitText("stale candidates", 1))
            input.onCreateInputConnection(EditorInfo())!!.commitText("fresh", 1)
            assertEquals("fresh", sent.last())
        }
    }

    @Test fun fractionalGridAndBoldUseTheSameColumnAdvance() {
        val pixels = 13f * context.resources.displayMetrics.scaledDensity
        val renderer = TerminalGridRenderer(Typeface.MONOSPACE, pixels, TerminalPalette.CAMPBELL)
        val paint = Paint().apply { typeface = Typeface.MONOSPACE; textSize = pixels }
        assertEquals(paint.measureText("0"), renderer.cellWidth, .001f)
        val terminal = TerminalEmulator(40, 2)
        terminal.accept("WiWi  中文 한국어 日本語 e\u0301 \u001b[1mbold".toByteArray())
        val bitmap = Bitmap.createBitmap((renderer.cellWidth * 40).toInt(), (renderer.cellHeight * 2).toInt(), Bitmap.Config.ARGB_8888)
        renderer.draw(android.graphics.Canvas(bitmap), terminal.snapshot(), 0, 2, null, true)
        assertTrue(bitmap.getPixel(0, 0) != 0)
        bitmap.recycle()
    }

    @Test fun importedTerminalFontIsIndependentPersistentAndFallsBackAfterDeletion() {
        val settings = AppSettings(context)
        val previous = settings.fontLibrary().firstOrNull { it.path == settings.terminalFontPath }
        val textFont = settings.textFontPath
        val codeFont = settings.codeFontPath
        val imported = settings.importFonts(listOf(Uri.fromFile(File("/system/fonts/DroidSansMono.ttf")))).getOrThrow().single()
        try {
            settings.selectTerminalFont(imported)
            assertEquals(imported.path, AppSettings(context).terminalFontPath)
            assertEquals(textFont, settings.textFontPath)
            assertEquals(codeFont, settings.codeFontPath)
            settings.deleteFont(imported)
            assertNull(settings.terminalFontPath)
            assertNull(settings.terminalFontName)
            assertFalse(File(imported.path).exists())
        } finally {
            if (File(imported.path).exists()) settings.deleteFont(imported)
            settings.selectTerminalFont(previous)
        }
    }

    @Test fun magnifierOnlyWhileDraggingAndActionsFollowSelectionThenDismissOrPaste() {
        val settings = AppSettings(context)
        val oldSize = settings.terminalFontSize
        settings.terminalFontSize = 13f
        val state = TerminalSelectionUiState()
        val terminal = TerminalEmulator(48, 30)
        val lines = listOf(
            "user@host:~ $ printf UTF-8", "English WiWi minimum 0123456789",
            "中文输出与输入 日本語の表示 한국어 터미널", "Combining: e\u0301  Supplementary: 𠀀",
            "\u001b[32mSUCCESS\u001b[0m  \u001b[33mWARNING\u001b[0m  \u001b[91mERROR\u001b[0m",
            "", "", "echo hello world", "Select text to copy or paste", "", "", "",
        )
        terminal.accept(lines.joinToString("\r\n").toByteArray())
        val connection = RecordingSession(terminal.snapshot())
        val screen = terminal.snapshot()
        try {
            compose.setContent { MaterialTheme { TerminalSurface(screen, connection, true, settings, state) } }
            compose.waitForIdle()
            val typeface = settings.terminalFontPath?.let(Typeface::createFromFile) ?: Typeface.MONOSPACE
            val renderer = TerminalGridRenderer(typeface, 13f * context.resources.displayMetrics.scaledDensity, TerminalPalette.CAMPBELL)
            val output = compose.onNodeWithContentDescription("terminal-output")
            val point = Offset(renderer.cellWidth * 7.5f, renderer.cellHeight * 7.5f)
            output.performTouchInput { down(point) }
            compose.mainClock.advanceTimeBy(850)
            compose.waitForIdle()
            saveScreenshot("terminal-held.png")
            compose.onNodeWithContentDescription("terminal-selection-magnifier").assertIsDisplayed()
            compose.onNodeWithContentDescription("terminal-selection-actions").assertDoesNotExist()
            saveScreenshot("terminal-magnifier.png")
            output.performTouchInput { up() }
            compose.onNodeWithContentDescription("terminal-selection-magnifier").assertDoesNotExist()
            compose.onNodeWithContentDescription("terminal-selection-actions").assertIsDisplayed()
            assertEquals("hello", TerminalSelectionText.extract(screen, state.selection!!))
            saveScreenshot("terminal-selection.png")
            val originalBounds = compose.onNodeWithContentDescription("terminal-selection-actions").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("terminal-selection-end").performTouchInput {
                down(center); moveBy(Offset(renderer.cellWidth * 5, 0f), delayMillis = 300); up()
            }
            val movedBounds = compose.onNodeWithContentDescription("terminal-selection-actions").fetchSemanticsNode().boundsInRoot
            assertTrue(movedBounds.left > originalBounds.left)
            compose.onNodeWithText(uiText(R.string.action_copy)).performClick()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().startsWith("hello"))
            assertNull(state.selection)

            output.performTouchInput { longClick(point) }
            compose.onNodeWithText(uiText(R.string.terminal_paste)).performClick()
            assertTrue(connection.writes.joinToString("").startsWith("hello"))
            assertNull(state.selection)

            output.performTouchInput { longClick(point) }
            output.performTouchInput { click(Offset(renderer.cellWidth, renderer.cellHeight * 15)) }
            compose.onNodeWithContentDescription("terminal-selection-actions").assertDoesNotExist()
            assertNull(state.selection)
        } finally { settings.terminalFontSize = oldSize }
    }

    @Test fun resizingAViewportKeepsOutputAndClearRemovesHistorySelectionAndCursor() {
        val settings = AppSettings(context)
        val connection = BufferSession()
        connection.accept((0..35).joinToString("\r\n") { "ROW-$it 中文 output" })
        val original = bufferText(connection.screen.value)
        val selection = TerminalSelectionUiState()
        var viewportHeight by mutableStateOf(400.dp)
        compose.setContent {
            val snapshot by connection.screen.collectAsState()
            MaterialTheme {
                Column {
                    TerminalClearButton(connection, selection::clear)
                    Box(Modifier.height(viewportHeight)) { TerminalSurface(snapshot, connection, true, settings, selection) }
                }
            }
        }
        compose.waitUntil(5_000) { connection.resizeCount > 0 }
        assertEquals(original, bufferText(connection.screen.value))
        for (height in listOf(180.dp, 500.dp, 220.dp, 400.dp)) {
            val count = connection.resizeCount
            compose.runOnIdle { viewportHeight = height }
            compose.waitUntil(5_000) { connection.resizeCount > count }
            assertEquals(original, bufferText(connection.screen.value))
        }
        compose.runOnIdle {
            connection.accept("\r\nNEW WHILE TYPING")
            selection.snapshot = connection.screen.value
            selection.selection = TerminalSelection(TerminalPosition(0, 0), TerminalPosition(0, 4))
        }
        compose.onNodeWithContentDescription("terminal-selection-actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("terminal-clear").performClick()
        compose.waitForIdle()
        assertEquals("", bufferText(connection.screen.value))
        assertEquals(0, connection.screen.value.cursorRow)
        assertEquals(0, connection.screen.value.cursorColumn)
        assertTrue(connection.screen.value.resetGeneration > 0)
        assertNull(selection.selection)
        assertTrue(connection.writes.isEmpty())
        compose.runOnIdle { connection.accept("fresh output") }
        assertEquals("fresh output", bufferText(connection.screen.value))
    }

    @Test fun realImeOpenCloseKeepsHistoryAndOutputReceivedWhileTyping() {
        val connection = BufferSession()
        connection.accept((0..65).joinToString("\r\n") { "ROW-$it 中文 terminal" })
        val original = bufferText(connection.screen.value)
        val settings = AppSettings(context)
        var hostView: android.view.View? = null
        compose.setContent {
            val snapshot by connection.screen.collectAsState()
            val view = LocalView.current
            SideEffect { hostView = view }
            MaterialTheme { TerminalSurface(snapshot, connection, true, settings) }
        }
        compose.waitUntil(5_000) { connection.lastHeight > 0 }
        val heightWithoutIme = connection.lastHeight
        val output = compose.onNodeWithContentDescription("terminal-output")
        repeat(2) { iteration ->
            output.performTouchInput { click(center) }
            compose.waitUntil(8_000) { connection.lastHeight < heightWithoutIme - 100 }
            compose.runOnIdle { connection.accept("\r\nWHILE-IME-$iteration") }
            assertEquals(original + (0..iteration).joinToString("") { "\nWHILE-IME-$it" }, bufferText(connection.screen.value))
            instrumentation.runOnMainSync {
                hostView?.findFocus()?.clearFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(hostView!!.windowToken, 0)
            }
            compose.waitUntil(8_000) { connection.lastHeight >= heightWithoutIme }
            assertEquals(original + (0..iteration).joinToString("") { "\nWHILE-IME-$it" }, bufferText(connection.screen.value))
        }
    }

    private fun bufferText(snapshot: TerminalSnapshot) = TerminalSelectionText.extract(snapshot,
        TerminalSelection(TerminalPosition(0, 0), TerminalPosition(snapshot.rows - 1, snapshot.columns - 1)),
    ).trimEnd()

    private fun saveScreenshot(name: String) {
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private class RecordingSession(snapshot: TerminalSnapshot) : TerminalSession {
        override val screen = MutableStateFlow(snapshot)
        override val state = MutableStateFlow(SshTerminalState(SshTerminalStatus.CONNECTED))
        val writes = mutableListOf<String>()
        override fun connect() = Unit
        override fun clear() {
            screen.value = screen.value.let { it.copy(
                cells = List(it.columns * it.rows) { TerminalCell() }, cursorColumn = 0, cursorRow = 0,
                resetGeneration = it.resetGeneration + 1,
            ) }
        }
        override fun disconnect(markRequested: Boolean) = Unit
        override fun close() = Unit
        override fun resize(newCols: Int, newRows: Int, widthPx: Int, heightPx: Int) = Unit
        override fun write(text: String): Boolean { writes += text; return true }
        override fun write(bytes: ByteArray) = write(bytes.toString(Charsets.UTF_8))
    }

    private class BufferSession : TerminalSession {
        private val terminal = TerminalEmulator(48, 30)
        override val screen = MutableStateFlow(terminal.snapshot())
        override val state = MutableStateFlow(SshTerminalState(SshTerminalStatus.CONNECTED))
        @Volatile var resizeCount = 0
        @Volatile var lastHeight = 0
        val writes = mutableListOf<String>()
        fun accept(text: String) { terminal.accept(text.toByteArray()); screen.value = terminal.snapshot() }
        override fun clear() { terminal.clear(); screen.value = terminal.snapshot() }
        override fun resize(newCols: Int, newRows: Int, widthPx: Int, heightPx: Int) {
            terminal.resize(newCols, newRows)
            screen.value = terminal.snapshot()
            lastHeight = heightPx
            resizeCount++
        }
        override fun connect() = Unit
        override fun disconnect(markRequested: Boolean) = Unit
        override fun close() = Unit
        override fun write(text: String): Boolean { writes += text; return true }
        override fun write(bytes: ByteArray) = write(bytes.toString(Charsets.UTF_8))
    }
}
