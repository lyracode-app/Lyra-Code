package com.yukisoffd.lyracode.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

internal const val MAX_WIDGET_CHARS = 100_000
internal const val WIDGET_FENCE = "lyra-widget"
internal const val HTML_FILE_FENCE = "lyra-html"
private const val PAGE_URL = "https://widget.invalid/preview"
private const val POLICY = "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; " +
    "style-src 'unsafe-inline'; img-src data:; font-src data:; media-src data:; " +
    "connect-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; worker-src 'none'"

private const val HEADER = """<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>:root{color-scheme:light dark;font:16px system-ui}body{margin:8px;overflow-wrap:anywhere}*,*::before,*::after{box-sizing:border-box}img,svg,canvas,video,input,select{max-width:100%}button,input,select,textarea{font:inherit}input[type=range],[role=slider]{touch-action:none}</style>
<script>
(() => {
  // Peer connections use their own network stack outside fetch and resource loading.
  for (const key of ['RTCPeerConnection','webkitRTCPeerConnection']) {
    Object.defineProperty(window, key, {value: undefined, configurable: false, writable: false});
  }
  const report = console.info.bind(console);
  let gesture, ancestors = [], dragControl = false, lastMask;
  function reportPan() {
    if (!ancestors.length) return;
    let mask = dragControl ? 1 : 0;
    for (const el of ancestors) {
      if (el === document.scrollingElement || !/^(auto|scroll|overlay)$/.test(getComputedStyle(el).overflowY)) continue;
      if (el.scrollTop > 1) mask |= 2;
      if (el.scrollTop + el.clientHeight < el.scrollHeight - 1) mask |= 4;
    }
    if (mask !== lastMask) { lastMask = mask; report('lyra-pan:' + gesture + ':' + mask); }
  }
  window.__lyraPanTarget = (id, x, y) => {
    gesture = id; ancestors = []; dragControl = false; lastMask = -1;
    const viewport = window.visualViewport;
    let el = document.elementFromPoint(x * (viewport ? viewport.width : innerWidth), y * (viewport ? viewport.height : innerHeight));
    while (el) {
      ancestors.push(el);
      const action = getComputedStyle(el).touchAction;
      if (el.matches('input[type=range],[role=slider],canvas,[draggable=true]') || action === 'none') dragControl = true;
      el = el.parentElement || el.getRootNode().host;
    }
    reportPan();
  };
  addEventListener('scroll', reportPan, true);
  addEventListener('touchmove', e => { if (e.defaultPrevented) { dragControl = true; reportPan(); } }, {passive:true});
  // Chromium cancels DOM touches when its compositor takes over an inner scroller.
  // Keep observing its edges until the Android pointer stream actually ends.
  window.__lyraEndPan = id => { if (id === gesture) ancestors = []; };
  addEventListener('unhandledrejection', e => console.error('Unhandled rejection: ' + String(e.reason)));
  addEventListener('DOMContentLoaded', () => {
    let timer, last;
    function measure() {
      timer = null;
      const box = document.body.getBoundingClientRect();
      const root = document.documentElement;
      const natural = Math.max(box.bottom + scrollY, box.top + scrollY + document.body.scrollHeight)
        + (parseFloat(getComputedStyle(document.body).marginBottom) || 0);
      const height = Math.ceil(Math.max(natural, root.scrollHeight > root.clientHeight ? root.scrollHeight : 0));
      if (height !== last) { last = height; report('lyra-height:' + height); }
    }
    const schedule = () => { if (!timer) timer = setTimeout(measure, 100); };
    if (window.ResizeObserver) new ResizeObserver(schedule).observe(document.body);
    new MutationObserver(schedule).observe(document.body, {subtree:true, attributes:true, childList:true, characterData:true});
    addEventListener('resize', schedule);
    measure();
  });
})();
</script>
"""

/** A private, opaque-origin document. Only the initial main-frame GET is served.
 * Page diagnostics are inert strings; no native object is exposed to JavaScript. */
