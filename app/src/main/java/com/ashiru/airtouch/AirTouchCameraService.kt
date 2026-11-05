package com.ashiru.airtouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Draws a short-lived, fading trail above other apps. It is deliberately
 * NOT_TOUCHABLE so it never steals normal screen touches.
 */
private class FingerTrailView(context: android.content.Context) : View(context) {
    private data class Point(val x: Float, val y: Float, val time: Long)
    private val lock = Any()
    private val points = ArrayDeque<Point>()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 245, 205)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 220)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 255, 220)
        style = Paint.Style.FILL
    }

    fun updateFinger(x: Float, y: Float) {
        synchronized(lock) {
            points.addLast(Point(x, y, SystemClock.uptimeMillis()))
            while (points.size > 18) points.removeFirst()
        }
        postInvalidateOnAnimation()
    }

    fun clearTrail() {
        synchronized(lock) { points.clear() }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val copy = synchronized(lock) {
            while (points.isNotEmpty() && now - points.first().time > 520L) points.removeFirst()
            points.toList()
        }
        if (copy.isEmpty()) return

        for (i in 1 until copy.size) {
            val a = copy[i - 1]
            val b = copy[i]
            val age = (now - b.time).coerceIn(0L, 520L)
            trailPaint.alpha = (255f * (1f - age / 520f)).toInt().coerceIn(0, 255)
            canvas.drawLine(a.x, a.y, b.x, b.y, trailPaint)
        }

        val tip = copy.last()
        val age = (now - tip.time).coerceIn(0L, 520L)
        val alpha = (255f * (1f - age / 520f)).toInt().coerceIn(0, 255)
        ringPaint.alpha = alpha
        fillPaint.alpha = (alpha * 0.30f).toInt()
        canvas.drawCircle(tip.x, tip.y, 27f, fillPaint)
        canvas.drawCircle(tip.x, tip.y, 27f, ringPaint)
        canvas.drawCircle(tip.x, tip.y, 6f, fillPaint)

        if (copy.size > 1) postInvalidateDelayed(32L)
    }
}

class AirTouchCameraService : LifecycleService() {
    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: HandLandmarker? = null
    private lateinit var executor: ExecutorService
    private var overlay: FingerTrailView? = null
    private var windowManager: WindowManager? = null
    private var cameraStarted = false

