package com.lazzy.losttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_RESTART_TRACKER_SERVICE &&
            action != ACTION_ENSURE_TRACKER_SERVICE_RUNNING &&
            action != ACTION_FCM_WAKE_TRACKER_SERVICE
        ) {
            return
        }
        val config = TrackerPrefs.load(context)
        if (config.deviceId != null) {
            TrackerServiceLauncher.start(context, syncNow = action == ACTION_FCM_WAKE_TRACKER_SERVICE)
            TrackerServiceLauncher.scheduleStartupFallback(
                context,
                syncNow = action == ACTION_FCM_WAKE_TRACKER_SERVICE,
                delayMs = 20_000L,
            )
        }
    }

    companion object {
        const val ACTION_RESTART_TRACKER_SERVICE = "com.lazzy.losttracker.action.RESTART_TRACKER_SERVICE"
        const val ACTION_ENSURE_TRACKER_SERVICE_RUNNING = "com.lazzy.losttracker.action.ENSURE_TRACKER_SERVICE_RUNNING"
        const val ACTION_FCM_WAKE_TRACKER_SERVICE = "com.lazzy.losttracker.action.FCM_WAKE_TRACKER_SERVICE"
    }
}
