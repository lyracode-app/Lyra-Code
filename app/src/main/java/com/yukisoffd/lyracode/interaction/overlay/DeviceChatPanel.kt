package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.yukisoffd.lyracode.interaction.session.ManualControlState

/** Stable view hierarchy: streaming updates never recreate the editor or lose its IME state. */
internal class DeviceChatPanel(
    context: Context,
    onDrag: View.OnTouchListener,
    private val onInputFocus: (Boolean) -> Unit,
    private val onSubmit: (String) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onConfirm: (com.yukisoffd.lyracode.interaction.model.ManualActionSelection) -> Unit,
    onReject: (com.yukisoffd.lyracode.interaction.model.ManualActionSelection) -> Unit,
    onApproval: (String, Boolean) -> Unit = { _, _ -> },
    onClearContext: () -> Unit = {},
) : LinearLayout(context) {
    private val palette = OverlayPalette(context)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val heading = label(18, palette.text)
    private val status = label(12, palette.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val transcript = DeviceChatTranscript(context)
    private val approval = label(13, palette.accent)
    private val approvalScroll = ScrollView(context).apply { addView(approval) }
    private val approvalButtons = LinearLayout(context)
    private lateinit var composerContainer: LinearLayout
    private val input = ImeAwareEditText(context, ::releaseInput).apply {
        hint = "描述任务，可跨多个应用执行"
        background = GradientDrawable().apply { setColor(palette.container); cornerRadius = dp(24).toFloat() }
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setHintTextColor(palette.muted)
        setTextColor(palette.text)
        textSize = 14f
        minLines = 1
        maxLines = 3
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
    }
    private val send = button("↑") {
        val text = input.text.toString().trim()
        if (text.isNotEmpty()) {
            releaseInput()
            onSubmit(text)
        }
    }
    private val pause = button("Ⅱ", onPause)
    private var renderedApproval: com.yukisoffd.lyracode.interaction.session.DeviceApproval? = null
    private var lastTranscript = ""
    private var renderedSelection: com.yukisoffd.lyracode.interaction.model.ManualActionSelection? = null

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(12))
        elevation = dp(8).toFloat()
        background = GradientDrawable().apply { setColor(palette.surface); cornerRadius = dp(26).toFloat() }
        val header = LinearLayout(context)
        header.addView(heading.apply {
            text = "Lyra"
            setPadding(dp(4), dp(10), 0, dp(10))
            setOnTouchListener(onDrag)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3); topMargin = dp(6) })
        header.addView(button("清空上下文") { releaseInput(); input.text.clear(); onClearContext() }.apply { contentDescription = "清空上下文，开始新任务" }, LayoutParams(dp(96), LayoutParams.WRAP_CONTENT))
        addView(header)
        body.addView(status)
        body.addView(transcript, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(approvalScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(110)))
        approvalButtons.addView(button("确认这一步") { releaseInput(); renderedApproval?.let { onApproval(it.id, true) } ?: renderedSelection?.let(onConfirm) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3); topMargin = dp(6) })
        approvalButtons.addView(button("拒绝") { releaseInput(); renderedApproval?.let { onApproval(it.id, false) } ?: renderedSelection?.let(onReject) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3); topMargin = dp(6) })
        body.addView(approvalButtons)
        val composer = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { setColor(palette.container); cornerRadius = dp(24).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        input.background = null
        composer.addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context).apply { gravity = android.view.Gravity.END }
        send.contentDescription = "发送任务"
        pause.contentDescription = "暂停任务"
        listOf(send, pause).forEach { control ->
            control.setTextColor(palette.surface)
            control.textSize = 22f
            control.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33777777),
                GradientDrawable().apply { setColor(palette.text); shape = GradientDrawable.OVAL }, null)
        }
        actions.addView(pause, LayoutParams(dp(44), dp(44)))
        actions.addView(send, LayoutParams(dp(44), dp(44)))
        composer.addView(actions)
        composerContainer = composer
        body.addView(composer)
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        input.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && input.isEnabled) {
                onInputFocus(true)
                input.requestFocus()
                input.beginInput()
            }
            false
        }
    }

    fun render(state: ManualControlState, expanded: Boolean) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        val chat = state.chat
        heading.text = "Lyra"
        if (chat.running && chat.messages.lastOrNull { it.role == "user" }?.text == input.text.toString().trim()) input.text.clear()
        status.text = listOf(chat.status, state.agentTargetPackage ?: state.targetPackage ?: "等待目标 App",
            chat.providerLabel).filter(String::isNotBlank).joinToString("\n")
        transcript.render(chat)
        val selection = state.selection
        renderedSelection = selection
        renderedApproval = state.approval
        val awaiting = state.approval != null || (selection != null && !selection.automatic && state.status == com.yukisoffd.lyracode.interaction.session.ManualControlStatus.TARGET_SELECTED)
        composerContainer.visibility = if (awaiting) View.GONE else View.VISIBLE
        if (awaiting && input.hasFocus()) releaseInput()
        approvalScroll.visibility = if (awaiting) View.VISIBLE else View.GONE
        approvalButtons.visibility = if (awaiting) View.VISIBLE else View.GONE
        if (selection != null) {
            val node = state.latestSnapshot?.nodes?.firstOrNull { it.handle == selection.elementHandle }
            val action = when (selection.action) {
                com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.ACTIVATE -> "点击"
                com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.SCROLL_FORWARD -> "向前滚动"
                com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.SCROLL_BACKWARD -> "向后滚动"
                com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.SET_TEXT -> "填写文本"
            }
            approval.text = (if (selection.confirmationStage == 2) "二次确认：此操作可能无法撤销\n" else "") + "$action · ${node?.hintText ?: node?.contentDescription ?: node?.text ?: node?.role ?: selection.elementHandle}\n${selection.expectedPackage}" +
                (selection.inputText?.let { "\n将替换输入框内容（${it.length} 字符），不会发送或提交：\n「$it」" } ?: "")
        }
        state.approval?.let { a -> approval.text = "${a.title} · ${if (a.stage == 2) "二次确认（不可撤销）" else "确认操作"}\n${a.packageName.orEmpty()}\n${a.detail}" }
        input.isEnabled = true
        send.isEnabled = !chat.running && state.targetPackage != null
        pause.isEnabled = chat.running
        pause.visibility = if (chat.running) View.VISIBLE else View.GONE
        send.visibility = if (chat.running) View.GONE else View.VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = View.MeasureSpec.getSize(heightMeasureSpec)
        val compact = View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED && available < dp(360)
        status.visibility = if (compact) View.GONE else View.VISIBLE
        val lines = if (compact) 1 else 3
        if (input.maxLines != lines) input.maxLines = lines
        val previewHeight = if (compact) dp(60) else dp(90)
        if (approvalScroll.layoutParams.height != previewHeight)
            approvalScroll.layoutParams = approvalScroll.layoutParams.apply { height = previewHeight }
        // Standalone previews have no window constraint; real overlays always provide an exact size.
        val boundedHeight = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
            View.MeasureSpec.makeMeasureSpec(dp(480), View.MeasureSpec.EXACTLY) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, boundedHeight)
    }

    fun releaseInput() {
        input.completeInput()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        onInputFocus(false)
    }

    private fun label(size: Int, color: Int) = TextView(context).apply {
        textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(title: String, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setTextColor(palette.accent)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2270a4ff),
            GradientDrawable().apply { setColor(palette.container); cornerRadius = dp(24).toFloat() }, null)
        minimumHeight = dp(44); setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { action() }
    }

    /**
     * A focusable application overlay receives Back after the IME. Consume it here so dismissing
     * the keyboard cannot become a Back action for the overlay/underlying app. Insets also cover
     * keyboards whose own collapse button hides the IME without sending KEYCODE_BACK.
     */
    internal class ImeAwareEditText(
        context: Context,
        private val onImeDismissed: () -> Unit,
    ) : EditText(context) {
        private var imeWasVisible = false
        private var dismissRequested = false
        private var inputSessionActive = false
        private val imeAnimations = mutableSetOf<WindowInsetsAnimation>()
        private val showKeyboard = Runnable { showKeyboardWhenReady() }
        private val finishDismiss = Runnable { finishDismissIfReady() }
        private var unregisterBackCallback: (() -> Unit)? = null
        private val imeMonitor = object : Runnable {
            override fun run() {
                if (!inputSessionActive) return
                if (hasFocus() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val visible = rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                    if (visible) markImeVisible()
                    else if (imeWasVisible || dismissRequested) finishDismissIfReady()
                }
                if (inputSessionActive) postDelayed(this, IME_MONITOR_INTERVAL_MILLIS)
            }
        }

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val visible = insets.isVisible(WindowInsets.Type.ime())
                    when {
                        visible && inputSessionActive -> markImeVisible()
                        inputSessionActive && (imeWasVisible || dismissRequested) -> post(finishDismiss)
                    }
                    view.onApplyWindowInsets(insets)
                }
                setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0) imeAnimations.add(animation)
                    }

                    override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>) = insets

                    override fun onEnd(animation: WindowInsetsAnimation) {
                        imeAnimations.remove(animation)
                        if (inputSessionActive) post(finishDismiss)
                    }
                })
            }
        }

        fun beginInput() {
            // Subsequent touches while editing must not reset the observed IME state or queue
            // another show request that can race with the keyboard's own collapse button.
            if (inputSessionActive) return
            inputSessionActive = true
            imeWasVisible = false
            dismissRequested = false
            removeCallbacks(finishDismiss)
            registerBackCallback()
            removeCallbacks(imeMonitor)
            post(imeMonitor)
            post(showKeyboard)
            postDelayed(showKeyboard, IME_SHOW_RETRY_MILLIS)
        }

        fun completeInput() {
            inputSessionActive = false
            imeWasVisible = false
            dismissRequested = false
            removeCallbacks(imeMonitor)
            removeCallbacks(showKeyboard)
            removeCallbacks(finishDismiss)
            unregisterBackCallback?.invoke()
            unregisterBackCallback = null
        }

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dismissNow()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dismissNow()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        private fun dismissNow() {
            if (!inputSessionActive || dismissRequested) return
            dismissRequested = true
            removeCallbacks(showKeyboard)
            if (!isAttachedToWindow || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                finishInputDismissal()
                return
            }
            windowInsetsController?.hide(WindowInsets.Type.ime())
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
            post(finishDismiss)
        }

        private fun showKeyboardWhenReady() {
            if (!inputSessionActive || dismissRequested || imeWasVisible || !hasFocus() || !isAttachedToWindow) return
            registerBackCallback()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowInsetsController?.show(WindowInsets.Type.ime())
            }
            context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }

        private fun markImeVisible() {
            imeWasVisible = true
            removeCallbacks(showKeyboard)
        }

        private fun finishDismissIfReady() {
            if (!inputSessionActive || (!imeWasVisible && !dismissRequested)) return
            // Insets report the final hidden state BEFORE the hide animation finishes. Changing
            // FLAG_NOT_FOCUSABLE then changes the IME target while its Surface is still animating.
            if (imeAnimations.isNotEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) return
            finishInputDismissal()
        }

        private fun finishInputDismissal() {
            completeInput()
            onImeDismissed()
        }

        private fun registerBackCallback() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && unregisterBackCallback == null) {
                unregisterBackCallback = Api33BackCallback.register(this, ::dismissNow)
            }
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && inputSessionActive) {
                registerBackCallback()
                removeCallbacks(imeMonitor)
                post(imeMonitor)
                post(showKeyboard)
            }
        }

        override fun onDetachedFromWindow() {
            completeInput()
            imeAnimations.clear()
            super.onDetachedFromWindow()
        }

        private companion object {
            const val IME_MONITOR_INTERVAL_MILLIS = 100L
            const val IME_SHOW_RETRY_MILLIS = 120L
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private object Api33BackCallback {
            fun register(view: View, onBack: () -> Unit): (() -> Unit)? {
                val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
                val callback = OnBackInvokedCallback(onBack)
                dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    callback,
                )
                return { dispatcher.unregisterOnBackInvokedCallback(callback) }
            }
        }
    }
}
