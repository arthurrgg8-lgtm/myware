package com.lazzy.losttracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HourlyReportEntry(
    val deviceId: String,
    val hourKey: String,
    val status: String,
    val reason: String?,
    val capturedAt: String,
    val deviceTimeZone: String,
    val deviceTimestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val lastKnownLatitude: Double?,
    val lastKnownLongitude: Double?,
    val accuracyM: Double?,
    val batteryLevel: Int,
    val networkStatus: String,
)

data class LastKnownLocation(
    val latitude: Double,
    val longitude: Double,
    val recordedAtMs: Long,
)

object HourlyReportStore {
    private const val PREFS_NAME = "lost_tracker_hourly_reports"
    private const val KEY_QUEUE = "queue"
    private const val KEY_RECORDED_PREFIX = "recorded_hour_"
    private const val KEY_LAST_KNOWN_LATITUDE = "last_known_latitude"
    private const val KEY_LAST_KNOWN_LONGITUDE = "last_known_longitude"
    private const val KEY_LAST_KNOWN_RECORDED_AT_MS = "last_known_recorded_at_ms"
    private val hourFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH")

    fun saveLastKnownLocation(
        context: Context,
        latitude: Double,
        longitude: Double,
        recordedAtMs: Long = System.currentTimeMillis(),
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_KNOWN_LATITUDE, latitude.toString())
            .putString(KEY_LAST_KNOWN_LONGITUDE, longitude.toString())
            .putLong(KEY_LAST_KNOWN_RECORDED_AT_MS, recordedAtMs)
            .apply()
    }

    fun getLastKnownLocation(context: Context): LastKnownLocation? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lat = prefs.getString(KEY_LAST_KNOWN_LATITUDE, null)?.toDoubleOrNull()
        val lon = prefs.getString(KEY_LAST_KNOWN_LONGITUDE, null)?.toDoubleOrNull()
        val recordedAtMs = prefs.getLong(KEY_LAST_KNOWN_RECORDED_AT_MS, 0L)
        return if (lat != null && lon != null && recordedAtMs > 0L) {
            LastKnownLocation(lat, lon, recordedAtMs)
        } else {
            null
        }
    }

    fun currentHourKey(capturedAt: String): String {
        val instant = Instant.parse(capturedAt)
        return hourFormatter.format(instant.atZone(ZoneId.systemDefault()))
    }

    fun shouldCaptureForTime(context: Context, deviceId: String, capturedAt: String): Boolean {
        return !hasEntryForHour(context, deviceId, currentHourKey(capturedAt))
    }

    fun hasEntryForHour(context: Context, deviceId: String, hourKey: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recordedHour = prefs.getString("$KEY_RECORDED_PREFIX$deviceId", null)
        return recordedHour == hourKey || getQueuedEntries(context, deviceId).any { it.hourKey == hourKey }
    }

    fun upsertEntry(context: Context, entry: HourlyReportEntry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remaining = readQueue(prefs).filterNot { it.deviceId == entry.deviceId && it.hourKey == entry.hourKey }
        val updated = (remaining + entry).sortedBy { it.capturedAt }
        prefs.edit()
            .putString(KEY_QUEUE, JSONArray(updated.map { it.toJson() }).toString())
            .putString("$KEY_RECORDED_PREFIX${entry.deviceId}", entry.hourKey)
            .apply()
    }

    fun getQueuedEntries(context: Context, deviceId: String): List<HourlyReportEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return readQueue(prefs).filter { it.deviceId == deviceId }.sortedBy { it.capturedAt }
    }

    fun removeEntries(context: Context, entries: List<HourlyReportEntry>) {
        if (entries.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val toRemove = entries.map { "${it.deviceId}|${it.hourKey}" }.toSet()
        val updated = readQueue(prefs).filterNot { "${it.deviceId}|${it.hourKey}" in toRemove }
        prefs.edit().putString(KEY_QUEUE, JSONArray(updated.map { it.toJson() }).toString()).apply()
    }

    private fun readQueue(prefs: android.content.SharedPreferences): List<HourlyReportEntry> {
        val array = runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    HourlyReportEntry(
                        deviceId = item.getString("deviceId"),
                        hourKey = item.getString("hourKey"),
                        status = item.getString("status"),
                        reason = item.optString("reason").takeIf { it.isNotBlank() },
                        capturedAt = item.getString("capturedAt"),
                        deviceTimeZone = item.optString("deviceTimeZone").takeIf { it.isNotBlank() }
                            ?: ZoneId.systemDefault().id,
                        deviceTimestampMs = item.optLong("deviceTimestampMs"),
                        latitude = item.optDouble("latitude").takeIf { !it.isNaN() },
                        longitude = item.optDouble("longitude").takeIf { !it.isNaN() },
                        lastKnownLatitude = item.optDouble("lastKnownLatitude").takeIf { !it.isNaN() },
                        lastKnownLongitude = item.optDouble("lastKnownLongitude").takeIf { !it.isNaN() },
                        accuracyM = item.optDouble("accuracyM").takeIf { !it.isNaN() },
                        batteryLevel = item.optInt("batteryLevel"),
                        networkStatus = item.optString("networkStatus"),
                    )
                )
            }
        }
    }

    private fun HourlyReportEntry.toJson(): JSONObject {
        return JSONObject()
            .put("deviceId", deviceId)
            .put("hourKey", hourKey)
            .put("status", status)
            .put("reason", reason)
            .put("capturedAt", capturedAt)
            .put("deviceTimeZone", deviceTimeZone)
            .put("deviceTimestampMs", deviceTimestampMs)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("lastKnownLatitude", lastKnownLatitude)
            .put("lastKnownLongitude", lastKnownLongitude)
            .put("accuracyM", accuracyM)
            .put("batteryLevel", batteryLevel)
            .put("networkStatus", networkStatus)
    }
}
