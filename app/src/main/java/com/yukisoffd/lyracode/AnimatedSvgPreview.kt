package com.yukisoffd.lyracode

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

@Composable
internal fun AnimatedSvgInlinePreview(decoded: DecodedDataUrl) {
    var fullscreenOpen by remember(decoded.bytes) { mutableStateOf(false) }
    AnimatedSvgPreview(decoded.bytes, onOpenFullscreen = { fullscreenOpen = true })
    if (fullscreenOpen) {
        FullscreenMediaPreviewDialog(
            title = uiText(R.string.title_image_preview), source = "", kind = "image", decoded = decoded,
            onDismiss = { fullscreenOpen = false },
        )
    }
}

@Composable
internal fun AnimatedSvgPreview(
    bytes: ByteArray, modifier: Modifier = Modifier, fullscreen: Boolean = false,
    onOpenFullscreen: (() -> Unit)? = null,
) {
    var document by remember(bytes) { mutableStateOf<AnimatedSvgDocument?>(null) }
    var failed by remember(bytes) { mutableStateOf(false) }
    LaunchedEffect(bytes) {
        document = withContext(Dispatchers.Default) { runCatching { AnimatedSvgDocument.create(bytes) }.getOrNull() }
        failed = document == null
    }
    val data = document
    val frame = modifier.then(previewLoadingHeight(bytes, data == null && !failed))
    if (data == null) {
        Text(uiText(if (failed) R.string.notice_image_decode_failed else R.string.ui_loading_media),
            modifier = frame, color = if (failed) MaterialTheme.colorScheme.error else KimiMuted,
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible = fullscreen || LocalPreviewVisible.current
    val holder = remember(data) { mutableStateOf<WebView?>(null) }
    DisposableEffect(lifecycle, data, visible) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (visible) holder.value?.onResume() else holder.value?.onPause()
                Lifecycle.Event.ON_STOP -> holder.value?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BoxWithConstraints(frame) {
        val layout = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
            .height((maxWidth / data.aspectRatio).coerceIn(48.dp, 360.dp))
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
        Box(layout.then(if (onOpenFullscreen == null) Modifier.semantics {
            contentDescription = uiText(R.string.title_image_preview)
        } else Modifier)) {
            key(data) {
                AndroidView(modifier = Modifier.fillMaxSize(),
                    factory = { context -> createAnimatedSvgWebView(context, data, onError = { failed = true }).also {
                        holder.value = it
                        if (!visible || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) it.onPause()
                    } },
                    onRelease = { view -> holder.value = null; releaseAnimatedSvgWebView(view) })
            }
            // Compose handles taps and vertical scrolling while the image plays underneath.
            onOpenFullscreen?.let { open -> Box(Modifier.fillMaxSize().clickable(onClick = open)
                .semantics { contentDescription = uiText(R.string.title_image_preview) }) }
            if (failed) {
                Text(uiText(R.string.notice_image_load_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private class SvgWebViewState { var active = true }

/** A self-contained animated image: no JavaScript, app bridge, file/content access or network resources. */
internal fun createAnimatedSvgWebView(
    context: Context, document: AnimatedSvgDocument,
    onReady: () -> Unit = {}, onError: () -> Unit = {},
): WebView = WebView(context).apply {
    val state = SvgWebViewState()
    tag = state
    val pageUrl = SVG_PREVIEW_ORIGIN + "preview.html"
    val imageUrl = SVG_PREVIEW_ORIGIN + "image.svg"
    val html = animatedSvgHtml().toByteArray(Charsets.UTF_8)
    val image = document.imageSource.toByteArray(Charsets.UTF_8)
    // AndroidView's default WRAP_CONTENT makes WebView use a zero-height layout
    // viewport, so the document's percentage-height image collapses despite
    // Compose measuring the preview at a nonzero height.
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.javaScriptEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.setSupportZoom(false)
    settings.textZoom = 100
    isHorizontalScrollBarEnabled = false
    isVerticalScrollBarEnabled = false
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = true
        override fun onPageFinished(view: WebView, url: String?) { if (state.active && url == pageUrl) onReady() }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (state.active && (request.isForMainFrame || request.url.toString() == imageUrl)) onError()
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = when (request.url.toString()) {
            pageUrl -> WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(html))
            imageUrl -> WebResourceResponse(SVG_MIME_TYPE, "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; img-src data:; script-src 'none'; sandbox"),
                ByteArrayInputStream(image))
            else -> WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }
    loadUrl(pageUrl)
}

internal fun releaseAnimatedSvgWebView(view: WebView) {
    (view.tag as? SvgWebViewState)?.active = false
    view.stopLoading()
    view.destroy()
}
