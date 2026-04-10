package com.lazzy.losttracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TrackerFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (token.isBlank()) {
            return
        }
        TrackerPrefs.savePushToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] != "command_wakeup") {
            return
        }
        TrackerServiceLauncher.start(this, syncNow = true)
        scheduleWakeFallback()
    }

    private fun scheduleWakeFallback() {
        val alarmManager = getSystemService(ALARM_SERVICE) as? AlarmManager ?: return
        val fallbackIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
            action = ServiceRestartReceiver.ACTION_FCM_WAKE_TRACKER_SERVICE
            `package` = packageName
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1003,
            fallbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 500L,
                pendingIntent
            )
        }
    }
}
