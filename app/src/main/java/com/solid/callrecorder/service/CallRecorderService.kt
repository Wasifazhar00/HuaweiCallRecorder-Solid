package com.solid.callrecorder.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.solid.callrecorder.SolidApp
import com.solid.callrecorder.recorder.CallRecorder
import com.solid.callrecorder.recorder.OplusRecorderBridge
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.ui.MainActivity

class CallRecorderService : Service() {

    private lateinit var classicRecorder: CallRecorder
    private lateinit var enhancedRecorder: ShizukuEnhancedRecorder
    private lateinit var oplusRecorder: OplusRecorderBridge
    private var backend = "none"
    private var currentNumber: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isActive = false

    override fun onCreate() {
        super.onCreate()
        classicRecorder = CallRecorder(this)
        enhancedRecorder = ShizukuEnhancedRecorder(this)
        oplusRecorder = OplusRecorderBridge(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isActive) return START_NOT_STICKY
                currentNumber = intent.getStringExtra(EXTRA_NUMBER)
                val offline = intent.getBooleanExtra(EXTRA_OFFLINE_ONLY, offlinePref())
                startRecording(offline)
            }
            ACTION_STOP -> {
                if (isActive) stopRecording()
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun offlinePref(): Boolean =
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_OFFLINE_ONLY, false)

    private fun startRecording(offlineOnly: Boolean) {
        promoteForeground("Recording")

        val started = when {
            oplusRecorder.isAvailable() && oplusRecorder.start(currentNumber) -> {
                backend = "oplus"
                true
            }
            !offlineOnly && enhancedRecorder.isAvailable() && enhancedRecorder.start(currentNumber) -> {
                backend = "shizuku"
                true
            }
            classicRecorder.start(currentNumber) -> {
                backend = "classic"
                true
            }
            else -> false
        }

        if (!started) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isActive = true
        acquireWakeLock()
        updateNotification("Recording ($backend)")
        Log.i(TAG, "Started backend=$backend")
    }

    private fun stopRecording() {
        isActive = false
        val file = when (backend) {
            "oplus" -> oplusRecorder.stop()
            "shizuku" -> enhancedRecorder.stop()
            else -> classicRecorder.stop()
        }
        releaseWakeLock()
        Log.i(TAG, "Stopped ${file?.name} backend=$backend")
        backend = "none"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SolidApp.CHANNEL_ID)
            .setContentTitle("Call Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            releaseWakeLock()
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SolidCallRecorder:Rec"
            ).apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        if (isActive) {
            try {
                when (backend) {
                    "oplus" -> oplusRecorder.stop()
                    "shizuku" -> enhancedRecorder.stop()
                    else -> classicRecorder.stop()
                }
            } catch (_: Exception) {
            }
            isActive = false
        }
        releaseWakeLock()
        enhancedRecorder.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.solid.callrecorder.START"
        const val ACTION_STOP = "com.solid.callrecorder.STOP"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_OFFLINE_ONLY = "offline_only"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "CallRecorderService"
    }
}
