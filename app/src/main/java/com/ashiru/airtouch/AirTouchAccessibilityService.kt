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

    // Scroll from the live fingertip position, not from the fixed screen centre.
    fun swipeAt(x: Float, y: Float, up: Boolean) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val sx = x.coerceIn(8f, w - 8f)
        val sy = y.coerceIn(h * 0.18f, h * 0.82f)
        val distance = h * 0.34f
        val ey = (if (up) sy - distance else sy + distance).coerceIn(4f, h - 4f)
        val p = Path().apply { moveTo(sx, sy); lineTo(sx, ey) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 320)).build(), null, null)
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    companion object {
        @Volatile var instance: AirTouchAccessibilityService? = null
            private set
    }
}
