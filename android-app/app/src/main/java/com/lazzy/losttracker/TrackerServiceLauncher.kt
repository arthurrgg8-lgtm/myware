package com.lazzy.losttracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat

object TrackerServiceLauncher {
    fun start(context: Context, syncNow: Boolean = false) {
        val serviceIntent = Intent(context, TrackerService::class.java).apply {
            if (syncNow) {
                action = TrackerService.ACTION_SYNC_NOW
            }
        }
        runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure {
            scheduleStartupFallback(context, syncNow = syncNow, delayMs = 1_500L)
        }
    }

    fun scheduleStartupFallback(
        context: Context,
        syncNow: Boolean = false,
        delayMs: Long = 15_000L,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val action = if (syncNow) {
            ServiceRestartReceiver.ACTION_FCM_WAKE_TRACKER_SERVICE
        } else {
            ServiceRestartReceiver.ACTION_ENSURE_TRACKER_SERVICE_RUNNING
        }
        val fallbackIntent = Intent(context, ServiceRestartReceiver::class.java).apply {
            this.action = action
            `package` = context.packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            if (syncNow) 1003 else 1002,
            fallbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
        }
    }

    fun cancelStartupFallback(context: Context, syncNow: Boolean = false) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val action = if (syncNow) {
            ServiceRestartReceiver.ACTION_FCM_WAKE_TRACKER_SERVICE
        } else {
            ServiceRestartReceiver.ACTION_ENSURE_TRACKER_SERVICE_RUNNING
        }
        val fallbackIntent = Intent(context, ServiceRestartReceiver::class.java).apply {
            this.action = action
            `package` = context.packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            if (syncNow) 1003 else 1002,
            fallbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
