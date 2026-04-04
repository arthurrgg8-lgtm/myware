package com.lazzy.losttracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TrackerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pollIntervalMs = 15_000L

    private val tick = object : Runnable {
        override fun run() {
            syncWithServer()
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_body)))
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        ioExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncWithServer() {
        val config = TrackerPrefs.load(this)
        if (config.deviceId == null) {
            stopSelf()
            return
        }
        if (!hasLocationPermission()) {
            ioExecutor.execute {
                queueHourlyUnavailable(config, DeviceStatus.read(this), "permission missing")
            }
            stopSelf()
            return
        }
        pushCurrentLocation(config, requestedByServer = false)
        ioExecutor.execute {
            runCatching { ApiClient.fetchCommands(config) }
                .onSuccess { commands ->
                    commands.filter { it.commandType == "request_location" }.forEach { command ->
                        handler.post {
                            pushCurrentLocation(config, requestedByServer = true, commandId = command.id)
                        }
                    }
                }
                .onFailure { error ->
                    updateNotification("Command sync failed: ${error.message}")
                }
        }
    }

    private fun pushCurrentLocation(
        config: TrackerConfig,
        requestedByServer: Boolean,
        commandId: Int? = null
    ) {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val snapshot = DeviceStatus.read(this)
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            ioExecutor.execute {
                val networkSnapshot = ApiClient.readNetworkSnapshot()
                val capturedAt = Instant.now().toString()
                runCatching {
                    ApiClient.sendLocationUnavailable(
                        config = config,
                        snapshot = snapshot,
                        capturedAt = capturedAt,
                        networkSnapshot = networkSnapshot
                    )
                    queueHourlyUnavailable(config, snapshot, "location disabled", capturedAt)
                    flushHourlyReports(config)
                    if (requestedByServer && commandId != null) {
                        ApiClient.completeCommand(config, commandId, "Location services are disabled on the device.")
                    }
                }.onSuccess {
                    updateNotification("Location services are off on this device.")
                }.onFailure { error ->
                    queueHourlyUnavailable(config, snapshot, "location disabled", capturedAt)
                    updateNotification("Send failed: ${error.message}")
                }
            }
            return
        }

        locationManager.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { location: Location? ->
            if (location == null) {
                ioExecutor.execute {
                    queueHourlyUnavailable(config, snapshot, "no GPS/network fix")
                }
                updateNotification("Keep protecting, waiting for location")
                return@getCurrentLocation
            }
            ioExecutor.execute {
                val capturedAt = Instant.now().toString()
                val networkSnapshot = ApiClient.readNetworkSnapshot()
                runCatching {
                    ApiClient.sendLocation(
                        config = config,
                        location = location,
                        snapshot = snapshot,
                        capturedAt = capturedAt,
                        networkSnapshot = networkSnapshot
                    )
                    HourlyReportStore.saveLastKnownLocation(this, location.latitude, location.longitude)
                    queueHourlySuccess(config, snapshot, location, capturedAt)
                    flushHourlyReports(config)
                    if (requestedByServer && commandId != null) {
                        ApiClient.completeCommand(config, commandId, "Fresh location uploaded from phone.")
                    }
                }.onSuccess {
                    updateNotification(getString(R.string.notification_body))
                }.onFailure { error ->
                    HourlyReportStore.saveLastKnownLocation(this, location.latitude, location.longitude)
                    queueHourlySuccess(config, snapshot, location, capturedAt)
                    updateNotification("Queued hourly location for later sync")
                }
            }
        }
    }

    private fun flushHourlyReports(config: TrackerConfig) {
        val deviceId = config.deviceId ?: return
        val pending = HourlyReportStore.getQueuedEntries(this, deviceId)
        if (pending.isEmpty()) {
            return
        }
        val delivered = mutableListOf<HourlyReportEntry>()
        for (entry in pending) {
            val uploaded = runCatching {
                ApiClient.sendHourlyReport(config, entry)
            }
            if (uploaded.isSuccess) {
                delivered += entry
            } else {
                break
            }
        }
        HourlyReportStore.removeEntries(this, delivered)
    }

    private fun queueHourlySuccess(
        config: TrackerConfig,
        snapshot: DeviceSnapshot,
        location: Location,
        capturedAt: String,
    ) {
        val deviceId = config.deviceId ?: return
        val hourKey = HourlyReportStore.currentHourKey(capturedAt)
        if (HourlyReportStore.hasEntryForHour(this, deviceId, hourKey)) {
            return
        }
        HourlyReportStore.upsertEntry(
            this,
            HourlyReportEntry(
                deviceId = deviceId,
                hourKey = hourKey,
                status = "success",
                reason = null,
                capturedAt = capturedAt,
                latitude = location.latitude,
                longitude = location.longitude,
                lastKnownLatitude = location.latitude,
                lastKnownLongitude = location.longitude,
                accuracyM = location.accuracy.toDouble(),
                batteryLevel = snapshot.batteryLevel,
                networkStatus = snapshot.networkStatus,
            )
        )
    }

    private fun queueHourlyUnavailable(
        config: TrackerConfig,
        snapshot: DeviceSnapshot,
        reason: String,
        capturedAt: String = Instant.now().toString(),
    ) {
        val deviceId = config.deviceId ?: return
        val hourKey = HourlyReportStore.currentHourKey(capturedAt)
        if (HourlyReportStore.hasEntryForHour(this, deviceId, hourKey)) {
            return
        }
        val lastKnown = HourlyReportStore.getLastKnownLocation(this)
        HourlyReportStore.upsertEntry(
            this,
            HourlyReportEntry(
                deviceId = deviceId,
                hourKey = hourKey,
                status = "location_unavailable",
                reason = reason,
                capturedAt = capturedAt,
                latitude = null,
                longitude = null,
                lastKnownLatitude = lastKnown?.first,
                lastKnownLongitude = lastKnown?.second,
                accuracyM = null,
                batteryLevel = snapshot.batteryLevel,
                networkStatus = snapshot.networkStatus,
            )
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Google Services", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            description = "Background protection status"
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stat_service)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    companion object {
        private const val CHANNEL_ID = "lost_tracker_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