    // Filtered coordinates prevent tiny camera/hand tremors from becoming actions.
    private var smoothX: Float? = null
    private var smoothY: Float? = null
    private var scrollAnchorX: Float? = null
    private var scrollAnchorY: Float? = null
    private var indexOnlyFrames = 0
    private var pinchFrames = 0
    private var openPalmFrames = 0
    private var wasPinching = false
    private var lastGestureAt = 0L
    private var lastVideoTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        try {
            landmarker = HandLandmarker.createFromOptions(
                this,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
                    )
                    // VIDEO mode lets MediaPipe use temporal tracking between frames.
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.65f)
                    .setMinHandPresenceConfidence(0.65f)
                    .setMinTrackingConfidence(0.60f)
                    .build()
            )
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Could not initialise hand tracking model", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        showFloatingCursor()
        if (!cameraStarted) startCameraAnalysis()
        return START_STICKY
    }

    private fun startCameraAnalysis() {
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { analyze(it) }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis
                )
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Could not start front camera analysis", error)
                cameraStarted = false
                stopSelf()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        var rotated: Bitmap? = null
        try {
            val source = proxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            }
            rotated = Bitmap.createBitmap(
                source, 0, 0, source.width, source.height, matrix, true
            )
            if (rotated !== source) source.recycle()

            val timestamp = maxOf(SystemClock.uptimeMillis(), lastVideoTimestamp + 1L)
            lastVideoTimestamp = timestamp
            val result = landmarker?.detectForVideo(
                BitmapImageBuilder(rotated).build(), timestamp
            )
            val hands = result?.landmarks()
            if (hands.isNullOrEmpty()) {
                resetTracking()
                overlay?.clearTrail()
                return
            }

            val hand = hands[0]
            // The cursor is controlled by the midpoint of thumb tip (4)
            // and index tip (8), so both fingers contribute to pointer motion.
            val pairMidX = (hand[4].x() + hand[8].x()) * 0.5f
            val pairMidY = (hand[4].y() + hand[8].y()) * 0.5f
            val rawX = (1f - pairMidX).coerceIn(0f, 1f)
            val rawY = pairMidY.coerceIn(0f, 1f)
            val x = smoothX?.let { it + (rawX - it) * SMOOTHING } ?: rawX
            val y = smoothY?.let { it + (rawY - it) * SMOOTHING } ?: rawY
            smoothX = x
            smoothY = y

            val dm = resources.displayMetrics
            val screenX = x * dm.widthPixels
            val screenY = y * dm.heightPixels
            // Click at the same two-finger midpoint used by the floating cursor.
            val tapX = screenX.coerceIn(1f, dm.widthPixels - 1f)
            val tapY = screenY.coerceIn(1f, dm.heightPixels - 1f)
            overlay?.updateFinger(screenX, screenY)

            val folded = listOf(12 to 10, 16 to 14, 20 to 18)
                .all { (tip, pip) -> hand[tip].y() > hand[pip].y() }
            val thumbIndexDistance = hypot(
                (hand[4].x() - hand[8].x()).toDouble(),
                (hand[4].y() - hand[8].y()).toDouble()
            )
            // Scale pinch distance by palm width, so the gesture works at
            // different camera distances and with different hand sizes.
            val palmWidth = hypot(
                (hand[5].x() - hand[17].x()).toDouble(),
                (hand[5].y() - hand[17].y()).toDouble()
            )
            val pinchRatio = if (palmWidth > 0.025) thumbIndexDistance / palmWidth else 99.0
            val pinch = pinchRatio < PINCH_START_RATIO && folded
            // Thumb + index held apart is the two-finger mouse/scroll pose.
            // Pinching those same fingers is reserved for a click.
            val twoFingerPose = folded && pinchRatio > PINCH_RELEASE_RATIO
            val openPalm = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
                .all { (tip, pip) -> hand[tip].y() < hand[pip].y() }

            val now = SystemClock.uptimeMillis()
            val control = AirTouchAccessibilityService.instance

            // Require a deliberate, sustained open palm before pausing.
            // A single noisy frame must not interrupt the user's control.
            if (openPalm) {
                openPalmFrames++
                if (openPalmFrames >= OPEN_PALM_STABLE_FRAMES) {
                    resetGestureState()
                }
                return
            } else {
                openPalmFrames = 0
            }

            // A pinch is one click only. Latch as soon as the pose is stable,
            // even if Accessibility is briefly unavailable or cooldown is active.
            if (pinch) {
                pinchFrames++
                indexOnlyFrames = 0
                scrollAnchorX = null
                scrollAnchorY = null
                if (pinchFrames >= REQUIRED_STABLE_FRAMES && !wasPinching) {
                    wasPinching = true
                    if (now - lastGestureAt >= CLICK_COOLDOWN && control != null) {
                        control.tap(tapX, tapY)
                        lastGestureAt = now
                    }
                }
                return
            } else if (pinchRatio > PINCH_RELEASE_RATIO || !folded) {
                pinchFrames = 0
                wasPinching = false
            }

            // Vertical hand movement while the thumb/index pair is apart
            // scrolls the currently foreground app (TikTok, browser, dashboard, etc.).
            if (twoFingerPose) {
                indexOnlyFrames++
                if (indexOnlyFrames == REQUIRED_STABLE_FRAMES) {
                    scrollAnchorX = x
                    scrollAnchorY = y
                }
                val anchorX = scrollAnchorX
                val anchorY = scrollAnchorY
                if (indexOnlyFrames >= REQUIRED_STABLE_FRAMES &&
                    anchorX != null && anchorY != null &&
                    abs(x - anchorX) < MAX_SCROLL_SIDE_DRIFT &&
                    abs(y - anchorY) >= SCROLL_THRESHOLD &&
                    now - lastGestureAt >= SCROLL_COOLDOWN &&
                    control != null
                ) {
                    control.swipeAt(screenX, screenY, y < anchorY)
                    lastGestureAt = now
                    scrollAnchorX = x
                    scrollAnchorY = y
                }
            } else {
                indexOnlyFrames = 0
                scrollAnchorX = null
                scrollAnchorY = null
            }
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Frame analysis skipped", error)
        } finally {
            rotated?.recycle()
            proxy.close()
        }
    }

    private fun resetGestureState() {
        scrollAnchorX = null
        scrollAnchorY = null
        indexOnlyFrames = 0
        pinchFrames = 0
        openPalmFrames = 0
        wasPinching = false
    }

    private fun resetTracking() {
        smoothX = null
        smoothY = null
        scrollAnchorX = null
        scrollAnchorY = null
        indexOnlyFrames = 0
        pinchFrames = 0
        openPalmFrames = 0
        // Keep the pinch latch until a real release/open-palm pose is seen.
        // A momentary tracking dropout must never create a second accidental tap.
    }

    private fun showFloatingCursor() {
        if (!android.provider.Settings.canDrawOverlays(this) || overlay != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val trail = FingerTrailView(this)
            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Keep the full-screen visual overlay transparent enough for Android 12+ touch-through.
                alpha = 0.70f
            }
            windowManager?.addView(trail, params)
            overlay = trail
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Floating tracking overlay could not be displayed", error)
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, AirTouchCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ASHIRU AIR TOUCH is running")
            .setContentText("Two-finger cursor • Pinch to click • Move hand up/down to scroll")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Air Touch camera control", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        if (::executor.isInitialized) executor.shutdownNow()
        landmarker?.close()
        try {
            overlay?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        overlay = null
        cameraStarted = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.ashiru.airtouch.STOP_CAMERA_SERVICE"
        private const val TAG = "AshiruAirTouch"
        private const val CHANNEL_ID = "air_touch_camera"
        private const val NOTIFICATION_ID = 411
        private const val SMOOTHING = 0.35f
        private const val PINCH_START_RATIO = 0.34
        private const val PINCH_RELEASE_RATIO = 0.56
        private const val REQUIRED_STABLE_FRAMES = 4
        private const val OPEN_PALM_STABLE_FRAMES = 5
        private const val CLICK_COOLDOWN = 750L
        private const val SCROLL_THRESHOLD = 0.13f
        private const val MAX_SCROLL_SIDE_DRIFT = 0.14f
        private const val SCROLL_COOLDOWN = 750L
    }
}
