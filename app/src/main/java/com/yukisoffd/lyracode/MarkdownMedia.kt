package com.yukisoffd.lyracode

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.AiResponseCache
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.TodoItem
import com.yukisoffd.lyracode.ai.WebViewWebAgent
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AuditEntry
import com.yukisoffd.lyracode.data.AuditLogStore
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.BackupOptions
import com.yukisoffd.lyracode.data.Conversation
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import com.yukisoffd.lyracode.data.SkillPack
import com.yukisoffd.lyracode.data.SshServerConfig
import com.yukisoffd.lyracode.data.WebDavServerConfig
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.webdav.TransferProgress
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.UploadedFile
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs
import android.graphics.Canvas as AndroidCanvas

internal data class MarkdownListItem(
    val marker: String,
    val text: String,
    val level: Int,
)

@Composable
internal fun MarkdownContent(markdown: String) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> MarkdownHeading(block)
                is MarkdownBlock.Paragraph -> MarkdownParagraph(block.text, style = MaterialTheme.typography.bodyMedium)
                is MarkdownBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    MarkdownInlineLatexContent(block.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                is MarkdownBlock.Numbered -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${block.number}.", style = MaterialTheme.typography.bodyMedium)
                    MarkdownInlineLatexContent(block.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                is MarkdownBlock.ListItems -> MarkdownList(block)
                is MarkdownBlock.Quote -> MarkdownText(
                    block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f))
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MarkdownBlock.Code -> CodeBlock(block)
                is MarkdownBlock.Table -> TableBlock(block)
                is MarkdownBlock.Math -> MathBlock(block)
                MarkdownBlock.Spacer -> Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
internal fun MarkdownWebView(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val html = remember(markdown, colorScheme.onSurface, colorScheme.onSurfaceVariant, colorScheme.surfaceVariant, colorScheme.outline) {
        markdownHtml(
            markdown = normalizeLatexInText(markdown).take(160_000),
            textColor = cssColor(colorScheme.onSurface),
            mutedColor = cssColor(colorScheme.onSurfaceVariant),
            surfaceColor = cssColor(colorScheme.surfaceVariant.copy(alpha = 0.58f)),
            outlineColor = cssColor(colorScheme.outline.copy(alpha = 0.28f)),
        )
    }
    KatexWebView(
        html = html,
        estimatedHeight = markdownWebHeight(markdown),
        modifier = modifier,
    )
}

@Composable
internal fun MarkdownParagraph(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val parts = remember(text) { splitMarkdownMedia(text) }
    if (parts.size == 1 && parts.first() is MarkdownInlinePart.TextPart) {
        MarkdownInlineLatexContent(text, modifier = modifier, style = style)
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is MarkdownInlinePart.TextPart -> {
                    if (part.text.isNotBlank()) MarkdownInlineLatexContent(part.text.trim(), style = style)
                }
                is MarkdownInlinePart.MediaPart -> MarkdownMediaPreview(part.alt, part.url)
            }
        }
    }
}

internal sealed class MarkdownInlinePart {
    data class TextPart(val text: String) : MarkdownInlinePart()
    data class MediaPart(val alt: String, val url: String) : MarkdownInlinePart()
}

internal fun splitMarkdownMedia(text: String): List<MarkdownInlinePart> {
    val regex = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")
    val result = mutableListOf<MarkdownInlinePart>()
    var cursor = 0
    regex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            result += MarkdownInlinePart.TextPart(text.substring(cursor, match.range.first))
        }
        result += MarkdownInlinePart.MediaPart(
            alt = match.groupValues[1],
            url = match.groupValues[2],
        )
        cursor = match.range.last + 1
    }
    if (cursor < text.length) result += MarkdownInlinePart.TextPart(text.substring(cursor))
    if (result.isNotEmpty()) return result
    val trimmed = text.trim()
    if (isMediaSource(trimmed) || isLikelyRawBase64Media(trimmed)) {
        return listOf(MarkdownInlinePart.MediaPart(uiText(R.string.label_media_file), trimmed))
    }
    val sourceMatch = mediaSourceRegex().find(text)
    if (sourceMatch != null) {
        val before = text.substring(0, sourceMatch.range.first)
        val source = sourceMatch.value
        val after = text.substring(sourceMatch.range.last + 1)
        return buildList {
            if (before.isNotBlank()) add(MarkdownInlinePart.TextPart(before))
            add(MarkdownInlinePart.MediaPart(uiText(R.string.label_media_file), source))
            if (after.isNotBlank()) add(MarkdownInlinePart.TextPart(after))
        }
    }
    return listOf(MarkdownInlinePart.TextPart(text))
}

@Composable
internal fun MarkdownMediaPreview(alt: String, url: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decoded = remember(url) { decodeMediaPayload(url) }
    val kind = remember(url, decoded?.mimeType) { mediaKindForSource(url, decoded?.mimeType) }
    var saveStatus by remember { mutableStateOf("") }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(alt.ifBlank { uiText(R.string.label_media_file) }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text(uiText(R.string.file_action_copy)) }
                TextButton(onClick = {
                    saveStatus = uiText(R.string.ui_saving)
                    scope.launch {
                        saveStatus = withContext(Dispatchers.IO) { saveMediaSource(context, url, decoded) ?: uiText(R.string.notice_save_failed) }
                    }
                }) { Text(uiText(R.string.file_editor_save)) }
            }
            if (decoded != null) {
                DataUrlInlinePreview(decoded)
            } else if (kind == "image") {
                SourceImagePreview(url)
            } else if (kind == "video" || kind == "audio") {
                EmbeddedMediaPlayer(url, kind)
            } else {
                MarkdownText("[$url]($url)", style = MaterialTheme.typography.bodySmall)
            }
            if (saveStatus.isNotBlank()) {
                Text(saveStatus, color = KimiMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun SourceImagePreview(source: String) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var status by remember(source) { mutableStateOf(uiText(R.string.ui_loading_media)) }
    var previewOpen by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readMediaBytes(context, source, 24 * 1024 * 1024)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        bitmap = loaded
        status = if (loaded == null) uiText(R.string.notice_image_load_failed) else ""
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { previewOpen = true },
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(status, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
    if (previewOpen && bitmap != null) {
        FullscreenMediaPreviewDialog(
            title = uiText(R.string.title_image_preview),
            source = source,
            kind = "image",
            bitmap = bitmap,
            onDismiss = { previewOpen = false },
        )
    }
}

@Composable
internal fun EmbeddedMediaPlayer(source: String, kind: String) {
    val context = LocalContext.current
    val uri = remember(source) { uriForMediaSource(source) }
    var previewOpen by remember(source) { mutableStateOf(false) }
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val videoViewHolder = remember(source) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(lifecycleOwner, source) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) videoViewHolder.value?.pause()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            runCatching { videoViewHolder.value?.stopPlayback() }
            videoViewHolder.value = null
        }
    }
    if (uri == null) {
        Text(uiText(R.string.ui_unrecognized_media_url) + source, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        return
    }
    Box {
        AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (kind == "audio") 96.dp else 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f)),
        factory = {
            VideoView(it).apply {
                videoViewHolder.value = this
                setMediaController(MediaController(context).also { controller -> controller.setAnchorView(this) })
                setVideoURI(uri)
                setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = false
                    seekTo(1)
                }
            }
        },
        update = { view ->
            if (view.tag != source) {
                view.tag = source
                view.setVideoURI(uri)
                view.seekTo(1)
            }
        },
        )
        if (kind == "video") {
            TextButton(
                onClick = { previewOpen = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(uiText(R.string.action_fullscreen))
            }
        }
    }
    if (previewOpen) {
        FullscreenMediaPreviewDialog(
            title = uiText(R.string.title_video_preview),
            source = source,
            kind = kind,
            onDismiss = { previewOpen = false },
        )
    }
}

