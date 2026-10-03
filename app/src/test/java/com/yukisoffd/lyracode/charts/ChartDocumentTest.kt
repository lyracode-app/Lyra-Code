package com.yukisoffd.lyracode.charts

import com.yukisoffd.lyracode.ai.chartToolDefinition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChartDocumentTest {
    @Test fun toolResultRoundTripsThroughTheMarkdownDocument() {
        val args = JSONObject().put("engine", "mermaid").put("title", "中文流程图")
            .put("source", "flowchart LR\n A[开始] --> B[结束]")
        val result = JSONObject(chartToolResult(args))
        assertEquals("prepared", result.getString("status"))
        val markdown = result.getString("markdown")
        val document = ChartDocument.fromFence("chart", markdown.substringAfter('\n').substringBeforeLast('\n'))
        assertEquals(args.getString("title"), document.title)
        assertEquals(args.getString("source"), document.source)
    }

    @Test fun maliciousLabelsCannotEscapeTheHtmlPayload() {
        val source = JSONObject().put("series", org.json.JSONArray().put(JSONObject().put("type", "pie")
            .put("data", org.json.JSONArray().put(JSONObject().put("name", "</script><script>window.pwned=true</script>\u2028&")
                .put("value", 3)))))
        val doc = ChartDocument.create("echarts", source.toString())
        val json = chartPayloadJson(doc, false)
        assertFalse(json.contains("</script>"))
        assertFalse(json.contains('<'))
        assertFalse(json.contains('\u2028'))
        assertEquals(doc.source, JSONObject(json).getString("source"))
        assertEquals(3, Regex("</script>").findAll(chartHtml(doc, false)).count())
    }

    @Test fun labelsContainingBackticksCannotCloseTheChartFence() {
        val document = ChartDocument.create("mermaid", "flowchart LR\n A[\"````\"] --> B[OK]")
        val markdown = document.markdown()
        assertTrue(markdown.startsWith("`````chart\n"))
        assertEquals(document, ChartDocument.fromFence("chart", markdown.substringAfter('\n').substringBeforeLast('\n')))
    }

    @Test fun invalidSourcesAndConfigurationOverridesAreRejected() {
        listOf(
            { ChartDocument.create("html", "<script>bad()</script>") },
            { ChartDocument.create("mermaid", "flowchart LR\n" + "x".repeat(MAX_CHART_SOURCE_LENGTH)) },
            { ChartDocument.create("mermaid", "%%{init: {securityLevel: 'loose'}}%%\nflowchart LR\nA-->B") },
            { ChartDocument.create("mermaid", "---\nconfig:\n securityLevel: loose\n---\nflowchart LR") },
            { ChartDocument.create("echarts", "{\"series\":[{\"type\":\"custom\"}]}") },
            { ChartDocument.create("echarts", "{\"series\":[{\"type\":\"bar\"}],\"__proto__\":{}}") },
            { ChartDocument.create("echarts", "{\"series\":[{\"type\":\"line\",\"data\":()=>evil()}]}") },
        ).forEach { invalid -> assertTrue("Invalid chart accepted", runCatching { invalid() }.isFailure) }
    }

    @Test fun schemaRequiresDataAndOffersBothRenderingEngines() {
        val function = chartToolDefinition().getJSONObject("function")
        assertEquals("create_chart", function.getString("name"))
        val parameters = function.getJSONObject("parameters")
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals(listOf("engine", "source"), parameters.getJSONArray("required").let { (0 until it.length()).map(it::getString) })
        assertEquals(2, parameters.getJSONObject("properties").getJSONObject("engine").getJSONArray("enum").length())
    }

    @Test fun supportedDiagramHeadersAndSeriesTypesAcceptChineseData() {
        listOf("flowchart LR", "sequenceDiagram", "gantt", "mindmap", "architecture-beta", "erDiagram", "classDiagram")
            .forEach { assertEquals("mermaid", ChartDocument.create("Mermaid", it).engine) }
        listOf("line", "bar", "pie", "graph", "tree").forEach { type ->
            assertEquals("echarts", ChartDocument.create("echarts", "{\"series\":[{\"type\":\"$type\",\"data\":[1,2]}]}").engine)
        }
    }
}
