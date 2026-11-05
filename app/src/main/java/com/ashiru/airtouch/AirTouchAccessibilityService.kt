package com.ashiru.airtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
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

    fun swipe(up: Boolean) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val x = w * 0.5f
        val startY = if (up) h * 0.78f else h * 0.28f
        val endY = if (up) h * 0.25f else h * 0.78f
        val p = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 260)).build(), null, null)
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    // Android 9+ supports locking the device through an accessibility global action.
    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    // HOME moves the currently open app to the background (minimizes it).
    fun minimizeApp(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        @Volatile var instance: AirTouchAccessibilityService? = null
            private set
    }
}
