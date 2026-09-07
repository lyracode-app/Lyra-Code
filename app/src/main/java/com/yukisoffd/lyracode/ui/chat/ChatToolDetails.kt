package com.yukisoffd.lyracode

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import org.json.JSONArray
import org.json.JSONObject


@Composable
internal fun ToolResultContent(
    content: String,
    toolName: String = "",
    toolInput: String = "{}",
    expanded: Boolean,
    onToggle: () -> Unit,
    compact: Boolean = false,
    inlineDetails: Boolean = false,
) {
    val summary = if (toolName.isNotBlank()) {
        uiText(R.string.ui_tool_call) + " $toolName · " + uiText(R.string.ui_view_details)
    } else {
        uiText(R.string.ui_tool_call_2) + " · " + uiText(R.string.ui_view_details)
    }
    ToolCallSummaryButton(text = summary, compact = compact, onClick = onToggle)
    if (expanded && inlineDetails) {
        // Application overlays have no Activity token for the full-screen detail Dialog.
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(content.ifBlank { uiText(R.string.label_empty_result) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    } else if (expanded) {
        ToolCallDetailPage(
            toolName = toolName,
            toolInput = toolInput.ifBlank { "{}" },
            toolOutput = content.ifBlank { uiText(R.string.label_empty_result) },
            onClose = onToggle,
        )
    }
}

@Composable
private fun ToolCallSummaryButton(text: String, compact: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KimiPillShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f))
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 5.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(if (compact) 15.dp else 19.dp),
        )
        Spacer(Modifier.width(if (compact) 6.dp else 9.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = uiText(R.string.ui_view_details),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(if (compact) 17.dp else 24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallDetailPage(
    toolName: String,
    toolInput: String,
    toolOutput: String,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(uiText(R.string.tool_detail_title))
                            Text(
                                toolName.ifBlank { uiText(R.string.tool_unknown_name) },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, contentDescription = uiText(R.string.cd_back), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
                contentPadding = PaddingValues(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item {
                    ToolJsonSection(
                        title = uiText(R.string.tool_input_title),
                        content = toolInput,
                        onCopy = { clipboard.setText(AnnotatedString(toolInput)) },
                    )
                }
                item {
                    ToolJsonSection(
                        title = uiText(R.string.tool_output_title),
                        content = toolOutput,
                        onCopy = { clipboard.setText(AnnotatedString(toolOutput)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolJsonSection(title: String, content: String, onCopy: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val codeFontFamily = LocalCodeFontFamily.current
    val highlightedJson = remember(content, colorScheme) {
        highlightedJsonForDisplay(
            value = content,
            keyColor = colorScheme.primary,
            stringColor = colorScheme.tertiary,
            numberColor = colorScheme.secondary,
            literalColor = colorScheme.error,
            punctuationColor = colorScheme.onSurfaceVariant,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge)
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("json", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = codeFontFamily)
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = uiText(R.string.file_action_copy), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                KimiDivider()
                SelectionContainer {
                    Text(
                        highlightedJson,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFontFamily),
                    )
                }
            }
        }
    }
}

internal fun prettyJsonForDisplay(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return "{}"
    return runCatching { JSONObject(trimmed).toString(2) }
        .recoverCatching { JSONArray(trimmed).toString(2) }
        .getOrDefault(value)
}

private val jsonSyntaxTokenRegex = Regex(
    """\"(?:\\.|[^\"\\])*\"|(?<![\w.])-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?(?![\w.])|\b(?:true|false|null)\b|[{}\[\],:]""",
)

internal fun highlightedJsonForDisplay(
    value: String,
    keyColor: Color,
    stringColor: Color,
    numberColor: Color,
    literalColor: Color,
    punctuationColor: Color,
): AnnotatedString {
    val formatted = prettyJsonForDisplay(value)
    return buildAnnotatedString {
        var cursor = 0
        jsonSyntaxTokenRegex.findAll(formatted).forEach { match ->
            if (cursor < match.range.first) append(formatted.substring(cursor, match.range.first))
            val token = match.value
            val color = when {
                token.startsWith('"') -> {
                    var next = match.range.last + 1
                    while (next < formatted.length && formatted[next].isWhitespace()) next++
                    if (formatted.getOrNull(next) == ':') keyColor else stringColor
                }
                token == "true" || token == "false" || token == "null" -> literalColor
                token.firstOrNull()?.let { it == '-' || it.isDigit() } == true -> numberColor
                else -> punctuationColor
            }
            withStyle(SpanStyle(color = color)) { append(token) }
            cursor = match.range.last + 1
        }
        if (cursor < formatted.length) append(formatted.substring(cursor))
    }
}

internal data class FileChangeView(
    val path: String,
    val added: Int,
    val removed: Int,
    val diff: String,
    val before: String,
    val after: String,
)

internal fun fileNameForDisplay(path: String): String {
    return path.trim().replace('\\', '/').substringAfterLast('/').ifBlank { path.ifBlank { uiText(R.string.label_unnamed_file) } }
}

internal fun stripUploadedFileBlocks(content: String): String {
    return content
        .replace(
            Regex("\\n*用户上传文件：[^\\n]+\\n大小：\\d+ bytes\\n\\n```text\\n[\\s\\S]*?\\n```\\n?"),
            "\n",
        )
        .replace(Regex("\\n*用户上传文件：[^\\n]+\\n大小：\\d+ bytes\\n?"), "\n")
        .trim()
}

internal fun uploadedFileTypeLabel(name: String): String {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "" -> uiText(R.string.file_manager_search_files)
        "txt", "md", "json", "xml", "csv", "log" -> ext.uppercase() + uiText(R.string.ui_text)
        "kt", "java", "py", "js", "ts", "html", "css", "go", "rs", "cpp", "c", "h" -> ext.uppercase() + uiText(R.string.ui_code)
        "zip", "7z", "rar", "tar", "gz" -> ext.uppercase() + uiText(R.string.ui_archive)
        "pdf" -> uiText(R.string.label_file_pdf)
        "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> ext.uppercase() + uiText(R.string.ui_document)
        else -> ext.uppercase() + uiText(R.string.ui_file)
    }
}

internal fun formatUploadedFileSize(bytes: Long?): String {
    val value = bytes ?: return ""
    if (value < 1024L) return "${value}B"
    val units = listOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unitIndex = -1
    do {
        size /= 1024.0
        unitIndex++
    } while (size >= 1024.0 && unitIndex < units.lastIndex)
    val text = if (size >= 10.0) "%.0f".format(size) else "%.1f".format(size)
    return "$text${units[unitIndex]}"
}

