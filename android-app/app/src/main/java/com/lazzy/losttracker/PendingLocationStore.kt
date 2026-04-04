package com.lazzy.losttracker

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class QueuedLocation(
    val deviceId: String,
    val hourKey: String,
    val capturedAt: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkStatus: String,
    val wifiSsid: String?,
    val carrierName: String?,
    val localIp: String?,
    val publicIp: String?,
    val ispName: String?,
)

object PendingLocationStore {
    private const val PREFS_NAME = "lost_tracker_queue"
    private const val KEY_PENDING_LOCATIONS = "pending_locations"
    private val hourFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH")

    fun enqueueHourlyLocation(
        context: Context,
        deviceId: String,
        location: Location,
        snapshot: DeviceSnapshot,
        publicIp: String?,
        ispName: String?,
        capturedAt: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val items = readItems(prefs)
        val hourKey = hourKeyFor(capturedAt)
        val replacement = QueuedLocation(
            deviceId = deviceId,
            hourKey = hourKey,
            capturedAt = capturedAt,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = location.accuracy.toDouble(),
            batteryLevel = snapshot.batteryLevel,
            isCharging = snapshot.isCharging,
            networkStatus = snapshot.networkStatus,
            wifiSsid = snapshot.wifiSsid,
            carrierName = snapshot.carrierName,
            localIp = snapshot.localIp,
            publicIp = publicIp,
            ispName = ispName,
        )

        val filtered = items.filterNot { it.deviceId == deviceId && it.hourKey == hourKey }
        val updated = (filtered + replacement).sortedBy { it.capturedAt }
        prefs.edit().putString(KEY_PENDING_LOCATIONS, JSONArray(updated.map { it.toJson() }).toString()).apply()
    }

    fun getPendingLocations(context: Context, deviceId: String): List<QueuedLocation> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return readItems(prefs).filter { it.deviceId == deviceId }.sortedBy { it.capturedAt }
    }

    fun removePendingLocations(context: Context, entries: List<QueuedLocation>) {
        if (entries.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val toRemove = entries.map { "${it.deviceId}|${it.hourKey}|${it.capturedAt}" }.toSet()
        val remaining = readItems(prefs).filterNot {
            "${it.deviceId}|${it.hourKey}|${it.capturedAt}" in toRemove
        }
        prefs.edit().putString(KEY_PENDING_LOCATIONS, JSONArray(remaining.map { it.toJson() }).toString()).apply()
    }

    private fun readItems(prefs: android.content.SharedPreferences): List<QueuedLocation> {
        val raw = prefs.getString(KEY_PENDING_LOCATIONS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    QueuedLocation(
                        deviceId = item.getString("deviceId"),
                        hourKey = item.getString("hourKey"),
                        capturedAt = item.getString("capturedAt"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        accuracyM = item.optDouble("accuracyM").takeIf { !it.isNaN() },
                        batteryLevel = item.optInt("batteryLevel"),
                        isCharging = item.optBoolean("isCharging"),
                        networkStatus = item.optString("networkStatus"),
                        wifiSsid = item.optString("wifiSsid").takeIf { it.isNotBlank() },
                        carrierName = item.optString("carrierName").takeIf { it.isNotBlank() },
                        localIp = item.optString("localIp").takeIf { it.isNotBlank() },
                        publicIp = item.optString("publicIp").takeIf { it.isNotBlank() },
                        ispName = item.optString("ispName").takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }

    private fun QueuedLocation.toJson(): JSONObject {
        return JSONObject()
            .put("deviceId", deviceId)
            .put("hourKey", hourKey)
            .put("capturedAt", capturedAt)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("accuracyM", accuracyM)
            .put("batteryLevel", batteryLevel)
            .put("isCharging", isCharging)
            .put("networkStatus", networkStatus)
            .put("wifiSsid", wifiSsid)
            .put("carrierName", carrierName)
            .put("localIp", localIp)
            .put("publicIp", publicIp)
            .put("ispName", ispName)
    }

    private fun hourKeyFor(capturedAt: String): String {
        val instant = Instant.parse(capturedAt)
        return hourFormatter.format(instant.atZone(ZoneId.systemDefault()))
    }
}
