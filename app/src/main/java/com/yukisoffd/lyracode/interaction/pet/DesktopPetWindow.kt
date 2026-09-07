package com.yukisoffd.lyracode.interaction.pet

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.*
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.abs

/** Separate, non-focusable window. Script has no filesystem, network, tool or approval access. */
internal class DesktopPetWindow(private val context: Context, private val toggleChat: () -> Unit,
    private val requestTask: (String) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var fallbackView: android.widget.TextView? = null
    private var x = 0
    private var y = (context.resources.displayMetrics.heightPixels * .7f).toInt()
    private var size = 72
    private var lastDisplaySize = ""
    private var docked = false
    private var dragging = false
    private var lastTouch = 0L
    private var lastMessage = 0L
    private var packageVersion = ""
    private var runtime: PetWebRuntime? = null
    private var dockDelay = 3500L
    private var autoDock = true
    private var dockFraction = .5
    private var dockOpacity = .42f
    private var activeOpacity = 1f
    private var lastPetEvent: String? = null
    private var lastState = ""
    private var lastConfig = ""
    private var script = JSONObject()
    private var state: ManualControlState? = null
    private var useDefault = false
    private var destroyed = false
    private val dock = Runnable { if (!dragging && !destroyed) dock() }
    private val refresh = object : Runnable {
        override fun run() {
            if (destroyed) return
            updateConfiguration()
            handler.postDelayed(this, 1000)
        }
    }
    init { runCatching { create() }.onFailure { showNativeFallback() }; handler.post(refresh); scheduleDock() }
    @Suppress("SetJavaScriptEnabled")
    private fun create() {
        if (android.os.Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":manual_control_overlay") && !dataDirectorySet) {
            WebView.setDataDirectorySuffix("desktop-pet-overlay"); dataDirectorySet = true
        }
        val view = WebView(context)
        web = view
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false; view.isHorizontalScrollBarEnabled = false
        view.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            domStorageEnabled = false; databaseEnabled = false; blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.addJavascriptInterface(Bridge(), "LyraPetHost")
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                runtime?.intercept(request) ?: WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            override fun onPageFinished(view: WebView?, url: String?) { lastState = ""; lastConfig = ""; updateConfiguration(); emitState() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handler.post { if (!destroyed && web === view) fallback() }; return true
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) view.setWebViewRenderProcessClient(object : WebViewRenderProcessClient() {
            override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {}
            override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) { renderer?.terminate() }
        })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        val settings = Runnable {
            if (!dragging && !destroyed) { lastTouch = 0L; openSettings() }
        }
        view.setOnTouchListener { _, e ->
            if (com.yukisoffd.lyracode.BuildConfig.DEBUG) android.util.Log.d("LyraPet", "touch=${e.actionMasked} docked=$docked")
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = x; startY = y; dragging = false
                    lastTouch = SystemClock.elapsedRealtime(); handler.removeCallbacks(dock); handler.postDelayed(settings, 700)
                    emit("pointerdown", JSONObject().put("x", e.x).put("y", e.y))
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) + abs(e.rawY - downY) > dp(8)) {
                        dragging = true; docked = false; handler.removeCallbacks(settings)
                        x = (startX + e.rawX - downX).toInt(); y = (startY + e.rawY - downY).toInt()
                        constrain(); position(); emit("drag", JSONObject().put("x", x).put("y", y))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(settings)
                    if (lastTouch != 0L) lastTouch = SystemClock.elapsedRealtime()
                    if (!dragging && lastTouch != 0L) {
                        lastTouch = SystemClock.elapsedRealtime()
                        if (docked) { docked = false; constrain(); position(); emit("reveal") }
                        else { emit("tap"); toggleChat() }
                    } else emit("dragend")
                    dragging = false; scheduleDock()
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(settings); dragging = false; scheduleDock() }
            }
            true
        }
        manager.addView(view, params())
        packageVersion = ""
        updateConfiguration()
    }
    private fun fallback() {
        web?.let { runCatching { manager.removeViewImmediate(it) }; it.destroy() }; web = null
        // A failing custom renderer cannot make the controls permanently inaccessible.
        if (useDefault) showNativeFallback() else {
            useDefault = true
            runCatching { create() }.onFailure { showNativeFallback() }
        }
    }
    private fun showNativeFallback() {
        web?.let { runCatching { manager.removeViewImmediate(it) }; runCatching { it.destroy() } }; web = null
        if (fallbackView != null) return
        val view = android.widget.TextView(context).apply {
            text = "🐱"; textSize = 36f; gravity = Gravity.CENTER
            contentDescription = "Lyra 桌宠：点击打开对话，长按设置"
            setOnClickListener { if (docked) { docked = false; constrain(); position() } else toggleChat() }
            setOnLongClickListener { openSettings(); true }
        }
        fallbackView = view; manager.addView(view, params())
    }
    private fun scheduleDock() {
        handler.removeCallbacks(dock)
        if (autoDock) handler.postDelayed(dock, dockDelay)
    }
    private fun updateConfiguration() {
        val manifest = if (useDefault) DevicePetStore.defaultManifest(context) else DevicePetStore.load(context)
        val options = DevicePetStore.effectiveOptions(context, manifest, useDefault)
        val nextDelay = options.optLong("dockDelayMs", 3500).coerceIn(500, 60000)
        val nextDock = options.optBoolean("autoDock", true)
        if (nextDelay != dockDelay || nextDock != autoDock) {
            dockDelay = nextDelay; autoDock = nextDock; scheduleDock()
            if (!autoDock && docked) { docked = false; constrain(); position(); emit("reveal") }
        }
        val nextFraction = options.optDouble("dockFraction", .5).coerceIn(0.0, .8)
        val nextOpacity = options.optDouble("dockOpacity", .42).toFloat().coerceIn(.1f, 1f)
        val nextActiveOpacity = options.optDouble("activeOpacity", 1.0).toFloat().coerceIn(.2f, 1f)
        val nextSize = options.optInt("size", 72).coerceIn(40, 240)
        val displaySize = "${width()}:${context.resources.displayMetrics.heightPixels}"
        if (nextSize != size || displaySize != lastDisplaySize || nextFraction != dockFraction ||
            nextOpacity != dockOpacity || nextActiveOpacity != activeOpacity) {
            size = nextSize; lastDisplaySize = displaySize; dockFraction = nextFraction
            dockOpacity = nextOpacity; activeOpacity = nextActiveOpacity
            if (docked) { constrain(); dock() } else { constrain(); position() }
        }
        val version = DevicePetStore.version(context) + ":" + options.optBoolean("soundEnabled", false)
        if (version != packageVersion) {
            packageVersion = version; script = manifest
            web?.evaluateJavascript("window.__lyraDispose?.()", null)
            if (script.optInt("apiVersion") == 2) {
                runtime = PetWebRuntime(context, script, useDefault)
                web?.loadUrl("https://lyra-pet.invalid/__lyra__/index.html")
            } else {
                runtime = null
                val prefix = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; media-src data:; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent;touch-action:none}</style><script>window.lyraPet={emit:(type,data={})=>LyraPetHost.postMessage(JSON.stringify({type,data}))};</script>"""
                web?.loadDataWithBaseURL("https://lyra-pet.invalid/", prefix + script.getString("html"), "text/html", "UTF-8", null)
            }
        }
        val config = DevicePetStore.controls(script, options).put("size", size)
            .put("autoDock", autoDock).put("dockDelayMs", dockDelay).put("dockFraction", dockFraction)
            .put("dockOpacity", dockOpacity).put("activeOpacity", activeOpacity)
            .put("soundEnabled", options.optBoolean("soundEnabled", false))
            .put("volume", options.optDouble("volume", .5).coerceIn(0.0, 1.0))
        if (config.toString() != lastConfig) { lastConfig = config.toString(); emit("config", config) }
    }
    fun render(state: ManualControlState) { this.state = state; emitState() }
    private fun emitState() {
        val current = state ?: return
        current.petEvent?.takeIf { it != lastPetEvent }?.let {
            lastPetEvent = it; runCatching { emit("operationResult", JSONObject(it)) }
        }
        val name = when {
            current.approval != null || current.selection != null -> "approval"
            current.status.name in setOf("EXECUTING", "VERIFYING") || current.chat.status.startsWith("执行中") -> "executing"
            current.chat.running -> "thinking"
            current.chat.status == "已完成" -> "completed"
            current.chat.status.startsWith("任务停止") -> "error"
            else -> "idle"
        }
        val data = JSONObject().put("state", name).put("docked", docked)
            .put("packageName", current.targetPackage.orEmpty())
        if (data.toString() != lastState) { lastState = data.toString(); emit("state", data) }
    }
    private fun emit(type: String, data: JSONObject = JSONObject()) {
        val payload = JSONObject().put("type", type).put("data", data).toString()
        web?.evaluateJavascript("window.dispatchEvent(new CustomEvent('lyrapet',{detail:$payload}));", null)
    }
    private fun dock() {
        docked = true
        x = if (x + dp(size) / 2 < width() / 2) -(dp(size) * dockFraction).toInt() else width() - (dp(size) * (1 - dockFraction)).toInt()
        position(); emit("dock", JSONObject().put("side", if (x < 0) "left" else "right")); emitState()
    }
    private fun constrain() {
        x = x.coerceIn(0, (width() - dp(size)).coerceAtLeast(0))
        y = y.coerceIn(dp(28), (context.resources.displayMetrics.heightPixels - dp(size + 32)).coerceAtLeast(dp(28)))
    }
    private fun position() { (web ?: fallbackView)?.let {
        // Oplus rejects input to low-alpha overlay windows. Fade the content while keeping
        // the window input surface fully eligible for touch dispatch.
        it.alpha = if (docked) dockOpacity else activeOpacity
        runCatching { manager.updateViewLayout(it, params()) }
    } }
    private fun params() = WindowManager.LayoutParams(dp(size), dp(size), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = this@DesktopPetWindow.x; y = this@DesktopPetWindow.y; alpha = 1f }
    private fun width() = context.resources.displayMetrics.widthPixels
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun openSettings() { context.startActivity(Intent(context, DesktopPetSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun destroy() {
        web?.evaluateJavascript("window.__lyraDispose?.()", null)
        destroyed = true; handler.removeCallbacksAndMessages(null)
        fallbackView?.let { runCatching { manager.removeViewImmediate(it) } }; fallbackView = null
        web?.let { runCatching { manager.removeViewImmediate(it) }; it.removeJavascriptInterface("LyraPetHost"); it.destroy() }; web = null
    }
    private companion object { var dataDirectorySet = false }
    private inner class Bridge {
        @JavascriptInterface fun postMessage(raw: String) {
            if (raw.length > 4096) return
            val now = SystemClock.elapsedRealtime()
            synchronized(this) { if (now - lastMessage < 100) return; lastMessage = now }
            handler.post {
                if (destroyed) return@post
                runCatching {
                    val message = JSONObject(raw); val data = message.optJSONObject("data") ?: JSONObject()
                    when (message.optString("type")) {
                        "move" -> if (!dragging && !docked) {
                            x += dp(data.optInt("dx").coerceIn(-8, 8)); y += dp(data.optInt("dy").coerceIn(-8, 8)); constrain(); position()
                        }
                        "reveal" -> if (!dragging) { docked = false; constrain(); position(); emit("reveal"); handler.removeCallbacks(dock); scheduleDock() }
                        "dock" -> if (!dragging) dock()
                        "settings" -> if (now - lastTouch in 0..700 && lastTouch != 0L) openSettings()
                        "requestUninstall" -> if (now - lastTouch in 0..700 && lastTouch != 0L) {
                            val pkg = data.optString("packageName")
                            if (Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(pkg)) {
                                lastTouch = 0L
                                requestTask("请卸载第三方应用 $pkg。通过 pm uninstall --user 0 执行，等待我两次确认。")
                                emit("operationRequested", JSONObject().put("packageName", pkg))
                            }
                        }
                    }
                }
            }
        }
    }
}
