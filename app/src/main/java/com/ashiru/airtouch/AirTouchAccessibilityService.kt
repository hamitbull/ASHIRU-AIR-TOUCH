package com.ashiru.airtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AirTouchAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 75)).build(), null, null)
    }

    fun doubleTap(x: Float, y: Float) {
        val first = Path().apply { moveTo(x, y) }
        val second = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(first, 0, 55))
            .addStroke(GestureDescription.StrokeDescription(second, 130, 55))
            .build(), null, null)
    }

    fun drag(startX: Float, startY: Float, endX: Float, endY: Float) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build(), null, null)
    }

    fun scrollAt(x: Float, y: Float, deltaY: Float) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val sx = x.coerceIn(8f, w - 8f)
        val sy = y.coerceIn(h * 0.12f, h * 0.88f)
        val amount = (kotlin.math.abs(deltaY) * 3f).coerceIn(h * 0.18f, h * 0.48f)
        val endY = (sy + if (deltaY > 0f) -amount else amount).coerceIn(4f, h - 4f)
        val path = Path().apply { moveTo(sx, sy); lineTo(sx, endY) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build(), null, null)
    }

    /** Swipe the foreground app in the requested content direction. */
    fun swipeAt(x: Float, y: Float, scrollUp: Boolean) {
        // scrollAt maps positive delta to an upward finger swipe.
        scrollAt(x, y, if (scrollUp) 1f else -1f)
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    companion object {
        @Volatile var instance: AirTouchAccessibilityService? = null
            private set
    }
}
