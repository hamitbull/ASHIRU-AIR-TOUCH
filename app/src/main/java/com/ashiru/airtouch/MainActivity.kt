package com.ashiru.airtouch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.ashiru.airtouch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null

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
        binding.startButton.setOnClickListener { requestCameraAndStart() }
        requestCameraAndStart()
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        binding.status.text = "Starting camera preview…"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                binding.status.text = "Camera ready. Hand tracking is the next milestone."
                binding.startButton.text = "CAMERA RUNNING"
            } catch (e: Exception) {
                binding.status.text = "Camera error: ${e.localizedMessage ?: "Unable to start"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        super.onDestroy()
    }
}
