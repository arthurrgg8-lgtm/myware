package com.lazzy.losttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val config = TrackerPrefs.load(context)
        if (config.deviceId != null) {
            TrackerServiceLauncher.start(context)
            TrackerServiceLauncher.scheduleStartupFallback(context, delayMs = 20_000L)
        }
    }
}
