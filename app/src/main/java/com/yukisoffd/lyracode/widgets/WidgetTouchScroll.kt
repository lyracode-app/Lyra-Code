package com.yukisoffd.lyracode.widgets

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** WebView owns the stream, including horizontal/canvas/range drags. At a document edge,
 * relay actual unconsumed vertical motion through AndroidViewHolder to the parent LazyColumn. */
internal class WidgetTouchScroll(private val view: WidgetWebView) {
    private val nested = NestedScrollingChildHelper(view).apply { isNestedScrollingEnabled = true }
    private val configuration = ViewConfiguration.get(view.context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var vertical: Boolean? = null
    private var handedOff = false
    private var sequence = 0
    private var domMask: Int? = null
    private val consumed = IntArray(2)

    fun report(sequence: Int?, mask: Int?) {
        if (sequence == this.sequence && mask != null && mask in 0..7) domMask = mask
    }

    fun dispatch(event: MotionEvent, deliver: (MotionEvent) -> Boolean): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            finish()
            downX = event.rawX
            downY = event.rawY
            lastY = downY
            vertical = null
            handedOff = false
            domMask = null
            sequence++
            velocity = VelocityTracker.obtain()
            nested.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
            // Fractions also work with a page-supplied viewport scale. A late result from a
            // previous touch can never transfer a new slider drag to the conversation.
            val x = event.x / view.width.coerceAtLeast(1)
            val y = event.y / view.height.coerceAtLeast(1)
            view.evaluateJavascript("window.__lyraPanTarget && window.__lyraPanTarget($sequence,$x,$y)", null)
        }
        val offsetX = event.rawX - event.x
        val offsetY = event.rawY - event.y
        event.offsetLocation(offsetX, offsetY)
        velocity?.addMovement(event)
        event.offsetLocation(-offsetX, -offsetY)
        if (action == MotionEvent.ACTION_POINTER_DOWN) vertical = false

        if (action == MotionEvent.ACTION_MOVE) {
            val dx = abs(event.rawX - downX)
            val dy = abs(event.rawY - downY)
            if (vertical == null && maxOf(dx, dy) > configuration.scaledTouchSlop) vertical = dy > dx
            val delta = (lastY - event.rawY).roundToInt()
            val mask = domMask
            val direction = if (delta > 0) 1 else -1
            val innerEdge = if (delta > 0) 4 else 2
            if (handedOff || (vertical == true && delta != 0 && mask != null &&
                    mask and (1 or innerEdge) == 0 && !view.canPanVertically(direction))) {
                consumed.fill(0)
                nested.dispatchNestedPreScroll(0, delta, consumed, null, ViewCompat.TYPE_TOUCH)
                val preConsumed = consumed[1]
                consumed.fill(0)
                nested.dispatchNestedScroll(0, 0, 0, delta - preConsumed, null, ViewCompat.TYPE_TOUCH, consumed)
                if (!handedOff && (preConsumed != 0 || consumed[1] != 0)) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    deliver(cancel)
                    cancel.recycle()
                    handedOff = true
                }
            }
            lastY = event.rawY
        }

        val handled = if (handedOff) true else deliver(event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            view.evaluateJavascript("window.__lyraEndPan && window.__lyraEndPan($sequence)", null)
            if (handedOff && action == MotionEvent.ACTION_UP) velocity?.let {
                it.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                val speed = -it.yVelocity
                if (abs(speed) >= configuration.scaledMinimumFlingVelocity) nested.dispatchNestedFling(0f, speed, false)
            }
            finish()
        } else {
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        return handled
    }

    fun finish() {
        if (velocity == null) return
        nested.stopNestedScroll(ViewCompat.TYPE_TOUCH)
        view.parent?.requestDisallowInterceptTouchEvent(false)
        velocity?.recycle()
        velocity = null
    }
}
