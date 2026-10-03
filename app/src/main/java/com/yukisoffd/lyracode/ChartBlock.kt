package com.yukisoffd.lyracode

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yukisoffd.lyracode.charts.CHART_ASSET_ORIGIN
import com.yukisoffd.lyracode.charts.ChartDocument
import com.yukisoffd.lyracode.charts.chartHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private class ChartRenderState {
    var ready by mutableStateOf(false)
    var error by mutableStateOf("")
    var height by mutableStateOf(240.dp)
    var view: WebView? = null
    var pendingFormat by mutableStateOf<String?>(null)
}

private class ChartSaveContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.first).putExtra(Intent.EXTRA_TITLE, input.second)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable
internal fun ChartBlock(language: String, source: String) {
    val parsed = remember(language, source) { runCatching { ChartDocument.fromFence(language, source) } }
    val document = parsed.getOrNull()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val navigationSwipeGuard = LocalNavigationSwipeGuard.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val state = remember(document, dark) { ChartRenderState() }
    var fullscreen by remember(document) { mutableStateOf(false) }
    var showSource by remember(document) { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var saving by remember { mutableStateOf(false) }
    val successMessage = uiText(R.string.chart_saved)
    val failureMessage = uiText(R.string.chart_save_failed)
    val saveContract = remember { ChartSaveContract() }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri != null && bytes != null) {
            scope.launch {
                saving = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                            ?: error("Unable to open destination.")
                    }
                }
                saving = false
                Toast.makeText(context, if (result.isSuccess) successMessage else failureMessage, Toast.LENGTH_LONG).show()
            }
        }
    }
    val save: (String, ByteArray) -> Unit = { extension, bytes ->
        if (pendingBytes == null && !saving) {
            pendingBytes = bytes
            val name = document?.title.orEmpty().replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(60).ifBlank { "chart" }
            val mime = when (extension) { "png" -> "image/png"; "svg" -> "image/svg+xml"; "json" -> "application/json"; else -> "text/plain" }
            saveLauncher.launch(mime to "$name.$extension")
        }
    }
    val title = document?.title?.ifBlank { uiText(R.string.chart_title) } ?: uiText(R.string.chart_title)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column {
            ChartToolbar(title, document, state, saving || pendingBytes != null, save,
                onCopy = { clipboard.setText(AnnotatedString(document?.source ?: source)) },
                onSource = { showSource = !showSource }, onFullscreen = { fullscreen = true })
            if (document != null) {
                ChartPreview(document, dark, state, save)
            } else {
                Text(uiText(R.string.chart_render_failed, parsed.exceptionOrNull()?.message.orEmpty()),
                    Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (showSource || document == null) {
                Text(document?.source ?: source, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                        .blockNavigationRevealOnTouch(navigationSwipeGuard).verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()).padding(12.dp))
            }
        }
    }
    if (fullscreen && document != null) {
        AuditDetailWindow(onDismiss = { fullscreen = false }) {
            val fullState = remember(document, dark) { ChartRenderState() }
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.union(WindowInsets.safeGestures.only(WindowInsetsSides.Bottom)),
                ).padding(bottom = 8.dp)) {
                    ChartToolbar(title, document, fullState, saving || pendingBytes != null, save,
                        onCopy = { clipboard.setText(AnnotatedString(document.source)) },
                        onSource = { clipboard.setText(AnnotatedString(document.source)) },
                        onFullscreen = { fullscreen = false }, fullscreen = true)
                    ChartPreview(document, dark, fullState, save, modifier = Modifier.weight(1f), fullscreen = true)
                }
            }
        }
    }
}

