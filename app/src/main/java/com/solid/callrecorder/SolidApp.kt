package com.solid.callrecorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SolidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // MIN = no sound, no heads-up, minimal battery impact
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent status while a call is recorded"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            // Recreate if old channel existed with higher importance
            manager.deleteNotificationChannel(CHANNEL_ID)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "call_recorder_silent"
    }
}
