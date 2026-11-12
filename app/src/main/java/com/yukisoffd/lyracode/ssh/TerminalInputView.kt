package com.yukisoffd.lyracode.ssh

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.Editable
import android.text.Selection
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * Text-capable IME endpoint. ASCII still reaches modal editors immediately; CJK candidate text
 * stays local until committed, so intermediate Korean syllables never become remote commands.
 */
class TerminalInputView(context: Context) : EditText(context) {
    var onText: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onEnter: () -> Unit = {}
    var onTerminalKey: (String) -> Unit = {}
    var onComposition: (String) -> Unit = {}

    private var inputGeneration = 0L

    private val composition = TerminalCompositionState(
        sendText = { dispatchText(it) },
        sendBackspace = { onBackspace() },
        onComposition = { onComposition(it) },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isSingleLine = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setTextColor(Color.TRANSPARENT)
        setHintTextColor(Color.TRANSPARENT)
        setBackgroundColor(Color.TRANSPARENT)
        background = null
        isCursorVisible = false
        setPadding(0, 0, 0, 0)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Closing and reopening the keyboard may destroy the old InputConnection without first
        // calling finishComposingText(). Its stale word must not affect the next IME session.
        composition.finish()
        BaseInputConnection.removeComposingSpans(text)
        if (text.isNotEmpty()) text.clear()
        Log.d(TAG, "input connection created")
        outAttrs.inputType = inputType
        outAttrs.imeOptions = imeOptions
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        val connectionGeneration = ++inputGeneration
        return object : BaseInputConnection(this@TerminalInputView, true) {
            override fun getEditable(): Editable = this@TerminalInputView.text

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (connectionGeneration != inputGeneration) return false
                Log.d(TAG, "commitText chars=${text?.length ?: 0}")
                composition.commit(text?.toString().orEmpty())
                super.commitText(text, newCursorPosition)
                editable.clear()
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (connectionGeneration != inputGeneration) return false
                Log.d(TAG, "setComposingText chars=${text?.length ?: 0}")
                composition.update(text?.toString().orEmpty())
                super.setComposingText(text, newCursorPosition)
                return true
            }

            override fun finishComposingText(): Boolean {
                if (connectionGeneration != inputGeneration) return false
                Log.d(TAG, "finishComposingText")
                composition.finish()
                super.finishComposingText()
                editable.clear()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (connectionGeneration != inputGeneration) return false
                Log.d(TAG, "deleteSurroundingText before=$beforeLength after=$afterLength")
                composition.deleteBefore(beforeLength, inCodePoints = false)
                syncComposingEditable()
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                if (connectionGeneration != inputGeneration) return false
                Log.d(TAG, "deleteSurroundingTextInCodePoints before=$beforeLength after=$afterLength")
                composition.deleteBefore(beforeLength)
                syncComposingEditable()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (connectionGeneration != inputGeneration) return false
                Log.d(TAG, "sendKeyEvent action=${event.action} keyCode=${event.keyCode}")
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return dispatchTerminalKey(event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                if (connectionGeneration != inputGeneration) return false
                Log.d(TAG, "performEditorAction action=$actionCode")
                finishComposingInput()
                onEnter()
                return true
            }

            private fun syncComposingEditable() {
                BaseInputConnection.removeComposingSpans(editable)
                editable.replace(0, editable.length, composition.pendingText)
                Selection.setSelection(editable, editable.length)
                if (editable.isNotEmpty()) super.setComposingRegion(0, editable.length)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = dispatchTerminalKey(event)

    private fun dispatchText(value: String) {
        Log.d(TAG, "dispatchText chars=${value.length}")
        var segmentStart = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\r' || character == '\n') {
                if (index > segmentStart) onText(value.substring(segmentStart, index))
                onEnter()
                // Clipboard text commonly contains CRLF; it represents one terminal Enter.
                if (character == '\r' && index + 1 < value.length && value[index + 1] == '\n') {
                    index++
                }
                segmentStart = index + 1
            }
            index++
        }
        if (segmentStart < value.length) onText(value.substring(segmentStart))
    }

    private fun dispatchTerminalKey(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> dispatchControlSequence("\u001b")
        KeyEvent.KEYCODE_TAB -> dispatchControlSequence("\t")
        KeyEvent.KEYCODE_DPAD_UP -> dispatchControlSequence("\u001b[A")
        KeyEvent.KEYCODE_DPAD_DOWN -> dispatchControlSequence("\u001b[B")
        KeyEvent.KEYCODE_DPAD_RIGHT -> dispatchControlSequence("\u001b[C")
        KeyEvent.KEYCODE_DPAD_LEFT -> dispatchControlSequence("\u001b[D")
        KeyEvent.KEYCODE_MOVE_HOME -> dispatchControlSequence("\u001b[H")
        KeyEvent.KEYCODE_MOVE_END -> dispatchControlSequence("\u001b[F")
        KeyEvent.KEYCODE_PAGE_UP -> dispatchControlSequence("\u001b[5~")
        KeyEvent.KEYCODE_PAGE_DOWN -> dispatchControlSequence("\u001b[6~")
        KeyEvent.KEYCODE_FORWARD_DEL -> dispatchControlSequence("\u001b[3~")
        KeyEvent.KEYCODE_DEL -> {
            composition.deleteBefore(1)
            true
        }
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            Log.d(TAG, "dispatch enter")
            finishComposingInput()
            onEnter()
            true
        }
        else -> {
            val unicode = event.unicodeChar
            if (unicode == 0) {
                super.onKeyDown(event.keyCode, event)
            } else {
                composition.finish()
                onText(String(Character.toChars(unicode)))
                true
            }
        }
    }

