package com.yukisoffd.lyracode

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max


@Composable
internal fun FileChangeDetail(change: FileChangeView) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(uiText(R.string.label_diff), style = MaterialTheme.typography.labelMedium)
        DiffView(change.diff.ifBlank { uiText(R.string.ui_no_line_level_diff) })
        CodeSnapshot(
            title = uiText(R.string.label_before_change),
            content = change.before,
            color = Color(0xFFD93025),
            modifier = Modifier.fillMaxWidth(),
        )
        CodeSnapshot(
            title = uiText(R.string.label_after_change),
            content = change.after,
            color = Color(0xFF188038),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun CodeSnapshot(title: String, content: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = color, style = MaterialTheme.typography.labelMedium)
        CodeLines(
            content.ifBlank { uiText(R.string.ui_empty) },
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        )
    }
}

@Composable
internal fun DiffView(diff: String) {
    CodeLines(diff, isDiff = true, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
}

@Composable
private fun CodeLines(content: String, modifier: Modifier, isDiff: Boolean = false) {
    var lines by remember(content) { mutableStateOf<List<CodeDisplayLine>?>(null) }
    LaunchedEffect(content) {
        lines = withContext(Dispatchers.Default) { codeDisplayLines(content) { ensureActive() } }
    }
    val visibleLines = lines
    if (visibleLines == null) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.padding(8.dp))
        return
    }
    SelectionContainer {
        LazyColumn(
            modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(visibleLines, key = { it.start }) { line ->
                val color = when {
                    isDiff && content.startsWith("+", line.sourceLineStart) -> Color(0xFF188038)
                    isDiff && content.startsWith("-", line.sourceLineStart) -> Color(0xFFD93025)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    content.substring(line.start, line.end),
                    color = color,
                    softWrap = false,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

internal fun parseFileChanges(content: String): List<FileChangeView> {
    parseJsonFileChanges(content).takeIf { it.isNotEmpty() }?.let { return it }
    val regex = Regex("LYRA_FILE_CHANGE_BEGIN\\n(.*?)\\nLYRA_FILE_CHANGE_END", RegexOption.DOT_MATCHES_ALL)
    return regex.findAll(content).mapNotNull { match ->
        val body = match.groupValues[1]
        val path = body.lineSequence().firstOrNull { it.startsWith("path: ") }?.removePrefix("path: ")?.trim().orEmpty()
        val added = body.lineSequence().firstOrNull { it.startsWith("added: ") }?.removePrefix("added: ")?.trim()?.toIntOrNull() ?: 0
        val removed = body.lineSequence().firstOrNull { it.startsWith("removed: ") }?.removePrefix("removed: ")?.trim()?.toIntOrNull() ?: 0
        val diffAndSnapshots = body.substringAfter("diff:\n", "")
        val diff = diffAndSnapshots.substringBefore("\nLYRA_FILE_BEFORE_BEGIN", diffAndSnapshots).trimEnd()
        val before = extractMarkedSection(body, "LYRA_FILE_BEFORE_BEGIN", "LYRA_FILE_BEFORE_END")
        val after = extractMarkedSection(body, "LYRA_FILE_AFTER_BEGIN", "LYRA_FILE_AFTER_END")
        if (path.isBlank()) null else FileChangeView(path, added, removed, diff, before, after)
    }.toList()
}

internal fun parseJsonFileChanges(content: String): List<FileChangeView> {
    val root = runCatching { JSONObject(content) }.getOrNull() ?: return emptyList()
    val changes = root.optJSONArray("file_changes") ?: JSONArray()
    return buildList {
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            val path = change.optString("path")
            if (path.isBlank()) continue
            add(
                FileChangeView(
                    path = path,
                    added = change.optInt("added", 0),
                    removed = change.optInt("removed", 0),
                    diff = change.optString("diff"),
                    before = change.optString("before"),
                    after = change.optString("after"),
                ),
            )
        }
    }
}

internal fun extractMarkedSection(text: String, start: String, end: String): String {
    val afterStart = text.substringAfter("$start\n", missingDelimiterValue = "")
    if (afterStart.isBlank()) return ""
    return afterStart.substringBefore("\n$end", afterStart).trimEnd()
}

internal sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Numbered(val number: String, val text: String) : MarkdownBlock()
    data class ListItems(val ordered: Boolean, val items: List<MarkdownListItem>) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data class Math(val formula: String, val display: Boolean) : MarkdownBlock()
    object Spacer : MarkdownBlock()
}







