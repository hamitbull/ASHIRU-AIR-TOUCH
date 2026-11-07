package com.ashiru.airtouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/** Floating laptop-style touchpad; target-app actions are sent through Accessibility. */
class TouchpadOverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var cursor: CursorView? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastTapAt = 0L

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cursorX = resources.displayMetrics.widthPixels * 0.5f
        cursorY = resources.displayMetrics.heightPixels * 0.42f
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (bubble == null) showBubble()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    private fun params(width: Int, height: Int, gravity: Int, flags: Int) =
        WindowManager.LayoutParams(width, height, overlayType(), flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
        }

    private fun showBubble() {
        val b = TextView(this).apply {
            text = "⌖"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0, 150, 136))
            elevation = 12f
            setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        }
        val lp = params(dp(58), dp(58), Gravity.TOP or Gravity.END,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        lp.x = dp(12)
        lp.y = dp(180)
        wm.addView(b, lp)
        bubble = b
    }

    private fun showPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.rgb(25, 32, 46))
            elevation = 18f
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "ASHIRU TOUCHPAD  •  Laptop mode"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val close = TextView(this).apply {
            text = "HIDE  ×"
            textSize = 13f
            setTextColor(Color.rgb(128, 230, 220))
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener { hidePanel() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(close)
        root.addView(header)

        root.addView(TouchSurface(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(190)
        ))
        val hint = TextView(this).apply {
            text = "Yatsa 1: move / tap / hold-and-drag   •   Yatsu 2: scroll"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(2))
        }
        root.addView(hint)
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT, dp(270),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        wm.addView(root, lp)
        panel = root
        showCursor()
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        cursor?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        cursor = null
    }

    private fun showCursor() {
        if (cursor != null) return
        val view = CursorView(this)
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        wm.addView(view, lp)
        cursor = view
        updateCursor()
    }

    private fun updateCursor() { cursor?.setPoint(cursorX, cursorY) }

    private fun moveCursor(dx: Float, dy: Float) {
        val dm = resources.displayMetrics
        cursorX = (cursorX + dx * POINTER_SPEED).coerceIn(2f, dm.widthPixels - 2f)
        cursorY = (cursorY + dy * POINTER_SPEED).coerceIn(2f, dm.heightPixels - dp(40).toFloat())
        updateCursor()
    }

    private inner class TouchSurface(context: android.content.Context) : View(context) {
        private var downX = 0f
        private var downY = 0f
        private var previousX = 0f
        private var previousY = 0f
        private var startCursorX = 0f
        private var startCursorY = 0f
        private var lastAverageY = 0f
        private var scrollDistance = 0f
        private var moved = false
        private var multiTouch = false
        private var downAt = 0L
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(47, 61, 82)
            style = Paint.Style.FILL
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 220, 210)
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(),
                dp(16).toFloat(), dp(16).toFloat(), bg)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawRoundRect(cx - dp(54), cy - dp(28), cx + dp(54), cy + dp(28),
                dp(10).toFloat(), dp(10).toFloat(), line)
            line.textSize = dp(12).toFloat()
            line.style = Paint.Style.FILL
            canvas.drawText("TOUCH & MOVE", cx - dp(43), cy + dp(5), line)
            line.style = Paint.Style.STROKE
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    previousX = event.x
                    previousY = event.y
                    startCursorX = cursorX
                    startCursorY = cursorY
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    multiTouch = false
                    scrollDistance = 0f
                    lastAverageY = event.y
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        multiTouch = true
                        lastAverageY = averageY(event)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 || multiTouch) {
                        val average = averageY(event)
                        scrollDistance += average - lastAverageY
                        lastAverageY = average
                        moved = true
                    } else {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) + abs(dy) > dp(5)) moved = true
                        moveCursor(event.x - previousX, event.y - previousY)
                    }
                    previousX = event.x
                    previousY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val control = AirTouchAccessibilityService.instance
                    if (multiTouch) {
                        if (abs(scrollDistance) > dp(8))
                            control?.scrollAt(cursorX, cursorY, scrollDistance)
                    } else if (!moved) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt in 1..350) {
                            control?.tap(cursorX, cursorY)
                            lastTapAt = 0L
                        } else {
                            control?.tap(cursorX, cursorY)
                            lastTapAt = now
                        }
                    } else if (SystemClock.uptimeMillis() - downAt > 350L) {
                        control?.drag(startCursorX, startCursorY, cursorX, cursorY)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        private fun averageY(event: MotionEvent): Float {
            var total = 0f
            for (i in 0 until event.pointerCount) total += event.getY(i)
            return total / event.pointerCount
        }
    }

    private class CursorView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 220)
            style = Paint.Style.FILL
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        private var px = 0f
        private var py = 0f
        fun setPoint(x: Float, y: Float) {
            px = x
            py = y
            postInvalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val path = Path().apply {
                moveTo(px, py)
                lineTo(px + 3f, py + 34f)
                lineTo(px + 12f, py + 25f)
                lineTo(px + 21f, py + 43f)
                lineTo(px + 28f, py + 39f)
                lineTo(px + 19f, py + 21f)
                lineTo(px + 33f, py + 20f)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ASHIRU Floating Touchpad")
            .setContentText("Touchpad yana aiki a kan sauran apps.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                android.app.PendingIntent.getService(this, 44,
                    Intent(this, TouchpadOverlayService::class.java).setAction(ACTION_STOP),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Floating Touchpad", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        bubble = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.ashiru.airtouch.STOP_TOUCHPAD"
        private const val CHANNEL_ID = "air_touch_touchpad"
        private const val NOTIFICATION_ID = 412
        private const val POINTER_SPEED = 1.8f
    }
}
