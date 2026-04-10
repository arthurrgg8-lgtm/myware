package com.lazzy.losttracker

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TrackerService : Service() {
    private data class LocationResolution(
        val location: Location?,
        val isFresh: Boolean,
    )

    private data class CachedLocationResolution(
        val location: Location?,
        val isRecent: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val heartbeatExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val compatibilityProfile = DeviceCompatibility.currentProfile()
    private val pollIntervalMs = compatibilityProfile.commandPollIntervalMs
    private val heartbeatIntervalMs = compatibilityProfile.heartbeatIntervalMs
    private val watchdogIntervalMs = compatibilityProfile.watchdogIntervalMs
    private val inFlightCommandIds = Collections.synchronizedSet(mutableSetOf<Int>())
    @Volatile
    private var lastNotificationContent: String? = null
    @Volatile
    private var reEnrollmentInProgress = false
    @Volatile
    private var lastHeartbeatSentAtMs = 0L
    @Volatile
    private var lastObservedNetworkStatus: String? = null
    @Volatile
    private var restartScheduled = false
    @Volatile
    private var serviceWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            lastHeartbeatSentAtMs = 0L
            handler.post {
                scheduleWatchdog()
                runCompatibilitySync()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            runCompatibilitySync()
            scheduleWatchdog()
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (BuildConfig.ENABLE_PERSISTENT_WAKE_LOCK) {
            acquireServiceWakeLock()
        }
        if (BuildConfig.ENABLE_PERSISTENT_WIFI_LOCK) {
            acquireWifiLock()
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restartScheduled = false
        TrackerServiceLauncher.cancelStartupFallback(this)
        TrackerServiceLauncher.cancelStartupFallback(this, syncNow = true)
        val content = getString(R.string.notification_body)
        lastNotificationContent = content
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(content),
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )
        handler.removeCallbacks(tick)
        scheduleWatchdog()
        if (intent?.action == ACTION_SYNC_NOW) {
            lastHeartbeatSentAtMs = 0L
            handler.post { runCompatibilitySync() }
        }
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        commandExecutor.shutdown()
        heartbeatExecutor.shutdown()
        ioExecutor.shutdown()
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        serviceWakeLock?.releaseSafe()
        serviceWakeLock = null
        wifiLock?.releaseSafe()
        wifiLock = null
        scheduleSelfRestart()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleSelfRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runCompatibilitySync() {
        val wakeLock = acquireTransientSyncWakeLock()
        try {
            syncWithServer()
        } finally {
            wakeLock?.releaseSafe()
        }
    }

    private fun syncWithServer() {
        val config = TrackerPrefs.load(this)
        if (config.deviceId == null) {
            stopSelf()
            return
        }
        pollCommands(config)
        val snapshot = DeviceStatus.readMinimal(this)
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
    }

    private fun pollCommands(config: TrackerConfig) {
        commandExecutor.execute {
            runCatching { ApiClient.fetchCommands(config) }
                .onSuccess { commands ->
                    commands.filter {
                        it.commandType == "request_location" ||
                            it.commandType == "request_details" ||
                            it.commandType == "hard_fetch"
                    }.forEach { command ->
                        if (!inFlightCommandIds.add(command.id)) {
                            return@forEach
                        }
                        handler.post {
                            when (command.commandType) {
                                "request_location" -> pushCurrentLocation(config, requestedByServer = true, commandId = command.id)
                                "request_details" -> pushCurrentDetails(config, command.id)
                                "hard_fetch" -> performHardFetch(config, command.id)
                            }
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
        val previousNetworkStatus = lastObservedNetworkStatus
        lastObservedNetworkStatus = snapshot.networkStatus
        if (snapshot.networkStatus == "offline") {
            lastHeartbeatSentAtMs = 0L
            return
        }
        val networkRecovered = previousNetworkStatus == "offline" && snapshot.networkStatus != "offline"
        val shouldSendHeartbeat = SystemClock.elapsedRealtime() - lastHeartbeatSentAtMs >= heartbeatIntervalMs
        if (!networkRecovered && !shouldSendHeartbeat) {
            return
        }
        heartbeatExecutor.execute {
            val networkSnapshot = ApiClient.NetworkSnapshot(null, null)
            runCatching {
                ApiClient.sendStatusUpdate(
                    config = config,
                    snapshot = snapshot,
                    locationServicesEnabled = isLocationProviderEnabled(),
                    capturedAt = capturedAt,
                    networkSnapshot = networkSnapshot
                )
            }.onSuccess {
                lastHeartbeatSentAtMs = SystemClock.elapsedRealtime()
                ioExecutor.execute {
                    flushHourlyReports(config)
                }
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
        val enabledProviders = buildList {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        if (enabledProviders.isEmpty()) {
            ioExecutor.execute {
                queueHourlyUnavailable(config, snapshot, "location disabled", capturedAt)
                flushHourlyReports(config)
            }
            return
        }

        resolveBestLocation(locationManager, enabledProviders) { resolution ->
            val location = resolution.location
            if (location == null) {
                ioExecutor.execute {
                    queueHourlyUnavailable(config, snapshot, "no GPS/network fix", capturedAt)
                    flushHourlyReports(config)
                }
                return@resolveBestLocation
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
        commandId: Int? = null,
        completionNotes: String = "Fresh location received from device.",
        wakeLock: PowerManager.WakeLock? = null
    ) {
        val snapshot = if (requestedByServer) DeviceStatus.readMinimal(this) else DeviceStatus.read(this)
        val isCommandFetch = commandId != null
        if (!hasLocationPermission()) {
            ioExecutor.execute {
                val networkSnapshot = if (requestedByServer) {
                    ApiClient.NetworkSnapshot(null, null)
                } else {
                    ApiClient.readNetworkSnapshot()
                }
                runCatching {
                    ApiClient.sendLocationUnavailable(
                        config = config,
                        snapshot = snapshot,
                        capturedAt = capturedAt,
                        networkSnapshot = networkSnapshot
                    )
                    if (!isCommandFetch) {
                        queueHourlyUnavailable(config, snapshot, "permission missing", capturedAt)
                        flushHourlyReports(config)
                    }
                    if (requestedByServer && commandId != null) {
                        ApiClient.completeCommand(config, commandId, "Location permission missing on device.")
                    } else if (commandId != null) {
                        ApiClient.completeCommand(config, commandId, "Hard fetch failed: location permission missing.")
                    }
                }.onSuccess {
                    updateNotification("Location permission missing on this device.")
                }.onFailure { error ->
                    if (handleMissingDevice(error)) {
                        releaseCommand(commandId)
                        wakeLock?.releaseSafe()
                        return@execute
                    }
                    if (!isCommandFetch) {
                        queueHourlyUnavailable(config, snapshot, "permission missing", capturedAt)
                    }
                    updateNotification("Send failed: ${error.message}")
                }.also {
                    releaseCommand(commandId)
                    wakeLock?.releaseSafe()
                }
            }
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val enabledProviders = buildList {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        if (enabledProviders.isEmpty()) {
            ioExecutor.execute {
                val networkSnapshot = if (requestedByServer) {
                    ApiClient.NetworkSnapshot(null, null)
                } else {
                    ApiClient.readNetworkSnapshot()
                }
                runCatching {
                    ApiClient.sendLocationUnavailable(
                        config = config,
                        snapshot = snapshot,
                        capturedAt = capturedAt,
                        networkSnapshot = networkSnapshot
                    )
                    if (!isCommandFetch) {
                        queueHourlyUnavailable(config, snapshot, "location disabled", capturedAt)
                        flushHourlyReports(config)
                    }
                    if (requestedByServer && commandId != null) {
                        ApiClient.completeCommand(config, commandId, "Location services disabled on device.")
                    } else if (commandId != null) {
                        ApiClient.completeCommand(config, commandId, "Hard fetch failed: location services disabled.")
                    }
                }.onSuccess {
                    updateNotification("Location services are off on this device.")
                }.onFailure { error ->
                    if (handleMissingDevice(error)) {
                        releaseCommand(commandId)
                        wakeLock?.releaseSafe()
                        return@execute
                    }
                    if (!isCommandFetch) {
                        queueHourlyUnavailable(config, snapshot, "location disabled", capturedAt)
                    }
                    updateNotification("Send failed: ${error.message}")
                }.also {
                    releaseCommand(commandId)
                    wakeLock?.releaseSafe()
                }
            }
            return
        }

        resolveBestLocation(locationManager, enabledProviders) { resolution ->
            ioExecutor.execute {
                val location = resolution.location
                if (location == null) {
                    if (!isCommandFetch) {
                        queueHourlyUnavailable(config, snapshot, "no GPS/network fix", capturedAt)
                    }
                    if (commandId != null) {
                        runCatching {
                            ApiClient.completeCommand(
                                config,
                                commandId,
                                if (requestedByServer) "No fresh or recent location fix available." else "Hard fetch failed: no fresh or recent location fix."
                            )
                        }
                    }
                    updateNotification("Keep protecting, waiting for location")
                    releaseCommand(commandId)
                    wakeLock?.releaseSafe()
                    return@execute
                }
                val networkSnapshot = if (requestedByServer) {
                    ApiClient.NetworkSnapshot(null, null)
                } else {
                    ApiClient.readNetworkSnapshot()
                }
                runCatching {
                    ApiClient.sendLocation(
                        config = config,
                        location = location,
                        snapshot = snapshot,
                        capturedAt = capturedAt,
                        networkSnapshot = networkSnapshot
                    )
                    if (!isCommandFetch) {
                        HourlyReportStore.saveLastKnownLocation(this, location.latitude, location.longitude)
                        queueHourlySuccess(config, snapshot, location, capturedAt)
                        flushHourlyReports(config)
                    }
                    if (commandId != null) {
                        val resolvedNotes = if (resolution.isFresh) {
                            completionNotes
                        } else {
                            "Recent last-known location returned; fresh fix unavailable."
                        }
                        ApiClient.completeCommand(config, commandId, resolvedNotes)
                    }
                }.onSuccess {
                    updateNotification(
                        if (resolution.isFresh) getString(R.string.notification_body)
                        else "Returned recent last-known location"
                    )
                }.onFailure { error ->
                    if (handleMissingDevice(error)) {
                        releaseCommand(commandId)
                        wakeLock?.releaseSafe()
                        return@execute
                    }
                    if (!isCommandFetch) {
                        HourlyReportStore.saveLastKnownLocation(this, location.latitude, location.longitude)
                        queueHourlySuccess(config, snapshot, location, capturedAt)
                        updateNotification("Queued hourly location for later sync")
                    } else {
                        updateNotification("Location upload failed. Waiting for connection.")
                    }
                }.also {
                    releaseCommand(commandId)
                    wakeLock?.releaseSafe()
                }
            }
        }
    }

    private fun resolveBestLocation(
        locationManager: LocationManager,
        providers: List<String>,
        onResolved: (LocationResolution) -> Unit,
    ) {
        val cachedFallback = readCachedLastKnownLocation()
        val osFallback = readBestLastKnownLocation(locationManager)
        val fallback = cachedFallback.location ?: osFallback
        val resolved = AtomicBoolean(false)
        val listeners = mutableListOf<LocationListener>()
        val fusedCancellation = CancellationTokenSource()
        var timeout: Runnable? = null

        fun finish(location: Location?, isFresh: Boolean) {
            if (resolved.compareAndSet(false, true)) {
                timeout?.let { handler.removeCallbacks(it) }
                fusedCancellation.cancel()
                listeners.forEach { listener ->
                    runCatching { locationManager.removeUpdates(listener) }
                }
                onResolved(LocationResolution(location, isFresh))
            }
        }

        timeout = Runnable {
            finish(fallback, isFresh = false)
        }

        runCatching {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null && cachedFallback.location == null && osFallback == null) {
                        finish(location, false)
                    }
                }
        }

        runCatching {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                fusedCancellation.token
            ).addOnSuccessListener(mainExecutor) { location ->
                if (location != null) {
                    finish(location, true)
                }
            }
        }

        providers.forEach { provider ->
            val listener = LocationListener { location ->
                finish(location, isFresh = true)
            }
            listeners += listener
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            providers.forEach { provider ->
                locationManager.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { location: Location? ->
                    if (location != null) {
                        finish(location, isFresh = true)
                    }
                }
            }
        }

        val timeoutMs = if (cachedFallback.isRecent) 4_000L else 12_000L
        handler.postDelayed(timeout, timeoutMs)
    }

    private fun readBestLastKnownLocation(locationManager: LocationManager): Location? {
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).asSequence()
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    private fun readCachedLastKnownLocation(): CachedLocationResolution {
        val cached = HourlyReportStore.getLastKnownLocation(this) ?: return CachedLocationResolution(null, false)
        val ageMs = System.currentTimeMillis() - cached.recordedAtMs
        val location = Location(LocationManager.PASSIVE_PROVIDER).apply {
            latitude = cached.latitude
            longitude = cached.longitude
            time = cached.recordedAtMs
        }
        return CachedLocationResolution(location, ageMs <= 15 * 60 * 1000L)
    }

    private fun pushCurrentDetails(config: TrackerConfig, commandId: Int) {
        val snapshot = DeviceStatus.read(this)
        val capturedAt = snapshot.deviceTime
        ioExecutor.execute {
            val networkSnapshot = ApiClient.readNetworkSnapshot()
            runCatching {
                ApiClient.sendStatusUpdate(
                    config = config,
                    snapshot = snapshot,
                    locationServicesEnabled = isLocationProviderEnabled(),
                    capturedAt = capturedAt,
                    networkSnapshot = networkSnapshot
                )
                ApiClient.completeCommand(config, commandId, "Fresh device details uploaded from phone.")
            }.onFailure { error ->
                if (handleMissingDevice(error)) {
                    releaseCommand(commandId)
                    return@execute
                }
                updateNotification("Send failed: ${error.message}")
            }.also {
                releaseCommand(commandId)
            }
        }
    }

    private fun performHardFetch(config: TrackerConfig, commandId: Int) {
        scheduleWatchdog()
        val wakeLock = acquireHardFetchWakeLock()
        val snapshot = DeviceStatus.read(this)
        val capturedAt = snapshot.deviceTime
        ioExecutor.execute {
            val networkSnapshot = ApiClient.readNetworkSnapshot()
            runCatching {
                ApiClient.sendStatusUpdate(
                    config = config,
                    snapshot = snapshot,
                    locationServicesEnabled = isLocationProviderEnabled(),
                    capturedAt = capturedAt,
                    networkSnapshot = networkSnapshot
                )
            }.onFailure { error ->
                if (handleMissingDevice(error)) {
                    releaseCommand(commandId)
                    wakeLock?.releaseSafe()
                    return@execute
                }
            }
            handler.post {
                pushCurrentLocation(
                    config = config,
                    requestedByServer = false,
                    capturedAt = capturedAt,
                    commandId = commandId,
                    completionNotes = "Hard fetch completed with aggressive status and location refresh.",
                    wakeLock = wakeLock
                )
            }
        }
    }

    private fun releaseCommand(commandId: Int?) {
        if (commandId != null) {
            inFlightCommandIds.remove(commandId)
        }
    }

    private fun acquireHardFetchWakeLock(): PowerManager.WakeLock? {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return null
        return runCatching {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:hard-fetch"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60_000L)
            }
        }.getOrNull()
    }

    private fun acquireTransientSyncWakeLock(): PowerManager.WakeLock? {
        if (!compatibilityProfile.useTransientSyncWakeLock) {
            return null
        }
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return null
        return runCatching {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:compat-sync"
            ).apply {
                setReferenceCounted(false)
                acquire(compatibilityProfile.syncWakeLockTimeoutMs)
            }
        }.getOrNull()
    }

    private fun acquireServiceWakeLock() {
        val existing = serviceWakeLock
        if (existing?.isHeld == true) {
            return
        }
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        serviceWakeLock = runCatching {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:service-heartbeat"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun acquireWifiLock() {
        val existing = wifiLock
        if (existing?.isHeld == true) {
            return
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = runCatching {
            wifiManager.createWifiLock(
                if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                },
                "$packageName:service-wifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun PowerManager.WakeLock.releaseSafe() {
        runCatching {
            if (isHeld) {
                release()
            }
        }
    }

    private fun WifiManager.WifiLock.releaseSafe() {
        runCatching {
            if (isHeld) {
                release()
            }
        }
    }

    private fun isLocationProviderEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
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
                lastKnownLatitude = lastKnown?.latitude,
                lastKnownLongitude = lastKnown?.longitude,
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
        val channel = NotificationChannel(CHANNEL_ID, "Google Services", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            description = "Background protection status"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun scheduleSelfRestart() {
        if (restartScheduled) {
            return
        }
        val config = TrackerPrefs.load(this)
        if (config.deviceId == null) {
            return
        }
        restartScheduled = true
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val restartIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_RESTART_TRACKER_SERVICE
            `package` = packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 3_000L,
                pendingIntent
            )
        }.onFailure {
            restartScheduled = false
        }
    }

    private fun scheduleWatchdog() {
        val config = TrackerPrefs.load(this)
        if (config.deviceId == null) {
            return
        }
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val watchdogIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_ENSURE_TRACKER_SERVICE_RUNNING
            `package` = packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1002,
            watchdogIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + watchdogIntervalMs,
                pendingIntent
            )
        }
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
        const val ACTION_SYNC_NOW = "com.lazzy.losttracker.action.SYNC_NOW"
        private const val CHANNEL_ID = "lost_tracker_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