    private fun dispatchControlSequence(sequence: String): Boolean {
        finishComposingInput()
        onTerminalKey(sequence)
        return true
    }

    fun focusAndShowKeyboard() {
        val focused = requestFocus()
        Log.d(TAG, "show keyboard requestFocus=$focused attached=$isAttachedToWindow")
        setSelection(text.length)
        post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Ends the IME's editable word before a terminal control key changes shell/editor mode.
     * Without this boundary, an IME can replace text composed in vim's insert mode after Esc and
     * emit synthetic backspaces into the subsequent `:w` command.
     */
    fun finishComposingInput() {
        composition.finish()
        BaseInputConnection.removeComposingSpans(text)
        if (text.isNotEmpty()) text.clear()
        post {
            if (hasFocus()) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.let { manager ->
                    manager.restartInput(this)
                    manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    fun pasteText(value: String) {
        finishComposingInput()
        dispatchText(value)
    }

    /** A cleared/replaced PTY must never receive the previous input connection's candidates. */
    fun discardComposingInput() {
        inputGeneration++
        composition.discard()
        BaseInputConnection.removeComposingSpans(text)
        text.clear()
        post {
            if (hasFocus()) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.restartInput(this)
            }
        }
    }

    private companion object {
        const val TAG = "LyraTerminalInput"
    }
}

/** Mirrors IME composition changes to a terminal, including replacements made by autocorrection. */
internal class TerminalCompositionState(
    private val sendText: (String) -> Unit,
    private val onComposition: (String) -> Unit = {},
    private val sendBackspace: () -> Unit,
) {
    private var forwarded = ""
    private var pending = ""
    val pendingText: String get() = pending
    val isComposing: Boolean get() = pending.isNotEmpty()

    fun update(next: String) {
        pending = next
        val ascii = next.all { it.code in 0x20..0x7e }
        forward(if (ascii) next else "")
        onComposition(if (ascii) "" else next)
    }

    private fun forward(next: String) {
        val prefixLength = commonPrefixLength(forwarded, next)
        val removed = forwarded.substring(prefixLength).codePointCount(
            0,
            forwarded.length - prefixLength,
        )
        repeat(removed) { sendBackspace() }
        next.substring(prefixLength).takeIf(String::isNotEmpty)?.let(sendText)
        forwarded = next
    }

    fun commit(text: String) {
        forward(text)
        forwarded = ""
        pending = ""
        onComposition("")
    }

    fun finish() {
        if (pending != forwarded) forward(pending)
        forwarded = ""
        pending = ""
        onComposition("")
    }

    fun discard() {
        forwarded = ""
        pending = ""
        onComposition("")
    }

    fun deleteBefore(count: Int, inCodePoints: Boolean = true) {
        if (count <= 0) return
        val composingCount = if (inCodePoints) pending.codePointCount(0, pending.length) else pending.length
        val localCount = minOf(count, composingCount)
        val next = if (inCodePoints) pending.dropLastCodePoints(localCount) else {
            var end = pending.length - localCount
            if (end > 0 && pending[end - 1].isHighSurrogate()) end--
            pending.substring(0, end)
        }
        if (localCount > 0) update(next)
        repeat((count - localCount).coerceAtLeast(0)) { sendBackspace() }
    }

    private fun commonPrefixLength(first: String, second: String): Int {
        var index = 0
        val limit = minOf(first.length, second.length)
        while (index < limit && first[index] == second[index]) index++
        // Never split a UTF-16 surrogate pair.
        if (index > 0 && index < first.length && first[index - 1].isHighSurrogate()) index--
        return index
    }

    private fun String.dropLastCodePoints(count: Int): String {
        if (isEmpty()) return this
        val actual = count.coerceAtMost(codePointCount(0, length))
        return substring(0, offsetByCodePoints(length, -actual))
    }
}
