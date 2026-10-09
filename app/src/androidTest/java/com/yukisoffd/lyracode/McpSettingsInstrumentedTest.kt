package com.yukisoffd.lyracode

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.mcp.McpJsonConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@SdkSuppress(minSdkVersion = 30)
class McpSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var settings: AppSettings
    private val serverId = "mcp-upgrade-ui-test"

    @Before fun prepare() {
        settings = AppSettings(context)
        AppStrings.initialize(context.localizedContext(AppSettings.LANGUAGE_EN))
    }
    @After fun clean() { settings.deleteMcpServer(serverId) }

    private fun server(tools: List<McpToolDefinition> = emptyList()) = McpServerConfig(
        serverId, "Test MCP", "https://example.com/mcp", "stale-key", "streamable_http", 30, false,
        """{"url":"https://example.com/mcp","headers":{"Authorization":"raw-key"},"custom":{"preserved":true}}""", tools,
    )
    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(250)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun rawJsonEditorAvoidsKeyboardAndSavesExactDocument() {
        var saved: McpServerConfig? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context.localizedContext(AppSettings.LANGUAGE_EN)) {
                MaterialTheme { McpServerDialog(server(), onDismiss = {}, onSave = { saved = it; settings.upsertMcpServer(it) }) }
            }
        }
        compose.onNodeWithText("Edit raw JSON").performScrollTo().performClick()
        val raw = """{
          "protocolVersion": "2026-07-28",
          "mcpServers": {
            "special": {
              "url": "https://new.example/mcp",
              "headers": {"Authorization": "NO-PREFIX", "X-Custom": "keep"},
              "vendor": {"keep": "unchanged"},
              "description": "${"a long custom field ".repeat(80)}"
            }
          }
        }"""
        val editor = compose.onNode(hasSetTextAction())
        val screenHeight = WindowInspector.getGlobalWindowViews().maxOf { it.height }
        editor.performTextReplacement(raw)
        editor.performClick()
        compose.waitUntil(10_000) { WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true } }
        editor.performTextInputSelection(TextRange(raw.length))
        // Window inset visibility is reported before the keyboard animation/layout finishes.
        try {
            compose.waitUntil(5_000) {
                val keyboardHeight = WindowInspector.getGlobalWindowViews().maxOf { it.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 }
                val coordinates = editor.fetchSemanticsNode().layoutInfo.coordinates
                coordinates.positionInRoot().y + coordinates.size.height <= screenHeight - keyboardHeight + 2
            }
        } finally { screenshot("mcp-json-keyboard.png") }
        compose.onNodeWithText("Save").assertIsDisplayed()
        val keyboardTop = screenHeight - WindowInspector.getGlobalWindowViews().maxOf { it.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 }
        val coordinates = editor.fetchSemanticsNode().layoutInfo.coordinates
        val fieldBottom = coordinates.positionInRoot().y + coordinates.size.height
        assertTrue("Editor must stay above the keyboard: $fieldBottom > $keyboardTop", fieldBottom <= keyboardTop + 2)
        screenshot("mcp-json-keyboard.png")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(raw, saved!!.rawJson)
            assertEquals("https://new.example/mcp", saved!!.url)
            assertEquals("NO-PREFIX", saved!!.authKey)
            assertEquals(raw, settings.mcpServers().first { it.id == serverId }.rawJson)
        }
    }

    @Test fun invalidRawJsonStaysInEditorAndRemovingAuthenticationClearsKey() {
        var saved: McpServerConfig? = null
        compose.setContent { MaterialTheme { McpServerDialog(server(), onDismiss = {}, onSave = { saved = it }) } }
        compose.onNodeWithText("Edit raw JSON").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("{invalid")
        compose.onNodeWithText("Save").performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { assertNull(saved) }
        val raw = """{"url":"https://example.com/mcp","headers":{},"custom":{"preserved":true}}"""
        compose.onNode(hasSetTextAction()).performTextReplacement(raw)
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(raw, saved!!.rawJson)
            assertEquals("", saved!!.authKey)
            assertTrue(McpJsonConfig(saved!!.rawJson).headers.isEmpty())
        }
    }

    @Test fun toolsOpenSubpagesWithFullNamesDescriptionsAndSchema() {
        val name = "a_complete_very_long_tool_name_".repeat(4)
        val description = "Detailed tool documentation with complete instructions.\n".repeat(30) + "FINAL DESCRIPTION LINE"
        val schema = """{"type":"object","properties":{"query":{"type":"string","description":"Detailed parameter explanation","default":"中文"},"options":{"type":"object","properties":{"limit":{"type":"integer"}}}},"required":["query"]}"""
        settings.upsertMcpServer(server(listOf(McpToolDefinition(name, description, schema))))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context.localizedContext(AppSettings.LANGUAGE_EN)) {
                var page by remember { mutableIntStateOf(0) }
                MaterialTheme {
                    SettingsDetailPage {
                        when (page) {
                            0 -> McpSettings(settings, McpClientManager(context, settings), onOpenTools = { page = 1 })
                            1 -> McpToolsPage(settings, serverId, 0, onOpenTool = { page = 2 })
                            else -> McpToolDetailPage(settings, serverId, name, 0)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(description).assertDoesNotExist()
        compose.onNodeWithText("View full tool names, descriptions, and parameter definitions").performScrollTo().performClick()
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.onNodeWithText(description).assertExists()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(description).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.single().lineCount > 30)
        assertFalse(results.single().hasVisualOverflow)
        compose.onNodeWithText("Parameters (JSON Schema)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(org.json.JSONObject(schema).toString(2)).performScrollTo().assertExists()
        screenshot("mcp-tool-parameters.png")
    }
}
