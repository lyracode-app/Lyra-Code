package com.yukisoffd.lyracode.charts

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val MAX_CHART_SOURCE_LENGTH = 100_000
internal const val CHART_ASSET_ORIGIN = "https://lyra-charts.invalid/"

/** A data-only document, stored in the assistant's Markdown so history stays portable. */
internal data class ChartDocument(val engine: String, val source: String, val title: String = "") {
    fun toJson(): JSONObject = JSONObject()
        .put("engine", engine).put("source", source).put("title", title)

    fun markdown(): String {
        val json = toJson().toString()
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(json).maxOfOrNull { it.value.length } ?: 0) + 1))
        return "$fence" + "chart\n$json\n$fence"
    }

    companion object {
        fun create(engine: String, source: String, title: String = ""): ChartDocument {
            val normalizedEngine = engine.trim().lowercase(Locale.ROOT)
            require(normalizedEngine in setOf("mermaid", "echarts")) { "engine must be mermaid or echarts." }
            require(source.length <= MAX_CHART_SOURCE_LENGTH) { "Chart source exceeds $MAX_CHART_SOURCE_LENGTH characters." }
            val text = source.trim().removePrefix("\uFEFF")
            require(text.isNotBlank()) { "Chart source must not be empty." }
            require(title.length <= 200) { "Chart title exceeds 200 characters." }
            if (normalizedEngine == "mermaid") {
                require(!Regex("%%\\s*\\{").containsMatchIn(text) && !text.startsWith("---")) {
                    "Use plain Mermaid syntax without configuration directives or YAML frontmatter; pass title separately."
                }
                val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("%%") }.orEmpty().trim()
                require(MERMAID_HEADER.containsMatchIn(firstLine)) { "Unsupported or missing Mermaid diagram header." }
                return ChartDocument(normalizedEngine, text, title.trim())
            }
            val option = JSONObject(text)
            validateChartJson(option)
            val series = option.opt("series")
            val entries = when (series) {
                is JSONObject -> listOf(series)
                is JSONArray -> (0 until series.length()).map { series.getJSONObject(it) }
                else -> error("ECharts option must contain a series object or array.")
            }
            require(entries.isNotEmpty() && entries.size <= 100) { "Provide 1 to 100 ECharts series." }
            entries.forEach {
                require(it.optString("type") in ECHARTS_SERIES_TYPES) { "Unsupported or missing ECharts series type." }
            }
            return ChartDocument(normalizedEngine, option.toString(), title.trim())
        }

        fun fromFence(language: String, code: String): ChartDocument = when (language.lowercase(Locale.ROOT)) {
            "mermaid", "echarts" -> create(language, code)
            "chart" -> JSONObject(code).let { create(it.getString("engine"), it.getString("source"), it.optString("title")) }
            else -> error("Unsupported chart fence: $language")
        }
    }
}

internal fun isChartLanguage(language: String): Boolean = language.lowercase(Locale.ROOT) in setOf("chart", "mermaid", "echarts")

private val MERMAID_HEADER = Regex(
    "^(flowchart|graph|sequenceDiagram|gantt|pie|mindmap|architecture-beta|erDiagram|classDiagram|stateDiagram(?:-v2)?|journey|gitGraph|timeline|quadrantChart|xychart-beta|sankey-beta|block-beta|requirementDiagram|packet-beta)(?:\\s|$)",
)
private val ECHARTS_SERIES_TYPES = setOf(
    "line", "bar", "pie", "scatter", "effectScatter", "radar", "tree", "treemap", "sunburst", "graph",
    "sankey", "funnel", "gauge", "heatmap", "boxplot", "parallel", "themeRiver", "candlestick", "pictorialBar",
)

private fun validateChartJson(value: Any?, depth: Int = 0) {
    require(depth <= 32) { "Chart data is nested too deeply." }
    when (value) {
        is JSONObject -> value.keys().forEach { key ->
            require(key !in setOf("__proto__", "prototype", "constructor")) { "Unsupported chart property: $key" }
            validateChartJson(value.opt(key), depth + 1)
        }
        is JSONArray -> for (index in 0 until value.length()) validateChartJson(value.opt(index), depth + 1)
    }
}

/** Never put model-authored source into HTML or executable JavaScript. */
internal fun chartPayloadJson(document: ChartDocument, dark: Boolean, fullscreen: Boolean = false): String = document.toJson()
    .put("dark", dark).put("fullscreen", fullscreen).toString()
    .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

internal fun chartHtml(document: ChartDocument, dark: Boolean, fullscreen: Boolean = false): String = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src data: blob:; font-src 'none'; connect-src 'none';">
    <style>html,body{margin:0;background:${if (dark) "#1c1b1f" else "#ffffff"};color:${if (dark) "#ece6f0" else "#1c1b1f"};font-family:system-ui,sans-serif}#chart-viewport{position:relative;width:100%}#chart{box-sizing:border-box;padding:12px}#chart>svg{display:block;margin:auto}#chart a{pointer-events:none}</style>
    </head><body><div id="chart-viewport"><div id="chart"></div></div>
    <script id="chart-data" type="application/json">${chartPayloadJson(document, dark, fullscreen)}</script>
    <script src="${document.engine}.min.js"></script><script src="renderer.js"></script>
    </body></html>
""".trimIndent()

internal fun chartToolResult(args: JSONObject): String {
    val chart = ChartDocument.create(args.getString("engine"), args.getString("source"), args.optString("title"))
    return JSONObject().put("status", "prepared").put("markdown", chart.markdown())
        .put("note", "Embed the markdown value verbatim in your answer where the chart belongs. Lyra renders it inline with save and fullscreen controls. If rendering reports an error, correct the source. Do not return an image link or wrap the chart fence in another code fence.")
        .toString()
}
