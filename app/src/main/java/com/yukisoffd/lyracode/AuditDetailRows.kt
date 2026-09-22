package com.yukisoffd.lyracode

import org.json.JSONArray
import org.json.JSONObject

internal const val AUDIT_TEXT_PAGE_CHARS = 8000

internal sealed interface AuditDetailRow {
    val key: String
    data class Field(val node: AuditTreeNode) : AuditDetailRow { override val key = node.key }
    data class TextBlock(override val key: String, val text: String, val depth: Int) : AuditDetailRow
    data class Pager(override val key: String, val owner: String, val page: Int, val pages: Int, val depth: Int) : AuditDetailRow
}

/** Bound both characters and lines; a single lazy item must never contain a screenful of pages. */
internal fun auditTextChunks(text: String): List<String> = buildList {
    var start = 0
    var lines = 0
    var lastLineBreak = -1
    var cursor = 0
    while (cursor < text.length) {
        if (text[cursor] == '\n') { lines++; lastLineBreak = cursor + 1 }
        cursor++
        if (cursor < text.length && text[cursor - 1].isHighSurrogate() && text[cursor].isLowSurrogate()) cursor++
        if (cursor - start >= 512 || lines >= 16) {
            val end = if (lastLineBreak > start) lastLineBreak else cursor
            add(text.substring(start, end))
            start = end
            lines = 0
        }
    }
    if (start < text.length) add(text.substring(start))
}

internal fun auditTextPage(text: String, page: Int): String {
    fun boundary(index: Int): Int = index.coerceAtMost(text.length).let {
        if (it in 1 until text.length && text[it - 1].isHighSurrogate() && text[it].isLowSurrogate()) it - 1 else it
    }
    return text.substring(boundary(page * AUDIT_TEXT_PAGE_CHARS), boundary((page + 1) * AUDIT_TEXT_PAGE_CHARS))
}

internal fun auditDetailRows(nodes: List<AuditTreeNode>, expanded: Map<String, Boolean>, pages: Map<String, Int>): List<AuditDetailRow> = buildList {
    nodes.forEach { node ->
        add(AuditDetailRow.Field(node))
        if (expanded[node.key] == true && node.value is String) {
            val count = ((node.value.length + AUDIT_TEXT_PAGE_CHARS - 1) / AUDIT_TEXT_PAGE_CHARS).coerceAtLeast(1)
            val page = (pages[node.key] ?: 0).coerceIn(0, count - 1)
            auditTextChunks(auditTextPage(node.value, page)).forEachIndexed { index, chunk ->
                add(AuditDetailRow.TextBlock("${node.key}/text/$page/$index", chunk, node.depth))
            }
            if (count > 1) add(AuditDetailRow.Pager("${node.key}/pager", node.key, page, count, node.depth))
        }
    }
}

internal fun auditHeaderNodes(value: Any, path: String): List<AuditTreeNode> {
    if (value !is JSONArray) return auditTreeNodes(value, path, 1, emptyMap())
    return (0 until value.length()).map { index ->
        val header = value.optJSONObject(index)
        val text = header?.optString("value").orEmpty()
        AuditTreeNode("$path$index", header?.optString("name").orEmpty(), text, 1, text.length > 160, "(${text.length})")
    }
}

internal fun auditRequestHighlights(body: Any?): List<Pair<String, String>> {
    if (body !is JSONObject) return emptyList()
    return buildList {
        for (field in listOf("model", "stream", "reasoning_effort", "max_tokens", "max_completion_tokens", "max_output_tokens")) {
            if (body.has(field)) add(field to body.opt(field).toString())
        }
        for ((parent, field) in listOf("reasoning" to "effort", "output_config" to "effort", "thinking" to "type")) {
            body.optJSONObject(parent)?.takeIf { it.has(field) }?.let { add("$parent.$field" to it.opt(field).toString()) }
        }
        for (field in listOf("messages", "input", "tools")) body.optJSONArray(field)?.let { add(field to "[${it.length()}]") }
    }
}