@Composable
internal fun DataUrlInlinePreview(decoded: DecodedDataUrl) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var previewOpen by remember(decoded.bytes) { mutableStateOf(false) }
    if (decoded.mimeType.startsWith("image/")) {
        val bitmap = remember(decoded.bytes) { BitmapFactory.decodeByteArray(decoded.bytes, 0, decoded.bytes.size) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { previewOpen = true },
                contentScale = ContentScale.Fit,
            )
            if (previewOpen) {
                FullscreenMediaPreviewDialog(
                    title = uiText(R.string.title_image_preview),
                    source = "",
                    kind = "image",
                    bitmap = bitmap,
                    decoded = decoded,
                    onDismiss = { previewOpen = false },
                )
            }
        } else {
            Text(uiText(R.string.notice_image_decode_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        val kind = mediaKindForSource("", decoded.mimeType)
        Text("${decoded.mimeType} · ${decoded.bytes.size} bytes", color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        if (kind == "video" || kind == "audio") {
            KimiChip(if (kind == "video") uiText(R.string.action_fullscreen_play) else uiText(R.string.media_preview_play), onClick = { previewOpen = true })
            if (previewOpen) {
                FullscreenMediaPreviewDialog(
                    title = uiText(R.string.title_media_preview),
                    source = "",
                    kind = kind,
                    decoded = decoded,
                    onDismiss = { previewOpen = false },
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KimiChip(uiText(R.string.action_save_file), onClick = {
            status = uiText(R.string.ui_saving)
            scope.launch {
                status = withContext(Dispatchers.IO) { saveDecodedDataUrl(context, decoded) ?: uiText(R.string.notice_save_failed) }
            }
        })
    }
    if (status.isNotBlank()) {
        Text(status, color = KimiMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun FullscreenMediaPreviewDialog(
    title: String,
    source: String,
    kind: String,
    bitmap: Bitmap? = null,
    decoded: DecodedDataUrl? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val resolvedDecoded = remember(source, decoded) { decoded ?: decodeMediaPayload(source) }
    val resolvedKind = remember(kind, resolvedDecoded?.mimeType) {
        if (resolvedDecoded != null) mediaKindForSource(source, resolvedDecoded.mimeType) else kind
    }
    DisposableEffect(resolvedKind) {
        val oldOrientation = activity?.requestedOrientation
        if (resolvedKind == "video") {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (oldOrientation != null) {
                activity.requestedOrientation = oldOrientation
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(12.dp),
        ) {
            when (resolvedKind) {
                "image" -> FullscreenImageContent(source = source, bitmap = bitmap, decoded = resolvedDecoded)
                "video", "audio" -> FullscreenVideoContent(source = source, decoded = resolvedDecoded, kind = resolvedKind)
                else -> Text(uiText(R.string.notice_cannot_preview_media), modifier = Modifier.align(Alignment.Center), color = Color.White)
            }
            Text(
                title.ifBlank { uiText(R.string.title_media_preview) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(end = 56.dp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(Icons.Default.Close, contentDescription = uiText(R.string.media_preview_close), tint = Color.White)
            }
        }
    }
}

@Composable
internal fun FullscreenImageContent(source: String, bitmap: Bitmap?, decoded: DecodedDataUrl?) {
    val context = LocalContext.current
    var loadedBitmap by remember(source, bitmap, decoded?.bytes) {
        mutableStateOf(bitmap ?: decoded?.bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
    }
    var status by remember(source) { mutableStateOf(if (loadedBitmap == null) uiText(R.string.ui_loading_media) else "") }
    LaunchedEffect(source, loadedBitmap) {
        if (loadedBitmap == null && source.isNotBlank()) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = readMediaBytes(context, source, 48 * 1024 * 1024)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            loadedBitmap = loaded
            status = if (loaded == null) uiText(R.string.notice_image_load_error) else ""
        }
    }
    if (loadedBitmap != null) {
        Image(
            bitmap = loadedBitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 42.dp, bottom = 12.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(Modifier.fillMaxSize()) {
            Text(status, modifier = Modifier.align(Alignment.Center), color = Color.White)
        }
    }
}

@Composable
internal fun FullscreenVideoContent(source: String, decoded: DecodedDataUrl?, kind: String) {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val videoViewHolder = remember(source) { mutableStateOf<VideoView?>(null) }
    val uri = remember(source, decoded?.bytes) {
        decoded?.let { cacheDecodedPreviewMedia(context, it) } ?: uriForMediaSource(source)
    }
    DisposableEffect(lifecycleOwner, source) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) videoViewHolder.value?.pause()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            runCatching { videoViewHolder.value?.stopPlayback() }
            videoViewHolder.value = null
        }
    }
    if (uri == null) {
        Text(uiText(R.string.error_cannot_open_media), modifier = Modifier.fillMaxWidth().padding(top = 80.dp), color = Color.White)
        return
    }
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 42.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        factory = {
            VideoView(it).apply {
                videoViewHolder.value = this
                setMediaController(MediaController(context).also { controller -> controller.setAnchorView(this) })
                setVideoURI(uri)
                setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = false
                    if (kind == "video") seekTo(1)
                    start()
                }
            }
        },
        update = { view ->
            if (view.tag != uri) {
                view.tag = uri
                view.setVideoURI(uri)
            }
        },
    )
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

@Composable
internal fun MarkdownHeading(block: MarkdownBlock.Heading) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        3 -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.bodyLarge
    }
    MarkdownText(block.text, style = style.copy(fontWeight = FontWeight.Bold))
}

@Composable
internal fun MarkdownList(block: MarkdownBlock.ListItems) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.Top) {
                Spacer(Modifier.width((item.level * 14).dp))
                Text(
                    if (block.ordered) item.marker.ifBlank { "${index + 1}." } else "•",
                    modifier = Modifier.width(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                MarkdownInlineLatexContent(
                    item.text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun MathBlock(block: MarkdownBlock.Math) {
    KatexFormula(
        formula = block.formula,
        displayMode = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun MarkdownInlineLatexContent(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val rendered = remember(text) { if (hasLatexFormula(text)) renderInlineLatex(normalizeLatexInText(text)) else text }
    MarkdownText(rendered, modifier = modifier, style = style)
}

@Composable
internal fun KatexFormula(
    formula: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val html = remember(formula, displayMode, textColor) {
        katexHtml(
            formula = normalizeLatexForKatex(formula).take(4096),
            displayMode = displayMode,
            textColor = cssColor(textColor),
        )
    }
    KatexWebView(
        html = html,
        estimatedHeight = katexHeight(formula, displayMode),
        modifier = modifier,
    )
}

@Composable
internal fun KatexRichText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val html = remember(text, textColor) {
        katexRichHtml(
            text = normalizeLatexInText(text).take(8192),
            textColor = cssColor(textColor),
        )
    }
    KatexWebView(
        html = html,
        estimatedHeight = katexRichTextHeight(text),
        modifier = modifier,
    )
}

@Composable
internal fun KatexWebView(
    html: String,
    estimatedHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var height by remember(html) { mutableStateOf(estimatedHeight) }
    AndroidView(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(4.dp)),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isHorizontalScrollBarEnabled = true
                isVerticalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.textZoom = 100
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url?.toString().orEmpty()
                        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                            runCatching { uriHandler.openUri(target) }
                            return true
                        }
                        return false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                        val target = url.orEmpty()
                        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                            runCatching { uriHandler.openUri(target) }
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        fun updateHeight() {
                            view.evaluateJavascript(
                                "(function(){return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight, 24);})()",
                            ) { value ->
                                val cssPx = value?.trim('"')?.toFloatOrNull() ?: return@evaluateJavascript
                                height = (cssPx + 10f).coerceAtLeast(24f).dp
                            }
                        }
                        updateHeight()
                        view.postDelayed({ updateHeight() }, 140)
                        view.postDelayed({ updateHeight() }, 520)
                    }
                }
                loadDataWithBaseURL(KATEX_BASE_URL, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(KATEX_BASE_URL, html, "text/html", "UTF-8", null)
        },
    )
}

@Composable
internal fun CodeBlock(block: MarkdownBlock.Code) {
    if (com.yukisoffd.lyracode.charts.isChartLanguage(block.language)) {
        ChartBlock(block.language, block.code)
        return
    }
    val clipboard = LocalClipboardManager.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                    Text(uiText(R.string.cd_copy_code))
                }
            }
            Text(
                block.code,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
internal fun TableBlock(block: MarkdownBlock.Table) {
    val columnCount = block.rows.maxOfOrNull { it.size } ?: 0
    val columnWidth = 176.dp
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
    ) {
        block.rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                repeat(columnCount) { columnIndex ->
                    val cell = row.getOrNull(columnIndex).orEmpty()
                    Box(
                        Modifier
                            .width(columnWidth)
                            .fillMaxHeight()
                            .background(if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .border(1.dp, KimiLine.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        MarkdownText(
                            renderInlineLatex(normalizeLatexInText(cell)),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    }
                }
            }
        }
    }
}

internal fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    var index = 0

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotBlank()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.clear()
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            flushParagraph()
            if (blocks.lastOrNull() !is MarkdownBlock.Spacer) blocks += MarkdownBlock.Spacer
            index++
            continue
        }
        val mathDelimiter = when {
            trimmed.startsWith("$$") -> "$$"
            trimmed.startsWith("\\[") -> "\\]"
            else -> null
        }
        if (mathDelimiter != null) {
            flushParagraph()
            val startToken = if (mathDelimiter == "$$") "$$" else "\\["
            val formula = StringBuilder()
            val first = trimmed.removePrefix(startToken)
            if (first.contains(mathDelimiter)) {
                formula.append(first.substringBefore(mathDelimiter))
                index++
            } else {
                if (first.isNotBlank()) formula.appendLine(first)
                index++
                while (index < lines.size && !lines[index].trim().contains(mathDelimiter)) {
                    formula.appendLine(lines[index])
                    index++
                }
                if (index < lines.size) {
                    formula.append(lines[index].substringBefore(mathDelimiter))
                    index++
                }
            }
            blocks += MarkdownBlock.Math(formula.toString().trim(), display = true)
            continue
        }
        val fence = fenceStart(trimmed)
        if (fence != null) {
            flushParagraph()
            val language = fence.second
            val delimiter = fence.first
            index++
            val code = StringBuilder()
            while (index < lines.size && !isFenceEnd(lines[index].trim(), delimiter)) {
                code.appendLine(lines[index])
                index++
            }
            if (index < lines.size) index++
            blocks += MarkdownBlock.Code(language, code.toString().trimEnd())
            continue
        }
        val heading = Regex("""^(#{1,6})\s+(.+)$""").matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            index++
            continue
        }
        val quote = Regex("""^ {0,3}>\s?(.*)$""").matchEntire(line)
        if (quote != null) {
            flushParagraph()
            val quoteLines = mutableListOf<String>()
            while (index < lines.size) {
                val current = Regex("""^ {0,3}>\s?(.*)$""").matchEntire(lines[index])
                if (current == null) break
                quoteLines += current.groupValues[1]
                index++
            }
            blocks += MarkdownBlock.Quote(quoteLines.joinToString("\n").trim())
            continue
        }
        val listStart = listMarker(line)
        if (listStart != null) {
            flushParagraph()
            val ordered = listStart.ordered
            val items = mutableListOf<MarkdownListItem>()
            var current: MarkdownListItem? = null
            while (index < lines.size) {
                val candidate = listMarker(lines[index])
                if (candidate != null) {
                    current?.let { items += it }
                    current = MarkdownListItem(
                        marker = candidate.marker,
                        text = candidate.text,
                        level = candidate.level,
                    )
                    index++
                    continue
                }
                val continuation = lines[index]
                if (continuation.trim().isBlank()) {
                    index++
                    if (index < lines.size && listMarker(lines[index]) != null) continue
                    break
                }
                if (fenceStart(continuation.trim()) != null) {
                    break
                }
                if (current != null && continuation.leadingSpaces() > current.level * 2) {
                    current = current.copy(text = listOf(current.text, continuation.trim()).filter { it.isNotBlank() }.joinToString("\n"))
                    index++
                    continue
                }
                break
            }
            current?.let { items += it }
            blocks += MarkdownBlock.ListItems(ordered, items)
            continue
        }
        if (isTableStart(lines, index)) {
            flushParagraph()
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && lines[index].trim().contains("|") && lines[index].isNotBlank()) {
                if (!isTableSeparator(lines[index].trim())) {
                    rows += parseTableRow(lines[index])
                }
                index++
            }
            blocks += MarkdownBlock.Table(rows)
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(trimmed)
        index++
    }
    flushParagraph()
    return blocks
        .dropWhile { it is MarkdownBlock.Spacer }
        .dropLastWhile { it is MarkdownBlock.Spacer }
}

internal fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val current = lines[index].trim()
    val next = lines[index + 1].trim()
    return current.contains("|") && isTableSeparator(next)
}

internal fun isTableSeparator(line: String): Boolean =
    Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$""").matches(line)

internal fun parseTableRow(line: String): List<String> {
    return line.trim().trim('|').split('|').map { it.trim() }
}

internal data class ParsedListMarker(
    val marker: String,
    val text: String,
    val level: Int,
    val ordered: Boolean,
)

internal fun fenceStart(trimmed: String): Pair<String, String>? {
    val match = Regex("""^(`{3,}|~{3,})\s*([A-Za-z0-9_+.#-]*)?.*$""").matchEntire(trimmed) ?: return null
    return match.groupValues[1] to match.groupValues.getOrElse(2) { "" }.trim()
}

internal fun isFenceEnd(trimmed: String, delimiter: String): Boolean {
    val fenceChar = delimiter.firstOrNull() ?: return false
    return trimmed.length >= delimiter.length && trimmed.all { it == fenceChar }
}

internal fun listMarker(line: String): ParsedListMarker? {
    val match = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.+)$""").matchEntire(line) ?: return null
    val marker = match.groupValues[2]
    return ParsedListMarker(
        marker = if (marker.firstOrNull()?.isDigit() == true) marker.trimEnd(')', '.') + "." else marker,
        text = match.groupValues[3].trim(),
        level = match.groupValues[1].replace("\t", "    ").length / 4,
        ordered = marker.firstOrNull()?.isDigit() == true,
    )
}

internal fun String.leadingSpaces(): Int = takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }

internal fun katexHeight(formula: String, displayMode: Boolean) = when {
    !displayMode -> 32.dp
    formula.length > 240 || formula.contains("\\begin") -> 120.dp
    formula.length > 120 || formula.contains("\\frac") || formula.contains("\\sum") || formula.contains("\\int") -> 86.dp
    else -> 58.dp
}

private const val KATEX_BASE_URL = "file:///android_asset/katex/"

internal fun katexHtml(formula: String, displayMode: Boolean, textColor: String): String {
    val formulaLiteral = JSONObject.quote(formula)
    val fallback = htmlEscape(formula)
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <link rel="stylesheet" href="katex.min.css">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              background: transparent;
              color: $textColor;
              overflow-x: auto;
              overflow-y: hidden;
              font-size: ${if (displayMode) 17 else 16}px;
            }
            #math {
              box-sizing: border-box;
              min-height: 32px;
              padding: ${if (displayMode) "4px 0" else "1px 0"};
              display: inline-block;
              min-width: max-content;
              white-space: nowrap;
            }
            .katex-display {
              margin: 0;
              text-align: left;
            }
          </style>
        </head>
        <body>
          <div id="math">$fallback</div>
          <script defer src="katex.min.js"></script>
          <script>
            window.addEventListener('load', function() {
              try {
                katex.render($formulaLiteral, document.getElementById('math'), {
                  displayMode: $displayMode,
                  throwOnError: false,
                  strict: 'ignore',
                  trust: false,
                  errorColor: '$textColor',
                  output: 'html'
                });
              } catch (e) {
                document.getElementById('math').textContent = $formulaLiteral;
              }
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}

internal fun katexRichHtml(text: String, textColor: String): String {
    val body = htmlEscape(text).replace("\n", "<br>")
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <link rel="stylesheet" href="katex.min.css">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              background: transparent;
              color: $textColor;
              overflow-x: auto;
              overflow-y: hidden;
              font-size: 16px;
              line-height: 1.55;
              word-break: break-word;
            }
            #content {
              box-sizing: border-box;
              padding: 0;
              min-width: 100%;
            }
            .katex {
              font-size: 1.02em;
              white-space: nowrap;
            }
            .katex-display {
              margin: 0.25em 0;
              text-align: left;
              overflow-x: auto;
              overflow-y: hidden;
              width: max-content;
              max-width: none;
            }
          </style>
        </head>
        <body>
          <div id="content">$body</div>
          <script defer src="katex.min.js"></script>
          <script defer src="contrib/auto-render.min.js"></script>
          <script>
            window.addEventListener('load', function() {
              try {
                renderMathInElement(document.getElementById('content'), {
                  delimiters: [
                    {left: '$$', right: '$$', display: true},
                    {left: '\\[', right: '\\]', display: true},
                    {left: '\\(', right: '\\)', display: false},
                    {left: '$', right: '$', display: false}
                  ],
                  throwOnError: false,
                  strict: 'ignore',
                  trust: false,
                  errorColor: '$textColor'
                });
              } catch (e) {}
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}

internal fun katexRichTextHeight(text: String): androidx.compose.ui.unit.Dp {
    val lineCount = text.count { it == '\n' } + (text.length / 28) + 1
    val extra = if (hasLatexFormula(text)) 18 else 0
    return (lineCount * 25 + extra).coerceIn(32, 180).dp
}

internal fun markdownWebHeight(markdown: String): androidx.compose.ui.unit.Dp {
    val visualLines = markdown.count { it == '\n' } + (markdown.length / 34) + 1
    val tableExtra = if (markdown.contains('|')) 80 else 0
    val mathExtra = Regex("""(\$\$|\\\[|\\\(|\\\\\[|\\\\\()""").findAll(markdown).count() * 28
    return (visualLines * 24 + tableExtra + mathExtra).coerceIn(48, 1800).dp
}

internal fun markdownHtml(
    markdown: String,
    textColor: String,
    mutedColor: String,
    surfaceColor: String,
    outlineColor: String,
): String {
    val sourceLiteral = JSONObject.quote(markdown)
    val fallback = htmlEscape(markdown).replace("\n", "<br>")
    val dollar = "$"
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <link rel="stylesheet" href="file:///android_asset/katex/katex.min.css">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              background: transparent;
              color: $textColor;
              overflow-x: hidden;
              overflow-y: hidden;
              font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              font-size: 16px;
              line-height: 1.62;
              word-break: break-word;
            }
            #content {
              box-sizing: border-box;
              width: 100%;
              min-width: 0;
            }
            p {
              margin: 0.42em 0;
            }
            h1, h2, h3, h4, h5, h6 {
              margin: 0.82em 0 0.42em;
              line-height: 1.28;
              font-weight: 760;
            }
            h1 { font-size: 1.42em; }
            h2 { font-size: 1.28em; }
            h3 { font-size: 1.16em; }
            strong {
              font-weight: 760;
            }
            em {
              font-style: italic;
            }
            ul, ol {
              margin: 0.45em 0;
              padding-left: 1.35em;
            }
            li {
              margin: 0.2em 0;
            }
            blockquote {
              margin: 0.65em 0;
              padding: 0.58em 0.78em;
              border-left: 3px solid $outlineColor;
              background: $surfaceColor;
              color: $textColor;
            }
            code {
              font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              font-size: 0.92em;
              padding: 0.08em 0.32em;
              border-radius: 5px;
              background: $surfaceColor;
            }
            pre {
              margin: 0.7em 0;
              padding: 0.72em 0.82em;
              border-radius: 12px;
              background: $surfaceColor;
              overflow-x: auto;
              white-space: pre;
            }
            pre code {
              padding: 0;
              border-radius: 0;
              background: transparent;
              white-space: pre;
            }
            a {
              color: #5f8cff;
              text-decoration: none;
            }
            hr {
              border: 0;
              height: 1px;
              background: $outlineColor;
              margin: 0.92em 0;
            }
            img, video {
              max-width: 100%;
              height: auto;
              border-radius: 12px;
            }
            .table-wrap {
              width: 100%;
              max-width: 100%;
              overflow-x: auto;
              overflow-y: hidden;
              margin: 0.72em 0;
              border-radius: 0;
              -webkit-overflow-scrolling: touch;
            }
            table {
              border-collapse: collapse;
              width: max-content;
              min-width: 100%;
              table-layout: auto;
              background: rgba(128, 128, 128, 0.08);
            }
            th, td {
              border: 1px solid $outlineColor;
              padding: 0.58em 0.72em;
              vertical-align: top;
              text-align: left;
              min-width: 7.5em;
              max-width: 22em;
              white-space: normal;
              overflow-wrap: break-word;
            }
            th {
              background: $surfaceColor;
              font-weight: 760;
            }
            .katex {
              font-size: 1.02em;
              color: $textColor;
            }
            .katex-display {
              margin: 0.45em 0;
              padding: 0.18em 0;
              overflow-x: auto;
              overflow-y: hidden;
              text-align: left;
              -webkit-overflow-scrolling: touch;
            }
            .katex-display > .katex {
              display: inline-block;
              min-width: max-content;
              max-width: none;
            }
            .fallback {
              margin: 0;
              white-space: pre-wrap;
              color: $mutedColor;
              font: inherit;
            }
          </style>
        </head>
        <body>
          <div id="content"><pre class="fallback">$fallback</pre></div>
          <script defer src="file:///android_asset/markdown-it/markdown-it.min.js"></script>
          <script defer src="file:///android_asset/katex/katex.min.js"></script>
          <script defer src="file:///android_asset/katex/contrib/auto-render.min.js"></script>
          <script>
            window.addEventListener('load', function() {
              var content = document.getElementById('content');
              var source = $sourceLiteral;
              try {
                var md = window.markdownit({
                  html: false,
                  linkify: true,
                  breaks: true,
                  typographer: false
                });
                content.innerHTML = md.render(source);
                Array.prototype.forEach.call(content.querySelectorAll('table'), function(table) {
                  if (table.parentNode && table.parentNode.className === 'table-wrap') return;
                  var wrap = document.createElement('div');
                  wrap.className = 'table-wrap';
                  table.parentNode.insertBefore(wrap, table);
                  wrap.appendChild(table);
                });
                if (window.renderMathInElement) {
                  renderMathInElement(content, {
                    delimiters: [
                      {left: '${dollar}${dollar}', right: '${dollar}${dollar}', display: true},
                      {left: '\\[', right: '\\]', display: true},
                      {left: '\\(', right: '\\)', display: false},
                      {left: '${dollar}', right: '${dollar}', display: false}
                    ],
                    throwOnError: false,
                    strict: 'ignore',
                    trust: false,
                    errorColor: '$textColor'
                  });
                }
              } catch (e) {
                content.innerHTML = '<pre class="fallback">' + source
                  .replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;') + '</pre>';
              }
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}

internal fun hasLatexFormula(text: String): Boolean {
    return text.contains("\\(") ||
        text.contains("\\[") ||
        text.contains("$$") ||
        containsInlineDollarFormula(text) ||
        text.contains("\\\\(") ||
        text.contains("\\\\[")
}

internal fun containsInlineDollarFormula(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        if (text[index] == '$' && !text.isEscapedAt(index)) {
            val end = findClosingDollar(text, index + 1)
            if (end > index) {
                val formula = text.substring(index + 1, end)
                if (formula.length <= 500 && looksLikeLatexFormula(formula)) return true
                index = end + 1
                continue
            }
        }
        index++
    }
    return false
}

internal fun normalizeLatexForKatex(raw: String): String {
    var text = raw.trim()
    text = text.removeSurrounding("$$")
        .removeSurrounding("\\(", "\\)")
        .removeSurrounding("\\[", "\\]")
        .removeSurrounding("\\\\(", "\\\\)")
        .removeSurrounding("\\\\[", "\\\\]")
    text = text
        .replace(Regex("""\\\\(?=[A-Za-z])"""), "\\")
        .replace("\\\\(", "\\(")
        .replace("\\\\)", "\\)")
        .replace("\\\\[", "\\[")
        .replace("\\\\]", "\\]")
    return text
}

internal fun normalizeLatexInText(raw: String): String {
    return raw
        .replace(Regex("""\\\\(?=[A-Za-z])"""), "\\")
        .replace("\\\\(", "\\(")
        .replace("\\\\)", "\\)")
        .replace("\\\\[", "\\[")
        .replace("\\\\]", "\\]")
}

internal fun cssColor(color: Color): String {
    val argb = color.toArgb()
    val r = android.graphics.Color.red(argb)
    val g = android.graphics.Color.green(argb)
    val b = android.graphics.Color.blue(argb)
    val a = android.graphics.Color.alpha(argb) / 255f
    return "rgba($r,$g,$b,$a)"
}

internal fun htmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var dataUrl by remember { mutableStateOf<String?>(null) }
    dataUrl?.let {
        DataUrlPreviewDialog(
            dataUrl = it,
            onDismiss = { dataUrl = null },
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        )
    }
    val annotated = remember(text) { markdownInline(text) }
    val resolvedStyle = style.copy(
        color = if (style.color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.color,
    )
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = resolvedStyle,
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.item?.let { url ->
                if (url.startsWith("data:", ignoreCase = true)) {
                    dataUrl = url
                } else {
                    runCatching { uriHandler.openUri(url) }
                }
            }
        },
    )
}

internal fun renderInlineLatex(text: String): String {
    return runCatching { renderInlineLatexUnsafe(text) }.getOrDefault(text)
}

internal fun renderLatexFormula(raw: String): String {
    return runCatching { renderLatexFormulaUnsafe(raw.take(4096), depth = 0) }.getOrDefault(raw)
}

internal fun renderInlineLatexUnsafe(text: String): String {
    if (!text.contains('$') && !text.contains("\\(") && !text.contains("\\[")) return text
    val output = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("\\(", index) -> {
                val end = text.indexOf("\\)", startIndex = index + 2)
                if (end > index) {
                    output.append(renderLatexFormula(text.substring(index + 2, end)))
                    index = end + 2
                } else {
                    output.append(text[index++])
                }
            }
            text.startsWith("\\[", index) -> {
                val end = text.indexOf("\\]", startIndex = index + 2)
                if (end > index) {
                    output.append(renderLatexFormula(text.substring(index + 2, end)))
                    index = end + 2
                } else {
                    output.append(text[index++])
                }
            }
            text.startsWith("$$", index) -> {
                val end = text.indexOf("$$", startIndex = index + 2)
                if (end > index) {
                    output.append(renderLatexFormula(text.substring(index + 2, end)))
                    index = end + 2
                } else {
                    output.append(text[index++])
                }
            }
            text[index] == '$' && !text.isEscapedAt(index) -> {
                val end = findClosingDollar(text, index + 1)
                if (end > index) {
                    val formula = text.substring(index + 1, end)
                    if (formula.length <= 500 && looksLikeLatexFormula(formula)) {
                        output.append(renderLatexFormula(formula))
                    } else {
                        output.append(text.substring(index, end + 1))
                    }
                    index = end + 1
                } else {
                    output.append(text[index++])
                }
            }
            else -> output.append(text[index++])
        }
    }
    return output.toString()
}

internal fun findClosingDollar(text: String, start: Int): Int {
    var index = start
    while (index < text.length) {
        if (text[index] == '$' && !text.isEscapedAt(index)) return index
        if (text[index] == '\n') return -1
        index++
    }
    return -1
}

internal fun String.isEscapedAt(index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

internal fun renderLatexFormulaUnsafe(raw: String, depth: Int): String {
    if (depth > 8) return raw
    var text = raw.trim()
        .replace("\\,", " ")
        .replace("\\;", " ")
        .replace("\\!", "")
        .replace("\\ ", " ")
    text = text
        .replace("\\left", "")
        .replace("\\right", "")

    repeat(6) {
        text = Regex("""\\frac\s*\{([^{}]+)}\s*\{([^{}]+)}""").replace(text) { match ->
            "(${renderLatexFormulaUnsafe(match.groupValues[1], depth + 1)})/(${renderLatexFormulaUnsafe(match.groupValues[2], depth + 1)})"
        }
        text = Regex("""\\sqrt\s*\{([^{}]+)}""").replace(text) { match ->
            "√(${renderLatexFormulaUnsafe(match.groupValues[1], depth + 1)})"
        }
    }

    latexSymbols().forEach { (from, to) -> text = text.replace(from, to) }
    text = Regex("""\^\{([^{}]+)}""").replace(text) { superscriptText(it.groupValues[1]) }
    text = Regex("""_ \{([^{}]+)}""").replace(text) { subscriptText(it.groupValues[1]) }
    text = Regex("""_\{([^{}]+)}""").replace(text) { subscriptText(it.groupValues[1]) }
    text = Regex("""\^([A-Za-z0-9+\-=()])""").replace(text) { superscriptText(it.groupValues[1]) }
    text = Regex("""_([A-Za-z0-9+\-=()])""").replace(text) { subscriptText(it.groupValues[1]) }
    text = text
        .replace(Regex("""\\operatorname\s*\{([^{}]+)}""")) { it.groupValues[1] }
        .replace(Regex("""\\mathrm\s*\{([^{}]+)}""")) { it.groupValues[1] }
        .replace(Regex("""\\text\s*\{([^{}]+)}""")) { it.groupValues[1] }
        .replace(Regex("""[{}]"""), "")
        .replace(Regex("""\\([A-Za-z]+)""")) { it.groupValues[1] }
        .replace(Regex("""\s+"""), " ")
        .trim()
    return text
}

internal fun looksLikeLatexFormula(formula: String): Boolean {
    return runCatching {
        val trimmed = formula.trim()
        if (trimmed.isBlank()) return false
        trimmed.contains('\\') ||
            trimmed.any { it == '^' || it == '_' || it == '=' } ||
            Regex("""[A-Za-z]\s*[+\-*/=]""").containsMatchIn(trimmed)
    }.getOrDefault(false)
}

internal fun latexSymbols(): Map<String, String> = mapOf(
    "\\alpha" to "α",
    "\\beta" to "β",
    "\\gamma" to "γ",
    "\\delta" to "δ",
    "\\epsilon" to "ε",
    "\\varepsilon" to "ε",
    "\\theta" to "θ",
    "\\lambda" to "λ",
    "\\mu" to "μ",
    "\\pi" to "π",
    "\\rho" to "ρ",
    "\\sigma" to "σ",
    "\\phi" to "φ",
    "\\omega" to "ω",
    "\\Delta" to "Δ",
    "\\Omega" to "Ω",
    "\\times" to "×",
    "\\cdot" to "·",
    "\\div" to "÷",
    "\\pm" to "±",
    "\\leq" to "≤",
    "\\le" to "≤",
    "\\geq" to "≥",
    "\\ge" to "≥",
    "\\neq" to "≠",
    "\\approx" to "≈",
    "\\sim" to "∼",
    "\\infty" to "∞",
    "\\to" to "→",
    "\\rightarrow" to "→",
    "\\leftarrow" to "←",
    "\\Rightarrow" to "⇒",
    "\\in" to "∈",
    "\\notin" to "∉",
    "\\subset" to "⊂",
    "\\subseteq" to "⊆",
    "\\cup" to "∪",
    "\\cap" to "∩",
    "\\int" to "∫",
    "\\sum" to "∑",
    "\\prod" to "∏",
    "\\partial" to "∂",
    "\\nabla" to "∇",
    "\\degree" to "°",
)

internal fun superscriptText(value: String): String = value.map { superscriptChar(it) }.joinToString("")

internal fun subscriptText(value: String): String = value.map { subscriptChar(it) }.joinToString("")

internal fun superscriptChar(char: Char): String = when (char) {
    '0' -> "⁰"
    '1' -> "¹"
    '2' -> "²"
    '3' -> "³"
    '4' -> "⁴"
    '5' -> "⁵"
    '6' -> "⁶"
    '7' -> "⁷"
    '8' -> "⁸"
    '9' -> "⁹"
    '+' -> "⁺"
    '-' -> "⁻"
    '=' -> "⁼"
    '(' -> "⁽"
    ')' -> "⁾"
    'n' -> "ⁿ"
    'i' -> "ⁱ"
    else -> char.toString()
}

internal fun subscriptChar(char: Char): String = when (char) {
    '0' -> "₀"
    '1' -> "₁"
    '2' -> "₂"
    '3' -> "₃"
    '4' -> "₄"
    '5' -> "₅"
    '6' -> "₆"
    '7' -> "₇"
    '8' -> "₈"
    '9' -> "₉"
    '+' -> "₊"
    '-' -> "₋"
    '=' -> "₌"
    '(' -> "₍"
    ')' -> "₎"
    'a' -> "ₐ"
    'e' -> "ₑ"
    'h' -> "ₕ"
    'i' -> "ᵢ"
    'j' -> "ⱼ"
    'k' -> "ₖ"
    'l' -> "ₗ"
    'm' -> "ₘ"
    'n' -> "ₙ"
    'o' -> "ₒ"
    'p' -> "ₚ"
    'r' -> "ᵣ"
    's' -> "ₛ"
    't' -> "ₜ"
    'u' -> "ᵤ"
    'v' -> "ᵥ"
    'x' -> "ₓ"
    else -> char.toString()
}

internal fun markdownInline(text: String): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(text, this, TextStyle.Default)
}

internal fun appendInlineMarkdown(text: String, builder: AnnotatedString.Builder, baseStyle: TextStyle) {
    var index = 0
    while (index < text.length) {
        val remaining = text.substring(index)
        when {
            remaining.startsWith("**") -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInlineMarkdown(text.substring(index + 2, end), builder, baseStyle)
                    builder.pop()
                    index = end + 2
                } else {
                    builder.append(text[index++])
                }
            }
            remaining.startsWith("__") -> {
                val end = text.indexOf("__", startIndex = index + 2)
                if (end > index) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInlineMarkdown(text.substring(index + 2, end), builder, baseStyle)
                    builder.pop()
                    index = end + 2
                } else {
                    builder.append(text[index++])
                }
            }
            remaining.startsWith("~~") -> {
                val end = text.indexOf("~~", startIndex = index + 2)
                if (end > index) {
                    builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendInlineMarkdown(text.substring(index + 2, end), builder, baseStyle)
                    builder.pop()
                    index = end + 2
                } else {
                    builder.append(text[index++])
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index) {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.18f)))
                    builder.append(text.substring(index + 1, end))
                    builder.pop()
                    index = end + 1
                } else {
                    builder.append(text[index++])
                }
            }
            text[index] == '[' -> {
                val close = text.indexOf("](", startIndex = index)
                val end = if (close >= 0) text.indexOf(')', startIndex = close + 2) else -1
                if (close > index && end > close) {
                    val url = text.substring(close + 2, end)
                    builder.pushStringAnnotation("URL", url)
                    builder.pushStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF1F6FEB), textDecoration = TextDecoration.Underline))
                    builder.append(text.substring(index + 1, close))
                    builder.pop()
                    builder.pop()
                    index = end + 1
                } else {
                    builder.append(text[index++])
                }
            }
            text[index] == '*' && index + 1 < text.length -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end > index + 1) {
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInlineMarkdown(text.substring(index + 1, end), builder, baseStyle)
                    builder.pop()
                    index = end + 1
                } else {
                    builder.append(text[index++])
                }
            }
            else -> builder.append(text[index++])
        }
    }
}

@Composable
internal fun DataUrlPreviewDialog(dataUrl: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decoded = remember(dataUrl) { decodeDataUrl(dataUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText(R.string.title_base64_file)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (decoded == null) {
                    Text(uiText(R.string.notice_cannot_parse_data_url), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(decoded.mimeType, color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    if (decoded.mimeType.startsWith("image/")) {
                        val bitmap = remember(decoded.bytes) { BitmapFactory.decodeByteArray(decoded.bytes, 0, decoded.bytes.size) }
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
                        }
                    } else {
                        Text(uiText(R.string.ui_decoded) + "${decoded.bytes.size} bytes" + uiText(R.string.ui_audio_video_and_other_files_can_be_saved_and), color = KimiMuted)
                    }
                    val saveStatus = remember { mutableStateOf("") }
                    if (saveStatus.value.isNotBlank()) Text(saveStatus.value, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KimiChip(uiText(R.string.action_copy_raw), onClick = onCopy)
                        KimiChip(uiText(R.string.action_save_to_download), onClick = {
                            saveStatus.value = uiText(R.string.ui_saving)
                            scope.launch {
                                saveStatus.value = withContext(Dispatchers.IO) { saveDecodedDataUrl(context, decoded) ?: uiText(R.string.notice_save_failed) }
                            }
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(uiText(R.string.cd_close)) } },
    )
}

internal data class DecodedDataUrl(
    val mimeType: String,
    val bytes: ByteArray,
)

internal fun decodeDataUrl(dataUrl: String): DecodedDataUrl? {
    val match = Regex("""^data:([^;,]+)?;base64,(.+)$""", RegexOption.IGNORE_CASE).matchEntire(dataUrl.trim()) ?: return null
    val mimeType = match.groupValues[1].ifBlank { "application/octet-stream" }
    val bytes = runCatching { Base64.decode(match.groupValues[2], Base64.DEFAULT) }.getOrNull() ?: return null
    return DecodedDataUrl(mimeType, bytes)
}

internal fun decodeMediaPayload(source: String): DecodedDataUrl? {
    val trimmed = source.trim()
    decodeDataUrl(trimmed)?.let { return it }
    if (!isLikelyRawBase64Media(trimmed)) return null
    val bytes = runCatching { Base64.decode(trimmed, Base64.DEFAULT) }.getOrNull() ?: return null
    val mimeType = detectMimeType(bytes) ?: return null
    return DecodedDataUrl(mimeType, bytes)
}

internal fun detectMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
    bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "video/mp4"
    bytes.size >= 3 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3" -> "audio/mpeg"
    bytes.size >= 4 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE" -> "audio/wav"
    else -> null
}

internal fun saveDecodedDataUrl(context: Context, decoded: DecodedDataUrl): String? = runCatching {
    val ext = mediaExtensionFromSource("", decoded.mimeType)
    saveBytesToDownloads(context, decoded.bytes, "decoded_${System.currentTimeMillis()}.$ext", decoded.mimeType)
}.getOrNull()

internal fun saveMediaSource(context: Context, source: String, decoded: DecodedDataUrl?): String? = runCatching {
    if (decoded != null) return@runCatching saveDecodedDataUrl(context, decoded) ?: error(uiText(R.string.notice_save_failed))
    val bytes = readMediaBytes(context, source, 200 * 1024 * 1024)
    val mimeType = detectMimeType(bytes) ?: mimeTypeFromSource(source)
    val ext = mediaExtensionFromSource(source, mimeType)
    saveBytesToDownloads(context, bytes, displayNameFromSource(source, ext), mimeType ?: "application/octet-stream")
}.getOrNull()

internal fun saveBytesToDownloads(context: Context, bytes: ByteArray, displayName: String, mimeType: String): String {
    val safeName = sanitizeDownloadFileName(displayName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LyraCode")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error(uiText(R.string.error_cannot_create_download))
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error(uiText(R.string.error_cannot_write_download))
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uiText(R.string.ui_saved_to) + "Download/LyraCode/$safeName"
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LyraCode").apply { mkdirs() }
    val file = uniqueFile(dir, safeName)
    FileOutputStream(file).use { it.write(bytes) }
    return uiText(R.string.ui_saved_to) + file.absolutePath
}

internal fun uniqueFile(dir: File, name: String): File {
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    var file = File(dir, name)
    var index = 1
    while (file.exists()) {
        file = File(dir, if (ext.isBlank()) "${base}_$index" else "${base}_$index.$ext")
        index++
    }
    return file
}

internal fun sanitizeDownloadFileName(name: String): String {
    val clean = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim()
    return clean.ifBlank { "media_${System.currentTimeMillis()}.bin" }
}

internal fun displayNameFromSource(source: String, ext: String): String {
    val clean = source.substringBefore('?').substringBefore('#').trim()
    val rawName = clean.substringAfterLast('/').takeIf { it.contains('.') }
    return sanitizeDownloadFileName(rawName ?: "media_${System.currentTimeMillis()}.$ext")
}

internal fun mimeTypeFromSource(source: String): String? {
    val lower = source.substringBefore('?').substringBefore('#').lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/aac"
        lower.endsWith(".ogg") -> "audio/ogg"
        else -> null
    }
}

internal fun readMediaBytes(context: Context, source: String, maxBytes: Int): ByteArray {
    val input = when {
        source.startsWith("http://", true) || source.startsWith("https://", true) -> URL(source).openStream()
        source.startsWith("content://", true) || source.startsWith("file://", true) -> context.contentResolver.openInputStream(Uri.parse(source))
        else -> File(source).inputStream()
    } ?: error(uiText(R.string.error_cannot_open_media))
    input.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { uiText(R.string.ui_media_exceeds) + "${maxBytes / 1024 / 1024}MB" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

internal fun uriForMediaSource(source: String): Uri? = runCatching {
    when {
        source.startsWith("http://", true) || source.startsWith("https://", true) -> Uri.parse(source)
        source.startsWith("content://", true) || source.startsWith("file://", true) -> Uri.parse(source)
        File(source).exists() -> Uri.fromFile(File(source))
        else -> Uri.parse(source)
    }
}.getOrNull()

internal fun cacheDecodedPreviewMedia(context: Context, decoded: DecodedDataUrl): Uri? = runCatching {
    val extension = when {
        decoded.mimeType.equals("video/mp4", ignoreCase = true) -> ".mp4"
        decoded.mimeType.equals("video/webm", ignoreCase = true) -> ".webm"
        decoded.mimeType.equals("audio/mpeg", ignoreCase = true) -> ".mp3"
        decoded.mimeType.equals("audio/wav", ignoreCase = true) -> ".wav"
        decoded.mimeType.equals("audio/aac", ignoreCase = true) -> ".aac"
        decoded.mimeType.equals("audio/ogg", ignoreCase = true) -> ".ogg"
        else -> ".bin"
    }
    val dir = File(context.cacheDir, "media_preview").apply { mkdirs() }
    val file = File(dir, "preview_${decoded.bytes.contentHashCode()}$extension")
    if (!file.exists() || file.length() != decoded.bytes.size.toLong()) {
        file.outputStream().use { it.write(decoded.bytes) }
    }
    Uri.fromFile(file)
}.getOrNull()

internal fun isMediaSource(source: String): Boolean {
    val clean = source.trim().trim('"', '\'', '。', '，', ',', ')', ']', '>')
    if (clean.startsWith("data:", ignoreCase = true)) return true
    if (clean.startsWith("content://", ignoreCase = true) || clean.startsWith("file://", ignoreCase = true)) return true
    return MEDIA_EXTENSIONS.any { clean.substringBefore('?').substringBefore('#').endsWith(it, ignoreCase = true) }
}

internal fun mediaSourceRegex(): Regex {
    val extensions = MEDIA_EXTENSIONS.joinToString("|") { Regex.escape(it.removePrefix(".")) }
    return Regex("""(?i)(https?://\S+\.(?:$extensions)(?:\?\S*)?|content://\S+|file://\S+|/[^\s)]+\.(?:$extensions))""")
}

internal fun isLikelyRawBase64Media(text: String): Boolean {
    val compact = text.trim().replace("\n", "").replace("\r", "")
    if (compact.length < 256 || compact.length % 4 != 0) return false
    if (!Regex("""^[A-Za-z0-9+/=]+$""").matches(compact)) return false
    val head = compact.take(64)
    return head.startsWith("iVBORw0KGgo") ||
        head.startsWith("/9j/") ||
        head.startsWith("R0lGOD") ||
        head.startsWith("SUQz") ||
        head.startsWith("AAAA") ||
        head.startsWith("UklGR")
}

internal fun mediaKindForSource(source: String, mimeType: String?): String {
    val mime = mimeType.orEmpty().lowercase()
    val lower = source.substringBefore('?').substringBefore('#').lowercase()
    return when {
        mime.startsWith("image/") || IMAGE_EXTENSIONS.any { lower.endsWith(it) } -> "image"
        mime.startsWith("video/") || VIDEO_EXTENSIONS.any { lower.endsWith(it) } -> "video"
        mime.startsWith("audio/") || AUDIO_EXTENSIONS.any { lower.endsWith(it) } -> "audio"
        else -> "file"
    }
}

internal fun mediaExtensionFromSource(source: String, mimeType: String?): String {
    val lower = source.substringBefore('?').substringBefore('#').lowercase()
    MEDIA_EXTENSIONS.firstOrNull { lower.endsWith(it) }?.let { return it.removePrefix(".") }
    return when {
        mimeType?.contains("png") == true -> "png"
        mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "jpg"
        mimeType?.contains("gif") == true -> "gif"
        mimeType?.contains("mp4") == true -> "mp4"
        mimeType?.contains("mpeg") == true || mimeType?.contains("mp3") == true -> "mp3"
        mimeType?.contains("wav") == true -> "wav"
        else -> "bin"
    }
}

internal val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")
internal val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".mov", ".m4v", ".3gp")
internal val AUDIO_EXTENSIONS = listOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac")
internal val MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS



