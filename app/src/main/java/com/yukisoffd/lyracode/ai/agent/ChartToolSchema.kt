package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.charts.MAX_CHART_SOURCE_LENGTH
import org.json.JSONArray
import org.json.JSONObject

internal fun chartToolDefinition(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
    .put("name", "create_chart")
    .put("description", "Create a chart for inline display in your answer with fullscreen and PNG/SVG/source saving. Use mermaid for flowcharts, sequence diagrams, Gantt charts, mind maps, architecture (flowchart subgraphs or architecture-beta), ER/class/state diagrams; use echarts for line, bar, pie, scatter, graph, tree and other data charts. No image model or network is required. Embed the returned markdown verbatim in your final answer where the chart belongs. This prepares chart data; the client renders it.")
    .put("parameters", JSONObject().put("type", "object").put("additionalProperties", false)
        .put("required", JSONArray(listOf("engine", "source")))
        .put("properties", JSONObject()
            .put("engine", JSONObject().put("type", "string").put("enum", JSONArray(listOf("mermaid", "echarts"))))
            .put("title", JSONObject().put("type", "string").put("maxLength", 200).put("description", "Optional short chart title."))
            .put("source", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_CHART_SOURCE_LENGTH)
                .put("description", "For mermaid, plain diagram syntax with a diagram header, without Markdown fences, YAML frontmatter or init directives. For echarts, a strict JSON option object with series type(s) and data; no JavaScript, functions or external resources. Example: {\"xAxis\":{\"type\":\"category\",\"data\":[\"A\",\"B\"]},\"yAxis\":{\"type\":\"value\"},\"series\":[{\"type\":\"bar\",\"data\":[12,18]}]}")))))
