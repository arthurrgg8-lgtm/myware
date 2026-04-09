package com.lazzy.losttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTART_TRACKER_SERVICE &&
            intent.action != ACTION_ENSURE_TRACKER_SERVICE_RUNNING &&
            intent.action != ACTION_FCM_WAKE_TRACKER_SERVICE
        ) {
            return
        }
        val config = TrackerPrefs.load(context)
        if (config.deviceId != null) {
            val serviceIntent = Intent(context, TrackerService::class.java)
            if (intent.action == ACTION_FCM_WAKE_TRACKER_SERVICE) {
                serviceIntent.action = TrackerService.ACTION_SYNC_NOW
            }
            runCatching {
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }

    companion object {
        const val ACTION_RESTART_TRACKER_SERVICE = "com.lazzy.losttracker.action.RESTART_TRACKER_SERVICE"
        const val ACTION_ENSURE_TRACKER_SERVICE_RUNNING = "com.lazzy.losttracker.action.ENSURE_TRACKER_SERVICE_RUNNING"
        const val ACTION_FCM_WAKE_TRACKER_SERVICE = "com.lazzy.losttracker.action.FCM_WAKE_TRACKER_SERVICE"
    }
}
