package com.yukisoffd.lyracode

import org.json.JSONArray
import org.json.JSONObject

internal data class AuditTreeNode(
    val key: String, val label: String, val value: Any?, val depth: Int,
    val expandable: Boolean, val summary: String,
)

internal fun parseAuditContent(text: String): Any = runCatching {
    when {
        text.trimStart().startsWith("{") -> JSONObject(text)
        text.trimStart().startsWith("[") -> JSONArray(text)
        // Keep exact SSE text available through the raw view; decode data events for inspection.
        text.lineSequence().any { it.startsWith("data:") } -> JSONArray().apply {
            text.split(Regex("\r?\n\r?\n")).filter { it.isNotBlank() }.forEach { event ->
                val data = event.lineSequence().filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
                put(JSONObject().put("event", event.lineSequence().firstOrNull { it.startsWith("event:") }.orEmpty())
                    .put("data", if (data.startsWith("{")) runCatching { JSONObject(data) }.getOrDefault(data) else data))
            }
        }
        else -> text
    }
}.getOrDefault(text)

internal fun auditTreeNodes(value: Any, path: String, depth: Int, expanded: Map<String, Boolean>): List<AuditTreeNode> = buildList {
    fun addValue(label: String, child: Any?, key: String) {
        val expandable = child is JSONObject || child is JSONArray || child is String && child.length > 160
        val summary = when (child) {
            is JSONObject -> "{${child.length()}}"
            is JSONArray -> "[${child.length()}]"
            is String -> "(${child.length})"
            else -> ""
        }
        add(AuditTreeNode(key, label, child, depth, expandable, summary))
        if (expanded[key] == true && (child is JSONObject || child is JSONArray) && depth < 64) {
            addAll(auditTreeNodes(child, "$key/", depth + 1, expanded))
        }
    }
    when (value) {
        is JSONObject -> value.keys().forEach { name ->
            val escaped = name.replace("~", "~0").replace("/", "~1")
            addValue(name, value.opt(name), "$path$escaped")
        }
        is JSONArray -> for (index in 0 until value.length()) addValue("[$index]", value.opt(index), "$path$index")
        else -> addValue("text", value, "${path}text")
    }
}
