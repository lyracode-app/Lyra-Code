package com.yukisoffd.lyracode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuditTreeTest {
    @Test fun longExpandedTextIsSplitIntoSmallLazyItemsWithReachablePageControls() {
        val text = "a\n".repeat(9000) + "END😀"
        val node = AuditTreeNode("body/text", "text", text, 1, true, "")
        val rows = auditDetailRows(listOf(node), mapOf(node.key to true), emptyMap())
        assertTrue(rows.last() is AuditDetailRow.Pager)
        val blocks = rows.filterIsInstance<AuditDetailRow.TextBlock>()
        assertTrue(blocks.size > 100)
        assertTrue(blocks.all { it.text.length <= 513 && it.text.count { c -> c == '\n' } <= 16 })
        assertEquals(text.take(8000), blocks.joinToString("") { it.text })
        val reconstructed = (0..2).joinToString("") { auditTextPage(text, it) }
        assertEquals(text, reconstructed)
        val unicode = "x".repeat(7999) + "😀tail"
        assertEquals(unicode, auditTextPage(unicode, 0) + auditTextPage(unicode, 1))
    }

    @Test fun summaryUsesActualFieldsAndHeadersKeepDuplicateNames() {
        val body = JSONObject("""{"model":"deepseek-flash","reasoning":{"effort":"max"},"tools":[{}]}""")
        assertTrue(auditRequestHighlights(body).contains("reasoning.effort" to "max"))
        assertTrue(auditRequestHighlights(JSONObject()).none { "effort" in it.first })
        val nodes = auditHeaderNodes(JSONArray("""[{"name":"X-Test","value":"a"},{"name":"X-Test","value":"b"}]"""), "headers/")
        assertEquals(listOf("X-Test", "X-Test"), nodes.map { it.label })
        assertEquals(listOf("a", "b"), nodes.map { it.value })
    }

    @Test fun jsonKeysStringsNumbersAndBooleansHaveDistinctStyles() {
        val palette = AuditSyntaxPalette(androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.Green,
            androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Yellow, androidx.compose.ui.graphics.Color.Gray)
        val text = """{"model":"deepseek-flash","stream":true,"count":4,"extra":null}"""
        val styled = highlightAuditJson(text, palette)
        assertEquals(text, styled.text)
        fun color(token: String) = styled.spanStyles.first { it.start == text.indexOf(token) }.item.color
        assertEquals(palette.key, color("\"model\""))
        assertEquals(palette.string, color("\"deepseek-flash\""))
        assertEquals(palette.boolean, color("true"))
        assertEquals(palette.number, color("4"))
    }
    @Test fun largeMessagesToolsAndMediaAreCollapsedUntilExplicitlyOpened() {
        val image = "data:image/png;base64," + "A".repeat(600_000)
        val root = JSONObject().put("messages", JSONArray().put(JSONObject().put("content", image)))
            .put("tools", JSONArray().put(JSONObject().put("name", "tool"))).put("reasoning_effort", "high")
        val collapsed = auditTreeNodes(root, "body/", 1, emptyMap())
        assertEquals(3, collapsed.size)
        assertTrue(collapsed.first { it.label == "messages" }.expandable)
        val expanded = auditTreeNodes(root, "body/", 1, mapOf("body/messages" to true, "body/messages/0" to true))
        assertEquals(image, expanded.first { it.label == "content" }.value)
        assertTrue(expanded.first { it.label == "content" }.expandable)
    }

    @Test fun sseThoughtAndToolDeltasRemainInspectable() {
        val text = "event: delta\ndata: {\"reasoning_content\":\"thinking\",\"tool_calls\":[{\"name\":\"run\"}]}\n\ndata: [DONE]\n\n"
        val parsed = parseAuditContent(text) as JSONArray
        assertEquals("thinking", parsed.getJSONObject(0).getJSONObject("data").getString("reasoning_content"))
        assertEquals("[DONE]", parsed.getJSONObject(1).getString("data"))
        assertEquals("not json", parseAuditContent("not json"))
    }
}
