package com.lazzy.losttracker

import android.content.Context
import android.net.Uri
import android.os.Build
import java.util.UUID

data class TrackerConfig(
    val serverUrl: String,
    val ownerEmail: String,
    val deviceName: String,
    val deviceToken: String,
    val deviceId: String?,
    val pushToken: String?
)

object TrackerPrefs {
    const val DEFAULT_SERVER_URL = "https://app.anuditk.com.np"
    private val DEVICE_ID_PATTERN = Regex("^[0-9]{16}$")
    private const val PREFS_NAME = "lost_tracker_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_OWNER_EMAIL = "owner_email"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PUSH_TOKEN = "push_token"
    private const val KEY_BACKGROUND_PROMPT_SHOWN = "background_prompt_shown"
    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    fun load(context: Context): TrackerConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_DEVICE_TOKEN, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_TOKEN, it).apply()
        }
        val rawDeviceId = prefs.all[KEY_DEVICE_ID]
        val storedDeviceId = when (rawDeviceId) {
            is String -> rawDeviceId.trim().takeIf { it.isNotEmpty() }
            is Int -> rawDeviceId.takeIf { it > 0 }?.toString()
            else -> null
        }?.takeIf { DEVICE_ID_PATTERN.matches(it) }
        val fallbackName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val fallbackOwner = "device-${token.take(12)}@tracker.local"
        val resolvedServerUrl = normalizeServerUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))
        return TrackerConfig(
            serverUrl = resolvedServerUrl,
            ownerEmail = prefs.getString(KEY_OWNER_EMAIL, fallbackOwner).orEmpty(),
            deviceName = prefs.getString(KEY_DEVICE_NAME, fallbackName).orEmpty(),
            deviceToken = token,
            deviceId = storedDeviceId,
            pushToken = prefs.getString(KEY_PUSH_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun ensureDefaults(context: Context) {
        val config = load(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_OWNER_EMAIL, config.ownerEmail)
            .putString(KEY_DEVICE_NAME, config.deviceName)
            .apply()
    }

    fun saveDeviceId(context: Context, deviceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun clearDeviceId(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    fun savePushToken(context: Context, pushToken: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PUSH_TOKEN, pushToken)
            .apply()
    }

    fun hasShownBackgroundPrompt(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_PROMPT_SHOWN, false)
    }

    fun markBackgroundPromptShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_PROMPT_SHOWN, true)
            .apply()
    }

    private fun normalizeServerUrl(rawValue: String?): String {
        val cleaned = rawValue.orEmpty().trim().trimEnd('/')
        if (cleaned.isBlank()) {
            return DEFAULT_SERVER_URL
        }
        val uri = runCatching { Uri.parse(cleaned) }.getOrNull() ?: return DEFAULT_SERVER_URL
        val host = uri.host?.lowercase()
        if (host != null && host in LOCAL_HOSTS) {
            return DEFAULT_SERVER_URL
        }
        return cleaned
    }
}
