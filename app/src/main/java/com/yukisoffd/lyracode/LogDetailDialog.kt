package com.yukisoffd.lyracode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AuditEntry
import com.yukisoffd.lyracode.data.AuditLogStore
import com.yukisoffd.lyracode.data.AuditSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun LogDetailDialog(entry: AuditEntry, store: AuditLogStore, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sections by remember(entry.id) { mutableStateOf(emptyList<AuditSection>()) }
    val loaded = remember(entry.id) { mutableStateMapOf<String, Any>() }
    val raw = remember(entry.id) { mutableStateMapOf<String, String>() }
    val expanded = remember(entry.id) { mutableStateMapOf<String, Boolean>() }
    val pages = remember(entry.id) { mutableStateMapOf<String, Int>() }
    var rawMode by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = remember(entry.id) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(entry.id, refresh) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                val all = store.sections(entry.id)
                val initial = all.filter { it.name in setOf("request.body", "request.headers", "request.metadata", "response.metadata", "duration_ms", "response.state") }
                    .associate { section -> store.readSection(entry.id, section.name).let { section.name to (it to parseAuditContent(it)) } }
                all to initial
            }
            sections = result.first.sortedBy { auditSectionOrder(it.name) }
            loaded.clear(); raw.clear(); expanded.clear(); pages.clear()
            result.second.forEach { (key, value) -> raw[key] = value.first; loaded[key] = value.second }
            listOf("request.body", "request.headers", "request.body/reasoning", "request.body/output_config", "request.body/thinking").forEach { expanded[it] = true }
            error = null
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
        finally { loading = false }
    }
    val nodes = remember(sections, loaded.toMap(), expanded.toMap(), rawMode) {
        buildList {
            sections.forEach { section ->
                val key = section.name
                add(AuditTreeNode(key, key, null, 0, true, "${section.length}"))
                if (expanded[key] == true) {
                    val value = if (rawMode) raw[key] else loaded[key]
                    if (value != null) {
                        if (!rawMode && key.endsWith(".headers")) addAll(auditHeaderNodes(value, "$key/"))
                        else addAll(auditTreeNodes(value, "$key/", 1, expanded))
                    }
                }
            }
        }
    }
    val rows = remember(nodes, expanded.toMap(), pages.toMap()) { auditDetailRows(nodes, expanded, pages) }
    fun changePage(row: AuditDetailRow.Pager, page: Int) {
        pages[row.owner] = page
        val index = rows.indexOfFirst { it is AuditDetailRow.Field && it.node.key == row.owner }
        if (index >= 0) scope.launch { listState.scrollToItem(index + 2) }
    }
    val palette = auditSyntaxPalette()
    fun toggle(node: AuditTreeNode) {
        val opening = expanded[node.key] != true
        expanded[node.key] = opening
        if (opening && node.depth == 0 && node.key !in loaded && pending[node.key] != true) {
            pending[node.key] = true
            scope.launch {
                try {
                    val content = withContext(Dispatchers.IO) { store.readSection(entry.id, node.key).let { it to parseAuditContent(it) } }
                    raw[node.key] = content.first
                    loaded[node.key] = content.second
                } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
                finally { pending.remove(node.key) }
            }
        }
    }
    AuditDetailWindow(onDismiss) {
        Column(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.safeGestures))
            .padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.title_log_detail), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, context.getString(R.string.cd_refresh_log)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, context.getString(R.string.action_delete)) }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, context.getString(R.string.cd_close)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Only the toolbar is fixed. Text blocks and page controls are independent lazy items,
            // so even a newline-heavy response can scroll fully above the navigation area.
            LazyColumn(Modifier.weight(1f).fillMaxWidth().clipToBounds(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
                item(key = "overview") { AuditOverview(entry, loaded, raw) }
                item(key = "raw-toggle") {
                    FilterChip(rawMode, { rawMode = !rawMode }, { Text(context.getString(R.string.log_raw_text)) })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is AuditDetailRow.Field -> {
                            val node = row.node
                            if (node.depth == 0) {
                                Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
                                    HorizontalDivider()
                                    Row(Modifier.fillMaxWidth().clickable { toggle(node) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (expanded[node.key] == true) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                            Text(context.getString(auditSectionLabel(node.key)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(node.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(context.getString(R.string.log_char_count, node.summary.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (pending[node.key] == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            } else {
                                AuditField(node, expanded[node.key] == true, palette) { toggle(node) }
                            }
                        }
                        is AuditDetailRow.TextBlock -> SelectionContainer {
                            Text(if (rawMode) highlightAuditJson(row.text, palette) else AnnotatedString(row.text, SpanStyle(color = palette.string)),
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(start = (row.depth.coerceAtMost(4) * 10 + 12).dp, end = 12.dp),
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        is AuditDetailRow.Pager -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { changePage(row, row.page - 1) }, enabled = row.page > 0) { Text(context.getString(R.string.log_previous)) }
                            Text("${row.page + 1} / ${row.pages}", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { changePage(row, row.page + 1) }, enabled = row.page + 1 < row.pages) { Text(context.getString(R.string.log_next)) }
                        }
                    }
                }
            }
        }
    }
}

private fun auditSectionOrder(name: String): Int = listOf("request.headers", "request.body", "response.headers", "response.body", "response.state", "request.metadata", "response.metadata", "duration_ms", "detail").indexOf(name).let { if (it < 0) 10 else it }
private fun auditSectionLabel(name: String): Int = when (name) {
    "request.headers" -> R.string.log_request_headers
    "request.body" -> R.string.log_request_body
    "response.headers" -> R.string.log_response_headers
    "response.body" -> R.string.log_response_body
    "request.metadata" -> R.string.log_request_metadata
    "response.metadata" -> R.string.log_response_metadata
    "response.state" -> R.string.log_response_state
    "duration_ms" -> R.string.log_duration
    else -> R.string.title_log_detail
}

@Composable
private fun AuditOverview(entry: AuditEntry, loaded: Map<String, Any>, raw: Map<String, String>) {
    val context = LocalContext.current
    val request = loaded["request.metadata"] as? JSONObject
    val response = loaded["response.metadata"] as? JSONObject
    val highlights = remember(loaded["request.body"]) { auditRequestHighlights(loaded["request.body"]) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            Text(formatTime(entry.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (request != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(request.optString("method"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    response?.let { Text("HTTP ${it.optInt("code")}", color = if (it.optInt("code") >= 400) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    raw["duration_ms"]?.let { Text("$it ms", style = MaterialTheme.typography.bodyMedium) }
                }
                SelectionContainer { Text(request.optString("url"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            highlights.forEach { (key, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(key, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text(value, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (loaded["request.body"] != null && highlights.none { "effort" in it.first }) {
                Text(context.getString(R.string.log_effort_absent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AuditField(node: AuditTreeNode, open: Boolean, palette: AuditSyntaxPalette, onToggle: () -> Unit) {
    val header = node.key.startsWith("request.headers/") || node.key.startsWith("response.headers/")
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    val indent = (node.depth.coerceAtMost(5) * 10).dp
    if (header && !node.expandable) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 10.dp)) {
            Text(node.label, color = palette.key, style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(node.value.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    } else {
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = palette.key)) { append(JSONObject.quote(node.label)) }
            append(": ")
            if (node.expandable) {
                withStyle(SpanStyle(color = palette.punctuation)) { append(node.summary) }
            } else {
                val literal = if (node.value is String) JSONObject.quote(node.value) else node.value.toString()
                append(highlightAuditJson(literal, palette))
            }
        }
        Row(Modifier.fillMaxWidth().background(background).then(if (node.expandable) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = indent, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.Top) {
            if (node.expandable) Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Spacer(Modifier.width(20.dp))
            SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal data class AuditSyntaxPalette(val key: Color, val string: Color, val number: Color, val boolean: Color, val punctuation: Color)

@Composable
private fun auditSyntaxPalette(): AuditSyntaxPalette {
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue < 1.5f }
    return AuditSyntaxPalette(MaterialTheme.colorScheme.primary,
        if (dark) Color(0xffa5d6a7) else Color(0xff35653c),
        if (dark) Color(0xff90caf9) else Color(0xff175ca3),
        if (dark) Color(0xffffcc80) else Color(0xff9b4b00), MaterialTheme.colorScheme.onSurfaceVariant)
}

private val auditJsonTokens = Regex("\"(?:\\\\.|[^\"\\\\])*\"|\\b(?:true|false|null)\\b|-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

internal fun highlightAuditJson(text: String, palette: AuditSyntaxPalette): AnnotatedString = buildAnnotatedString {
    append(text)
    auditJsonTokens.findAll(text).forEach { token ->
        val quoted = token.value.startsWith('"')
        val key = quoted && text.substring(token.range.last + 1).trimStart().startsWith(':')
        val color = when { key -> palette.key; quoted -> palette.string; token.value in setOf("true", "false", "null") -> palette.boolean; else -> palette.number }
        addStyle(SpanStyle(color = color), token.range.first, token.range.last + 1)
    }
}
