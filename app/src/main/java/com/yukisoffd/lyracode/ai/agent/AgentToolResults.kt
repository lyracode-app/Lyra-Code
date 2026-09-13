package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

internal fun ToolExecution.toToolOutputJson(toolName: String, ok: Boolean): String {
    return JSONObject()
        .put("schema", "lyra_tool_output_v2")
        .put("ok", ok)
        .put("tool", toolName)
        .put("content", content)
        .put("error", if (ok) "" else content)
        .put("file_changes", JSONArray().apply { fileChanges.forEach { put(it.toJson()) } })
        .toString()
}

internal data class FileDiff(
    val path: String,
    val added: Int,
    val removed: Int,
    val diff: String,
    val before: String,
    val after: String,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("path", path)
            .put("added", added)
            .put("removed", removed)
            .put("diff", diff)
            .put("before", before)
            .put("after", after)
    }

    fun toToolText(): String {
        return """
        LYRA_FILE_CHANGE_BEGIN
        path: $path
        added: $added
        removed: $removed
        diff:
        $diff
        LYRA_FILE_BEFORE_BEGIN
        $before
        LYRA_FILE_BEFORE_END
        LYRA_FILE_AFTER_BEGIN
        $after
        LYRA_FILE_AFTER_END
        LYRA_FILE_CHANGE_END
        """.trimIndent()
    }

    companion object {
        fun from(path: String, before: String, after: String): FileDiff {
            val beforeLines = before.toDiffLines()
            val afterLines = after.toDiffLines()
            val lcs = Array(beforeLines.size + 1) { IntArray(afterLines.size + 1) }
            for (i in beforeLines.indices.reversed()) {
                for (j in afterLines.indices.reversed()) {
                    lcs[i][j] = if (beforeLines[i] == afterLines[j]) {
                        lcs[i + 1][j + 1] + 1
                    } else {
                        maxOf(lcs[i + 1][j], lcs[i][j + 1])
                    }
                }
            }
            val diffLines = mutableListOf<String>()
            var added = 0
            var removed = 0
            var i = 0
            var j = 0
            while (i < beforeLines.size && j < afterLines.size) {
                when {
                    beforeLines[i] == afterLines[j] -> {
                        diffLines += "  ${beforeLines[i]}"
                        i++
                        j++
                    }
                    lcs[i + 1][j] >= lcs[i][j + 1] -> {
                        diffLines += "- ${beforeLines[i]}"
                        removed++
                        i++
                    }
                    else -> {
                        diffLines += "+ ${afterLines[j]}"
                        added++
                        j++
                    }
                }
            }
            while (i < beforeLines.size) {
                diffLines += "- ${beforeLines[i++]}"
                removed++
            }
            while (j < afterLines.size) {
                diffLines += "+ ${afterLines[j++]}"
                added++
            }
            return FileDiff(
                path = path,
                added = added,
                removed = removed,
                diff = diffLines.take(2_000).joinToString("\n"),
                before = before.take(20_000),
                after = after.take(20_000),
            )
        }

        private fun String.toDiffLines(): List<String> {
            if (isEmpty()) return emptyList()
            return replace("\r\n", "\n").lines()
        }
    }
}

internal fun JSONObject.cleanString(name: String): String {
    return stringFieldOrNull(name).orEmpty()
}

internal fun JSONObject.stringFieldOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name) ?: return null
    val text = value as? String ?: return null
    return text.takeUnless { it.equals("null", ignoreCase = true) }
}

internal fun JSONObject.booleanOrNull(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return optBoolean(name)
}

