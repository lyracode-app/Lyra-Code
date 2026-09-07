package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.yukisoffd.lyracode.interaction.model.ScreenBounds
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import com.yukisoffd.lyracode.interaction.session.ManualControlStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/** Draggable conversation overlay with a separate non-touchable target highlight. */
internal class TaskHudController(
    private val context: Context,
    private val onConfirm: (com.yukisoffd.lyracode.interaction.model.ManualActionSelection) -> Unit,
    private val onCancelSelection: (com.yukisoffd.lyracode.interaction.model.ManualActionSelection) -> Unit,
    private val onStop: () -> Unit,
    private val onSubmit: (String) -> Unit,
    private val onPause: () -> Unit,
    private val onApproval: (String, Boolean) -> Unit = { _, _ -> },
    private val onClearContext: () -> Unit = {},
) {
    init {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Overlay views must share the main looper with InputMethodManager"
        }
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var pet: com.yukisoffd.lyracode.interaction.pet.DesktopPetWindow? = null
    private var chatVisible = true
    private var panelHost: FrameLayout? = null
    private var chatPanel: DeviceChatPanel? = null
    private var inputFocused = false
    private var highlight: HighlightView? = null
    private var highlightedBounds: ScreenBounds? = null
    private var latestState: ManualControlState? = null
    private var pendingPanelState: ManualControlState? = null
    private var lastPanelSignature: PanelSignature? = null
    private var panelTouchActive = false
    private var expanded = true
    private var panelX = dp(12)
    private var panelY = dp(96)
    private var panelWidth = minOf(dp(340), context.resources.displayMetrics.widthPixels - dp(24))
    private var panelHeight = minOf(dp(480), (context.resources.displayMetrics.heightPixels * .55f).roundToInt())
    private var keyboardTop: Int? = null
    private var bars = android.graphics.Insets.NONE

    private fun availableArea(): OverlayRect {
        val bounds = windowManager.currentWindowMetrics.bounds
        val top = bars.top + dp(6)
        val bottom = minOf(bounds.height() - bars.bottom, keyboardTop ?: bounds.height()) - dp(8)
        return OverlayRect(bars.left + dp(6), top,
            (bounds.width() - bars.left - bars.right - dp(12)).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }

    private fun visibleRect() = OverlayGeometry.fit(OverlayRect(panelX, panelY, panelWidth, panelHeight), availableArea())

    private fun updatePanelGeometry() {
        panelHost?.takeIf(View::isAttachedToWindow)?.let { host ->
            val next = panelLayoutParams()
            val old = host.layoutParams as WindowManager.LayoutParams
            if (old.x != next.x || old.y != next.y || old.width != next.width || old.height != next.height || old.flags != next.flags)
                windowManager.updateViewLayout(host, next)
        }
    }

    private fun rememberRect(rect: OverlayRect) {
        panelX = rect.x; panelY = rect.y; panelWidth = rect.width; panelHeight = rect.height
        updatePanelGeometry()
    }

    fun render(state: ManualControlState) {
        latestState = state
        if (!state.isActive()) {
            removeViews()
            return
        }

        if (state.approval != null || state.selection?.automatic == false) chatVisible = true
        if (pet == null) pet = com.yukisoffd.lyracode.interaction.pet.DesktopPetWindow(context, {
            chatVisible = !chatVisible
            if (!chatVisible) chatPanel?.releaseInput()
            panelHost?.visibility = if (chatVisible) View.VISIBLE else View.GONE
        }, onSubmit)
        pet?.render(state)
        // Create the non-touchable highlight layer first so the interactive panel always stays above it.
        ensureHighlightLayer()
        val selectedBounds = state.selection?.let { selection ->
            state.latestSnapshot?.nodes?.firstOrNull { it.handle == selection.elementHandle }?.let { node ->
                node.bounds
            }
        }
        if (selectedBounds == null) hideHighlight() else updateHighlight(selectedBounds)
        if (panelTouchActive) {
            pendingPanelState = state
        } else {
            updatePanel(state)
        }
    }

    fun destroy() = removeViews()

    private fun updatePanel(state: ManualControlState) {
        panelHost?.visibility = if (chatVisible) View.VISIBLE else View.GONE
        updatePanelGeometry()
        val signature = panelSignature(state)
        if (signature == lastPanelSignature) return
        val host = panelHost ?: TouchAwareFrameLayout(context).also { newHost ->
            runCatching { windowManager.addView(newHost, panelLayoutParams()) }
                .onSuccess { panelHost = newHost }
                .onFailure { Log.w(LOG_TAG, "Unable to add control panel", it) }
        }
        if (host === panelHost) {
            val panel = chatPanel ?: DeviceChatPanel(context, DragTouchListener(),
                onInputFocus = { focused ->
                    updateInputFocus(focused)
                }, onSubmit = onSubmit, onPause = onPause, onStop = onStop,
                onConfirm = onConfirm, onReject = onCancelSelection, onApproval = onApproval, onClearContext = onClearContext,
            ).also { chatPanel = it; host.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
            panel.render(state, expanded)
            lastPanelSignature = signature
        }
    }

    private fun panelSignature(state: ManualControlState) = PanelSignature(
        expanded, state.status, state.targetPackage, state.selection,
        state.latestSnapshot?.snapshotId, state.chat, state.approval,
    )

    private fun updateInputFocus(focused: Boolean) {
        if (inputFocused == focused) return
        inputFocused = focused
        panelHost?.takeIf(View::isAttachedToWindow)?.let { host ->
            runCatching { windowManager.updateViewLayout(host, panelLayoutParams()) }
                .onFailure { Log.w(LOG_TAG, "Unable to update overlay input focus", it) }
        }
    }

    private fun updateHighlight(bounds: ScreenBounds) {
        if (bounds.width <= 0 || bounds.height <= 0) {
            hideHighlight()
            return
        }
        ensureHighlightLayer()
        val view = highlight ?: return
        highlightedBounds = bounds
        view.visibility = View.VISIBLE
        runCatching { windowManager.updateViewLayout(view, highlightLayoutParams(bounds)) }
            .onFailure { Log.w(LOG_TAG, "Unable to position highlight", it) }

        // Some OEM window managers offset application overlays by an inset. Correct once using
        // the actual screen location without returning to a full-screen touch-through window.
        view.post {
            if (highlight !== view || highlightedBounds != bounds || view.visibility != View.VISIBLE) return@post
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val deltaX = bounds.left - location[0]
            val deltaY = bounds.top - location[1]
            if (deltaX != 0 || deltaY != 0) {
                val corrected = highlightLayoutParams(bounds).apply {
                    x += deltaX
                    y += deltaY
                }
                runCatching { windowManager.updateViewLayout(view, corrected) }
                    .onFailure { Log.w(LOG_TAG, "Unable to correct highlight position", it) }
            }
        }
    }

    private fun ensureHighlightLayer() {
        if (highlight != null) return
        val view = HighlightView(context).apply { visibility = View.INVISIBLE }
        runCatching { windowManager.addView(view, highlightLayoutParams(null)) }
            .onSuccess { highlight = view }
            .onFailure { Log.w(LOG_TAG, "Unable to add highlight layer", it) }
    }

    private fun hideHighlight() {
        highlightedBounds = null
        highlight?.visibility = View.INVISIBLE
    }

    private fun removeHighlight() {
        highlight?.let { view ->
            runCatching { windowManager.removeView(view) }
                .onFailure { Log.w(LOG_TAG, "Unable to remove highlight layer", it) }
        }
        highlight = null
        highlightedBounds = null
    }

    private fun removeViews() {
        pet?.destroy(); pet = null
        panelHost?.let { runCatching { windowManager.removeView(it) } }
        panelHost = null
        chatPanel = null
        inputFocused = false
        keyboardTop = null
        latestState = null
        pendingPanelState = null
        lastPanelSignature = null
        panelTouchActive = false
        removeHighlight()
    }

    private fun panelLayoutParams(): WindowManager.LayoutParams {
        val rect = visibleRect()
        return WindowManager.LayoutParams(
            rect.width, rect.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (inputFocused) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rect.x; y = rect.y
            // Fit explicitly: resizing the native message area preserves the editor and its IME session.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
        }
    }

    private fun highlightLayoutParams(bounds: ScreenBounds?) = WindowManager.LayoutParams(
        bounds?.width?.coerceAtLeast(1) ?: 1,
        bounds?.height?.coerceAtLeast(1) ?: 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds?.left ?: 0
        y = bounds?.top ?: 0
        alpha = HIGHLIGHT_WINDOW_ALPHA
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private inner class DragTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = visibleRect().x
                    startY = visibleRect().y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val current = visibleRect()
                    val moved = OverlayGeometry.fit(current.copy(x = (startX + event.rawX - downRawX).roundToInt(),
                        y = (startY + event.rawY - downRawY).roundToInt()), availableArea())
                    panelX = moved.x; panelY = moved.y
                    updatePanelGeometry()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    return true
                }
            }
            return false
        }
    }

    private inner class TouchAwareFrameLayout(context: Context) : FrameLayout(context) {
        private var resizing = false
        private var resizeLeft = false
        private var resizeTop = false
        private var downX = 0f
        private var downY = 0f
        private var start = OverlayRect(0, 0, 1, 1)
        private var imeAnimating = false
        private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OverlayPalette(context).muted; alpha = 150; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
        }
        private val applyGeometry = Runnable { updatePanelGeometry() }

        init {
            setOnApplyWindowInsetsListener { _, insets ->
                updateInsets(insets)
                insets
            }
            setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) imeAnimating = true
                }
                override fun onProgress(insets: android.view.WindowInsets, animations: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets {
                    updateInsets(insets)
                    return insets
                }
                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) {
                        imeAnimating = false
                        rootWindowInsets?.let(::updateInsets)
                    }
                }
            })
        }

        private fun updateInsets(insets: android.view.WindowInsets) {
            bars = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val visible = insets.isVisible(android.view.WindowInsets.Type.ime())
            val overlap = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            if (visible && overlap > 0 && height > 0) {
                val location = IntArray(2); getLocationOnScreen(location)
                val top = location[1] + height - overlap
                keyboardTop = minOf(keyboardTop ?: Int.MAX_VALUE, top)
            } else if (!visible && !imeAnimating) keyboardTop = null
            removeCallbacks(applyGeometry)
            post(applyGeometry)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            post(applyGeometry)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val inset = dp(9).toFloat(); val length = dp(10).toFloat()
            for (left in listOf(true, false)) for (top in listOf(true, false)) {
                val x = if (left) inset else width - inset
                val y = if (top) inset else height - inset
                canvas.drawLine(x, y, x + if (left) length else -length, y, gripPaint)
                canvas.drawLine(x, y, x, y + if (top) length else -length, gripPaint)
            }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                panelTouchActive = true
                requestUnbufferedDispatch(event)
                resizing = (event.x < dp(28) || event.x > width - dp(28)) &&
                    (event.y < dp(28) || event.y > height - dp(28))
                if (resizing) {
                    resizeLeft = event.x < width / 2; resizeTop = event.y < height / 2
                    downX = event.rawX; downY = event.rawY; start = visibleRect()
                }
            }
            val handled = if (resizing) {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) rememberRect(OverlayGeometry.resize(start, resizeLeft, resizeTop,
                    (event.rawX - downX).roundToInt(), (event.rawY - downY).roundToInt(), availableArea(), dp(240), dp(240)))
                true
            } else super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                resizing = false
                panelTouchActive = false
                val pending = pendingPanelState
                pendingPanelState = null
                if (pending != null) post { render(pending) }
            }
            return handled
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(applyGeometry)
            super.onDetachedFromWindow()
        }
    }

    private data class PanelSignature(
        val expanded: Boolean,
        val status: ManualControlStatus,
        val targetPackage: String?,
        val selection: com.yukisoffd.lyracode.interaction.model.ManualActionSelection?,
        val snapshotId: String?,
        val chat: com.yukisoffd.lyracode.interaction.session.DeviceChatState,
        val approval: com.yukisoffd.lyracode.interaction.session.DeviceApproval?,
    )

    private class HighlightView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(77, 208, 225)
            style = Paint.Style.STROKE
            strokeWidth = 6f * resources.displayMetrics.density
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = paint.strokeWidth / 2f
            canvas.drawRect(
                inset,
                inset,
                width - inset,
                height - inset,
                paint,
            )
        }
    }

    private companion object {
        const val LOG_TAG = "LyraManualControl"
        const val HIGHLIGHT_WINDOW_ALPHA = 0.79f
    }
}