@Composable
private fun ChartToolbar(
    title: String, document: ChartDocument?, state: ChartRenderState, saving: Boolean,
    onSave: (String, ByteArray) -> Unit, onCopy: () -> Unit, onSource: () -> Unit,
    onFullscreen: () -> Unit, fullscreen: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ContentCopy, uiText(R.string.cd_copy_code), Modifier.size(18.dp))
        }
        if (!fullscreen) IconButton(onClick = onSource, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Code, uiText(R.string.chart_source), Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = document != null && !saving && state.pendingFormat == null, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FileDownload, uiText(R.string.chart_save), Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf("png" to R.string.chart_save_png, "svg" to R.string.chart_save_svg, "source" to R.string.chart_save_source).forEach { (format, label) ->
                    DropdownMenuItem(text = { Text(uiText(label)) }, enabled = format == "source" || state.ready, onClick = {
                        menu = false
                        if (document != null) {
                            if (format == "source") onSave(if (document.engine == "mermaid") "mmd" else "json", document.source.toByteArray(Charsets.UTF_8))
                            else if (state.ready && state.pendingFormat == null) {
                                state.pendingFormat = format
                                state.view?.evaluateJavascript("window.lyraExport('$format');", null)
                            }
                        }
                    })
                }
            }
        }
        IconButton(onClick = onFullscreen, enabled = document != null, modifier = Modifier.size(36.dp)) {
            Icon(if (fullscreen) Icons.Default.Close else Icons.Default.Fullscreen,
                uiText(if (fullscreen) R.string.chart_close_fullscreen else R.string.chart_fullscreen), Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ChartPreview(
    document: ChartDocument, dark: Boolean, state: ChartRenderState,
    onSave: (String, ByteArray) -> Unit, modifier: Modifier = Modifier, fullscreen: Boolean = false,
) {
    val save by rememberUpdatedState(onSave)
    val navigationSwipeGuard = LocalNavigationSwipeGuard.current
    Box(modifier = modifier.then(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(state.height))
        .blockNavigationRevealOnTouch(navigationSwipeGuard)) {
        key(state) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                createChartWebView(context, document, dark, fullscreen,
                    onReady = { height -> state.ready = true; state.height = height.dp; state.error = "" },
                    onError = { error -> state.error = error; state.ready = false },
                    onExport = { format, bytes ->
                        if (format == state.pendingFormat) { state.pendingFormat = null; save(format, bytes) }
                    },
                    onExportError = { error ->
                        state.pendingFormat = null
                        Toast.makeText(context, uiText(R.string.chart_save_failed) + "\n" + error, Toast.LENGTH_LONG).show()
                    },
                ).also { state.view = it }
            }, onRelease = { view -> state.view = null; state.pendingFormat = null; releaseChartWebView(view) })
        }
        if (!state.ready && state.error.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
        }
        if (state.error.isNotEmpty()) {
            Text(uiText(R.string.chart_render_failed, state.error),
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(12.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal class ChartBridge(
    private val onReady: (Float) -> Unit, private val onError: (String) -> Unit,
    private val onExport: (String, ByteArray) -> Unit, private val onExportError: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var active = true
    @Volatile var finished = false
    private fun post(action: () -> Unit) { handler.post { if (active) action() } }
    @JavascriptInterface fun ready(height: Float) { if (height.isFinite()) { finished = true; post { onReady(height.coerceIn(96f, 240f)) } } }
    @JavascriptInterface fun failed(message: String) { finished = true; post { onError(message.take(2000)) } }
    @JavascriptInterface fun exported(format: String, data: String) {
        if (format !in setOf("png", "svg") || data.length > 32_000_000) { exportFailed("Chart export is too large."); return }
        runCatching { if (format == "png") Base64.decode(data, Base64.DEFAULT) else data.toByteArray(Charsets.UTF_8) }
            .onSuccess { bytes -> post { onExport(format, bytes) } }.onFailure { exportFailed(it.message.orEmpty()) }
    }
    @JavascriptInterface fun exportFailed(message: String) { post { onExportError(message.take(2000)) } }
}

/** Assets are served from a private HTTPS origin; all other resource requests are blocked. */
internal fun createChartWebView(
    context: Context, document: ChartDocument, dark: Boolean, fullscreen: Boolean = false,
    onReady: (Float) -> Unit, onError: (String) -> Unit,
    onExport: (String, ByteArray) -> Unit, onExportError: (String) -> Unit,
): WebView = WebView(context).apply {
    val bridge = ChartBridge(onReady, onError, onExport, onExportError)
    val pageUrl = CHART_ASSET_ORIGIN + "chart.html"
    val pageBytes = chartHtml(document, dark, fullscreen).toByteArray(Charsets.UTF_8)
    tag = bridge
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.setSupportZoom(fullscreen)
    settings.builtInZoomControls = fullscreen
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.textZoom = 100
    setBackgroundColor(if (dark) android.graphics.Color.rgb(28, 27, 31) else android.graphics.Color.WHITE)
    addJavascriptInterface(bridge, "LyraChart")
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) bridge.failed(message.message())
            return true
        }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = true
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) bridge.failed(error.description.toString())
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            // Serve the document as well as its scripts explicitly. This avoids
            // version-dependent interception of loadDataWithBaseURL's data page.
            if (request.url.toString() == pageUrl) {
                return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(pageBytes))
            }
            val name = request.url.toString().removePrefix(CHART_ASSET_ORIGIN)
            if (request.url.toString() == CHART_ASSET_ORIGIN + name && name in setOf("mermaid.min.js", "echarts.min.js", "renderer.js")) {
                return WebResourceResponse("application/javascript", "UTF-8", context.assets.open("charts/$name"))
            }
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }
    loadUrl(pageUrl)
    postDelayed({ if (bridge.active && !bridge.finished) bridge.failed("Chart renderer timed out.") }, 30_000)
}

internal fun releaseChartWebView(view: WebView) {
    (view.tag as? ChartBridge)?.active = false
    view.stopLoading()
    view.removeJavascriptInterface("LyraChart")
    view.destroy()
}
