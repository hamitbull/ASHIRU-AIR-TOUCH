package com.ashiru.airtouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.content.Intent
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
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

class AirTouchCameraService : LifecycleService() {
    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: HandLandmarker? = null
    private lateinit var executor: ExecutorService
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    @Volatile private var anchorY: Float? = null
    @Volatile private var wasPinching = false
    @Volatile private var wasFist = false
    @Volatile private var lastGestureAt = 0L

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        try {
            landmarker = HandLandmarker.createFromOptions(this,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE).setNumHands(1)
                    .setMinHandDetectionConfidence(0.55f)
                    .setMinHandPresenceConfidence(0.55f)
                    .setMinTrackingConfidence(0.5f).build())
        } catch (_: Exception) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, buildNotification())
        showFloatingCursor()
        startCameraAnalysis()
        return START_STICKY
    }

    private fun startCameraAnalysis() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { analyze(it) }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) { stopSelf() }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        var rotated: Bitmap? = null
        try {
            val src = proxy.toBitmap()
            val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (rotated !== src) src.recycle()
            val hands = landmarker?.detect(BitmapImageBuilder(rotated).build())?.landmarks()
            if (hands.isNullOrEmpty()) {
                anchorY = null; wasPinching = false; wasFist = false
                return
            }
            val hand = hands[0]
            val x = (1f - hand[8].x()).coerceIn(0f, 1f)
            val y = hand[8].y().coerceIn(0f, 1f)
            moveFloatingCursor(x, y)
            val pinch = hypot((hand[4].x()-hand[8].x()).toDouble(),
                (hand[4].y()-hand[8].y()).toDouble()) < 0.055
            val fist = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
                .all { (tip,pip) -> hand[tip].y() > hand[pip].y() }
            val openPalm = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
                .all { (tip,pip) -> hand[tip].y() < hand[pip].y() }
            if (openPalm) { anchorY = y; return }

            val now = System.currentTimeMillis()
            val control = AirTouchAccessibilityService.instance
            if (pinch && !wasPinching && now-lastGestureAt > 850 && control != null) {
                val dm = resources.displayMetrics
                control.tap(x*dm.widthPixels, y*dm.heightPixels)
                lastGestureAt = now
            }
            if (fist && !wasFist && now-lastGestureAt > 850 && control != null) {
                control.back(); lastGestureAt = now
            }
            val base = anchorY
            if (!pinch && !fist && base != null && abs(y-base) > 0.20f &&
                now-lastGestureAt > 900 && control != null) {
                control.swipe(y < base); lastGestureAt = now; anchorY = y
            } else if (base == null) anchorY = y
            wasPinching = pinch; wasFist = fist
        } catch (_: Exception) {
        } finally {
            rotated?.recycle()
            proxy.close()
        }
    }

    private fun showFloatingCursor() {
        if (!android.provider.Settings.canDrawOverlays(this) || overlay != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val dot = TextView(this).apply {
                text = "●"; textSize = 30f; setTextColor(Color.rgb(0,220,190))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(35,0,220,190)); setStroke(2,Color.rgb(0,220,190))
                }
                gravity = Gravity.CENTER
            }
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(48,48,type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            windowManager?.addView(dot,params); overlay=dot; overlayParams=params
        } catch (_: Exception) { }
    }

    private fun moveFloatingCursor(x: Float,y: Float) {
        val dot=overlay ?: return
        val p=overlayParams ?: return
        val dm=resources.displayMetrics
        p.x=(x*dm.widthPixels-24).toInt().coerceAtLeast(0)
        p.y=(y*dm.heightPixels-24).toInt().coerceAtLeast(0)
        try { windowManager?.updateViewLayout(dot,p) } catch (_: Exception) { }
    }

    private fun buildNotification(): Notification {
        val stop=PendingIntent.getService(this,2,
            Intent(this,AirTouchCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ASHIRU AIR TOUCH is running")
            .setContentText("Hand gesture control is active")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Stop",stop).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel=NotificationChannel(CHANNEL_ID,"Air Touch camera control",NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        if (::executor.isInitialized) executor.shutdown()
        landmarker?.close()
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        overlay=null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP="com.ashiru.airtouch.STOP_CAMERA_SERVICE"
        private const val CHANNEL_ID="air_touch_camera"
        private const val NOTIFICATION_ID=411
    }
}