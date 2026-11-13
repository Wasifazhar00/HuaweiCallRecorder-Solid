package com.solid.callrecorder

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SolidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        fun quiet(id: String, name: String) {
            nm.deleteNotificationChannel(id)
            nm.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Kept at the bottom of the shade. No sound."
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }

        quiet(CHANNEL_ID, "Recording")
        quiet(CHANNEL_MONITOR, "Auto-record")
    }

    companion object {
        const val CHANNEL_ID = "rec_min_v3"
        const val CHANNEL_MONITOR = "mon_min_v3"
    }
}
