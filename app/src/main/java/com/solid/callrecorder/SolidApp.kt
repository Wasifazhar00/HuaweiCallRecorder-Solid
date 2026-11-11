package com.solid.callrecorder

import android.app.Application
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

        fun minChannel(id: String, name: String, desc: String) {
            nm.deleteNotificationChannel(id)
            nm.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_MIN).apply {
                    description = desc
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                }
            )
        }

        minChannel(CHANNEL_ID, "Recording", "While a call is recorded")
        minChannel(CHANNEL_MONITOR, "Auto-record", "Call monitor (silent)")
    }

    companion object {
        const val CHANNEL_ID = "call_recorder_silent"
        const val CHANNEL_MONITOR = "call_monitor_silent"
    }
}
