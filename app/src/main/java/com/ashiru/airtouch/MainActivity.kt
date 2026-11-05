package com.ashiru.airtouch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.ashiru.airtouch.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var handLandmarker: HandLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService
    @Volatile private var previousIndexY: Float? = null
    @Volatile private var previousPinch = false
    @Volatile private var previousFist = false
    @Volatile private var lastActionAt = 0L

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else binding.status.text = "An bukaci izinin Camera domin app din ya gane hannu."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.startButton.setOnClickListener { requestCameraAndStart() }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        initializeHandTracking()
        requestCameraAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.accessibilityButton.text =
                if (AirTouchAccessibilityService.instance != null) "ACCESSIBILITY: ON"
                else "ENABLE PHONE CONTROL"
        }
    }

    private fun initializeHandTracking() {
        try {
            val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.55f)
                .setMinHandPresenceConfidence(0.55f)
                .setMinTrackingConfidence(0.5f)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            binding.status.text = "Hand model bai samu ba: ${e.localizedMessage ?: "duba build"}"
        }
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (handLandmarker == null) {
            binding.status.text = "Hand model bai shirya ba. Sake sauke sabon APK."
            return
        }
        binding.status.text = "Ana bude camera..."
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
                binding.status.text = "Nuna tafin hannunka ga camera."
                binding.startButton.text = "AIR TOUCH RUNNING"
            } catch (e: Exception) {
                binding.status.text = "Camera error: ${e.localizedMessage ?: "ba a iya budewa ba"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        var rotated: Bitmap? = null
        try {
            val source = imageProxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (rotated !== source) source.recycle()
            val result = handLandmarker?.detect(BitmapImageBuilder(rotated).build())
            val hands = result?.landmarks()
            if (hands.isNullOrEmpty()) {
                previousIndexY = null
                previousPinch = false
                previousFist = false
                runOnUiThread {
                    binding.handCursor.hideCursor()
                    binding.status.text = "Ba a ga hannu ba. Nuna hannunka a fili."
                }
            } else {
                val hand = hands[0]
                val index = hand[8]
                val x = (1f - index.x()).coerceIn(0f, 1f)
                val y = index.y().coerceIn(0f, 1f)
                val pinch = hypot(
                    (hand[4].x() - hand[8].x()).toDouble(),
                    (hand[4].y() - hand[8].y()).toDouble()
                ) < 0.055
                val fist = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
                    .all { (tip, pip) -> hand[tip].y() > hand[pip].y() }
                val openPalm = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
                    .all { (tip, pip) -> hand[tip].y() < hand[pip].y() }

                if (!openPalm && !pinch && !fist) {
                    val oldY = previousIndexY
                    if (oldY != null && kotlin.math.abs(y - oldY) > 0.18f && canTrigger()) {
                        AirTouchAccessibilityService.instance?.swipe(y < oldY)
                        lastActionAt = System.currentTimeMillis()
                    }
                }
                if (pinch && !previousPinch && canTrigger()) {
                    val svc = AirTouchAccessibilityService.instance
                    if (svc != null) {
                        val dm = resources.displayMetrics
                        svc.tap(x * dm.widthPixels, y * dm.heightPixels)
                        lastActionAt = System.currentTimeMillis()
                    }
                }
                if (fist && !previousFist && canTrigger()) {
                    AirTouchAccessibilityService.instance?.back()
                    lastActionAt = System.currentTimeMillis()
                }

                previousIndexY = y
                previousPinch = pinch
                previousFist = fist
                runOnUiThread {
                    binding.handCursor.showCursor(x, y)
                    val serviceOn = AirTouchAccessibilityService.instance != null
                    binding.accessibilityButton.text =
                        if (serviceOn) "ACCESSIBILITY: ON" else "ENABLE PHONE CONTROL"
                    binding.status.text = when {
                        openPalm -> "AIR PAUSE • Hannu a bude: an dakatar da gesture"
                        pinch -> "AIR CLICK • pinch"
                        fist -> "AIR BACK • dunkule hannu"
                        else -> if (serviceOn) "Air control yana aiki • motsa hannu sama/ƙasa"
                               else "Cursor yana aiki a nan • kunna Phone Control domin sauran apps"
                    }
                }
            }
        } catch (_: Exception) {
            runOnUiThread { binding.handCursor.hideCursor() }
        } finally {
            rotated?.recycle()
            imageProxy.close()
        }
    }

    private fun canTrigger(): Boolean = System.currentTimeMillis() - lastActionAt > 850

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        handLandmarker?.close()
        super.onDestroy()
    }
}