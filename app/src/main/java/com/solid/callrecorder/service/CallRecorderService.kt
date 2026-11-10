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
import com.solid.callrecorder.recorder.ShizukuEnhancedRecorder
import com.solid.callrecorder.ui.MainActivity

/**
 * Foreground service that prefers the Enhanced (Shizuku) path and falls back to Classic.
 * Shows the active capture strategy in the notification when available.
 */
class CallRecorderService : Service() {

    private lateinit var classicRecorder: CallRecorder
    private lateinit var enhancedRecorder: ShizukuEnhancedRecorder
    private var usingEnhanced = false
    private var currentNumber: String? = null
    private var activeStrategyLabel: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        classicRecorder = CallRecorder(this)
        enhancedRecorder = ShizukuEnhancedRecorder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentNumber = intent.getStringExtra(EXTRA_NUMBER)
                startRecording()
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_STICKY
    }

    private fun startRecording() {
        // Promote to foreground ASAP (Android 12+ background start rules)
        promoteForeground("Starting…")

        val started = if (enhancedRecorder.isAvailable()) {
            usingEnhanced = true
            Log.i(TAG, "Attempting Enhanced (Shizuku) path")
            enhancedRecorder.start(currentNumber).also {
                if (it) {
                    activeStrategyLabel = enhancedRecorder.lastStrategyLabel()
                    Log.i(TAG, "Enhanced started, strategy=$activeStrategyLabel")
                }
            }
        } else {
            usingEnhanced = false
            Log.i(TAG, "Shizuku not ready – Classic path")
            classicRecorder.start(currentNumber)
        }

        if (!started && usingEnhanced) {
            Log.w(TAG, "Enhanced failed – falling back to Classic")
            usingEnhanced = false
            activeStrategyLabel = null
            if (!classicRecorder.start(currentNumber)) {
                Log.e(TAG, "Classic also failed – stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        } else if (!started) {
            Log.e(TAG, "Could not start any recorder")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        acquireWakeLock()
        val mode = if (usingEnhanced) "Enhanced" else "Classic"
        val strategy = activeStrategyLabel?.let { " · $it" } ?: ""
        updateNotification("Recording ($mode$strategy)")
        Log.i(TAG, "Recording active for $currentNumber ($mode$strategy)")
    }

    private fun stopRecording() {
        val file = if (usingEnhanced) {
            enhancedRecorder.stop()
        } else {
            classicRecorder.stop()
        }
        releaseWakeLock()
        Log.i(TAG, "Stopped. File=${file?.absolutePath} strategy=$activeStrategyLabel")
        activeStrategyLabel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, CallRecorderService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SolidApp.CHANNEL_ID)
            .setContentTitle("Solid Call Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SolidCallRecorder:Recording"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // 2h safety cap
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        enhancedRecorder.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.solid.callrecorder.START"
        const val ACTION_STOP = "com.solid.callrecorder.STOP"
        const val EXTRA_NUMBER = "number"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "CallRecorderService"
    }
}
