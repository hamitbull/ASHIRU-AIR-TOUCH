package com.ashiru.airtouch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var handLandmarker: HandLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else binding.status.text = "Camera permission denied. Allow it in Android Settings."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.startButton.setOnClickListener { requestCameraAndStart() }
        initializeHandTracking()
        requestCameraAndStart()
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
            binding.status.text = "Hand model unavailable: ${e.localizedMessage ?: "check app assets"}"
        }
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (handLandmarker == null) {
            binding.status.text = "Hand model is not ready. Install the latest build."
            return
        }
        binding.status.text = "Starting camera and hand tracking…"
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
                binding.status.text = "Show one hand to the camera."
                binding.startButton.text = "AIR TOUCH RUNNING"
            } catch (e: Exception) {
                binding.status.text = "Camera error: ${e.localizedMessage ?: "Unable to start"}"
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
            val mpImage = BitmapImageBuilder(rotated).build()
            val result = handLandmarker?.detect(mpImage)
            val landmarks = result?.landmarks()
            if (!landmarks.isNullOrEmpty()) {
                val indexTip = landmarks[0][8]
                val x = 1f - indexTip.x() // Mirror X for the front-camera preview.
                val y = indexTip.y()
                runOnUiThread {
                    binding.handCursor.showCursor(x, y)
                    binding.status.text = "Hand detected • Index finger cursor active"
                }
            } else {
                runOnUiThread {
                    binding.handCursor.hideCursor()
                    binding.status.text = "No hand detected • Show your palm to the camera"
                }
            }
        } catch (_: Exception) {
            runOnUiThread { binding.handCursor.hideCursor() }
        } finally {
            rotated?.recycle()
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        handLandmarker?.close()
        super.onDestroy()
    }
}
