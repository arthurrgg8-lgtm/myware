package com.lazzy.losttracker

import android.location.Location
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    data class PendingCommand(
        val id: Int,
        val commandType: String
    )

    private data class PublicNetworkInfo(
        val ip: String?,
        val isp: String?
    )

    data class NetworkSnapshot(
        val publicIp: String?,
        val ispName: String?
    )

    @Volatile
    private var cachedPublicNetworkInfo: PublicNetworkInfo? = null

    @Volatile
    private var cachedPublicNetworkInfoAtMs: Long = 0L

    private val publicNetworkInfoEndpoints = listOf(
        "https://ipwho.is/",
        "https://ipapi.co/json/"
    )

    fun enroll(config: TrackerConfig): String {
        val payload = JSONObject()
            .put("name", config.deviceName)
            .put("ownerEmail", config.ownerEmail)
            .put("platform", "android")
            .put("deviceToken", config.deviceToken)
        val response = request("${config.serverUrl}/api/devices", "POST", payload)
        return response.getJSONObject("device").getString("id")
    }

    fun readNetworkSnapshot(): NetworkSnapshot {
        val info = readPublicNetworkInfo()
        return NetworkSnapshot(
            publicIp = info.ip,
            ispName = info.isp
        )
    }

    fun sendLocation(
        config: TrackerConfig,
        location: Location,
        snapshot: DeviceSnapshot,
        capturedAt: String = java.time.Instant.now().toString(),
        networkSnapshot: NetworkSnapshot = readNetworkSnapshot()
    ) {
        val deviceId = config.deviceId ?: error("Device is not enrolled yet.")
        val payload = buildLocationPayload(
            snapshot = snapshot,
            networkSnapshot = networkSnapshot,
            capturedAt = capturedAt,
            locationServicesEnabled = true,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = location.accuracy.toDouble()
        )
        request("${config.serverUrl}/api/devices/$deviceId/location", "POST", payload)
    }

    fun sendLocationUnavailable(
        config: TrackerConfig,
        snapshot: DeviceSnapshot,
        capturedAt: String = java.time.Instant.now().toString(),
        networkSnapshot: NetworkSnapshot = readNetworkSnapshot()
    ) {
        val deviceId = config.deviceId ?: error("Device is not enrolled yet.")
        val payload = buildLocationPayload(
            snapshot = snapshot,
            networkSnapshot = networkSnapshot,
            capturedAt = capturedAt,
            locationServicesEnabled = false
        )
        request("${config.serverUrl}/api/devices/$deviceId/location", "POST", payload)
    }

    fun sendHourlyReport(config: TrackerConfig, report: HourlyReportEntry) {
        val deviceId = config.deviceId ?: error("Device is not enrolled yet.")
        val payload = JSONObject()
            .put("hourKey", report.hourKey)
            .put("status", report.status)
            .put("reason", report.reason)
            .put("capturedAt", report.capturedAt)
            .put("latitude", report.latitude)
            .put("longitude", report.longitude)
            .put("lastKnownLatitude", report.lastKnownLatitude)
            .put("lastKnownLongitude", report.lastKnownLongitude)
            .put("accuracyM", report.accuracyM)
            .put("batteryLevel", report.batteryLevel)
            .put("networkStatus", report.networkStatus)
        request("${config.serverUrl}/api/devices/$deviceId/hourly-reports", "POST", payload)
    }

    fun fetchCommands(config: TrackerConfig): List<PendingCommand> {
        val deviceId = config.deviceId ?: return emptyList()
        val response = request("${config.serverUrl}/api/devices/$deviceId/commands", "GET")
        val items = response.getJSONArray("commands")
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(PendingCommand(item.getInt("id"), item.getString("commandType")))
            }
        }
    }

    fun completeCommand(config: TrackerConfig, commandId: Int, notes: String) {
        val deviceId = config.deviceId ?: error("Device is not enrolled yet.")
        val payload = JSONObject().put("notes", notes)
        request("${config.serverUrl}/api/devices/$deviceId/commands/$commandId/complete", "POST", payload)
    }

    private fun request(endpoint: String, method: String, payload: JSONObject? = null): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doInput = true

        if (payload != null) {
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
        }

        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = BufferedReader(stream.reader()).use { it.readText() }
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException(body.ifBlank { "Request failed with ${connection.responseCode}" })
        }
        return JSONObject(body)
    }

    private fun buildLocationPayload(
        snapshot: DeviceSnapshot,
        networkSnapshot: NetworkSnapshot,
        capturedAt: String,
        locationServicesEnabled: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyM: Double? = null
    ): JSONObject {
        return JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("accuracyM", accuracyM)
            .put("batteryLevel", snapshot.batteryLevel)
            .put("isCharging", snapshot.isCharging)
            .put("networkStatus", snapshot.networkStatus)
            .put("wifiSsid", snapshot.wifiSsid)
            .put("carrierName", snapshot.carrierName)
            .put("localIp", snapshot.localIp)
            .put("publicIp", networkSnapshot.publicIp)
            .put("ispName", networkSnapshot.ispName)
            .put("locationServicesEnabled", locationServicesEnabled)
            .put("capturedAt", capturedAt)
    }

    private fun readPublicNetworkInfo(): PublicNetworkInfo {
        val now = System.currentTimeMillis()
        val cached = cachedPublicNetworkInfo
        if (cached != null && now - cachedPublicNetworkInfoAtMs < 5 * 60_000L) {
            return cached
        }

        val fresh = publicNetworkInfoEndpoints
            .asSequence()
            .mapNotNull { endpoint -> runCatching { requestPublicNetworkInfo(endpoint) }.getOrNull() }
            .firstOrNull { it.ip != null || it.isp != null }

        if (fresh != null) {
            cachedPublicNetworkInfo = fresh
            cachedPublicNetworkInfoAtMs = now
            return fresh
        }

        return cached ?: PublicNetworkInfo(null, null)
    }

    private fun requestPublicNetworkInfo(endpoint: String): PublicNetworkInfo {
        val response = request(endpoint, "GET")
        val connection = response.optJSONObject("connection")
        return PublicNetworkInfo(
            ip = response.optString("ip").takeIf { it.isNotBlank() }
                ?: response.optString("ip_address").takeIf { it.isNotBlank() },
            isp = connection?.optString("isp")?.takeIf { it.isNotBlank() }
                ?: connection?.optString("org")?.takeIf { it.isNotBlank() }
                ?: connection?.optString("asn")?.takeIf { it.isNotBlank() }
                ?: response.optString("org").takeIf { it.isNotBlank() }
                ?: response.optString("asn").takeIf { it.isNotBlank() }
                ?: response.optString("isp").takeIf { it.isNotBlank() }
        )
    }
}
