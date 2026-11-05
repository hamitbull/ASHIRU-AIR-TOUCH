package com.ashiru.airtouch

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ashiru.airtouch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) continueSetup()
        else binding.status.text = "Ana bukatar izinin camera domin gano hannunka."
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) continueSetup()
        else binding.status.text = "Ka ba app izinin notification domin ganin lokacin da Air Touch yake aiki."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.overlayButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                binding.status.text = "Izinin floating tracking yana kunne. Yanzu danna START AIR TOUCH."
            }
        }
        binding.status.text = permissionSummary()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) binding.status.text = permissionSummary()
    }

    private fun requestPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        continueSetup()
    }

    private fun continueSetup() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            binding.status.text = "Ka kunna 'Display over other apps', ka dawo nan, sannan danna START AIR TOUCH."
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (!isAirTouchAccessibilityEnabled()) {
            binding.status.text = "Ka kunna ASHIRU AIR TOUCH a Accessibility settings, sannan ka dawo ka danna START AIR TOUCH."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        ContextCompat.startForegroundService(
            this, Intent(this, AirTouchCameraService::class.java)
        )
        binding.status.text = "AIR TOUCH yana aiki. Yatsa daya = scroll; babban yatsa + manuni = click. Layin haske yana nuna tracking."
        binding.startButton.text = "AIR TOUCH RUNNING"
    }

    private fun isAirTouchAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, AirTouchAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.asSequence().any { it.equals(expected, ignoreCase = true) }
    }

    private fun permissionSummary(): String {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val accessibility = isAirTouchAccessibilityEnabled()
        val cameraMark = if (camera) "✓" else "✗"
        val overlayMark = if (overlay) "✓" else "✗"
        val accessibilityMark = if (accessibility) "✓" else "✗"
        return "Izini: Camera $cameraMark  •  Floating tracking $overlayMark  •  Accessibility $accessibilityMark\n\nDanna maɓallan izini, sannan START AIR TOUCH."
    }
}
