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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TrackerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pollIntervalMs = 2_000L
    @Volatile
    private var lastNotificationContent: String? = null
    @Volatile
    private var reEnrollmentInProgress = false

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
        val content = getString(R.string.notification_body)
        lastNotificationContent = content
        startForeground(NOTIFICATION_ID, buildNotification(content))
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
        flushQueuedReportsAsync(config)
        val snapshot = DeviceStatus.read(this)
        val routineCapturedAt = snapshot.deviceTime
        syncStatusIfChanged(config, snapshot, routineCapturedAt)
        if (!hasLocationPermission()) {
            if (shouldCaptureRoutineState(config, routineCapturedAt)) {
                ioExecutor.execute {
                    queueHourlyUnavailable(config, snapshot, "permission missing", routineCapturedAt)
                    flushHourlyReports(config)
                }
            }
            if (snapshot.networkStatus != "offline") {
                pollCommands(config)
            }
            updateNotification("Location permission missing. Waiting for dashboard requests or hourly sync.")
            return
        }
        if (shouldCaptureRoutineState(config, routineCapturedAt)) {
            captureHourlyState(config, snapshot, routineCapturedAt)
        }
        if (snapshot.networkStatus == "offline") {
            updateNotification("Device is offline. Hourly state will sync when connection returns.")
            return
        }
        pollCommands(config)
    }

    private fun pollCommands(config: TrackerConfig) {
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
                    if (handleMissingDevice(error)) {
                        return@execute
                    }
                    updateNotification("Command sync failed: ${error.message}")
                }
        }
    }

    private fun syncStatusIfChanged(
        config: TrackerConfig,
        snapshot: DeviceSnapshot,
        capturedAt: String,
    ) {
        if (snapshot.networkStatus == "offline") {
            return
        }
        val locationServicesEnabled = isLocationProviderEnabled()
        val fingerprint = buildStatusFingerprint(snapshot, locationServicesEnabled)
        if (loadLastStatusFingerprint() == fingerprint) {
            return
        }
        ioExecutor.execute {
            val networkSnapshot = ApiClient.readNetworkSnapshot()
            runCatching {
                ApiClient.sendStatusUpdate(
                    config = config,
                    snapshot = snapshot,
                    locationServicesEnabled = locationServicesEnabled,
                    capturedAt = capturedAt,
                    networkSnapshot = networkSnapshot
                )
            }.onSuccess {
                saveLastStatusFingerprint(fingerprint)
            }.onFailure { error ->
                handleMissingDevice(error)
            }
        }
    }

    private fun captureHourlyState(
        config: TrackerConfig,
        snapshot: DeviceSnapshot,
        capturedAt: String,
    ) {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            ioExecutor.execute {
                queueHourlyUnavailable(config, snapshot, "location disabled", capturedAt)
                flushHourlyReports(config)
            }
            return
        }

        locationManager.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { location: Location? ->
            if (location == null) {
                ioExecutor.execute {
                    queueHourlyUnavailable(config, snapshot, "no GPS/network fix", capturedAt)
                    flushHourlyReports(config)
                }
                return@getCurrentLocation
            }
            ioExecutor.execute {
                HourlyReportStore.saveLastKnownLocation(this, location.latitude, location.longitude)
                queueHourlySuccess(config, snapshot, location, capturedAt)
                flushHourlyReports(config)
            }
        }
    }

    private fun pushCurrentLocation(
        config: TrackerConfig,
        requestedByServer: Boolean,
        capturedAt: String = DeviceStatus.currentDeviceTime(),
        commandId: Int? = null
    ) {
        val snapshot = DeviceStatus.read(this)
        if (!hasLocationPermission()) {
            ioExecutor.execute {
                val networkSnapshot = ApiClient.readNetworkSnapshot()
                runCatching {
                    ApiClient.sendLocationUnavailable(
                        config = config,
                        snapshot = snapshot,
                        capturedAt = capturedAt,
                        networkSnapshot = networkSnapshot
                    )
                    queueHourlyUnavailable(config, snapshot, "permission missing", capturedAt)
                    flushHourlyReports(config)
                    if (requestedByServer && commandId != null) {
                        ApiClient.completeCommand(config, commandId, "Location permission is missing on the device.")
                    }
                }.onSuccess {
                    updateNotification("Location permission missing on this device.")
                }.onFailure { error ->
                    if (handleMissingDevice(error)) {
                        return@execute
                    }
                    queueHourlyUnavailable(config, snapshot, "permission missing", capturedAt)
                    updateNotification("Send failed: ${error.message}")
                }
            }
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            ioExecutor.execute {
                val networkSnapshot = ApiClient.readNetworkSnapshot()
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
                    if (handleMissingDevice(error)) {
                        return@execute
                    }
                    queueHourlyUnavailable(config, snapshot, "location disabled", capturedAt)
                    updateNotification("Send failed: ${error.message}")
                }
            }
            return
        }

        locationManager.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { location: Location? ->
            if (location == null) {
                ioExecutor.execute {
                    queueHourlyUnavailable(config, snapshot, "no GPS/network fix", capturedAt)
                }
                updateNotification("Keep protecting, waiting for location")
                return@getCurrentLocation
            }
            ioExecutor.execute {
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
                    if (handleMissingDevice(error)) {
                        return@execute
                    }
                    HourlyReportStore.saveLastKnownLocation(this, location.latitude, location.longitude)
                    queueHourlySuccess(config, snapshot, location, capturedAt)
                    updateNotification("Queued hourly location for later sync")
                }
            }
        }
    }

    private fun isLocationProviderEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun buildStatusFingerprint(snapshot: DeviceSnapshot, locationServicesEnabled: Boolean): String {
        return listOf(
            snapshot.batteryLevel.toString(),
            snapshot.isCharging.toString(),
            snapshot.networkStatus,
            snapshot.wifiSsid.orEmpty(),
            snapshot.carrierName.orEmpty(),
            snapshot.localIp.orEmpty(),
            snapshot.deviceTimeZone,
            locationServicesEnabled.toString(),
        ).joinToString("|")
    }

    private fun loadLastStatusFingerprint(): String? {
        return getSharedPreferences(STATUS_PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_STATUS_FINGERPRINT, null)
    }

    private fun saveLastStatusFingerprint(fingerprint: String) {
        getSharedPreferences(STATUS_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATUS_FINGERPRINT, fingerprint)
            .apply()
    }

    private fun flushQueuedReportsAsync(config: TrackerConfig) {
        ioExecutor.execute {
            flushHourlyReports(config)
        }
    }

    private fun shouldCaptureRoutineState(config: TrackerConfig, capturedAt: String): Boolean {
        val deviceId = config.deviceId ?: return false
        return HourlyReportStore.shouldCaptureForTime(this, deviceId, capturedAt)
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
            } else if (handleMissingDevice(uploaded.exceptionOrNull())) {
                break
            } else {
                break
            }
        }
        HourlyReportStore.removeEntries(this, delivered)
    }

    private fun handleMissingDevice(error: Throwable?): Boolean {
        if (error == null || !ApiClient.isDeviceNotFound(error)) {
            return false
        }
        reEnrollDevice()
        return true
    }

    private fun reEnrollDevice() {
        if (reEnrollmentInProgress) {
            return
        }
        reEnrollmentInProgress = true
        ioExecutor.execute {
            updateNotification("Device was removed from dashboard. Re-enrolling.")
            TrackerPrefs.clearDeviceId(this)
            val config = TrackerPrefs.load(this)
            runCatching {
                val newDeviceId = ApiClient.enroll(config)
                TrackerPrefs.saveDeviceId(this, newDeviceId)
            }.onSuccess {
                updateNotification(getString(R.string.notification_body))
            }.onFailure { error ->
                updateNotification("Re-enroll failed: ${error.message}")
            }
            reEnrollmentInProgress = false
        }
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
                deviceTimeZone = snapshot.deviceTimeZone,
                deviceTimestampMs = snapshot.deviceTimestampMs,
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
        capturedAt: String = DeviceStatus.currentDeviceTime(),
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
                deviceTimeZone = snapshot.deviceTimeZone,
                deviceTimestampMs = snapshot.deviceTimestampMs,
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
        if (!shouldSurfaceNotification(content)) {
            return
        }
        if (lastNotificationContent == content) {
            return
        }
        lastNotificationContent = content
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun shouldSurfaceNotification(@Suppress("UNUSED_PARAMETER") content: String): Boolean {
        return false
    }

    companion object {
        private const val CHANNEL_ID = "lost_tracker_channel"
        private const val NOTIFICATION_ID = 1001
        private const val STATUS_PREFS_NAME = "tracker_status_state"
        private const val KEY_LAST_STATUS_FINGERPRINT = "last_status_fingerprint"
    }
}