@SuppressLint("SetJavaScriptEnabled")
internal class WidgetWebView(
    context: Context,
    source: String,
    onHeight: (Int) -> Unit = {},
    private val onScrollEdges: (Int) -> Unit = {},
    onReady: () -> Unit = {},
    onError: (String) -> Unit,
) : WebView(context) {
    private var released = false
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail("WebView timed out during startup.") }
    private val report = onError
    private val gestures = WidgetTouchScroll(this)
    /** Chromium rounds CSS scroll extents and device offsets separately at fractional densities. */
    internal fun canPanVertically(direction: Int): Boolean {
        val remaining = if (direction < 0) scrollY else computeVerticalScrollRange() - height - scrollY
        return remaining > resources.displayMetrics.density.coerceAtLeast(1f)
    }

    private fun updateScrollEdges() {
        if (!released) onScrollEdges((if (canPanVertically(-1)) 1 else 0) or (if (canPanVertically(1)) 2 else 0))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.dispatch(event) { super.onTouchEvent(it) }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        updateScrollEdges()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { updateScrollEdges() }
    }

    private fun fail(reason: String) {
        if (released || failed) return
        failed = true
        handler.removeCallbacks(timeout)
        report(reason.take(600))
    }

    init {
        val bytes = (HEADER + source).toByteArray(Charsets.UTF_8)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true) // Default WebChromeClient refuses new windows.
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
        }
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setDownloadListener { _, _, _, _, _ -> }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (released || failed) return true
                val value = message.message()
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    fail("Line ${(message.lineNumber() - HEADER.count { it == '\n' }).coerceAtLeast(1)}: ${value.take(540)}")
                } else if (value.startsWith("lyra-height:") && value.length < 24) {
                    value.substringAfter(':').toIntOrNull()?.let { onHeight(it.coerceIn(80, 16_384)) }
                    post { updateScrollEdges() }
                } else if (value.startsWith("lyra-pan:") && value.length < 32) {
                    val parts = value.split(':')
                    if (parts.size == 3) gestures.report(parts[1].toIntOrNull(), parts[2].toIntOrNull())
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.isForMainFrame && request.method == "GET" && request.url.toString() == PAGE_URL) {
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf(
                        "Content-Security-Policy" to POLICY, "Cache-Control" to "no-store",
                        "Referrer-Policy" to "no-referrer",
                        "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()",
                    ), ByteArrayInputStream(bytes))
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url != PAGE_URL || released || failed) return
                evaluateJavascript("document.readyState") { state ->
                    if (!released && !failed && state == "\"complete\"") {
                        handler.removeCallbacks(timeout)
                        onReady()
                    }
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(error.description.toString())
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("WebView renderer stopped.")
                release()
                return true
            }
        }
        handler.postDelayed(timeout, 8000)
        loadUrl(PAGE_URL)
    }

    fun release() {
        if (released) return
        released = true
        gestures.finish()
        handler.removeCallbacks(timeout)
        (parent as? ViewGroup)?.removeView(this)
        destroy()
    }
}

/** Exercise initialization in the same engine and security policy as the visible widget. */
internal suspend fun validateWidget(context: Context, source: String) = withContext(Dispatchers.Main) {
    require(source.isNotBlank() && source.length <= MAX_WIDGET_CHARS) { "Widget must contain 1..$MAX_WIDGET_CHARS characters." }
    val ready = CompletableDeferred<Unit>()
    val error = CompletableDeferred<String>()
    val view = WidgetWebView(context, source, onReady = { ready.complete(Unit) }, onError = {
        error.complete(it)
        ready.complete(Unit)
    })
    try {
        val width = (360 * context.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, width)
        check(withTimeoutOrNull(10_000) { ready.await(); true } == true) { "WebView initialization timed out." }
        val reason = withTimeoutOrNull(200) { error.await() }
        check(reason == null) { "Widget failed to start. Repair the source and retry. WebView diagnostic: $reason" }
    } finally {
        view.release()
    }
}
